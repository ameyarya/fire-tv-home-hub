#!/usr/bin/env bash
# Build the Smart Home app and install it on the TV. It shows up as a tile on the clean home screen.
set -euo pipefail
cd "$(dirname "$0")/smarthome"
TV="${TV_HOST:-192.168.1.248}:5555"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}" ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease -q --console=plain
adb connect "$TV" >/dev/null
adb -s "$TV" install -r app/build/outputs/apk/release/app-release.apk
# Lets Smart Home switch Private DNS to Automatic and back after a power cut (DnsRescue).
adb -s "$TV" shell pm grant com.homecontrol.tv android.permission.WRITE_SECURE_SETTINGS
adb -s "$TV" shell cmd package compile -m speed -f com.homecontrol.tv </dev/null >/dev/null
echo "Installed. Open Smart Home from the home screen."
