#!/usr/bin/env python3
"""Edit which apps the clean launcher shows, from the Mac. Same file the TV's own tile menu writes.

  ./apps.sh list                 every app with its state (dock / shown / hidden)
  ./apps.sh hide <pkg>...        hide apps from the home screen
  ./apps.sh show <pkg>...        bring hidden apps back
  ./apps.sh dock <pkg>...        set the dock, in order (up to 4)
  ./apps.sh reset                drop your changes, back to the built-in config.xml
"""
import json, os, re, subprocess, sys

TV = os.environ.get("TV_HOST", "192.168.1.248") + ":5555"
PKG = "com.gathin.tvhome"
HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_XML = os.path.join(HERE, "..", "launcher", "app", "src", "main", "res", "values", "config.xml")


def adb(*args, check=True):
    r = subprocess.run(["adb", "-s", TV, *args], capture_output=True, text=True, stdin=subprocess.DEVNULL)
    if check and r.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {r.stderr.strip() or r.stdout.strip()}")
    return r.stdout.replace("\r", "")


def xml_array(name):
    text = open(CONFIG_XML, encoding="utf-8").read()
    m = re.search(rf'<string-array name="{name}">(.*?)</string-array>', text, re.S)
    return re.findall(r"<item>\s*([^<\s]+)\s*</item>", m.group(1)) if m else []


def broadcast(*extra):
    """Ask the launcher's LayoutReceiver; returns the layout it reports."""
    out = adb("shell", "am", "broadcast", "-n", f"{PKG}/.LayoutReceiver", *extra)
    m = re.search(r'data="(.*)"', out)
    if not m:
        sys.exit("the launcher did not answer; is it installed? (./install-firetv.sh)\n" + out.strip())
    cfg = json.loads(m.group(1))
    if not cfg.get("ok", False):
        sys.exit("the launcher could not save the change")
    return cfg


def load():
    return broadcast()


def save(cfg):
    # Quoted for the TV's shell, so an empty list arrives as '' instead of vanishing.
    q = lambda items: "'" + ",".join(items) + "'"
    broadcast("-a", "com.gathin.tvhome.SET_LAYOUT",
              "--esa", "dock", q(cfg["dock_apps"]), "--esa", "hidden", q(cfg["hidden_apps"]))
    reload()


def reset():
    broadcast("-a", "com.gathin.tvhome.RESET_LAYOUT")
    reload()


def reload():
    adb("shell", "am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME",
        "-n", f"{PKG}/.MainActivity", "--ez", "reload", "true")


def apps():
    """Every app the launcher can show: leanback apps plus config.xml extra_apps, package -> activity."""
    found = {}
    for cat in ("android.intent.category.LEANBACK_LAUNCHER", "android.intent.category.LAUNCHER"):
        out = adb("shell", "cmd", "package", "query-activities", "--brief", "-a", "android.intent.action.MAIN", "-c", cat)
        for line in out.split("\n"):
            line = line.strip()
            if "/" in line:
                pkg = line.split("/")[0]
                if cat.endswith("LEANBACK_LAUNCHER") or pkg in xml_array("extra_apps"):
                    found.setdefault(pkg, line)
    found.pop(PKG, None)
    return found


def main():
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help"):
        print(__doc__.strip()); return
    subprocess.run(["adb", "connect", TV], capture_output=True)
    cmd, pkgs = sys.argv[1], sys.argv[2:]
    if cmd == "list":
        cfg, installed = load(), apps()
        dock = [p for p in cfg["dock_apps"] if p in installed][:4]
        for pkg in sorted(installed, key=lambda p: (p not in dock, dock.index(p) if p in dock else 0, p)):
            state = f"dock {dock.index(pkg) + 1}" if pkg in dock else "hidden" if pkg in cfg["hidden_apps"] else "shown"
            print(f"{state:8}  {pkg}")
        return
    if cmd == "reset":
        reset(); print("Back to the built-in layout."); return
    if not pkgs:
        sys.exit(f"usage: apps.sh {cmd} <package>...  (see ./apps.sh list)")
    installed, cfg = apps(), load()
    unknown = [p for p in pkgs if p not in installed]
    if unknown:
        sys.exit("not an app on this TV: " + ", ".join(unknown) + "  (see ./apps.sh list)")
    if cmd == "hide":
        cfg["hidden_apps"] += [p for p in pkgs if p not in cfg["hidden_apps"]]
        cfg["dock_apps"] = [p for p in cfg["dock_apps"] if p not in pkgs]
    elif cmd == "show":
        cfg["hidden_apps"] = [p for p in cfg["hidden_apps"] if p not in pkgs]
    elif cmd == "dock":
        if len(pkgs) > 4:
            sys.exit("the dock has 4 slots")
        cfg["dock_apps"] = list(pkgs)
        cfg["hidden_apps"] = [p for p in cfg["hidden_apps"] if p not in pkgs]
    else:
        sys.exit(__doc__.strip())
    save(cfg)
    print("Saved. Home now shows the change.")


if __name__ == "__main__":
    main()
