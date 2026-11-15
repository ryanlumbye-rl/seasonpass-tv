*English · [Dansk](INSTALL.da.md)*

# SeasonPass TV – installation

SeasonPass TV is a small home screen for Android TV: a big show on top, Next episode, Your apps, Paused, Discover series and
Coming soon. The shows come from the SeasonPass app on your phone over Wi-Fi. The app does not go online except to fetch images
(TMDB/TVmaze).

A new version changes nothing without you: everything below can be undone with `undo-home.sh`.

## You need

- An Android TV (Android 8 or newer) and SeasonPass on your phone (Settings → TV).
- A computer on the same network with **adb** (Android platform-tools) and a bash terminal (macOS, Linux, Windows with Git Bash/WSL).
- About 10 minutes.

## Three steps

**1. Turn on debugging on the TV**
Settings → Device Preferences → About → press "Build" 7 times (this turns on developer mode).
Then go to Settings → Device Preferences → Developer options → turn on **USB debugging** (or "Network debugging").
Note the TV's IP address (Settings → Network).

**2. Download and run the installer**
```bash
# in the folder with install.sh, lib.sh and SeasonPass-TV.apk
./install.sh 192.168.x.y
```
The first time, the TV asks "Allow USB debugging?": choose **Allow**. The script shows what it will do and asks before it starts.

If you only want to try the app without changing the TV's home screen: `./install.sh 192.168.x.y --no-home`. It is then opened from the app list.

**3. Pair with your phone**
Open SeasonPass on your phone → Settings → TV. Enter the four-digit code the TV shows.

## What the script does (full setup)

1. Installs the app (`pm install`).
2. Makes it the TV's Home (`cmd package set-home-activity`).
3. Turns off Google's home screen (`pm disable-user`), because it would otherwise win over Home. It is not uninstalled.
4. Turns on the helper `PowerOnService`. It is an accessibility service that only sees when the TV's own Live TV opens at power-on and sends you back to SeasonPass. It reads no keys or text.

## Screensaver, clock and music

`install.sh` selects SeasonPass as the TV's screensaver: your shows as a slideshow, a big clock and the date. If you play music in the Spotify app
on the TV, the screensaver shows a small box with the track and artist (the app only reads what is playing; no notifications).

Everything can be chosen under **Settings** at the top right of the home screen: content (mixed, your shows, upcoming, discover series or clock only),
the clock (large, small, off), music on/off and "Try the screensaver". The installer gives the app two things: `WRITE_SECURE_SETTINGS`, so Settings can switch the screensaver itself,
and the approval as a notification listener (`cmd notification allow_listener`), which Android requires to see what is playing. The latter can only be granted with adb. Music is only read from apps that play on the TV itself; if you
play from your phone over Spotify Connect, the TV cannot see it.

## Undo

```bash
./undo-home.sh 192.168.x.y             # Google's home screen back
./undo-home.sh 192.168.x.y --uninstall # and remove SeasonPass
```
Without a computer: uninstall SeasonPass under Settings → Apps. If Google's home screen does not come back:
Apps → Show system apps → Android TV Home → Enable.

## Update

The TV shows a "New version" pill at the top, and under Settings → Update you can download and install it. The app checks the checksum, and Android only allows an update signed with the same key.
If you would rather do it yourself:

Run `./install.sh` again with the new APK. The app is signed with the same key every time, so pairing and data stay.

## Troubleshooting

- *Could not connect*: are the TV and computer on the same network? Is USB debugging turned on? Press Allow on the TV.
- *UPDATE_INCOMPATIBLE*: there is a SeasonPass app signed with a different key. Run with `--reinstall` and pair again.
- *The TV starts in Live TV*: wait a few seconds; the helper sends you back. If it is turned off, run `install.sh` again.
- Built from source without a release key, the APK is signed with the debug key and cannot update a published APK.

## For developers: build it yourself

Only if you want to change the code and make your own APK. See the "For developers" section in [README.md](README.md).
