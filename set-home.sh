#!/usr/bin/env bash
# Bygger appen og kører install.sh med den (til udvikling). Brug: TV_HOST=192.168.x.y ./set-home.sh
set -euo pipefail
cd "$(dirname "$0")"
if [ -z "${JAVA_HOME:-}" ]; then
  if [ -x /usr/libexec/java_home ]; then JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || true); fi
  export JAVA_HOME
fi
./gradlew assembleRelease -q --console=plain -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease
exec ./install.sh "${TV_HOST:?sæt TV_HOST til fjernsynets IP}" --apk app/build/outputs/apk/release/app-release.apk -y "$@"
