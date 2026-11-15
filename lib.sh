# Shared helpers for install.sh, set-home.sh and undo-home.sh (read with `source`).

PKG=dk.seasonpass.tvhome
# Google's home screens on Android TV (older TVs and Google TV). Only the ones that exist are touched.
LAUNCHERS="com.google.android.tvlauncher com.google.android.apps.tv.launcherx"

# ---------------------------------------------------------------------------------------------------------------------
# Language. The scripts speak the language of the computer ($LANG), English if it is not one of the eight below.
# Choose another with --lang xx (en, da, sv, no, fi, is, fo, kl) or SP_LANG=xx. Finnish, Icelandic, Faroese and Greenlandic
# are drafts that have not been read by a native speaker. Works in bash 3.2 (macOS): no associative arrays.
# ---------------------------------------------------------------------------------------------------------------------
detect_lang_of() {
  local l="${1%%.*}"; l="${l%%@*}"
  case "$l" in
    da*) echo da ;; sv*) echo sv ;; nb*|nn*|no*) echo no ;; fi*) echo fi ;; is*) echo is ;; fo*) echo fo ;; kl*) echo kl ;;
    *) echo en ;;
  esac
}
set_lang() { SP_LANG=$(detect_lang_of "$1"); }
SP_LANG=$(detect_lang_of "${SP_LANG:-${LC_ALL:-${LC_MESSAGES:-${LANG:-}}}}")

# msg key [args...]   prints the text with the arguments in the %s places and a newline; msgn: without the newline.
# Missing in a language: the English text is used.
_text() {
  local t=""
  t=$("_msg_$SP_LANG" "$1" 2>/dev/null) || true
  [ -n "$t" ] || t=$(_msg_en "$1")
  printf '%s' "$t"
}
msg()  { local f; f=$(_text "$1"); shift; printf "$f\n" "$@"; }
msgn() { local f; f=$(_text "$1"); shift; printf "$f" "$@"; }

# Does the answer to the question mean yes? (y always works, plus the language's own letter)
is_yes() {
  case "$SP_LANG:$1" in
    *:y|*:Y) return 0 ;;
    da:j|da:J|sv:j|sv:J|no:j|no:J|is:j|is:J|fo:j|fo:J|fi:k|fi:K|kl:i|kl:I) return 0 ;;
  esac
  return 1
}

_msg_en() { case "$1" in
  need_ip) echo "Missing the TV's IP address. Use: TV_HOST=192.168.x.y %s   (or give the IP address as the first argument)" ;;
  no_adb) echo "Could not find adb. Install Android platform-tools: https://developer.android.com/tools/releases/platform-tools" ;;
  no_conn1) echo "Could not connect to %s:5555 (%s)." ;;
  no_conn2) echo "Check that developer options and USB debugging are turned on on the TV, that the TV and the computer are on the same network," ;;
  no_conn3) echo "and that you pressed 'Allow' in the prompt that appears on the TV the first time." ;;
  unknown_opt) echo "Unknown option: %s" ;;
  fetching) echo "Downloading the latest release from github.com/%s ..." ;;
  no_apk) echo "No APK found. Put SeasonPass-TV.apk next to the script or use --apk." ;;
  apk_missing) echo "The APK does not exist: %s" ;;
  connected) echo "Connected to %s (Android %s)." ;;
  not_tv) echo "This does not look like an Android TV." ;;
  plan_head) echo "\nThis is what the script does:" ;;
  plan1) echo "  1. installs SeasonPass (%s)" ;;
  plan2) echo "  2. makes SeasonPass the TV's home screen" ;;
  plan3) echo "  3. turns off Google's home screen (%s); it is not uninstalled and can be turned on again with undo-home.sh" ;;
  none_found) echo "none found" ;;
  plan4) echo "  4. turns on the helper (an accessibility service that only sends the TV's own Live TV back to SeasonPass at power-on)" ;;
  plan5) echo "  5. selects SeasonPass as the screensaver and gives access to show what is playing (Spotify); no notifications are read" ;;
  prompt) echo "Continue? [y/N] " ;;
  aborted) echo "Aborted." ;;
  incompatible) echo "A SeasonPass app signed with a different key is already installed. Run again with --reinstall (pairing with the phone will then have to be done again)." ;;
  failed) echo "The installation failed." ;;
  warn_secure) echo "Warning: could not grant WRITE_SECURE_SETTINGS (the screensaver will then have to be chosen in the TV's own settings)." ;;
  warn_music) echo "Warning: could not give access to music (Spotify on the screensaver)." ;;
  done) echo "\nDone. Next step: open SeasonPass on your phone → Settings → TV. The phone finds the TV,\nand the TV shows a four-digit code that you enter. Then the shows are sent over Wi-Fi.\nUndo: ./undo-home.sh %s" ;;
