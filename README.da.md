*[English](README.md) · Dansk*

# SeasonPass TV

En startskærm til Android TV, der viser dine serier: stor serie øverst, **Næste afsnit**, **Dine apps**, **På pause**, **Opdag serier**
og **Kommer snart**. Den har også en pauseskærm med serie-slideshow, stort ur og det, der spilles i Spotify.

> **Kræver SeasonPass-appen på din telefon.** SeasonPass TV er kun den ene halvdel: det er telefonappen, der kender dine serier og sender dem
> til tv'et over Wi-Fi. SeasonPass-appen er et separat projekt og ikke en del af dette repo. Uden den viser tv'et kun dine apps og en tom serieliste.

## Det kan den

- Viser næste afsnit af dine serier og regner selv ud, hvornår et nyt afsnit er udkommet, også når telefonen er slukket.
- **Opdag serier** med forslag ud fra det, du ser, og knapperne *Har set den* og *Ikke for mig*, som telefonen henter og følger op på.
- **Pauseskærm** med serierne som slideshow (cirkel-overgang med blød kant og stille zoom), stort ur og en lille boks med det, der spilles i Spotify-appen på tv'et.
- **Indstillinger** på tv'et: pauseskærmens indhold, ur, musik og opdatering. Avataren viser forbogstavet af dit navn fra telefonen.
- Giver besked om en ny version og kan opdatere sig selv (hentet fra GitHub, med kontrolsum).
- Kan gøres til tv'ets startskærm og fortrydes igen. Lille: ca. 150 KB, ingen afhængigheder.

## Det skal du bruge

1. Et **Android TV** (Android 8 eller nyere).
2. **SeasonPass-appen** på telefonen.
3. En **computer** til selve installationen, én gang (Mac, Linux eller Windows med Git Bash/WSL). Det tager ca. 10 minutter.

## Installér

Hent `SeasonPass-TV.apk`, `install.sh`, `lib.sh` og `undo-home.sh` fra [seneste udgivelse](https://github.com/ryanlumbye-rl/seasonpass-tv/releases/latest)
og læg dem i samme mappe. Slå USB-fejlfinding til på tv'et, kør `./install.sh <tv-ip>`, og par med telefonen.
Trin for trin: **[INSTALL.da.md](INSTALL.da.md)**.

## Licens og kreditering

MIT, se [LICENSE](LICENSE). Tredjepart og kreditering (Urbanist, TMDB, TVmaze): [THIRD_PARTY.da.md](THIRD_PARTY.da.md).

---

## For udviklere (kan springes over)

Det her er kun for dig, der vil ændre i koden og lave din egen APK. Hvis du bare vil bruge appen, er du færdig ovenfor.

```bash
./gradlew assembleRelease        # kræver JDK 17+ og Android SDK (sdk.dir i local.properties)
```

APK'en ender i `app/build/outputs/apk/release/`. Uden en egen signeringsnøgle signeres den med en debug-nøgle, og så kan den ikke
opdatere den udgivne APK (Android kræver samme nøgle). `./release.sh` laver en signeret APK til udgivelse og kræver en nøgle uden for repoet
(`~/.seasonpass-keys/`). Under udvikling er det nemmeste `TV_HOST=<tv-ip> ./set-home.sh`, der bygger og installerer i ét hug.
