#!/usr/bin/env bash
# Build the clean launcher and install it on the Fire TV. Fire OS keeps Home for Amazon's launcher, so
# the FireHomeWatcher accessibility service brings this launcher up whenever Amazon's home appears.
# Undo: ./uninstall-firetv.sh
set -euo pipefail
cd "$(dirname "$0")/launcher"
TV="${TV_HOST:-192.168.1.248}:5555"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}" ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease -q --console=plain
adb connect "$TV" >/dev/null
adb -s "$TV" install -r app/build/outputs/apk/release/app-release.apk
S(){ adb -s "$TV" shell "$@" </dev/null; }
S pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION || true
S cmd package compile -m speed -f com.gathin.tvhome >/dev/null
# Append our service without dropping any other enabled accessibility service.
cur=$(S settings get secure enabled_accessibility_services | tr -d '\r')
svc=com.gathin.tvhome/com.gathin.tvhome.FireHomeWatcher
case "$cur" in *FireHomeWatcher*) ;; null|"") S settings put secure enabled_accessibility_services "$svc" ;; *) S settings put secure enabled_accessibility_services "$cur:$svc" ;; esac
S settings put secure accessibility_enabled 1
S am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.gathin.tvhome/.MainActivity >/dev/null
echo "Installed. Press Home on the remote."