esac; }

_msg_da() { case "$1" in
  need_ip) echo "Mangler tv'ets IP. Brug: TV_HOST=192.168.x.y %s   (eller giv IP'en som første argument)" ;;
  no_adb) echo "Kunne ikke finde adb. Installér Android platform-tools: https://developer.android.com/tools/releases/platform-tools" ;;
  no_conn1) echo "Fik ikke forbindelse til %s:5555 (%s)." ;;
  no_conn2) echo "Tjek: udviklertilstand og USB-fejlfinding er slået til på tv'et, tv og computer er på samme netværk," ;;
  no_conn3) echo "og du har trykket 'Tillad' i spørgsmålet, der kommer på tv'et første gang." ;;
  unknown_opt) echo "Ukendt valg: %s" ;;
  fetching) echo "Henter seneste udgivelse fra github.com/%s ..." ;;
  no_apk) echo "Fandt ingen APK. Læg SeasonPass-TV.apk ved siden af scriptet eller brug --apk." ;;
  apk_missing) echo "APK findes ikke: %s" ;;
  connected) echo "Forbundet til %s (Android %s)." ;;
  not_tv) echo "Det her ligner ikke et Android TV." ;;
  plan_head) echo "\nDet her gør scriptet:" ;;
  plan1) echo "  1. installerer SeasonPass (%s)" ;;
  plan2) echo "  2. gør SeasonPass til tv'ets startskærm" ;;
  plan3) echo "  3. slår Googles startskærm fra (%s); den afinstalleres ikke og kan slås til igen med undo-home.sh" ;;
  none_found) echo "ingen fundet" ;;
  plan4) echo "  4. slår hjælperen til (en tilgængelighedstjeneste, der kun sender tv'ets egen Live TV tilbage til SeasonPass ved tænding)" ;;
  plan5) echo "  5. vælger SeasonPass som pauseskærm og giver adgang til at vise, hvad der spilles (Spotify); ingen notifikationer læses" ;;
  prompt) echo "Fortsæt? [j/N] " ;;
  aborted) echo "Afbrudt." ;;
  incompatible) echo "Der ligger allerede en SeasonPass-app med en anden signering. Kør igen med --reinstall (parringen med telefonen skal så laves igen)." ;;
  failed) echo "Installationen fejlede." ;;
  warn_secure) echo "Advarsel: kunne ikke give WRITE_SECURE_SETTINGS (pauseskærmen skal så vælges i tv'ets egne indstillinger)." ;;
  warn_music) echo "Advarsel: kunne ikke give adgang til musik (Spotify på pauseskærmen)." ;;
  done) echo "\nFærdig. Næste skridt: åbn SeasonPass på telefonen → Indstillinger → Fjernsyn. Telefonen finder tv'et,\nog tv'et viser en kode på fire cifre, du skriver ind. Derefter sendes serierne over Wi-Fi.\nFortryd: ./undo-home.sh %s" ;;
esac; }

