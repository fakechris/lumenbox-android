# AGENTS.md

House rules for agents working on `lumenbox-android` (companion repository to `fakechris/lumenbox`, `docs/106` & `docs/110`).

## House rules

- **Licence**: `PolyForm Shield License 1.0.0` (`LICENSE` + `NOTICE`). Keep third-party dependencies permissive (`Apache-2.0`, `MIT`, `BSD`).
- **One session, one fresh worktree**: start every task in its own `git worktree add <dir> <base>`, commit only your own files (`git add <paths>`, never `git add -A`), and remove the worktree once merged.
- **Test and hygiene gates**: run `python3 scripts/check-public-repo.py && ./gradlew :core:test` before every commit or PR. Never merge on a red suite.
- **Never commit or print secrets**: never print or commit tokens, `.keystore` / `.jks` signing keys, `google-services.json`, `.env` files, personal email addresses, `/Users/...` home paths, private LAN IPs, or tailnet hostnames.
- **Research stays out of the repo**: notes, competitive analysis, and probe scripts belong only in `/research/` (gitignored) and are refused by `scripts/check-public-repo.py`. Cite design decisions in `fakechris/lumenbox` (`docs/106`, `docs/110`) without naming third-party competing products.
