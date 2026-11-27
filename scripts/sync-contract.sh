#!/usr/bin/env bash
set -euo pipefail
LUMENBOX_ROOT="${1:-../lumenbox}"
if [[ -f "${LUMENBOX_ROOT}/docs/api/v1.openapi.json" ]]; then
  cp "${LUMENBOX_ROOT}/docs/api/v1.openapi.json" contracts/v1.openapi.json
  echo "Synced contracts/v1.openapi.json from ${LUMENBOX_ROOT}"
else
  echo "Note: ${LUMENBOX_ROOT}/docs/api/v1.openapi.json not exported yet (pending INV-2213/INV-2217)."
fi