_msg_sv() { case "$1" in
  need_ip) echo "Tv:ns IP-adress saknas. Använd: TV_HOST=192.168.x.y %s   (eller ange IP-adressen som första argument)" ;;
  no_adb) echo "Hittade inte adb. Installera Android platform-tools: https://developer.android.com/tools/releases/platform-tools" ;;
  no_conn1) echo "Fick ingen kontakt med %s:5555 (%s)." ;;
  no_conn2) echo "Kontrollera att utvecklaralternativ och USB-felsökning är påslagna på tv:n, att tv:n och datorn är på samma nätverk," ;;
  no_conn3) echo "och att du har tryckt på 'Tillåt' i frågan som visas på tv:n första gången." ;;
  unknown_opt) echo "Okänt alternativ: %s" ;;
  fetching) echo "Hämtar senaste utgåvan från github.com/%s ..." ;;
  no_apk) echo "Hittade ingen APK. Lägg SeasonPass-TV.apk bredvid skriptet eller använd --apk." ;;
  apk_missing) echo "APK-filen finns inte: %s" ;;
  connected) echo "Ansluten till %s (Android %s)." ;;
  not_tv) echo "Det här ser inte ut som en Android TV." ;;
  plan_head) echo "\nDet här gör skriptet:" ;;
  plan1) echo "  1. installerar SeasonPass (%s)" ;;
  plan2) echo "  2. gör SeasonPass till tv:ns startskärm" ;;
  plan3) echo "  3. stänger av Googles startskärm (%s); den avinstalleras inte och kan slås på igen med undo-home.sh" ;;
  none_found) echo "ingen hittades" ;;
  plan4) echo "  4. slår på hjälparen (en tillgänglighetstjänst som bara skickar tillbaka tv:ns egen Live TV till SeasonPass vid start)" ;;
  plan5) echo "  5. väljer SeasonPass som skärmsläckare och ger åtkomst till att visa vad som spelas (Spotify); inga notiser läses" ;;
  prompt) echo "Fortsätta? [j/N] " ;;
  aborted) echo "Avbrutet." ;;
  incompatible) echo "Det finns redan en SeasonPass-app med en annan signering. Kör igen med --reinstall (parkopplingen med telefonen måste då göras om)." ;;
  failed) echo "Installationen misslyckades." ;;
  warn_secure) echo "Varning: kunde inte ge WRITE_SECURE_SETTINGS (skärmsläckaren måste då väljas i tv:ns egna inställningar)." ;;
  warn_music) echo "Varning: kunde inte ge åtkomst till musik (Spotify på skärmsläckaren)." ;;
  done) echo "\nKlart. Nästa steg: öppna SeasonPass i telefonen → Inställningar → Tv. Telefonen hittar tv:n,\noch tv:n visar en fyrsiffrig kod som du skriver in. Sedan skickas serierna över Wi-Fi.\nÅngra: ./undo-home.sh %s" ;;
esac; }

_msg_no() { case "$1" in
  need_ip) echo "Mangler tv-ens IP-adresse. Bruk: TV_HOST=192.168.x.y %s   (eller gi IP-adressen som første argument)" ;;
  no_adb) echo "Fant ikke adb. Installer Android platform-tools: https://developer.android.com/tools/releases/platform-tools" ;;
  no_conn1) echo "Fikk ikke kontakt med %s:5555 (%s)." ;;
  no_conn2) echo "Sjekk at utvikleralternativer og USB-feilsøking er slått på på tv-en, at tv-en og datamaskinen er på samme nettverk," ;;
  no_conn3) echo "og at du har trykket 'Tillat' i spørsmålet som kommer på tv-en første gang." ;;
  unknown_opt) echo "Ukjent valg: %s" ;;
  fetching) echo "Henter siste utgivelse fra github.com/%s ..." ;;
  no_apk) echo "Fant ingen APK. Legg SeasonPass-TV.apk ved siden av skriptet eller bruk --apk." ;;
  apk_missing) echo "APK-filen finnes ikke: %s" ;;
  connected) echo "Koblet til %s (Android %s)." ;;
  not_tv) echo "Dette ser ikke ut som en Android TV." ;;
  plan_head) echo "\nDette gjør skriptet:" ;;
  plan1) echo "  1. installerer SeasonPass (%s)" ;;
  plan2) echo "  2. gjør SeasonPass til tv-ens startskjerm" ;;
  plan3) echo "  3. slår av Googles startskjerm (%s); den avinstalleres ikke og kan slås på igjen med undo-home.sh" ;;
  none_found) echo "ingen funnet" ;;
  plan4) echo "  4. slår på hjelperen (en tilgjengelighetstjeneste som bare sender tv-ens egen Live TV tilbake til SeasonPass ved oppstart)" ;;
  plan5) echo "  5. velger SeasonPass som skjermsparer og gir tilgang til å vise hva som spilles (Spotify); ingen varsler leses" ;;
  prompt) echo "Fortsette? [j/N] " ;;
  aborted) echo "Avbrutt." ;;
  incompatible) echo "Det finnes allerede en SeasonPass-app med en annen signering. Kjør på nytt med --reinstall (paringen med telefonen må da gjøres på nytt)." ;;
  failed) echo "Installasjonen mislyktes." ;;
  warn_secure) echo "Advarsel: kunne ikke gi WRITE_SECURE_SETTINGS (skjermspareren må da velges i tv-ens egne innstillinger)." ;;
  warn_music) echo "Advarsel: kunne ikke gi tilgang til musikk (Spotify på skjermspareren)." ;;
  done) echo "\nFerdig. Neste steg: åpne SeasonPass på telefonen → Innstillinger → TV. Telefonen finner tv-en,\nog tv-en viser en firesifret kode som du skriver inn. Deretter sendes seriene over Wi-Fi.\nAngre: ./undo-home.sh %s" ;;
