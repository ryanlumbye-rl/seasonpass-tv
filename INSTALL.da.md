*[English](INSTALL.md) · Dansk*

# SeasonPass TV – installation

SeasonPass TV er en lille startskærm til Android TV: stor serie øverst, Næste afsnit, Dine apps, På pause, Opdag serier og
Kommer snart. Serierne kommer fra SeasonPass-appen på din telefon over Wi-Fi. Appen går ikke på nettet andet end til billeder
(TMDB/TVmaze).

Ny version ændrer ikke på noget uden dig: alt nedenfor kan fortrydes med `undo-home.sh`.

## Du skal bruge

- Et Android TV (Android 8 eller nyere) og SeasonPass på telefonen (Indstillinger → Fjernsyn).
- En computer på samme netværk med **adb** (Android platform-tools) og en bash-terminal (macOS, Linux, Windows med Git Bash/WSL).
- Ca. 10 minutter.

## Tre trin

**1. Slå fejlfinding til på tv'et**
Indstillinger → Enhedsindstillinger → Om → tryk 7 gange på "Build" (så er udviklertilstand slået til).
Gå så til Indstillinger → Enhedsindstillinger → Udviklerindstillinger → slå **USB-fejlfinding** (eller "Netværksfejlfinding") til.
Notér tv'ets IP-adresse (Indstillinger → Netværk).

**2. Hent og kør installeren**
```bash
# i mappen med install.sh, lib.sh og SeasonPass-TV.apk
./install.sh 192.168.x.y
```
Første gang spørger tv'et "Tillad USB-fejlfinding?": vælg **Tillad**. Scriptet viser, hvad det vil gøre, og spørger før det starter.

Scriptet taler computerens sprog (engelsk, dansk, svensk, norsk, finsk, islandsk, færøsk eller grønlandsk; ellers engelsk). Vælg selv med `--lang`, fx `./install.sh 192.168.x.y --lang da`. De finske, islandske, færøske og grønlandske tekster er udkast, som ikke er læst igennem af en indfødt.

Vil du kun prøve appen uden at ændre tv'ets startskærm: `./install.sh 192.168.x.y --no-home`. Den åbnes så fra app-listen.

**3. Par med telefonen**
Åbn SeasonPass på telefonen → Indstillinger → Fjernsyn. Skriv koden på fire cifre, tv'et viser.

## Hvad scriptet gør (fuld setup)

1. Installerer appen (`pm install`).
2. Gør den til tv'ets Home (`cmd package set-home-activity`).
3. Slår Googles startskærm fra (`pm disable-user`), fordi den ellers vinder over Home. Den afinstalleres ikke.
4. Slår hjælperen `PowerOnService` til. Det er en tilgængelighedstjeneste, der kun ser, hvornår tv'ets egen Live TV åbner ved tænding, og sender tilbage til SeasonPass. Den læser ingen taster eller tekst.

## Pauseskærm, ur og musik

`install.sh` vælger SeasonPass som tv'ets pauseskærm: serierne som slideshow, et stort ur og dato. Spiller du musik i Spotify-appen
på tv'et, viser pauseskærmen en lille boks med nummer og kunstner (appen læser kun, hvad der spilles; ingen notifikationer).

Alt kan vælges under **Indstillinger** øverst til højre på forsiden: indhold (blandet, dine serier, kommende, opdag serier eller kun ur),
uret (stort, lille, fra), musik til/fra og "Prøv pauseskærmen". Installeren giver appen to ting: `WRITE_SECURE_SETTINGS`, så Indstillinger selv kan skifte pauseskærm,
og godkendelsen som notifikationslytter (`cmd notification allow_listener`), som Android kræver for at se, hvad der spilles. Den sidste kan kun gives med adb. Musik læses kun fra apps, der spiller på selve tv'et; spiller du
fra telefonen over Spotify Connect, kan tv'et ikke se det.

## Fortryd

```bash
./undo-home.sh 192.168.x.y             # Googles startskærm tilbage
./undo-home.sh 192.168.x.y --uninstall # og fjern SeasonPass
```
Uden computer: afinstallér SeasonPass under Indstillinger → Apps. Hvis Googles startskærm ikke vender tilbage:
Apps → Vis systemapps → Android TV Home → Slå til.

## Opdatér

Tv'et giver besked med en pille "Ny version" øverst, og under Indstillinger → Opdatering kan du hente og installere den. Appen tjekker kontrolsummen, og Android tillader kun en opdatering, der er signeret med samme nøgle.
Ved du hellere vil gøre det selv:

Kør `./install.sh` igen med den nye APK. Appen er signeret med samme nøgle hver gang, så parring og data bliver.

## Fejl

- *Fik ikke forbindelse*: tv og computer på samme netværk? USB-fejlfinding slået til? Tryk Tillad på tv'et.
- *UPDATE_INCOMPATIBLE*: der ligger en SeasonPass-app med en anden signering. Kør med `--reinstall` og par igen.
- *Tv'et starter i Live TV*: vent et par sekunder; hjælperen sender tilbage. Er den slået fra, så kør `install.sh` igen.
- Bygget fra kilden uden release-nøgle bliver APK'en signeret med debug-nøglen og kan ikke opdatere en udgivet APK.

## For udviklere: byg selv

Kun hvis du vil ændre koden og lave din egen APK. Se afsnittet "For udviklere" i [README.da.md](README.da.md).
