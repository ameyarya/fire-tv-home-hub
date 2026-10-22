#!/usr/bin/env bash
# Install the Smart Home app on the TV (it opens from the Smart Home pill on the clean home screen).
#   ./install-smarthome.sh              build it here (needs JDK 17 and the Android SDK)
#   ./install-smarthome.sh --download   use the ready-made APK from the latest GitHub release
set -euo pipefail
. "$(dirname "$0")/lib/tv.sh"
APK=$(apk smarthome smart-home "${1:-}")
adb connect "$TV" >/dev/null
adb -s "$TV" install -r "$APK"
# Lets Smart Home switch Private DNS to Automatic and back after a power cut (DnsRescue).
adb -s "$TV" shell pm grant com.homecontrol.tv android.permission.WRITE_SECURE_SETTINGS
# Lets the phone page open a camera on the TV (from the background).
adb -s "$TV" shell appops set com.homecontrol.tv SYSTEM_ALERT_WINDOW allow
adb -s "$TV" shell cmd package compile -m speed -f com.homecontrol.tv </dev/null >/dev/null
echo "Installed. Open Smart Home from the home screen."
