#!/usr/bin/env bash
# Giver Home tilbage til Googles startskærm. SeasonPass-appen bliver liggende (åbnes fra app-listen).
# Brug: ./undo-home.sh <tv-ip>   (eller TV_HOST=<tv-ip> ./undo-home.sh)
# Fjern også appen: ./undo-home.sh <tv-ip> --uninstall
# Uden en computer: afinstallér "SeasonPass" under Indstillinger > Apps; så tager Googles startskærm Home igen
# (kan den ikke, så slå den til under Apps > Vis systemapps > Android TV Home).
set -euo pipefail
cd "$(dirname "$0")"
. ./lib.sh
UNINSTALL=0
for a in "$@"; do case "$a" in --uninstall) UNINSTALL=1 ;; -*) ;; *) TV_HOST="$a" ;; esac; done
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
