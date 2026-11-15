# SeasonPass TV

En lille startskærm til Android TV, bygget til at følge dine serier: stor serie øverst, **Næste afsnit**, **Dine apps**, **På pause**,
**Opdag serier** og **Kommer snart**. Serierne kommer fra SeasonPass-appen på din telefon over Wi-Fi; tv'et regner selv ud, hvornår
et afsnit er udkommet.

- Ren Java, ingen afhængigheder, ca. 150 KB.
- **Pauseskærm** med serierne som slideshow (cirkel-overgang med blød kant og stille zoom), stort ur og det, der spilles i Spotify på tv'et.
- Indstillinger på tv'et: pauseskærmens indhold, ur, musik og opdatering.
- Opdag-rækken har knapperne *Har set den* og *Ikke for mig*, som telefonen henter og følger op på.
- Giver besked om en ny version og kan opdatere sig selv (hentet fra GitHub Releases, SHA-256-tjekket).
- Kan gøres til tv'ets startskærm og fortrydes igen.

## Installér

Hent `SeasonPass-TV.apk` og `install.sh`/`lib.sh` fra [seneste udgivelse](https://github.com/ryanlumbye-rl/seasonpass-tv/releases/latest),
eller klon repoet, og følg **[INSTALL.md](INSTALL.md)**. Kort: slå USB-fejlfinding til på tv'et, kør `./install.sh <tv-ip>`, og par med telefonen.

## Byg selv

```bash
./gradlew assembleRelease        # JDK 17+ og Android SDK (sdk.dir i local.properties)
```
`./release.sh` laver den signerede APK til udgivelse og kræver en nøgle uden for repoet (`~/.seasonpass-keys/`); uden nøgle signeres med debug-nøglen.

## Licens

MIT, se [LICENSE](LICENSE). Tredjepart og kreditering: [THIRD_PARTY.md](THIRD_PARTY.md).
