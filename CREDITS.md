# Credits

## cleanyourtv

This project started from **[cleanyourtv](https://github.com/Gathin23/cleanyourtv)** by Gathin, MIT License.
Thank you: the clean launcher and the debloat tooling here are built on it.

Taken from cleanyourtv, then changed for Fire OS 8:

| Here | From cleanyourtv | Changed |
|---|---|---|
| `launcher/` | `launcher/` | Fire TV: FireHomeWatcher (brings the launcher back when Amazon's home appears, survives rapid Home presses), Settings button, icon-only top pills plus a Smart Home pill, consistent "made" tiles and recoloured banners, moving tiles, an All apps drawer, the idle-clock timer, and the phone remote's key handling |
| `tools/tv.py`, `tools/b64push.py`, `tools/disable.*`, `tools/restore.*`, `tools/theme.*`, `tools/requirements.txt` | `tools/` | as upstream |

Everything else (Smart Home, the phone web app, Photos, the voice stick, the Hisense Fire TV debloat lists, the
Mac CLIs, the install and test scripts) was written for this project. The Hisense Fire TV debloat list was also
contributed back upstream: [Gathin23/cleanyourtv#545](https://github.com/Gathin23/cleanyourtv/pull/545).

cleanyourtv's license notice, as it requires:

```
MIT License

Copyright (c) 2026 Gathin

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Others

- **Outfit** font: SIL Open Font License 1.1 (`launcher/FONT-LICENSE-OFL.txt`).
- **Open-Meteo** for the screensaver's weather (free, no key).
- **M5Unified / M5GFX** (M5Stack) and **ESP-SR** (Espressif) for the voice stick, fetched at build time.
- **ZXing core** (QR codes) and **JmDNS** (`smart-home.local`), Apache 2.0, fetched by Gradle.
