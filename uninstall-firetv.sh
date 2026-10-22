#!/usr/bin/env bash
# Remove the clean launcher; Amazon's home screen comes straight back.
. "$(dirname "$0")/lib/tv.sh"; adb connect "$TV" >/dev/null
S(){ adb -s "$TV" shell "$@" </dev/null; }
cur=$(S settings get secure enabled_accessibility_services | tr -d '\r')
new=$(printf '%s' "$cur" | tr ':' '\n' | grep -v FireHomeWatcher | paste -sd: -)
if [ -z "$new" ]; then S settings delete secure enabled_accessibility_services; else S settings put secure enabled_accessibility_services "$new"; fi
S pm uninstall com.gathin.tvhome
S input keyevent KEYCODE_HOME