esac; }

_msg_fi() { case "$1" in
  need_ip) echo "Television IP-osoite puuttuu. Käytä: TV_HOST=192.168.x.y %s   (tai anna IP-osoite ensimmäisenä argumenttina)" ;;
  no_adb) echo "adb:tä ei löytynyt. Asenna Android platform-tools: https://developer.android.com/tools/releases/platform-tools" ;;
  no_conn1) echo "Yhteyttä ei saatu osoitteeseen %s:5555 (%s)." ;;
  no_conn2) echo "Tarkista, että kehittäjäasetukset ja USB-virheenkorjaus on otettu käyttöön televisiossa, että televisio ja tietokone ovat samassa verkossa," ;;
  no_conn3) echo "ja että painoit televisiossa ensimmäisellä kerralla näkyvästä kysymyksestä 'Salli'." ;;
  unknown_opt) echo "Tuntematon valinta: %s" ;;
  fetching) echo "Ladataan uusin julkaisu osoitteesta github.com/%s ..." ;;
  no_apk) echo "APK-tiedostoa ei löytynyt. Laita SeasonPass-TV.apk skriptin viereen tai käytä valintaa --apk." ;;
  apk_missing) echo "APK-tiedostoa ei ole: %s" ;;
  connected) echo "Yhdistetty laitteeseen %s (Android %s)." ;;
  not_tv) echo "Tämä ei näytä Android TV:ltä." ;;
  plan_head) echo "\nSkripti tekee seuraavat asiat:" ;;
  plan1) echo "  1. asentaa SeasonPassin (%s)" ;;
  plan2) echo "  2. tekee SeasonPassista television aloitusnäytön" ;;
  plan3) echo "  3. poistaa Googlen aloitusnäytön käytöstä (%s); sitä ei poisteta, ja sen voi ottaa takaisin käyttöön komennolla undo-home.sh" ;;
  none_found) echo "ei löytynyt" ;;
  plan4) echo "  4. ottaa apuohjelman käyttöön (esteettömyyspalvelu, joka vain ohjaa television oman Live TV:n takaisin SeasonPassiin virran kytkemisen yhteydessä)" ;;
  plan5) echo "  5. valitsee SeasonPassin näytönsäästäjäksi ja antaa oikeuden näyttää, mitä soi (Spotify); ilmoituksia ei lueta" ;;
  prompt) echo "Jatketaanko? [k/E] " ;;
  aborted) echo "Keskeytetty." ;;
  incompatible) echo "Laitteessa on jo SeasonPass-sovellus, joka on allekirjoitettu eri avaimella. Aja uudelleen valinnalla --reinstall (puhelimen kanssa pariliitos on silloin tehtävä uudelleen)." ;;
  failed) echo "Asennus epäonnistui." ;;
  warn_secure) echo "Varoitus: WRITE_SECURE_SETTINGS-oikeutta ei voitu antaa (näytönsäästäjä on silloin valittava television omista asetuksista)." ;;
  warn_music) echo "Varoitus: musiikin käyttöoikeutta ei voitu antaa (Spotify näytönsäästäjässä)." ;;
  done) echo "\nValmis. Seuraava vaihe: avaa SeasonPass puhelimessa → Asetukset → Televisio. Puhelin löytää television,\nja televisio näyttää nelinumeroisen koodin, jonka kirjoitat. Sen jälkeen sarjat lähetetään Wi-Fin kautta.\nPeruuta: ./undo-home.sh %s" ;;
