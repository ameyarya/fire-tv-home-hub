#!/usr/bin/env bash
# Builds the voice stick's firmware and flashes it over USB-C (the stick shows up as /dev/cu.usbmodem*).
set -euo pipefail
cd "$(dirname "$0")"
[ -f main/secrets.h ] || { echo "Run ./setup.sh first."; exit 1; }
. "${IDF_PATH:-$HOME/esp/esp-idf-v5.4.2}/export.sh" >/dev/null
port="${PORT:-$(ls /dev/cu.usbmodem* 2>/dev/null | head -1)}"
[ -n "$port" ] || { echo "Stick not found on USB."; exit 1; }
idf.py set-target esp32s3 >/dev/null 2>&1 || true
idf.py -p "$port" build flash
