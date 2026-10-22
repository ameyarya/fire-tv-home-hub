#!/usr/bin/env python3
"""End-to-end UI tests for the clean launcher on the real Fire TV, driven over adb.

Fire OS has no working uiautomator, so the tests read what Home shows from the launcher's own
`dumpsys activity top` output (MainActivity.dump: focused tile, tiles, open menu) and which app is in
front from `dumpsys activity activities`. Keys are injected with `input keyevent`.

  ./run-tests.sh              unit tests + all UI tests
  ./run-tests.sh -k dock      only UI tests whose name contains "dock"
  ./run-tests.sh --reboot     also reboot the TV and check everything comes back

The tests change the dock and hidden apps while they run; your config.json is backed up first and put
back at the end, pass or fail. Screenshots of failures go to test-results/.
"""
import importlib.util, os, re, subprocess, sys, time, traceback

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "tools") if "tests" in __file__ else os.path.dirname(os.path.abspath(__file__)))
import tvconf  # noqa: E402
TV = tvconf.tv()   # TV_HOST, or tv.conf at the repo root
PKG = "com.gathin.tvhome"
HOME = f"{PKG}/.MainActivity"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESULTS = os.path.join(ROOT, "test-results")
DOCK = 4
# Fire OS refuses to disable these two; the debloat lists still name them.
PROTECTED = {"com.amazon.ftvads.deeplinking", "com.amazon.device.crashmanager"}
# Fire OS switches these back on at boot (seen after a reboot); ./apply.sh turns them off again.
RESTORED_AT_BOOT = {"com.amazon.vizzini"}

spec = importlib.util.spec_from_file_location("apps", os.path.join(ROOT, "tools", "apps.py"))
apps = importlib.util.module_from_spec(spec)
spec.loader.exec_module(apps)


# ---- adb helpers ---------------------------------------------------------------------------------

def sh(*cmd, timeout=60):
    r = subprocess.run(["adb", "-s", TV, "shell", *cmd], capture_output=True, text=True,
                       stdin=subprocess.DEVNULL, timeout=timeout)
    return r.stdout.replace("\r", "")


def key(name, times=1, wait=0.6):
    for _ in range(times):
        sh("input", "keyevent", "KEYCODE_" + name)
        time.sleep(wait)


def top():
    m = re.search(r"mResumedActivity: ActivityRecord\{\S+ u0 (\S+)", sh("dumpsys", "activity", "activities"))
    return m.group(1) if m else ""


def wait_for(pred, timeout=10, what="condition"):
    end = time.time() + timeout
    last = None
    while time.time() < end:
        last = pred()
        if last:
            return last
        time.sleep(0.7)
    raise AssertionError(f"timed out waiting for {what}")


def wait_top(prefix, timeout=12):
    return wait_for(lambda: top().startswith(prefix), timeout, f"{prefix} in front (front is {top()})")


def state():
    """The launcher's own dump: hero, focus, tiles [(kind, pkg, label)], popup title and rows."""
    m = re.search(r"mResumedActivity: ActivityRecord\{(\S+) u0 " + re.escape(HOME), sh("dumpsys", "activity", "activities"))
    if not m:
        return None
    # `dumpsys activity top` dumps every task's top activity; take the one on screen.
    out = sh("dumpsys", "activity", "top")
    at = out.find(f"ACTIVITY {HOME} {m.group(1)} ")
    block = out[at:].split("TvHome state:", 1) if at >= 0 else []
    if len(block) < 2:
        return None
    lines = block[1].split("\n")
    s = {"hero": "", "focus": "", "tiles": [], "popup": None, "rows": []}
    for line in lines[1:]:
        line = line.strip()
        if not line or not re.match(r"(hero=|focus=|tile |popup |row )", line):
            if s["tiles"] or line.startswith(("TASK", "ACTIVITY")):
                break
            continue
        if line.startswith("hero="):
            s["hero"] = line[5:]
        elif line.startswith("focus="):
            s["focus"] = line.split()[0][6:]
        elif line.startswith("tile "):
            m = re.match(r"tile \d+ kind=(\d+) pkg=(\S+) art=(\S+) label=(.*)", line)
            s["tiles"].append((int(m.group(1)), m.group(2), m.group(4), m.group(3)))   # kind, pkg, label, art
        elif line.startswith("popup title="):
            s["popup"] = line[len("popup title="):]
        elif line.startswith("row "):
            m = re.match(r"row \d+( focused)? title=(.*)", line)
            s["rows"].append((m.group(2), bool(m.group(1))))
    return s