esac; }

_msg_is() { case "$1" in
  need_ip) echo "IP-tölu sjónvarpsins vantar. Notaðu: TV_HOST=192.168.x.y %s   (eða gefðu IP-töluna sem fyrsta færibreytu)" ;;
  no_adb) echo "Fann ekki adb. Settu upp Android platform-tools: https://developer.android.com/tools/releases/platform-tools" ;;
  no_conn1) echo "Náði ekki sambandi við %s:5555 (%s)." ;;
  no_conn2) echo "Athugaðu að þróunarvalkostir og USB-villuleit séu virk í sjónvarpinu, að sjónvarpið og tölvan séu á sama neti," ;;
  no_conn3) echo "og að þú hafir ýtt á 'Leyfa' í spurningunni sem birtist í sjónvarpinu í fyrsta skipti." ;;
  unknown_opt) echo "Óþekktur valkostur: %s" ;;
  fetching) echo "Sæki nýjustu útgáfu frá github.com/%s ..." ;;
  no_apk) echo "Fann enga APK-skrá. Settu SeasonPass-TV.apk við hliðina á skriftunni eða notaðu --apk." ;;
  apk_missing) echo "APK-skráin er ekki til: %s" ;;
  connected) echo "Tengt við %s (Android %s)." ;;
  not_tv) echo "Þetta lítur ekki út eins og Android TV." ;;
  plan_head) echo "\nÞetta gerir skriftan:" ;;
  plan1) echo "  1. setur SeasonPass upp (%s)" ;;
  plan2) echo "  2. gerir SeasonPass að upphafsskjá sjónvarpsins" ;;
  plan3) echo "  3. slekkur á upphafsskjá Google (%s); hann er ekki fjarlægður og hægt er að kveikja aftur á honum með undo-home.sh" ;;
  none_found) echo "enginn fannst" ;;
  plan4) echo "  4. kveikir á hjálparþættinum (aðgengisþjónusta sem sendir aðeins Live TV sjónvarpsins aftur í SeasonPass við ræsingu)" ;;
  plan5) echo "  5. velur SeasonPass sem skjáhvílu og gefur aðgang að því að sýna hvað er í spilun (Spotify); engar tilkynningar eru lesnar" ;;
  prompt) echo "Halda áfram? [j/N] " ;;
  aborted) echo "Hætt við." ;;
  incompatible) echo "SeasonPass-app með öðrum undirritunarlykli er þegar uppsett. Keyrðu aftur með --reinstall (para þarf þá símann aftur)." ;;
  failed) echo "Uppsetningin mistókst." ;;
  warn_secure) echo "Viðvörun: gat ekki gefið WRITE_SECURE_SETTINGS (þá þarf að velja skjáhvíluna í stillingum sjónvarpsins sjálfs)." ;;
  warn_music) echo "Viðvörun: gat ekki gefið aðgang að tónlist (Spotify á skjáhvílunni)." ;;
  done) echo "\nTilbúið. Næsta skref: opnaðu SeasonPass í símanum → Stillingar → Sjónvarp. Síminn finnur sjónvarpið,\nog sjónvarpið sýnir fjögurra stafa kóða sem þú slærð inn. Svo eru þættirnir sendir yfir Wi-Fi.\nAfturkalla: ./undo-home.sh %s" ;;
esac; }

