# fire-tv-home-hub

**Turn a Fire TV into a clean, ad-free home screen and a local smart-home hub, with a phone web app, a screensaver,
a photo viewer and a pocket voice remote.** No cloud: everything runs on the TV and your Wi-Fi.

Built on and tested with a **Hisense Fire TV (AFTCIBFBD1, Fire OS 8 / Android 11)**. The clean launcher and the
debloat tooling come from **[cleanyourtv](https://github.com/Gathin23/cleanyourtv)**; see [CREDITS.md](CREDITS.md).

## Quick start (about 10 minutes)

You need a Mac or Linux computer on the same Wi-Fi as the TV, and [`adb`](https://developer.android.com/tools/releases/platform-tools)
(`brew install android-platform-tools` on a Mac).

1. **On the TV:** Settings → My Fire TV → Developer options → **ADB debugging** on. Note the TV's address
   (Settings → My Fire TV → About → Network).
2. **On the computer:** get this repo and tell it which TV (once):
   ```bash
   git clone https://github.com/ameyarya/fire-tv-home-hub && cd fire-tv-home-hub
   echo 'TV_HOST=192.168.1.50' > tv.conf      # your TV's address; tv.conf is git-ignored
   ```
3. **Install** (the first time, the TV asks to allow debugging from your computer: tick *Always allow*):
   ```bash
   ./install-firetv.sh --download              # the clean home screen (undo: ./uninstall-firetv.sh)
   ./install-smarthome.sh --download           # Smart Home, the phone web app, the screensaver and wallpapers
   ./apply.sh lists/1-ads-telemetry.txt        # optional: switch off Amazon's ads and telemetry (undo: ./restore.sh <list>)
   ./install-photos.sh --download              # optional: the Photos tile
   ```
4. **Open Smart Home on the TV → Settings → Phone remote** and scan the QR code with your phone. That's the phone app:
   `http://home.local:8765` (or `http://<TV address>:8765`).
5. **Settings → Location:** pick your city. The wallpaper then follows the sun and the weather where you live.

Tips: give the TV a fixed address in your router so the link never changes. `--download` fetches ready-made APKs
from the [latest release](https://github.com/ameyarya/fire-tv-home-hub/releases/latest); it needs no Java or Android SDK,
but a release can be a little behind this page. To get everything in the repo, leave `--download` out and the scripts
build from source (JDK 17 and the Android SDK, build-tools 34 / platform 34). Release APKs are signed with the
maintainer's key: if you built and installed one yourself before, uninstall it first (Android won't update across keys).

Add plugs and cameras from the phone app's **+**, or from the computer: `./home.sh find` (and `./home.sh --help`).

## Wallpapers

The home screen and the screensaver show the same scene, picked by the sun and the weather where you are: night,
dawn, golden hour, bright day, or misty when it's overcast or wet (with rain, snow, fog or storms drawn over it).
Ten scenes come with it. Scenes that suit the same moment take turns every 30 seconds. In the phone app, the picture icon at the top opens the
**Wallpapers** gallery: tap a scene to turn it on or off (at least one stays on).

**Add your own:** any wide photo with open sky works; the clock digits sit *behind* the scenery, so the picture needs
sky to put them in (a close-up forest does not).

```bash
pip install pillow numpy scipy
python3 tools/make_scene.py my-photo.jpg lagoon launcher/app/src/main/res/drawable-nodpi --seed 900,40 --grad 16
```

`--seed x,y` points at the sky (in a 1920×1080 picture; repeat it for sky split by trees or mountains) and `--grad`
is how smooth the sky is (raise it for clouds or northern lights). Copy the three files it makes
(`scene_lagoon.jpg`, `_glass.jpg`, `_fg.webp`) into `smarthome/app/src/main/res/drawable` too, then add one `Look` line to
`SceneView.java` in both apps, one entry to `Sky.java` (the scene's mood), and to `SkyStore.java` and `HomeApi.java` (its
picture and name in the gallery). Rebuild and install. The fit of the clock (`top`, `size`) is easiest to tune
while looking at the TV; aim to keep less than half of the digits hidden behind the scenery.

## What's in it

| Folder | What it is |
|---|---|
| [`launcher/`](launcher) | **Clean home screen** (from cleanyourtv, adapted to Fire OS 8). Your apps on a shelf, no ads. Fire OS won't hand Home to another launcher, so an accessibility service brings this one back whenever Amazon's home appears (rapid Home presses included). Icon-only Wi-Fi / Settings pills (and Inputs, where the TV lets an app switch them; Fire OS doesn't); move tiles anywhere; an All apps drawer; opens the screensaver clock after idle minutes, never while something plays. |
| [`smarthome/`](smarthome) | **Smart Home** for the TV, in the style of Apple Home: rooms, favourites, scenes, Kasa and Shelly plugs and sensors (found on the Wi-Fi, re-found when they move), RTSP and MJPEG cameras (a doorbell can bring one up: full screen, or a small picture over what's playing), and automations on a time, sunrise/sunset, or the **TV turning on or off**, with days, time windows and "turn off after". It also: |
| | - serves the **phone web app** at `http://<TV address>:8765` (below) |
| | - a **live wallpaper** that follows the sun and the weather (see [Wallpapers](#wallpapers)); needs a city under Settings → Location and the phone remote on, else it goes by the clock |
| | - the **screensaver clock**: large clock, date and the weather now and for the next hours (Open-Meteo), dims at night |
| | - recovers from the **power-cut trap** (clock reset → Private DNS fails → no internet) by itself |
| [`photos/`](photos) | **Photos** tile: a self-hosted gallery (e.g. [telegram-photo-vault](https://github.com/ameyarya/telegram-photo-vault)) full screen, found on the Wi-Fi, with a remote-driven pointer and ←/→ for previous/next photo. |
| [`stick/`](stick) | **Voice remote** for an M5Stack StickS3: hold A and say "turn on plug one", "good night", "volume up". Recognised on the stick (ESP-SR, offline); plugs switched directly, TV keys through Smart Home. Also an **IR remote** for things with only their own (a lamp, a diffuser): teach it the buttons at `http://stick.local`, and keep it plugged in where it can see them. |
| [`lists/`](lists) | Fire OS 8 debloat lists (disable, never uninstall): ads and telemetry, and optional apps. |
| `tools/`, `*.sh` | Mac command line: debloat and restore, install, set up Smart Home (`home.sh`), add/remove home-screen apps (`apps.sh`), tests. |

## The phone web app

Smart Home runs a small web server on the TV, at **`http://home.local:8765`** or **`http://<TV address>:8765`** (give the TV a fixed address in your
router so the link stays put):

- **Home**, like the TV app: a summary line, filter pills, scenes, cameras, plugs with live watts, automations.
- **+** at the top right: add a plug or sensor (find on Wi-Fi, or by address), a camera, a scene, an automation, a room.
- **✎**: move tiles and whole sections; the order is shared with the TV. In ✎ mode, tap a plug, sensor or camera to rename it, or a scene or automation to edit it.
- In the TV remote, **Screen** (instead of Touchpad): the TV's screen as a picture, a new one each second (Android 11's limit for
  this); tap on it to click what's there, drag to scroll, and type. No cursor to steer in Silk. Netflix and Prime
  show black. (The launcher takes the pictures and clicks; Fire OS cancels touch input from apps, so a tap clicks the
  element under it.)
- **Remotes** section, **TV**: touchpad, Back / Home / Play / Menu, volume, power and typing on the TV.
- **Activity**, behind ☰.
- **Wallpapers** (the picture icon at the top): the gallery; see [Wallpapers](#wallpapers).
- **Energy**, the ⚡ at the top (also on the TV): power now, today, this month and where it is heading, the last 7 days, a chart by hour or by day, each plug's share, the busiest hour and day, what runs all the time, and costs at the electricity price you set. The plugs are read once a minute (while the phone remote is on) and the history stays on the TV (120 days).

Pair a phone once: scan the QR code in Smart Home → Settings → Phone remote, or open `http://<TV address>:8765`
and enter the 4-digit code shown there (change it under Settings → Phone remote → Change code). Optionally (Settings → *Phone remote when the TV is off*) the TV keeps its
Wi-Fi up while off, so the page works any time, at a few watts.

Fire OS lets no ordinary app press keys, so the remote's arrows, OK, Back, Home and Menu go through Fire OS's own
remote service (the one Amazon's Fire TV phone app uses, on port 8080), and work in
every app, Netflix included. Smart Home pairs with it by itself the first time: a PIN flashes up on the TV and the
launcher reads it. Typing goes into the text field on screen through Fire OS's keyboard (so web apps like Spotify
see it), or presses the keys of an app's own on-screen keyboard (Prime Video); in YouTube's search it walks the arrows
over its keyboard (letters, space, - and '; about a second a letter). Enter submits. Netflix draws a keyboard
nothing can read, so type there with the arrows. Play/pause and volume go straight to what's playing.

## Voice remote (optional)

Voice stick: install [ESP-IDF 5.4](https://docs.espressif.com/projects/esp-idf/), then in `stick/` run
`./setup.sh` (asks for your Wi-Fi password, hidden, and fetches the TV's phone-remote secret over adb into the
git-ignored `main/secrets.h`) and `./flash.sh` with the stick on USB-C. It needs your own build: the Wi-Fi details
go into the firmware.

## Tests

`./run-tests.sh --mac-only` runs the unit tests (59 launcher, 219 Smart Home) and the `home.sh` tests; no TV needed. `./run-tests.sh` also runs
UI tests that drive the TV in `tv.conf` over adb, with fake Kasa and Shelly devices served from your computer, and
your own Smart Home setup kept aside in a backup slot meanwhile. Tests that need real devices are skipped unless you
name them: `REAL_PLUG=<Kasa plug address> REAL_CAMERA=<RTSP camera address> ./run-tests.sh`.

## Notes

- Everything stays local. The only outside calls are Open-Meteo (weather, sent your city's coordinates) and
  whatever your own apps do.
- Debloating disables packages; nothing is uninstalled, and `restore.sh` brings them back.
- Not affiliated with Amazon, Hisense, TP-Link (Kasa), Shelly or M5Stack.

MIT License; see [LICENSE](LICENSE) and [CREDITS.md](CREDITS.md).
