#!/usr/bin/env bash
# One-time setup for the voice stick: the Wi-Fi it joins and the TV it talks to. Writes main/secrets.h
# (private, git-ignored), then ./flash.sh builds and flashes. Your Wi-Fi password is typed here, hidden,
# and goes only into that file and onto the stick.
set -euo pipefail
cd "$(dirname "$0")"
. "$(dirname "$0")/../lib/tv.sh"   # the TV whose phone-remote secret the stick needs

now=$(ipconfig getsummary en0 2>/dev/null | awk -F' : ' '/ SSID/ {print $2; exit}')
read -r -p "Wi-Fi name [${now:-}]: " ssid
ssid="${ssid:-$now}"
[ -n "$ssid" ] || { echo "Need a Wi-Fi name."; exit 1; }
read -r -s -p "Wi-Fi password (hidden): " pass; echo
[ -n "$pass" ] || { echo "Need the password."; exit 1; }

echo "Asking the TV for its phone-remote secret (adb)…"
adb connect "$TV" >/dev/null 2>&1 || true
link=$(adb -s "$TV" shell am broadcast -a com.homecontrol.tv.REMOTE_LINK -n com.homecontrol.tv/.ControlReceiver 2>/dev/null \
       | sed -n 's/.*data="\(.*\)"$/\1/p')
secret=$(printf '%s' "$link" | python3 -c 'import json,sys; print(json.load(sys.stdin)["secret"])' 2>/dev/null || true)
address=$(printf '%s' "$link" | python3 -c 'import json,sys; print(json.load(sys.stdin)["address"] or "")' 2>/dev/null || true)
on=$(printf '%s' "$link" | python3 -c 'import json,sys; print(json.load(sys.stdin)["on"])' 2>/dev/null || true)
if [ -z "$secret" ]; then
  echo "Couldn't reach Smart Home on the TV; TV commands will be off until you run this again."
  secret="none"; address="${TV%:*}"
elif [ "$on" != "True" ]; then
  echo "Note: the phone remote is off on the TV (Smart Home → Settings → Phone remote); TV commands need it on."
fi

esc() { printf '%s' "$1" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))'; }
umask 077
cat > main/secrets.h <<H
// Written by setup.sh. Private: git-ignored.
#pragma once
#define WIFI_SSID $(esc "$ssid")
#define WIFI_PASS $(esc "$pass")
#define TV_ADDRESS $(esc "${address:-${TV%:*}}")
#define TV_SECRET $(esc "$secret")
H
unset pass
echo "Saved. Now plug the stick in and run ./flash.sh"