_msg_fo() { case "$1" in
  need_ip) echo "IP-adressan hjá sjónvarpinum manglar. Brúka: TV_HOST=192.168.x.y %s   (ella gev IP-adressuna sum fyrsta argument)" ;;
  no_adb) echo "Fann ikki adb. Set Android platform-tools upp: https://developer.android.com/tools/releases/platform-tools" ;;
  no_conn1) echo "Fekk ikki samband við %s:5555 (%s)." ;;
  no_conn2) echo "Kanna, at menningarmøguleikar og USB-villuleiting eru tendrað á sjónvarpinum, at sjónvarpið og teldan eru á sama netverki," ;;
  no_conn3) echo "og at tú hevur trýst á 'Loyv' í spurninginum, sum kemur á sjónvarpið fyrstu ferð." ;;
  unknown_opt) echo "Ókendur møguleiki: %s" ;;
  fetching) echo "Heinti seinastu útgávu frá github.com/%s ..." ;;
  no_apk) echo "Fann ongan APK. Legg SeasonPass-TV.apk við síðuna av skjalinum ella brúka --apk." ;;
  apk_missing) echo "APK-fílan finst ikki: %s" ;;
  connected) echo "Knýtt at %s (Android %s)." ;;
  not_tv) echo "Hetta sær ikki út sum eitt Android TV." ;;
  plan_head) echo "\nHetta ger skjalið:" ;;
  plan1) echo "  1. setur SeasonPass upp (%s)" ;;
  plan2) echo "  2. ger SeasonPass til byrjanarskíggan á sjónvarpinum" ;;
  plan3) echo "  3. sløkkir byrjanarskíggan hjá Google (%s); hann verður ikki strikaður og kann tendrast aftur við undo-home.sh" ;;
  none_found) echo "eingin funnin" ;;
  plan4) echo "  4. tendrar hjálparan (ein atgongdartænasta, sum bert sendir Live TV á sjónvarpinum aftur í SeasonPass, tá tú tendrar)" ;;
  plan5) echo "  5. velur SeasonPass sum skíggjavarðveitslu og gevur atgongd til at vísa, hvat verður spælt (Spotify); ongar fráboðanir verða lisnar" ;;
  prompt) echo "Halda fram? [j/N] " ;;
  aborted) echo "Steðgað." ;;
  incompatible) echo "Ein SeasonPass-app við øðrum undirskriftarlykli er longu sett upp. Koyr aftur við --reinstall (tá skal telefonin parast av nýggjum)." ;;
  failed) echo "Uppsetanin miseydnaðist." ;;
  warn_secure) echo "Ávaring: kundi ikki geva WRITE_SECURE_SETTINGS (skíggjavarðveitslan skal tá veljast í stillingunum á sjónvarpinum sjálvum)." ;;
  warn_music) echo "Ávaring: kundi ikki geva atgongd til tónleik (Spotify á skíggjavarðveitsluni)." ;;
  done) echo "\nFerdugt. Næsta stig: lat SeasonPass upp á telefonini → Stillingar → Sjónvarp. Telefonin finnur sjónvarpið,\nog sjónvarpið vísir eina kotu við fýra tølum, sum tú skrivar inn. Síðani verða seriurnar sendar um Wi-Fi.\nAngra: ./undo-home.sh %s" ;;
esac; }

