# fire-tv-home-hub

**Turn a Fire TV into a clean, ad-free home screen and a local smart-home hub, with a phone web app, a screensaver,
a photo viewer and a pocket voice remote.** No cloud: everything runs on the TV and your Wi-Fi.

Built on and tested with a **Hisense Fire TV (AFTCIBFBD1, Fire OS 8 / Android 11)**. The clean launcher and the
debloat tooling come from **[cleanyourtv](https://github.com/Gathin23/cleanyourtv)**; see [CREDITS.md](CREDITS.md).

## What's in it

| Folder | What it is |
|---|---|
| [`launcher/`](launcher) | **Clean home screen** (from cleanyourtv, adapted to Fire OS 8). Your apps on a shelf, no ads. Fire OS won't hand Home to another launcher, so an accessibility service brings this one back whenever Amazon's home appears (rapid Home presses included). Icon-only Inputs / Wi-Fi / Settings pills plus a Smart Home pill; move tiles anywhere; an All apps drawer; opens the screensaver clock after idle minutes, never while something plays. |
| [`smarthome/`](smarthome) | **Smart Home** for the TV, in the style of Apple Home: rooms, favourites, scenes, Kasa and Shelly plugs and sensors (found on the Wi-Fi, re-found when they move), RTSP cameras, and automations on a time, sunrise/sunset, or the **TV turning on or off**, with days, time windows and "turn off after". It also: |
| | - serves the **phone web app** at `http://smart-home.local:8765` (below) |
| | - the **screensaver clock**: large clock, date and the weather now and for the next hours (Open-Meteo), dims at night |
| | - recovers from the **power-cut trap** (clock reset → Private DNS fails → no internet) by itself |
| [`photos/`](photos) | **Photos** tile: a self-hosted gallery (e.g. [telegram-photo-vault](https://github.com/ameyarya/telegram-photo-vault)) full screen, found on the Wi-Fi, with a remote-driven pointer and ←/→ for previous/next photo. |
| [`stick/`](stick) | **Voice remote** for an M5Stack StickS3: hold A and say "turn on plug one", "good night", "volume up". Recognised on the stick (ESP-SR, offline); plugs switched directly, TV keys through Smart Home. |
| [`lists/`](lists) | Fire OS 8 debloat lists (disable, never uninstall): ads and telemetry, and optional apps. |
| `tools/`, `*.sh` | Mac command line: debloat and restore, install, set up Smart Home (`home.sh`), add/remove home-screen apps (`apps.sh`), tests. |

## The phone web app

Smart Home runs a small web server on the TV (port 8765), announced as **`smart-home.local`**:

- **Home**, like the TV app: a summary line, filter pills, scenes, cameras, plugs with live watts, automations.
- **+** at the top right: add a plug or sensor (find on Wi-Fi, or by address), a camera, a scene, an automation, a room.
- **✎**: move tiles and whole sections; the order is shared with the TV. In ✎ mode, tap a plug, sensor or camera to rename it, or a scene or automation to edit it.
- **Remote**: touchpad, Back / Home / Play / Menu, volume, power and typing on the TV.
- **Activity**, behind ☰.

**HTTPS (optional):** run `./https-setup.sh` once. It makes your home's own certificate authority (kept on your
computer in `~/.config/fire-tv-home-hub`), signs a certificate for `smart-home.local` and hands it to the TV, which
then serves **`https://smart-home.local:8443`** (and `https://<TV address>:8443`, which the QR code uses: it opens at once, where a `.local` name can take ~5 s to look up; give the TV a fixed address in your router so it stays valid) (plain http on 8765 redirects there; the API stays on http for the
voice stick). On each phone, open `http://smart-home.local:8765/setup` once to trust the authority. The page has a
home-screen icon either way.

Pair a phone once: scan the QR code in Smart Home → Settings → Phone remote, or open `http://smart-home.local:8765`
and enter the 4-digit code shown there (change it under Settings → Phone remote → Change code). Optionally (Settings → *Phone remote when the TV is off*) the TV keeps its
Wi-Fi up while off, so the page works any time, at a few watts.

Fire OS lets no ordinary app press keys, so the remote's arrows, OK, Back, Home and Menu go through Fire OS's own
remote service (the one Amazon's Fire TV phone app uses, on port 8080; that's why the page is on 8443), and work in
every app, Netflix included. Smart Home pairs with it by itself the first time: a PIN flashes up on the TV and the
launcher reads it. Typing, and the keys when that service isn't there, go through the launcher's accessibility
service; play/pause and volume go straight to what's playing.

## Setup

1. On the TV: Settings → My Fire TV → Developer options → **ADB debugging** on. Note the TV's address
   (Settings → My Fire TV → About → Network).
2. On a Mac (or Linux) with [`adb`](https://developer.android.com/tools/releases/platform-tools), tell the scripts
   which TV, once:
   ```bash
   echo 'TV_HOST=192.168.1.50' > tv.conf      # your TV's address; tv.conf is git-ignored
   ```
3. Install, using the **ready-made APKs** from the [latest release](https://github.com/ameyarya/fire-tv-home-hub/releases/latest)
   (no Java or Android SDK needed):
   ```bash
   ./apply.sh lists/1-ads-telemetry.txt        # debloat: disables packages (undo: ./restore.sh <list>)
   ./install-firetv.sh --download              # the clean launcher (undo: ./uninstall-firetv.sh)
   ./install-smarthome.sh --download           # Smart Home, the phone web app and the screensaver
   ./install-photos.sh --download              # optional: Photos
   ./home.sh find                              # optional: add Kasa plugs and cameras from the Mac (./home.sh --help)
   ```
   The first time, the TV asks to allow debugging from your computer: tick *Always allow*. Leave out `--download`
   to build from source instead (JDK 17 and the Android SDK, build-tools 34 / platform 34).

Then open **Smart Home → Settings → Phone remote** on the TV to pair your phone.

The release APKs are signed with the maintainer's key. If you had built and installed one yourself before, uninstall
it first (Android won't update an app signed with a different key).

Voice stick: install [ESP-IDF 5.4](https://docs.espressif.com/projects/esp-idf/), then in `stick/` run
`./setup.sh` (asks for your Wi-Fi password, hidden, and fetches the TV's phone-remote secret over adb into the
git-ignored `main/secrets.h`) and `./flash.sh` with the stick on USB-C. It needs your own build: the Wi-Fi details
go into the firmware.

## Tests

`./run-tests.sh --mac-only` runs the unit tests and the `home.sh` tests; no TV needed. `./run-tests.sh` also runs
UI tests that drive the TV in `tv.conf` over adb, with fake Kasa and Shelly devices served from your computer, and
your own Smart Home setup kept aside in a backup slot meanwhile. Tests that need real devices are skipped unless you
name them: `REAL_PLUG=<Kasa plug address> REAL_CAMERA=<RTSP camera address> ./run-tests.sh`.

## Notes

- Everything stays local. The only outside calls are Open-Meteo (weather, sent your city's coordinates) and
  whatever your own apps do.
- Debloating disables packages; nothing is uninstalled, and `restore.sh` brings them back.
- Not affiliated with Amazon, Hisense, TP-Link (Kasa), Shelly or M5Stack.

MIT License; see [LICENSE](LICENSE) and [CREDITS.md](CREDITS.md).
