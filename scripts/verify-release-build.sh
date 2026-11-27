#!/usr/bin/env bash
# Verifies R8 release minification, resource shrinking, APK + AAB packaging, and ProGuard mapping
# output without ever storing a keystore inside the repository (`docs/110 §5.4`, INV-2259).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

python3 scripts/check-public-repo.py

if [[ -z "${ANDROID_HOME:-}" && -d "${HOME}/Library/Android/sdk" ]]; then
  export ANDROID_HOME="${HOME}/Library/Android/sdk"
fi

./gradlew :core:openApiGenerate :core:test :app:compileDebugKotlin :app:assembleRelease :app:bundleRelease

UNSIGNED_OR_SIGNED_APK="$(find app/build/outputs/apk/release -maxdepth 2 -name '*.apk' | head -n 1)"
RELEASE_AAB="$(find app/build/outputs/bundle/release -maxdepth 2 -name '*.aab' | head -n 1)"
R8_MAPPING="app/build/outputs/mapping/release/mapping.txt"

if [[ -z "${UNSIGNED_OR_SIGNED_APK}" || ! -s "${UNSIGNED_OR_SIGNED_APK}" ]]; then
  echo "[release-check] FAIL: missing release APK under app/build/outputs/apk/release" >&2
  exit 1
fi

if [[ -z "${RELEASE_AAB}" || ! -s "${RELEASE_AAB}" ]]; then
  echo "[release-check] FAIL: missing release AAB under app/build/outputs/bundle/release" >&2
  exit 1
fi

if [[ ! -s "${R8_MAPPING}" ]]; then
  echo "[release-check] FAIL: missing R8 mapping file ${R8_MAPPING}" >&2
  exit 1
fi

echo "[release-check] ok: APK=$(basename "${UNSIGNED_OR_SIGNED_APK}"), AAB=$(basename "${RELEASE_AAB}"), R8 mapping verified"
