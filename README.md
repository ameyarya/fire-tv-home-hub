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
- **✎**: move tiles and whole sections; the order is shared with the TV. Tap an automation (or a scene in ✎ mode) to edit it.
- **Remote**: touchpad, Back / Home / Play / Menu, volume, power and typing on the TV.
- **Activity**, behind ☰.

Pair a phone once: scan the QR code in Smart Home → Settings → Phone remote, or open `http://smart-home.local:8765`
and enter the 4-digit code shown there. Optionally (Settings → *Phone remote when the TV is off*) the TV keeps its
Wi-Fi up while off, so the page works any time, at a few watts.

Fire OS lets no ordinary app press keys, so the remote's arrows, OK, Back, Home and typing go through the launcher's
accessibility service; play/pause and volume go straight to what's playing. Apps that draw their own screens may
not follow the arrows.

## Setup

You need the TV's **ADB debugging** on (Settings → My Fire TV → Developer options), the TV's address, and a Mac
with `adb`, JDK 17 and the Android SDK (build-tools 34, platform 34). Scripts default to the TV at
`192.168.1.248`; set `TV_HOST=<address>` for yours (the voice stick's `setup.sh` takes `TV=<address>:5555`).

```bash
./apply.sh lists/1-ads-telemetry.txt      # debloat (disables packages; undo: ./restore.sh <list>)
./install-firetv.sh                       # the clean launcher (undo: ./uninstall-firetv.sh)
./install-smarthome.sh                    # Smart Home, the phone web app and the screensaver
./home.sh find                            # add your Kasa plugs and cameras from the Mac (./home.sh --help)
```

Photos: `cd photos && ./gradlew assembleRelease`, then `adb install -r app/build/outputs/apk/release/app-release.apk`.

Voice stick: install [ESP-IDF 5.4](https://docs.espressif.com/projects/esp-idf/), then in `stick/` run
`./setup.sh` (asks for your Wi-Fi password, hidden, and fetches the TV's phone-remote secret over adb into the
git-ignored `main/secrets.h`) and `./flash.sh` with the stick on USB-C.

## Tests

`./run-tests.sh` runs the JVM unit tests, then UI tests that drive the real TV over adb (with fake Kasa/Shelly
devices on the Mac, and your Smart Home setup kept aside in a backup slot meanwhile). `--mac-only` skips the TV.
Some tests expect devices on my network; adjust the addresses in `tests/` for yours.

## Notes

- Everything stays local. The only outside calls are Open-Meteo (weather, sent your city's coordinates) and
  whatever your own apps do.
- Debloating disables packages; nothing is uninstalled, and `restore.sh` brings them back.
- Not affiliated with Amazon, Hisense, TP-Link (Kasa), Shelly or M5Stack.

MIT License; see [LICENSE](LICENSE) and [CREDITS.md](CREDITS.md).
