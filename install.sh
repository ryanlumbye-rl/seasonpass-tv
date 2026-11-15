#!/usr/bin/env bash
# Installs the SeasonPass home screen on an Android TV and makes it Home (the same setup as the developer's).
#
# Usage:  ./install.sh <tv-ip> [--apk file.apk] [--no-home] [--reinstall] [-y] [--lang xx]
#   --apk        use this APK (otherwise SeasonPass-TV.apk next to the script, otherwise the latest release)
#   --no-home    only install the app; it is opened from the app list and Google's home screen is left alone
#   --reinstall  uninstall an existing SeasonPass app first (when switching between two signing keys; needs new pairing)
#   -y           do not ask before the changes
#   --lang xx    language of the messages: en, da, sv, no, fi, is, fo, kl (default: the computer's language, else English)
# Undo: ./undo-home.sh <tv-ip>
set -euo pipefail
cd "$(dirname "$0")"
. ./lib.sh

REPO="${SEASONPASS_TV_REPO:-ryanlumbye-rl/seasonpass-tv}"   # where the releases are on GitHub
APK="" HOME_SETUP=1 REINSTALL=0 YES=0
while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="$2"; shift ;;
    --no-home) HOME_SETUP=0 ;;
    --reinstall) REINSTALL=1 ;;
    -y|--yes) YES=1 ;;
    --lang) set_lang "$2"; shift ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    -*) msg unknown_opt "$1" >&2; exit 1 ;;
    *) TV_HOST="$1" ;;
  esac
  shift
done

if [ -z "$APK" ]; then
  if [ -f SeasonPass-TV.apk ]; then APK=SeasonPass-TV.apk
  elif [ -f app/build/outputs/apk/release/app-release.apk ]; then APK=app/build/outputs/apk/release/app-release.apk
  elif [ -n "$REPO" ]; then
    msg fetching "$REPO"
    curl -fL -o SeasonPass-TV.apk "https://github.com/$REPO/releases/latest/download/SeasonPass-TV.apk"
    APK=SeasonPass-TV.apk
  else
    msg no_apk >&2; exit 1
  fi
fi
[ -f "$APK" ] || { msg apk_missing "$APK" >&2; exit 1; }

connect_tv
msg connected "$($ADB shell getprop ro.product.model | tr -d '\r')" "$($ADB shell getprop ro.build.version.release | tr -d '\r')"
$ADB shell "pm list features" | grep -q "android.software.leanback" || { msg not_tv >&2; exit 1; }

LAUNCH=$(installed_launchers || true)
if [ "$HOME_SETUP" = 1 ] && [ "$YES" = 0 ]; then
  msg plan_head
  msg plan1 "$APK"
  msg plan2
  msg plan3 "${LAUNCH:-$(msg none_found)}"
  msg plan4
  msg plan5
  msgn prompt; read -r a
  is_yes "$a" || { msg aborted; exit 1; }
fi

if [ "$REINSTALL" = 1 ]; then $ADB shell "pm uninstall $PKG" >/dev/null 2>&1 || true; fi
# adb install can hang on Android 14 (it waits for a confirmation on the screen): push + pm install instead.
$ADB push "$APK" /data/local/tmp/sp-home.apk >/dev/null
OUT=$($ADB shell "pm install -r /data/local/tmp/sp-home.apk; rm -f /data/local/tmp/sp-home.apk" | tr -d '\r')
echo "$OUT"
if echo "$OUT" | grep -q "UPDATE_INCOMPATIBLE"; then
  msg incompatible >&2
  exit 1
fi
echo "$OUT" | grep -q Success || { msg failed >&2; exit 1; }

# The app may install its own updates without an extra question on the TV. Android only lets it put an APK on top of itself,
# and only if it is signed with the same key; it cannot install other apps.
$ADB shell "appops set $PKG REQUEST_INSTALL_PACKAGES allow" >/dev/null 2>&1 || true

# One permission that only adb can give: it lets the app switch the screensaver and music access itself in Settings.
$ADB shell "pm grant $PKG android.permission.WRITE_SECURE_SETTINGS" >/dev/null 2>&1 || msg warn_secure >&2

if [ "$HOME_SETUP" = 1 ]; then
  $ADB shell "cmd package set-home-activity $PKG/.MainActivity" >/dev/null
  # Google's home screen is privileged and would otherwise win Home (set-home-activity alone changes nothing).
  for p in $LAUNCH; do $ADB shell "pm disable-user --user 0 $p" >/dev/null; done
  $ADB shell "settings put secure enabled_accessibility_services $PKG/.PowerOnService; settings put secure accessibility_enabled 1"
  # Screensaver (slideshow, clock, now playing) and access to see what Spotify is playing. Reads no notifications.
  $ADB shell "settings put secure screensaver_components $PKG/.SeasonDream; settings put secure screensaver_enabled 1"
  # Only the system's own approval counts; writing the setting alone does not work.
  $ADB shell "cmd notification allow_listener $PKG/$PKG.MediaListener" >/dev/null 2>&1 || msg warn_music >&2
  $ADB shell "am start -a android.intent.action.MAIN -c android.intent.category.HOME" >/dev/null
  sleep 3
  echo "Home: $($ADB shell 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1' | tr -d '\r')"
else
  $ADB shell "monkey -p $PKG -c android.intent.category.LEANBACK_LAUNCHER 1" >/dev/null 2>&1 || true
fi

msg done "$TV_HOST"
