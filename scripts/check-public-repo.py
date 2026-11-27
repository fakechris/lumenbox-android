#!/usr/bin/env python3
"""Public-repository hygiene gate (mirrors fakechris/lumenbox scripts/public-content.mjs + docs-lint).

Fails if any tracked file:
1. Lives under research/, docs/research/, scripts/research/, or .runtime/.
2. Carries signing/key/credential extensions (.keystore, .jks, .p8, .p12, google-services.json, .env*).
3. Contains local home paths (/Users/..., /home/...), private LAN/tailnet addresses, non-placeholder
   email addresses, hashed internal host/account names, or secret/token prefixes.
"""

from __future__ import annotations

import hashlib
import pathlib
import re
import subprocess
import sys

INTERNAL_NAME_HASHES = {
    "0869aff6c3903b4593b80181dde17e4ea67794f2bfca64b2a2884ebb43d9fadd",
    "16779331dd047f08908b43682582a3f27fb3a03c0f0b18b35c8e46530aec39ef",
    "bf7a579311c63a3a2e829b89d4b171e6cf79f846795bbe4dfd9485aa97a90cf5",
}

FORBIDDEN_PREFIXES = (
    "research/",
    "docs/research/",
    "scripts/research/",
    ".runtime/",
)

FORBIDDEN_FILE_PATTERNS = re.compile(
    r"(^|/)\.env(\..+)?$|\.(p8|p12|mobileprovision|provisionprofile|keystore|jks)$"
    r"|(^|/)(google-services\.json|GoogleService-Info\.plist)$",
    re.IGNORECASE,
)

BINARY_EXTENSIONS = {".jar", ".png", ".jpg", ".jpeg", ".webp", ".icns", ".ico", ".pdf"}

RE_USERS_HOME = re.compile(r"/Users/([A-Za-z0-9._-]+)/")
RE_LINUX_HOME = re.compile(r"/home/([a-z0-9_-]+)/")
RE_PRIVATE_IP = re.compile(
    r"\b(100\.(?:6[4-9]|[7-9]\d|1[01]\d|12[0-7])|10\.\d{1,3}|192\.168)\.\d{1,3}\.\d{1,3}\b(?!\/\d)"
)
RE_TAILNET = re.compile(r"\b([a-z0-9-]+)\.ts\.net\b", re.IGNORECASE)
RE_EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
RE_PLACEHOLDER_EMAIL = re.compile(
    r"@([a-z0-9-]+\.)*(example\.(com|org|net)|example|local|localhost|test|invalid|corp\.com|users\.noreply\.github\.com)$",
    re.IGNORECASE,
)
RE_SECRET_TOKEN = re.compile(
    r"-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----"
    r"|\binv_agent_[A-Za-z0-9_-]{8,}"
    r"|\bghp_[A-Za-z0-9]{20,}"
    r"|\bgithub_pat_[A-Za-z0-9_]{20,}"
    r"|\bsk-[A-Za-z0-9]{20,}"
    r"|\bAKIA[0-9A-Z]{16}\b"
)
RE_WORD_TOKEN = re.compile(r"[A-Za-z0-9_@.-]+")


def main() -> int:
    root = pathlib.Path(__file__).resolve().parent.parent
    out = subprocess.check_output(["git", "-C", str(root), "ls-files"], text=True)
    tracked = [line for line in out.splitlines() if line]
    problems: list[str] = []

    for rel in tracked:
        if rel.startswith(FORBIDDEN_PREFIXES):
            problems.append(f"{rel}: research or runtime scratch files must never be tracked")
            continue
        if FORBIDDEN_FILE_PATTERNS.search(rel):
            problems.append(f"{rel}: signing key, keystore, or environment secret file tracked")
            continue
        path = root / rel
        if path.suffix.lower() in BINARY_EXTENSIONS or not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue

        for idx, line in enumerate(text.splitlines(), start=1):
            at = f"{rel}:{idx}"
            for m in RE_USERS_HOME.finditer(line):
                problems.append(f"{at}: local home directory (/Users/{m.group(1)}/); use ~/ or <repo>/")
            for m in RE_LINUX_HOME.finditer(line):
                if m.group(1) not in {"box", "hostd"}:
                    problems.append(f"{at}: local home directory (/home/{m.group(1)}/); use ~/")
            for m in RE_PRIVATE_IP.finditer(line):
                problems.append(f"{at}: private network address ({m.group(0)})")
            for m in RE_TAILNET.finditer(line):
                if m.group(1).lower() != "example":
                    problems.append(f"{at}: tailnet hostname (*.ts.net)")
            for m in RE_EMAIL.finditer(line):
                if not RE_PLACEHOLDER_EMAIL.search(m.group(0)):
                    problems.append(f"{at}: non-placeholder email address")
            if RE_SECRET_TOKEN.search(line):
                problems.append(f"{at}: secret key or credential token pattern detected")
            for tok in RE_WORD_TOKEN.findall(line):
                bare = tok.strip(".@-").lower()
                if bare and hashlib.sha256(bare.encode("utf-8")).hexdigest() in INTERNAL_NAME_HASHES:
                    problems.append(f"{at}: internal host or account name matched blocklist hash")

    if problems:
        for p in problems:
            print(f"[public-check] FAIL {p}", file=sys.stderr)
        return 1

    print(f"[public-check] ok: {len(tracked)} tracked files verified clean")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
