#!/data/data/com.termux/files/usr/bin/bash
# Build the release APK and stage it into repo/apk.
set -euo pipefail

cd "$(dirname "$0")/.."

export KEY_STORE_PASSWORD="${KEY_STORE_PASSWORD:-changeit}"
export KEY_PASSWORD="${KEY_PASSWORD:-changeit}"
export ALIAS="${ALIAS:-watchhentai}"

./gradlew :extension:assembleRelease --no-daemon

mkdir -p repo/apk
cp -f extension/build/outputs/apk/release/*.apk repo/apk/

echo
echo "Staged APK:"
ls -lh repo/apk/*.apk
