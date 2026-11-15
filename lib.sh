# Fælles hjælpefunktioner til install.sh, set-home.sh og undo-home.sh (læses med `source`).

PKG=dk.seasonpass.tvhome
# Googles startskærme på Android TV (ældre tv og Google TV). Kun dem, der findes, røres.
LAUNCHERS="com.google.android.tvlauncher com.google.android.apps.tv.launcherx"

find_adb() {
  if command -v adb >/dev/null 2>&1; then command -v adb; return; fi
  local d
  for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "${LOCALAPPDATA:-}/Android/Sdk"; do
    [ -n "$d" ] && [ -x "$d/platform-tools/adb" ] && { echo "$d/platform-tools/adb"; return; }
  done
  return 1
}

# Sætter ADB til "adb -s <ip>:5555" og forbinder. Kræver TV_HOST (eller første argument).
connect_tv() {
  [ -n "${TV_HOST:-}" ] || { echo "Mangler tv'ets IP. Brug: TV_HOST=192.168.x.y $0   (eller giv IP'en som første argument)" >&2; exit 1; }
  local bin
  bin=$(find_adb) || { echo "Kunne ikke finde adb. Installér Android platform-tools: https://developer.android.com/tools/releases/platform-tools" >&2; exit 1; }
  ADB_BIN="$bin"
  "$ADB_BIN" connect "$TV_HOST:5555" >/dev/null 2>&1 || true
  ADB="$ADB_BIN -s $TV_HOST:5555"
  local state
  state=$($ADB get-state 2>&1 || true)
  if [ "$state" != "device" ]; then
    echo "Fik ikke forbindelse til $TV_HOST:5555 ($state)." >&2
    echo "Tjek: udviklertilstand og USB-fejlfinding er slået til på tv'et, tv og computer er på samme netværk," >&2
    echo "og du har trykket 'Tillad' i spørgsmålet, der kommer på tv'et første gang." >&2
    exit 1
  fi
}

installed_launchers() {
  local p
  for p in $LAUNCHERS; do
    $ADB shell "pm list packages $p" 2>/dev/null | tr -d '\r' | grep -qx "package:$p" && echo "$p"
  done
}
