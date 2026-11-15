#!/usr/bin/env bash
# Installerer SeasonPass-startskærmen på et Android TV og gør den til Home (samme setup som udvikleren).
#
# Brug:   ./install.sh <tv-ip> [--apk fil.apk] [--no-home] [--reinstall] [-y]
#   --apk        brug denne APK (ellers SeasonPass-TV.apk ved siden af scriptet, ellers seneste udgivelse)
#   --no-home    installér kun appen; den åbnes fra app-listen, og Googles startskærm røres ikke
#   --reinstall  afinstallér en eksisterende SeasonPass-app først (ved skift mellem to signeringsnøgler; kræver ny parring)
#   -y           spørg ikke før ændringerne
# Fortryd: ./undo-home.sh <tv-ip>
set -euo pipefail
cd "$(dirname "$0")"
. ./lib.sh

REPO="${SEASONPASS_TV_REPO:-ryanlumbye-rl/seasonpass-tv}"   # hvor udgivelserne ligger på GitHub
APK="" HOME_SETUP=1 REINSTALL=0 YES=0
while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="$2"; shift ;;
    --no-home) HOME_SETUP=0 ;;
    --reinstall) REINSTALL=1 ;;
    -y|--yes) YES=1 ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    -*) echo "Ukendt valg: $1" >&2; exit 1 ;;
    *) TV_HOST="$1" ;;
  esac
  shift
done

if [ -z "$APK" ]; then
  if [ -f SeasonPass-TV.apk ]; then APK=SeasonPass-TV.apk
  elif [ -f app/build/outputs/apk/release/app-release.apk ]; then APK=app/build/outputs/apk/release/app-release.apk
  elif [ -n "$REPO" ]; then
    echo "Henter seneste udgivelse fra github.com/$REPO ..."
    curl -fL -o SeasonPass-TV.apk "https://github.com/$REPO/releases/latest/download/SeasonPass-TV.apk"
    APK=SeasonPass-TV.apk
  else
    echo "Fandt ingen APK. Læg SeasonPass-TV.apk ved siden af scriptet eller brug --apk." >&2; exit 1
  fi
fi
[ -f "$APK" ] || { echo "APK findes ikke: $APK" >&2; exit 1; }

connect_tv
echo "Forbundet til $($ADB shell getprop ro.product.model | tr -d '\r') (Android $($ADB shell getprop ro.build.version.release | tr -d '\r'))."
$ADB shell "pm list features" | grep -q "android.software.leanback" || { echo "Det her ligner ikke et Android TV." >&2; exit 1; }

LAUNCH=$(installed_launchers || true)
if [ "$HOME_SETUP" = 1 ] && [ "$YES" = 0 ]; then
  echo
  echo "Det her gør scriptet:"
  echo "  1. installerer SeasonPass ($APK)"
  echo "  2. gør SeasonPass til tv'ets startskærm"
  echo "  3. slår Googles startskærm fra (${LAUNCH:-ingen fundet}); den afinstalleres ikke og kan slås til igen med undo-home.sh"
  echo "  4. slår hjælperen til (en tilgængelighedstjeneste, der kun sender tv'ets egen Live TV tilbage til SeasonPass ved tænding)"
  echo "  5. vælger SeasonPass som pauseskærm og giver adgang til at vise, hvad der spilles (Spotify); ingen notifikationer læses"
  printf "Fortsæt? [j/N] "; read -r a
  case "$a" in j|J|y|Y) ;; *) echo "Afbrudt."; exit 1 ;; esac
fi

if [ "$REINSTALL" = 1 ]; then $ADB shell "pm uninstall $PKG" >/dev/null 2>&1 || true; fi
# adb install kan hænge på Android 14 (venter på bekræftelse på skærmen): push + pm install i stedet.
$ADB push "$APK" /data/local/tmp/sp-home.apk >/dev/null
OUT=$($ADB shell "pm install -r /data/local/tmp/sp-home.apk; rm -f /data/local/tmp/sp-home.apk" | tr -d '\r')
echo "$OUT"
if echo "$OUT" | grep -q "UPDATE_INCOMPATIBLE"; then
  echo "Der ligger allerede en SeasonPass-app med en anden signering. Kør igen med --reinstall (parringen med telefonen skal så laves igen)." >&2
  exit 1
fi
echo "$OUT" | grep -q Success || { echo "Installationen fejlede." >&2; exit 1; }

# Appen må installere sine egne opdateringer uden et ekstra spørgsmål på tv'et. Android lader den kun lægge en APK oven på sig selv,
# og kun hvis den er signeret med samme nøgle; andre apps kan den ikke installere.
$ADB shell "appops set $PKG REQUEST_INSTALL_PACKAGES allow" >/dev/null 2>&1 || true

# Én tilladelse, som kun adb kan give: så kan appen selv skifte pauseskærm og musikadgang i Indstillinger.
$ADB shell "pm grant $PKG android.permission.WRITE_SECURE_SETTINGS" >/dev/null 2>&1 || echo "Advarsel: kunne ikke give WRITE_SECURE_SETTINGS (pauseskærmen skal så vælges i tv'ets egne indstillinger)." >&2

if [ "$HOME_SETUP" = 1 ]; then
  $ADB shell "cmd package set-home-activity $PKG/.MainActivity" >/dev/null
  # Googles startskærm er privilegeret og vinder ellers Home (set-home-activity alene ændrer intet).
  for p in $LAUNCH; do $ADB shell "pm disable-user --user 0 $p" >/dev/null; done
  $ADB shell "settings put secure enabled_accessibility_services $PKG/.PowerOnService; settings put secure accessibility_enabled 1"
  # Pauseskærm (slideshow, ur, spiller nu) og adgang til at se, hvad Spotify spiller. Læser ingen notifikationer.
  $ADB shell "settings put secure screensaver_components $PKG/.SeasonDream; settings put secure screensaver_enabled 1"
  # Kun systemets egen godkendelse tæller; at skrive i indstillingen alene virker ikke.
  $ADB shell "cmd notification allow_listener $PKG/$PKG.MediaListener" >/dev/null 2>&1 || echo "Advarsel: kunne ikke give adgang til musik (Spotify på pauseskærmen)." >&2
  $ADB shell "am start -a android.intent.action.MAIN -c android.intent.category.HOME" >/dev/null
  sleep 3
  echo "Home: $($ADB shell 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1' | tr -d '\r')"
else
  $ADB shell "monkey -p $PKG -c android.intent.category.LEANBACK_LAUNCHER 1" >/dev/null 2>&1 || true
fi

cat <<MSG

Færdig. Næste skridt: åbn SeasonPass på telefonen → Indstillinger → Fjernsyn. Telefonen finder tv'et,
og tv'et viser en kode på fire cifre, du skriver ind. Derefter sendes serierne over Wi-Fi.
Fortryd: ./undo-home.sh $TV_HOST
MSG
