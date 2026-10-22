#!/usr/bin/env bash
# Re-enable every package in a list. Usage: ./restore.sh lists/1-ads-telemetry.txt   (or lists/*.txt for all)
set -u; TV="${TV_HOST:-192.168.1.248}:5555"
adb connect "$TV" >/dev/null
for f in "$@"; do grep -vE '^\s*(#|$)' "$f" | awk '{print $1}' | while read -r p; do
  printf '%-40s ' "$p"; adb -s "$TV" shell pm enable "$p" </dev/null 2>&1 | tr -d '\r' | tail -n1
done; done
