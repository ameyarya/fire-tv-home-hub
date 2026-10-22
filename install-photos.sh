#!/usr/bin/env bash
# Install the Photos app (a self-hosted gallery, full screen) on the TV.
#   ./install-photos.sh              build it here (needs JDK 17 and the Android SDK)
#   ./install-photos.sh --download   use the ready-made APK from the latest GitHub release
set -euo pipefail
. "$(dirname "$0")/lib/tv.sh"
APK=$(apk photos photos "${1:-}")
adb connect "$TV" >/dev/null
adb -s "$TV" install -r "$APK"
echo "Installed. Open Photos from the home screen (All apps, or add it to Home)."
