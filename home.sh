#!/usr/bin/env bash
# Set up the Smart Home dashboard from the Mac. ./home.sh --help
exec python3 -I "$(dirname "$0")/tools/home.py" "$@"
