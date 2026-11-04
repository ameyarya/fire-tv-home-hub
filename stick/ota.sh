#!/usr/bin/env bash
# Builds the stick's firmware and sends it over Wi-Fi, for a stick that's plugged in somewhere away from the Mac.
# It needs the secret from main/secrets.h; the stick restarts into the new firmware (USB: ./flash.sh instead).
set -euo pipefail
cd "$(dirname "$0")"
[ -f main/secrets.h ] || { echo "Run ./setup.sh first."; exit 1; }
. "${IDF_PATH:-$HOME/esp/esp-idf-v5.4.2}/export.sh" >/dev/null
idf.py build >/dev/null
key=$(sed -n 's/^#define TV_SECRET "\(.*\)"$/\1/p' main/secrets.h)
host="${STICK:-stick.local}"
echo "Sending to $host…"
curl -sS --fail -m 120 --data-binary @build/voice_stick.bin "http://$host/update?key=$key" && echo && echo "Done: the stick is restarting."
