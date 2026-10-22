"""The TV's address for the Python tools: TV_HOST, or TV_HOST=... in the git-ignored tv.conf at the repo root."""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def tv_host():
    host = os.environ.get("TV_HOST", "").strip()
    conf = os.path.join(ROOT, "tv.conf")
    if not host and os.path.exists(conf):
        with open(conf) as f:
            for line in f:
                line = line.strip()
                if line.startswith("TV_HOST="):
                    host = line.split("=", 1)[1].strip().strip("'\"")
    if not host:
        sys.exit(f"Which TV? Save its address once:  echo 'TV_HOST=192.168.1.50' > {conf}\n"
                 "(Fire TV: Settings → My Fire TV → About → Network.)")
    return host


def tv():
    """host:5555, as adb wants it."""
    return tv_host() + ":5555"
