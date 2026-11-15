#!/usr/bin/env bash
# Gives Home back to Google's home screen. The SeasonPass app stays installed (opened from the app list).
# Usage: ./undo-home.sh <tv-ip> [--lang xx]   (or TV_HOST=<tv-ip> ./undo-home.sh)
# Remove the app too: ./undo-home.sh <tv-ip> --uninstall
# Without a computer: uninstall "SeasonPass" under Settings > Apps; then Google's home screen takes Home again
# (if it does not, turn it on under Apps > Show system apps > Android TV Home).
set -euo pipefail
cd "$(dirname "$0")"
. ./lib.sh
UNINSTALL=0
while [ $# -gt 0 ]; do case "$1" in --uninstall) UNINSTALL=1 ;; --lang) set_lang "$2"; shift ;; -*) ;; *) TV_HOST="$1" ;; esac; shift; done
connect_tv
$ADB shell "settings put secure enabled_accessibility_services null; settings put secure accessibility_enabled 0"
$ADB shell "settings put secure screensaver_components com.android.dreams.basic/.Colors" >/dev/null 2>&1 || true
for p in $LAUNCHERS; do
  if $ADB shell "pm list packages $p" | tr -d '\r' | grep -qx "package:$p"; then
    $ADB shell "pm enable $p" >/dev/null
    $ADB shell "cmd package set-home-activity $p/.MainActivity" >/dev/null 2>&1 || true
  fi
done
[ "$UNINSTALL" = 1 ] && $ADB shell "pm uninstall $PKG" || true
$ADB shell "am start -a android.intent.action.MAIN -c android.intent.category.HOME" >/dev/null
echo "Home: $($ADB shell 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1' | tr -d '\r')"
