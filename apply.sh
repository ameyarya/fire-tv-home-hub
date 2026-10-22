#!/usr/bin/env bash
# Disable (not uninstall) every package in a list on the TV. Undo with ./restore.sh <same list>.
# Usage: ./apply.sh lists/1-ads-telemetry.txt
set -u; . "$(dirname "$0")/lib/tv.sh"
adb connect "$TV" >/dev/null
grep -vE '^\s*(#|$)' "$1" | awk '{print $1}' | while read -r p; do
  printf '%-40s ' "$p"; adb -s "$TV" shell pm disable-user --user 0 "$p" </dev/null 2>&1 | tr -d '\r' | tail -n1
done
