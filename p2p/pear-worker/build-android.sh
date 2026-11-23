#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKER="$ROOT/p2p/pear-worker"
ASSETS="$ROOT/android/app/src/main/assets"
ADDONS="$ROOT/android/app/src/main/jniLibs"

command -v node >/dev/null || { echo "Node.js 22+ is required to package the Pear worker." >&2; exit 1; }
command -v npm >/dev/null || { echo "npm is required to package the Pear worker." >&2; exit 1; }

mkdir -p "$ASSETS" "$ADDONS"
cd "$WORKER"
npm install --no-audit --no-fund

# Android embeds Bare worklets and links native addons into the APK.
npx --no-install bare-pack \
  --host android-arm64 \
  --host android-x64 \
  --linked \
  --out "$ASSETS/pear-worker.bundle.mjs" \
  index.js

npx --no-install bare-link \
  --preset android \
  --out "$ADDONS" \
  index.js

test -s "$ASSETS/pear-worker.bundle.mjs"
echo "Pear Android worker bundle and native addon link step completed."