_msg_kl() { case "$1" in
  need_ip) echo "TV-p IP-adressea amigaatinneqarpoq. Atoruk: TV_HOST=192.168.x.y %s   (imaluunniit IP-adresse siullermik allaruk)" ;;
  no_adb) echo "adb nassaarineqanngilaq. Android platform-tools ikkussuuk: https://developer.android.com/tools/releases/platform-tools" ;;
  no_conn1) echo "%s:5555-imut atassuteqarsinnaanngilaq (%s)." ;;
  no_conn2) echo "Misissuk: TV-mi udviklerindstillinger USB-fejlsøgninglu ammarsimapput, TV naatsorsuutillu netværk ataatsimik atuupput," ;;
  no_conn3) echo "aamma TV-mi apeqqutip siullermik takuneqartup 'Tillad' naqiterlugu." ;;
  unknown_opt) echo "Toqqagaq nalunaarneqanngitsoq: %s" ;;
  fetching) echo "github.com/%s-imit saqqummersitaq nutaaq aaruk ..." ;;
  no_apk) echo "APK nassaarineqanngilaq. SeasonPass-TV.apk scriptip eqqaanut ikkuuk imaluunniit --apk atoruk." ;;
  apk_missing) echo "APK-fil nassaarineqanngilaq: %s" ;;
  connected) echo "%s-imut atassuteqarpoq (Android %s)." ;;
  not_tv) echo "Una Android TV-mik takkuteqanngilaq." ;;
  plan_head) echo "\nScript una iliorpoq:" ;;
  plan1) echo "  1. SeasonPass ikkussaa (%s)" ;;
  plan2) echo "  2. SeasonPass TV-mi aallarnermi allaffittut ikkussaa" ;;
  plan3) echo "  3. Google-p aallarnermi allaffia unitsippaa (%s); peerneqanngilaq, undo-home.sh-ilu uteqqissinnaavoq" ;;
  none_found) echo "nassaarineqanngilaq" ;;
  plan4) echo "  4. ikiuuteqqaaq ammarpaa (tikinnissamut ikiorsiisoq, TV-p Live TV-ia kisiat tamanna SeasonPass-imut uterfiginnittartoq)" ;;
  plan5) echo "  5. SeasonPass silaqqusatut toqqaavoq sunillu ingerlatsinersoq takutissinnaanermut (Spotify) akuerinninnermik tunniuteqarpoq; nalunaarutit atuarneqanngillat" ;;
  prompt) echo "Ingerlaqqinnissaa? [i/N] " ;;
  aborted) echo "Unitsinneqarpoq." ;;
  incompatible) echo "SeasonPass-imik app atsiuutimik allamik atorluni ikkussuinikkut uteqqipparpoq. --reinstall-imik ingerlaqqiguk (telefonimut atassuteqarnissaq nutaamik pisussaavoq)." ;;
  failed) echo "Ikkussuineq ajorpoq." ;;
  warn_secure) echo "Mianersoqqussut: WRITE_SECURE_SETTINGS tunniunneqarsinnaanngilaq (silaqqusaq TV-p namminermini aaqqissuutaani toqqartariaqarpoq)." ;;
  warn_music) echo "Mianersoqqussut: nipilersuutinut akuerineq tunniunneqarsinnaanngilaq (Spotify silaqqusami)." ;;
  done) echo "\nPeereerpoq. Tulleq: telefonimi SeasonPass ammaruk → Aaqqissuutit → TV. Telefoni TV nassaarivaa,\nTV-lu kode sisamanik normoqartoq takutippaa, taanna allaruk. Taava serierit Wi-Fi atorlugu nassiunneqassapput.\nUteqqiguk: ./undo-home.sh %s" ;;
esac; }

find_adb() {
  if command -v adb >/dev/null 2>&1; then command -v adb; return; fi
  local d
  for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "${LOCALAPPDATA:-}/Android/Sdk"; do
    [ -n "$d" ] && [ -x "$d/platform-tools/adb" ] && { echo "$d/platform-tools/adb"; return; }
  done
  return 1
}

# Sets ADB to "adb -s <ip>:5555" and connects. Needs TV_HOST (or the first argument).
connect_tv() {
  [ -n "${TV_HOST:-}" ] || { msg need_ip "$0" >&2; exit 1; }
  local bin
  bin=$(find_adb) || { msg no_adb >&2; exit 1; }
  ADB_BIN="$bin"
  "$ADB_BIN" connect "$TV_HOST:5555" >/dev/null 2>&1 || true
  ADB="$ADB_BIN -s $TV_HOST:5555"
  local state
  state=$($ADB get-state 2>&1 || true)
  if [ "$state" != "device" ]; then
    msg no_conn1 "$TV_HOST" "$state" >&2
    msg no_conn2 >&2
    msg no_conn3 >&2
    exit 1
  fi
}

installed_launchers() {
  local p
  for p in $LAUNCHERS; do
    $ADB shell "pm list packages $p" 2>/dev/null | tr -d '\r' | grep -qx "package:$p" && echo "$p"
  done
}
