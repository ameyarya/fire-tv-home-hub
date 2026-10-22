#!/usr/bin/env bash
# Add and remove apps on the clean home screen from the Mac. Run ./apps.sh for help.
. "$(dirname "$0")/lib/tv.sh"
exec python3 -I "$(dirname "$0")/tools/apps.py" "$@"
