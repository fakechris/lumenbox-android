# AGENTS.md

How agents work on `lumenbox-android` (companion repository to `fakechris/lumenbox`, `docs/106` & `docs/110`).

## The work graph this repo is bound to

| | |
|---|---|
| Involute PROJECT | **INV-2231** — `fakechris/lumenbox-android` (`9ddebabb-f65b-4248-8580-b11cde9d32ce`) |
| Server companion PROJECT | **INV-96** — `fakechris/lumenbox` (`2ea544a1-f7cd-46c5-98b9-3f3da2993ada`) |
| Team key | `INV` |
| Server | `https://involute.lumenopen.com/mcp` (MCP, bearer `inv_agent_…`) |
| Web UI | `https://involute.lumenopen.com/` |

Everything below **INV-2231** is a `MILESTONE` (a delivery phase) or an `ISSUE` (one independently acceptable piece of work), linked with `CONTAINS`.

## The loop

1. **Look before you invent.** `work_search` before proposing anything.
2. **Do not lease what nobody committed.** Only `work_claim` items in `work_list_ready` that a human has committed.
3. **`work_claim` before coding**, and **omit `run_id` when starting a new `run_report`**.
4. **Attach durable `evidence`** (PR URL or test run) before completing a run.
5. **Agents propose, humans commit; agents never mark work `Done`** (except committed `research` issues). Completed runs land in **`In Review`**. Every proposal must carry a 3-part structured Chinese description (`### 1. 目标与架构定位`, `### 2. 核心功能与交付范围`, `### 3. 验收标准与验证方案`).

## House rules

- **Licence**: `PolyForm Shield License 1.0.0` (`LICENSE` + `NOTICE`). Keep third-party dependencies permissive (`Apache-2.0`, `MIT`, `BSD`).
- **One session, one fresh worktree**: start every task in its own `git worktree add <dir> <base>`, commit only your own files (`git add <paths>`, never `git add -A`), and remove the worktree once merged.
- **Test and hygiene gates**: run `python3 scripts/check-public-repo.py && ./gradlew :core:test` before every commit or PR. Never merge on a red suite.
- **Crypto and `:core` changes run on a device too**: `./gradlew :core:test` uses OpenJDK, not Android's providers or Android's `java.*`. A change to the AEAD, X25519, Noise, the offline cache, or any JDK API `:core` calls also runs `./gradlew :app:connectedDebugAndroidTest` on an API 28 (`minSdk`) emulator before the PR (README, *Verification*); CI runs it on API 28 and 33.
- **Never commit or print secrets**: never print or commit tokens, `.keystore` / `.jks` signing keys, `google-services.json`, `.env` files, personal email addresses, local user home paths, private LAN IPs, or tailnet hostnames.
- **Research stays out of the repo**: notes, competitive analysis, and probe scripts belong only in `/research/` (gitignored) and are refused by `scripts/check-public-repo.py`. Cite design decisions in `fakechris/lumenbox` (`docs/106`, `docs/110`) without naming third-party competing products.