def pkgs():
    return [t[1] for t in state()["tiles"] if t[0] in (0, 2)]


def home():
    """Home in front with the first dock tile focused and no menu open."""
    key("HOME", wait=1.2)
    wait_top(HOME)
    wait_for(lambda: (st := state()) and st["focus"] == "tile:0" and st["popup"] is None, 8, "Home focused on tile 0")


def focus_tile(i):
    home()
    key("DPAD_DOWN", i // DOCK, wait=0.7)
    key("DPAD_RIGHT", i % DOCK, wait=0.5)
    wait_for(lambda: state()["focus"] == f"tile:{i}", 5, f"focus on tile {i} (focus is {state()['focus']})")


def focus_pkg(pkg):
    focus_tile([t[1] for t in state()["tiles"]].index(pkg))


def focus_pill(name):
    """name: btnInputs, btnWifi or btnSettings; Up from the dock lands on the top bar."""
    home()
    key("DPAD_UP", wait=0.8)
    for _ in range(4):
        if state()["focus"] == f"view:{name}":
            return
        key("DPAD_RIGHT" if name != "btnInputs" else "DPAD_LEFT", wait=0.6)
    raise AssertionError(f"could not focus {name} (focus is {state()['focus']})")


def choose(title):
    """Pick a row in the open menu by its title."""
    rows = wait_for(lambda: state()["rows"], 5, "menu rows")
    titles = [r[0] for r in rows]
    assert title in titles, f"{title!r} not in menu {titles}"
    cur = next((i for i, r in enumerate(rows) if r[1]), 0)
    want = titles.index(title)
    key("DPAD_DOWN" if want > cur else "DPAD_UP", abs(want - cur), wait=0.4)
    assert state()["rows"][want][1], f"row {title!r} did not take focus"
    before = state()["tiles"]
    key("DPAD_CENTER", wait=1.5)
    return before


def wait_tiles_change(before):
    wait_top(HOME)
    return wait_for(lambda: (st := state()) and st["tiles"] != before and st["tiles"], 10, "Home to rebuild")


def layout():
    return apps.load()


def screenshot(name):
    os.makedirs(RESULTS, exist_ok=True)
    path = os.path.join(RESULTS, name + ".png")
    with open(path, "wb") as f:
        f.write(subprocess.run(["adb", "-s", TV, "exec-out", "screencap", "-p"], capture_output=True).stdout)
    return path


# ---- tests ---------------------------------------------------------------------------------------

def test_watcher_is_bound():
    out = sh("dumpsys", "accessibility")
    assert "label=Fire TV home" in out.split("Bound services:", 1)[-1].split("\n", 1)[0], "FireHomeWatcher not bound"


def test_home_button_opens_clean_launcher():
    sh("monkey", "-p", "com.netflix.ninja", "-c", "android.intent.category.LEANBACK_LAUNCHER", "1")
    wait_top("com.netflix.ninja")
    key("HOME", wait=1)
    wait_top(HOME)


def test_rapid_home_presses_end_on_clean_launcher():
    for presses, gap in ((2, 0.3), (3, 0.5), (5, 0.15), (4, 0.8)):
        home()
        for _ in range(presses):
            sh("input", "keyevent", "KEYCODE_HOME")
            time.sleep(gap)
        time.sleep(3)
        assert top().startswith(HOME), f"{presses} Home presses {gap}s apart left {top()} in front"


def test_amazon_home_twice_inside_guard_still_ends_clean():
    # The race behind fast Home presses on the real remote: Amazon's home comes up again right after the
    # watcher has just switched away from it. Forced here with am start, which skips the remote's key path.
    amazon = "am start -a android.intent.action.MAIN -c android.intent.category.HOME -n com.amazon.tv.launcher/.ui.HomeActivity_vNext >/dev/null"
    for gap in (0.1, 0.3, 0.5):
        home()
        sh(f"{amazon}; sleep {gap}; {amazon}")
        time.sleep(3)
        assert top().startswith(HOME), f"Amazon home twice {gap}s apart left {top()} in front"


def test_every_tile_is_full_colour_art():
    """Apps without a banner get one made from their icon, so no tile is a small icon on a grey card."""
    home()
    tiles = state()["tiles"]
    assert all(t[3] in ("banner", "made", "recolored") for t in tiles), tiles
    spotify = [t for t in tiles if t[1] == "com.spotify.tv.android"]
    if spotify:
        assert spotify[0][3] == "recolored", spotify
    made = [t[2] for t in tiles if t[3] == "made"]
    print(f"        made from the icon: {made}", flush=True)
    if "com.homecontrol.tv" in [t[1] for t in tiles]:
        assert next(t for t in tiles if t[1] == "com.homecontrol.tv")[3] == "banner", "Smart Home has its own banner"


def test_dock_matches_config():
    home()
    installed = apps.apps()
    want = [p for p in layout()["dock_apps"] if p in installed][:DOCK]
    got = pkgs()[:len(want)]
    assert got == want, f"dock is {got}, config says {want}"


def test_dock_navigation_updates_title():
    home()
    tiles = state()["tiles"]
    for i in range(DOCK):
        assert state()["focus"] == f"tile:{i}"
        assert state()["hero"] == tiles[i][2], f"title {state()['hero']!r} for tile {tiles[i][2]!r}"
        key("DPAD_RIGHT", wait=0.6)
    assert state()["focus"] == f"tile:{DOCK - 1}", "Right at the end of the dock moved focus off it"


def test_down_reaches_grid_and_up_returns():
    home()
    key("DPAD_DOWN", wait=0.8)
    assert state()["focus"] == f"tile:{DOCK}", f"Down from the dock went to {state()['focus']}"
    key("DPAD_UP", wait=0.8)
    assert state()["focus"] == "tile:0", f"Up from the grid went to {state()['focus']}"


def test_up_from_dock_reaches_top_bar():
    home()
    key("DPAD_UP", wait=0.8)
    assert state()["focus"].startswith("view:btn"), f"Up from the dock went to {state()['focus']}"


def test_hidden_apps_are_not_tiles():
    home()
    shown = set(pkgs())
    leaked = shown & set(layout()["hidden_apps"])
    assert not leaked, f"hidden apps on Home: {leaked}"


def test_hidden_apps_tile_counts_hidden():
    home()
    installed = apps.apps()
    hidden = [p for p in layout()["hidden_apps"] if p in installed]
    tile = [t for t in state()["tiles"] if t[0] == 3]
    if hidden:
        assert tile and tile[0][2] == f"Hidden apps ({len(hidden)})", f"Hidden apps tile is {tile}, {len(hidden)} hidden"
        assert state()["tiles"][-1][0] == 3, "Hidden apps tile is not last"
    else:
        assert not tile, "Hidden apps tile shown with nothing hidden"


def test_fire_os_extra_apps_have_tiles():
    home()
    hidden = set(layout()["hidden_apps"])
    for p in apps.xml_array("extra_apps"):
        if p not in hidden and p in apps.apps():
            assert p in pkgs(), f"{p} has no tile"


def test_every_dock_app_launches_and_home_returns():
    home()
    for i, pkg in enumerate(pkgs()[:DOCK]):
        focus_tile(i)
        key("DPAD_CENTER", wait=1)
        wait_top(pkg, timeout=20)
        key("HOME", wait=1.5)
        wait_top(HOME)


def show_for_test(*pkgs):
    """Shows apps the owner may have hidden; main() puts their own layout back at the end."""
    cfg = apps.load()
    if any(p in cfg["hidden_apps"] for p in pkgs):
        apps.save({"dock_apps": cfg["dock_apps"], "hidden_apps": [p for p in cfg["hidden_apps"] if p not in pkgs]})
        wait_for(lambda: all(p in pkgs_() for p in pkgs), 10, "apps to show")


def pkgs_():
    st = state()
    return [t[1] for t in st["tiles"]] if st else []


def test_back_out_of_app_returns_to_clean_launcher():
    show_for_test("com.amazon.venezia")
    # A grid app that closes on Back (dock apps like Netflix ask "Exit?" first).
    home()
    shown = pkgs()[DOCK:]
    pkg = next((p for p in ("com.amazon.venezia", "com.amazon.tahoe") if p in shown), None)
    assert pkg, "no Appstore or Amazon Kids tile on Home to test with"
    focus_pkg(pkg)
    key("DPAD_CENTER", wait=1)
    wait_top(pkg, timeout=15)
    time.sleep(2)
    for _ in range(4):
        key("BACK", wait=1.5)
        if top().startswith(HOME):
            return
    raise AssertionError(f"Back out of {pkg} left {top()} in front")


def test_appstore_tile_opens_and_home_returns():
    show_for_test("com.amazon.venezia")
    focus_pkg("com.amazon.venezia")
    key("DPAD_CENTER", wait=1)
    wait_top("com.amazon.venezia", timeout=15)
    time.sleep(3)
    assert top().startswith("com.amazon.venezia"), f"Appstore closed itself: {top()}"
    key("HOME", wait=1.5)
    wait_top(HOME)


def test_settings_pill_opens_settings_and_back_returns():
    focus_pill("btnSettings")
    key("DPAD_CENTER", wait=1)
    wait_top("com.amazon.tv.launcher/.ui.MainSettingsActivity")
    time.sleep(3)
    assert top().startswith("com.amazon.tv.launcher/.ui.MainSettingsActivity"), f"Settings closed itself: {top()}"
    key("DPAD_DOWN", wait=0.8)
    key("DPAD_CENTER", wait=1)
    wait_top("com.amazon.tv.settings.v2")
    key("BACK", wait=1.5)
    wait_top("com.amazon.tv.launcher/.ui.MainSettingsActivity")
    key("BACK", wait=1.5)
    wait_top(HOME)


def test_home_button_leaves_settings():
    focus_pill("btnSettings")
    key("DPAD_CENTER", wait=1)
    wait_top("com.amazon.tv.launcher/.ui.MainSettingsActivity")
    key("HOME", wait=1.5)
    wait_top(HOME)


def test_wifi_pill_opens_network_settings():
    focus_pill("btnWifi")
    key("DPAD_CENTER", wait=1)
    wait_top("com.amazon.tv.settings.v2/.tv.network")
    key("HOME", wait=1.5)
    wait_top(HOME)


def test_inputs_pill_explains_fire_os_and_stays_home():
    # Fire OS keeps input switching to Amazon's apps: the pill says how to switch instead of opening Live TV settings.
    focus_pill("btnInputs")
    key("DPAD_CENTER", wait=1.5)
    assert state()["popup"] is None and not state()["rows"], "Inputs opened a menu"
    assert top().startswith(HOME), f"Inputs left Home for {top()}"


def test_tile_menu_for_grid_app():
    focus_pkg("com.amazon.cloud9")
    key("MENU", wait=1)
    st = state()
    assert st["popup"] == "Amazon Silk", f"menu title {st['popup']!r}"
    assert [r[0] for r in st["rows"]] == ["Add to dock", "Hide from home", "App info"], st["rows"]
    assert st["rows"][0][1], "first row not focused"
    key("BACK", wait=1)
    assert state()["popup"] is None


def test_tile_menu_for_dock_app():
    focus_tile(1)
    key("MENU", wait=1)
    titles = [r[0] for r in state()["rows"]]
    assert titles == ["Move left", "Move right", "Remove from dock", "Hide from home", "App info"], titles
    key("BACK", wait=1)
    focus_tile(0)
    key("MENU", wait=1)
    titles = [r[0] for r in state()["rows"]]
    assert "Move left" not in titles and "Move right" in titles, titles
    key("BACK", wait=1)


def test_hide_then_show_from_hidden_apps():
    focus_pkg("com.amazon.cloud9")
    key("MENU", wait=1)
    before = choose("Hide from home")
    wait_tiles_change(before)
    assert "com.amazon.cloud9" not in pkgs(), "Silk still on Home after Hide"
    assert "com.amazon.cloud9" in layout()["hidden_apps"], "Hide was not saved"
    hidden_tile = [i for i, t in enumerate(state()["tiles"]) if t[0] == 3]
    assert hidden_tile, "no Hidden apps tile"
    focus_tile(hidden_tile[0])
    key("DPAD_CENTER", wait=1)
    assert state()["popup"] == "Hidden apps"
    before = choose("Amazon Silk")
    wait_tiles_change(before)
    assert "com.amazon.cloud9" in pkgs(), "Silk did not come back"
    assert "com.amazon.cloud9" not in layout()["hidden_apps"]


def test_add_move_remove_dock():
    original = layout()["dock_apps"]
    focus_pkg("com.amazon.cloud9")
    key("MENU", wait=1)
    wait_tiles_change(choose("Add to dock"))
    assert pkgs()[DOCK - 1] == "com.amazon.cloud9", f"dock is {pkgs()[:DOCK]}"
    assert original[DOCK - 1] not in pkgs()[:DOCK], "full dock did not give up its last app"
    focus_tile(DOCK - 1)
    key("MENU", wait=1)
    wait_tiles_change(choose("Move left"))
    assert pkgs()[DOCK - 2] == "com.amazon.cloud9", f"dock is {pkgs()[:DOCK]}"
    focus_tile(DOCK - 2)
    key("MENU", wait=1)
    wait_tiles_change(choose("Remove from dock"))
    assert "com.amazon.cloud9" not in layout()["dock_apps"]


def test_app_info_opens_app_settings():
    focus_pkg("com.amazon.cloud9")
    key("MENU", wait=1)
    choose("App info")
    wait_top("com.amazon.tv.settings.v2")
    key("HOME", wait=1.5)
    wait_top(HOME)


def test_apps_sh_hide_show_dock():
    run = lambda *a: subprocess.run([os.path.join(ROOT, "apps.sh"), *a], capture_output=True, text=True, timeout=60)
    r = run("hide", "com.amazon.cloud9")
    assert r.returncode == 0, r.stderr
    wait_for(lambda: "com.amazon.cloud9" not in pkgs(), 10, "Silk to leave Home")
    r = run("show", "com.amazon.cloud9")
    assert r.returncode == 0, r.stderr
    wait_for(lambda: "com.amazon.cloud9" in pkgs(), 10, "Silk to come back")
    dock = layout()["dock_apps"][:DOCK]
    r = run("dock", *reversed(dock))
    assert r.returncode == 0, r.stderr
    wait_for(lambda: pkgs()[:len(dock)] == list(reversed(dock)), 10, "reversed dock")
    lst = run("list").stdout
    assert re.search(r"^dock 1\s+" + re.escape(dock[-1]) + "$", lst, re.M), lst
    r = run("hide", "not.a.real.app")
    assert r.returncode != 0, "apps.sh accepted a package that isn't installed"


def test_debloat_still_applied():
    disabled = set(l.split(":", 1)[1].strip() for l in sh("pm", "list", "packages", "-d").split("\n") if ":" in l)
    for f in ("lists/1-ads-telemetry.txt", "lists/2-your-choices.txt"):
        for line in open(os.path.join(ROOT, f)):
            p = line.split("#")[0].strip()
            if p and p not in PROTECTED and p not in disabled:
                if p in RESTORED_AT_BOOT:
                    print(f"        note: Fire OS switched {p} back on at boot; ./apply.sh {f} turns it off", flush=True)
                else:
                    raise AssertionError(f"{p} ({f}) is enabled again")


def test_private_dns_is_adguard():
    assert sh("settings", "get", "global", "private_dns_mode").strip() == "hostname"
    assert sh("settings", "get", "global", "private_dns_specifier").strip() == "dns.adguard-dns.com"


def test_reboot_comes_back_clean():
    sh("reboot")
    time.sleep(25)
    end = time.time() + 240
    while time.time() < end:
        subprocess.run(["adb", "connect", TV], capture_output=True)
        if sh("getprop", "sys.boot_completed").strip() == "1":
            break
        time.sleep(3)
    wait_top(HOME, timeout=60)
    test_watcher_is_bound()
    test_hidden_apps_are_not_tiles()
    test_debloat_still_applied()
    test_private_dns_is_adguard()


# ---- runner --------------------------------------------------------------------------------------

def main():
    args = sys.argv[1:]
    pattern = args[args.index("-k") + 1] if "-k" in args else ""
    tests = [(n, f) for n, f in globals().items() if n.startswith("test_") and callable(f)]
    if "--reboot" not in args:
        tests = [t for t in tests if t[0] != "test_reboot_comes_back_clean"]
    tests = [t for t in tests if pattern in t[0]]
    subprocess.run(["adb", "connect", TV], capture_output=True)
    if not top():
        sys.exit(f"TV not reachable at {TV}: switch ADB debugging on and check the IP")

    saved = apps.load()
    failed = []
    try:
        for name, fn in tests:
            t0 = time.time()
            try:
                fn()
                print(f"  PASS  {name}  ({time.time() - t0:.0f}s)", flush=True)
            except Exception as e:
                shot = screenshot(name)
                msg = str(e) or traceback.format_exc(limit=1).strip().split("\n")[-1]
                print(f"  FAIL  {name}  ({time.time() - t0:.0f}s): {msg}\n        screenshot: {os.path.relpath(shot, ROOT)}", flush=True)
                failed.append(name)
                key("BACK", wait=0.5)
    finally:
        # Put the owner's layout back exactly as it was.
        apps.save({"dock_apps": saved["dock_apps"], "hidden_apps": saved["hidden_apps"]})
        key("HOME", wait=1)
    print(f"\n{len(tests) - len(failed)} passed, {len(failed)} failed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
