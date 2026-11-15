#!/usr/bin/env bash
# Builds the app and runs install.sh with it (for development). Usage: TV_HOST=192.168.x.y ./set-home.sh
set -euo pipefail
cd "$(dirname "$0")"
if [ -z "${JAVA_HOME:-}" ]; then
  if [ -x /usr/libexec/java_home ]; then JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || true); fi
  export JAVA_HOME
fi
./gradlew assembleRelease -q --console=plain -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease
exec ./install.sh "${TV_HOST:?set TV_HOST to the IP address of the TV}" --apk app/build/outputs/apk/release/app-release.apk -y "$@"
