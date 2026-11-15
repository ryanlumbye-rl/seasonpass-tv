#!/usr/bin/env bash
# Bygger den signerede release-APK og lægger den klar til udgivelse i release/ (med SHA-256).
# Kræver nøglen i ~/.seasonpass-keys/ (eller SEASONPASS_KEYSTORE_PROPS); uden den nægter scriptet at udgive.
set -euo pipefail
cd "$(dirname "$0")"
KP="${SEASONPASS_KEYSTORE_PROPS:-$HOME/.seasonpass-keys/keystore.properties}"
[ -f "$KP" ] || { echo "Release-nøglen mangler ($KP). En debug-signeret APK må ikke udgives." >&2; exit 1; }
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then export JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || true); fi
./gradlew clean assembleRelease -q --console=plain -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease
mkdir -p release
cp app/build/outputs/apk/release/app-release.apk release/SeasonPass-TV.apk
( cd release && shasum -a 256 SeasonPass-TV.apk | tee SeasonPass-TV.apk.sha256 )
V=$(grep -m1 versionName app/build.gradle | sed "s/.*'\(.*\)'.*/\1/")
echo "Version $V klar i tv-home/release/. Udgiv: gh release create v$V release/SeasonPass-TV.apk release/SeasonPass-TV.apk.sha256 --title \"SeasonPass TV $V\""
