#!/usr/bin/env python3
"""UI tests for the Smart Home app on the real TV, against fake Shelly devices on the Mac.

tools/fake_shelly.py serves four devices on 127.0.0.1:8081-8084 (two Gen3 plugs, a Gen1 plug, a Gen3
temperature/humidity sensor). `adb reverse` makes them the TV's 127.0.0.1 too, and `dns-sd -P` announces
them at that address, so discovery, control and polling all run for real without opening the Mac's
firewall. State on the screen is read from the app's own dumpsys output.

  ./run-tests.sh                       launcher + Smart Home tests
  python3 -I tests/smarthome_test.py   only these; -k <text> to filter

The app is reset at the start and the end. If it holds anything that isn't a test device, the tests
refuse to run rather than wipe it.
"""
import importlib.util, json, os, re, subprocess, sys, time, traceback, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
spec = importlib.util.spec_from_file_location("ui", os.path.join(ROOT, "tests", "ui_test.py"))
ui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ui)
sh, key, top, wait_for, wait_top, TV = ui.sh, ui.key, ui.top, ui.wait_for, ui.wait_top, ui.TV

APP = "com.homecontrol.tv"
MAIN = f"{APP}/.MainActivity"
PORTS = (8081, 8082, 8083, 8084)
LAMP, GEN1, SENSOR, FAN = PORTS
KASA_PORT, KASA_HOOKS = 9998, 9997   # tools/fake_kasa.py
FAKE_NAMES = {"Desk lamp", "Fan", "SHPLG-S 1", "Bedroom"}


# ---- fake devices --------------------------------------------------------------------------------

def fake(port):
    return json.loads(urllib.request.urlopen(f"http://127.0.0.1:{port}/_state", timeout=3).read())


def kasa_fake():
    return json.loads(urllib.request.urlopen(f"http://127.0.0.1:{KASA_HOOKS}/_state", timeout=3).read())


def kasa_set(on):
    urllib.request.urlopen(f"http://127.0.0.1:{KASA_HOOKS}/_set?on={str(on).lower()}", timeout=3).read()


def fake_set(port, **q):
    qs = "&".join(f"{k}={str(v).lower()}" for k, v in q.items())
    urllib.request.urlopen(f"http://127.0.0.1:{port}/_set?{qs}", timeout=3).read()


def receiver(*extra):
    out = sh("am", "broadcast", "-n", f"{APP}/.ControlReceiver", *extra)
    m = re.search(r'data="(.*)"', out)
    assert m, out
    return json.loads(m.group(1))


# ---- reading the screen --------------------------------------------------------------------------

def state():
    m = re.search(r"mResumedActivity: ActivityRecord\{(\S+) u0 " + re.escape(MAIN), sh("dumpsys", "activity", "activities"))
    if not m:
        return None
    out = sh("dumpsys", "activity", "top")
    at = out.find(f"ACTIVITY {MAIN} {m.group(1)} ")
    if at < 0 or "SmartHome state:" not in out[at:]:
        return None
    s = {"tiles": [], "focus": None, "focus_raw": "", "status": "", "toast": "", "dialog": None, "items": [], "focused": None,
         "checked": set(), "ok": False, "filter": ""}
    for line in out[at:].split("SmartHome state:", 1)[1].split("\n")[1:]:
        line = line.strip()
        if line.startswith(("TASK", "ACTIVITY")):
            break
        if line.startswith("status="):
            s["status"] = line[7:]
        elif line.startswith("toast="):
            s["toast"] = line[6:]
        elif line.startswith("focus="):
            s["focus_raw"] = line[6:]
            s["focus"] = int(line.split(":")[1]) if line.startswith("focus=tile:") else None
        elif line.startswith("filter="):
            s["filter"] = line[7:]
        elif line.startswith("tile "):
            t = re.match(r"tile (\d+) row=(\d+) col=(\d+) kind=(\S+) id=(.*?) section=(\S*) title=(.*?) \| state=(.*?) \| detail=(.*)$", line)
            s["tiles"].append(dict(i=int(t[1]), row=int(t[2]), col=int(t[3]), kind=t[4], id=t[5], section=t[6], title=t[7],
                                   state=t[8], detail=t[9]))
        elif line.startswith("dialog title="):
            s["dialog"] = line[13:]
        elif line.startswith("dialog item="):
            item = line[12:]
            name = item.replace(" [focused]", "").replace(" [checked]", "")
            if "[focused]" in item: s["focused"] = len(s["items"])
            if "[checked]" in item: s["checked"].add(name)
            s["items"].append(name)
        elif line.startswith("dialog okFocused="):
            s["ok"] = line.endswith("true")
    return s


def tile(kind, title=None, section=None):
    for t in state()["tiles"]:
        if t["kind"] == kind and (title is None or t["title"] == title) and (section is None or t["section"] == section):
            return t
    return None


def devices():
    """Device and sensor tiles, once each (a favorite is also listed under Favorites)."""
    return [t for t in state()["tiles"] if t["kind"] in ("device", "sensor") and t["section"] != "fav"]


def headers():
    return [t["title"] for t in state()["tiles"] if t["kind"] == "header"]


def open_app():
    sh("am", "start", "-n", MAIN)
    wait_top(MAIN)
    wait_for(lambda: (s := state()) and s["focus_raw"] not in ("", "none"), 8, "Smart Home to settle")


def focus_view(name):
    """Header buttons (btnAdd, btnActivity, btnSettings): Up out of the grid and filters, then along the row."""
    order = ["btnAdd", "btnActivity", "btnSettings"]
    for _ in range(25):
        f = state()["focus_raw"]
        if f == "view:" + name:
            return
        if f.startswith("view:") and f[5:] in order:
            key("DPAD_RIGHT" if order.index(f[5:]) < order.index(name) else "DPAD_LEFT", wait=0.4)
        else:
            key("DPAD_UP", wait=0.4)
    raise AssertionError(f"could not focus {name} (focus {state()['focus_raw']})")


def press_add(*path):
    """+ Add, then each choice in turn."""
    focus_view("btnAdd")
    key("DPAD_CENTER", wait=1)
    for step in path:
        choose(step)


DEVICE = f"{APP}/.DeviceActivity"


def device_state():
    out = sh("dumpsys", "activity", "top")
    if "Device state:" not in out:
        return None
    s = {"items": [], "dialog": None, "toast": ""}
    for line in out.split("Device state:", 1)[1].split("\n")[1:]:
        line = line.strip()
        if line.startswith(("TASK", "ACTIVITY")):
            break
        k, _, v = line.partition("=")
        if k == "dialog item":
            s["items"].append(v.replace(" [focused]", "").replace(" [checked]", ""))
        elif k == "dialog title":
            s["dialog"] = v
        else:
            s[k] = v
    return s


def open_device(title):
    focus(tile("device", title) or tile("sensor", title))
    key("MENU", wait=1.5)
    wait_top(DEVICE)
    return wait_for(lambda: (d := device_state()) and d.get("name") == title and d, 8, f"{title}'s screen")


def device_button(name):
    order = ["btnToggle", "btnFavorite", "btnRoom", "btnRename", "btnRemove"]
    for _ in range(10):
        f = device_state()["focus"]
        if f == name:
            key("DPAD_CENTER", wait=1.2)
            return
        key("DPAD_RIGHT" if f in order and order.index(f) < order.index(name) else "DPAD_LEFT" if f in order else "DPAD_DOWN", wait=0.4)
    raise AssertionError(f"could not reach {name}")


def focus(t):
    """Moves focus to a tile with the D-pad, using the rows and columns the app reports. The tile is looked
    up again on every step: discovery can add tiles while we steer, moving it."""
    for _ in range(40):
        s = state()
        f = next((x for x in s["tiles"] if x["i"] == s["focus"]), None)
        t = next((x for x in s["tiles"] if x["kind"] == t["kind"] and x["title"] == t["title"]), t)
        if f and f["i"] == t["i"]:
            return
        if f is None:
            key("DPAD_DOWN", wait=0.5)   # from the header buttons or filters into the grid
        elif f["row"] != t["row"]:
            key("DPAD_DOWN" if f["row"] < t["row"] else "DPAD_UP", wait=0.5)
        else:
            key("DPAD_RIGHT" if f["col"] < t["col"] else "DPAD_LEFT", wait=0.4)
    raise AssertionError(f"could not focus {t['title']}")


def press(kind, title=None, wait=1.0):
    t = wait_for(lambda: tile(kind, title), 8, f"{kind} {title} tile")
    focus(t)
    key("DPAD_CENTER", wait=wait)


def type_and_ok(text):
    wait_for(lambda: state()["dialog"], 5, "dialog")
    # One input process for all the keys: end of field, then 60 deletes.
    sh("input", "keyevent", "KEYCODE_MOVE_END", *["KEYCODE_DEL"] * 60)
    sh("input", "text", "'" + text.replace(" ", "%s") + "'")
    key("DPAD_DOWN", wait=0.5)    # lands on the button nearest the cursor: OK (left) or, after long text, Cancel
    for _ in range(2):
        if state()["ok"]:
            break
        key("DPAD_LEFT", wait=0.4)
    assert state()["ok"], "OK button did not take focus"
    key("DPAD_CENTER", wait=1.5)


def tick(names):
    """In a multi-choice list: check exactly `names`, then press Next."""
    for _ in range(40):
        s = state()
        todo = [n for n in s["items"] if (n in names) != (n in s["checked"])]
        if not todo:
            break
        want = s["items"].index(todo[0])
        cur = s["focused"] if s["focused"] is not None else -1
        if cur == want:
            key("DPAD_CENTER", wait=0.5)
        else:
            key("DPAD_DOWN" if cur < want else "DPAD_UP", wait=0.35)
    s = state()
    assert set(names) == s["checked"], f"checked {s['checked']}, wanted {names}"
    for _ in range(len(s["items"]) + 2):
        if state()["ok"]:
            break
        key("DPAD_DOWN", wait=0.35)
    assert state()["ok"], "could not reach Next"
    key("DPAD_CENTER", wait=1.2)


def choose(item, reader=None):
    reader = reader or state
    items = wait_for(lambda: reader()["items"], 5, "dialog items")
    assert item in items, f"{item!r} not in {items}"
    key("DPAD_DOWN", items.index(item), wait=0.3)
    key("DPAD_CENTER", wait=1.2)


def wait_device(title, field, want, timeout=12):
    return wait_for(lambda: (t := tile("device", title) or tile("sensor", title)) and t[field] == want and t,
                    timeout, f"{title} {field}={want!r} (is {(tile('device', title) or tile('sensor', title) or {}).get(field)!r})")


def drop_real_devices():
    """Find on Wi-Fi also discovers the owner's real plugs; take them straight back out of the test setup,
    so no test can ever switch them (the read-only tests add the one they read, and remove it after)."""
    for d in receiver()["devices"]:
        if not d["host"].startswith("127.0.0.1"):
            receiver("-a", f"{APP}.REMOVE", "--es", "id", d["id"])


def find_all():
    press_add("Plug or sensor", "Find on Wi-Fi")
    wait_for(lambda: len(devices()) >= 5 or "Added" in state()["toast"] or "No new" in state()["toast"], 20, "discovery")
    wait_for(lambda: all(t["state"] not in ("…", "") for t in devices()), 10, "first status of every device")
    drop_real_devices()


# ---- tests ---------------------------------------------------------------------------------------

def test_app_tile_on_clean_home_screen_opens_app():
    ui.home()
    assert APP in ui.pkgs(), "Smart Home has no tile on the home screen"
    ui.focus_pkg(APP)
    key("DPAD_CENTER", wait=1)
    wait_top(MAIN)
    key("HOME", wait=1.5)
    wait_top(ui.HOME)


def test_empty_home_explains_what_to_do():
    open_app()
    s = state()
    assert "+ Add" in s["status"], s["status"]
    assert [t["kind"] for t in s["tiles"] if t["kind"] != "header"] == ["tv-input", "tv-volume"]


def test_find_devices_discovers_every_kind():
    open_app()
    find_all()
    ds = {t["title"]: t for t in devices()}
    # Real Kasa plugs on the Wi-Fi answer the TV's broadcast too; they may be listed, never switched.
    assert FAKE_NAMES <= set(ds), set(ds)
    assert ds["Bedroom"]["kind"] == "sensor"
    assert ds["SHPLG-S 1"]["state"] == "ON" and ds["SHPLG-S 1"]["detail"] == "61 W", ds["SHPLG-S 1"]
    assert ds["Bedroom"]["state"] == "21.4°" and ds["Bedroom"]["detail"] == "47% humidity", ds["Bedroom"]
    assert re.search(r"Added \d+ new", state()["toast"]), state()["toast"]


def test_find_devices_again_adds_nothing_twice():
    open_app()
    before = len(devices())
    press_add("Plug or sensor", "Find on Wi-Fi")
    # The owner's real plugs come back as "new" each time (they're dropped after every scan); the fakes must not.
    wait_for(lambda: "No new" in state()["toast"] or "Added" in state()["toast"], 20, "second scan to finish")
    drop_real_devices()
    wait_for(lambda: len(devices()) == before, 5, f"no fake device added twice ({len(devices())} vs {before})")


def test_toggle_gen3_plug_both_ways():
    open_app()
    fake_set(LAMP, on=False)
    wait_device("Desk lamp", "state", "OFF")
    press("device", "Desk lamp")
    wait_for(lambda: fake(LAMP)["on"], 5, "lamp to switch on")
    wait_device("Desk lamp", "state", "ON")
    wait_device("Desk lamp", "detail", "40 W")
    press("device", "Desk lamp")
    wait_for(lambda: not fake(LAMP)["on"], 5, "lamp to switch off")
    wait_device("Desk lamp", "state", "OFF")
    assert any(r.startswith("/rpc/Switch.Set?id=0&on=") for r in fake(LAMP)["requests"])


def test_toggle_gen1_plug():
    open_app()
    fake_set(GEN1, on=True)
    wait_device("SHPLG-S 1", "state", "ON")
    press("device", "SHPLG-S 1")
    wait_for(lambda: not fake(GEN1)["on"], 5, "Gen1 plug to switch off")
    assert "/relay/0?turn=off" in fake(GEN1)["requests"]
    wait_device("SHPLG-S 1", "state", "OFF")


def test_change_made_elsewhere_shows_up():
    open_app()
    fake_set(FAN, on=False)
    wait_device("Fan", "state", "OFF")
    fake_set(FAN, on=True)   # someone pressed the plug's own button
    wait_device("Fan", "state", "ON", timeout=10)


def test_sensor_updates():
    open_app()
    fake_set(SENSOR, temp=23.0)
    wait_device("Bedroom", "state", "23.0°", timeout=10)
    fake_set(SENSOR, temp=21.4)


def test_unreachable_device_shows_offline_and_recovers():
    open_app()
    fake_set(FAN, down=True)
    try:
        wait_device("Fan", "state", "Offline", timeout=15)
        assert "127.0.0.1:8084" in tile("device", "Fan")["detail"]
    finally:
        fake_set(FAN, down=False)
    wait_device("Fan", "state", "OFF", timeout=12) if not fake(FAN)["on"] else wait_device("Fan", "state", "ON", timeout=12)


def test_add_by_ip_and_reject_non_shelly():
    open_app()
    fan = tile("device", "Fan")
    receiver("-a", f"{APP}.REMOVE", "--es", "id", fan["id"])
    wait_for(lambda: tile("device", "Fan") is None, 5, "Fan removed")
    press_add("Plug or sensor", "Enter its address")
    assert state()["dialog"] == "Add by IP"
    type_and_ok("127.0.0.1:8084")
    wait_for(lambda: tile("device", "Fan"), 8, "Fan added by IP")
    press_add("Plug or sensor", "Enter its address")
    type_and_ok("127.0.0.1:9")   # nothing listens there
    wait_for(lambda: "No Shelly or Kasa device answered" in state()["toast"], 10, "a clear error")
    press_add("Plug or sensor", "Enter its address")
    type_and_ok("127.0.0.1:8084")
    wait_for(lambda: "already added" in state()["toast"], 5, "duplicate refused")


def test_create_and_run_scene_on_tv():
    open_app()
    fake_set(LAMP, on=False)
    fake_set(GEN1, on=True)
    press_add("Scene")
    assert state()["dialog"] == "Scene name"
    type_and_ok("Movie night")
    # Turn ON: the Fan; Turn OFF: the Desk lamp and the Gen1 plug.
    wait_for(lambda: state()["dialog"] == "Turn ON", 5, "Turn ON list")
    tick({"Fan"})
    wait_for(lambda: state()["dialog"] == "Turn OFF", 5, "Turn OFF list")
    tick({"Desk lamp", "SHPLG-S 1"})
    sc = wait_for(lambda: tile("scene", "Movie night"), 5, "scene tile")
    assert sc["detail"] == "1 on · 2 off", sc["detail"]
    fake_set(LAMP, on=True)
    fake_set(FAN, on=False)
    press("scene", "Movie night")
    wait_for(lambda: fake(FAN)["on"] and not fake(LAMP)["on"] and not fake(GEN1)["on"], 6, "scene to apply")
    wait_for(lambda: "Movie night: done" in state()["toast"], 5, "done message")


def test_scene_reports_devices_that_did_not_answer():
    open_app()
    receiver("-a", f"{APP}.ADD_SCENE", "--es", "name", "'Good night'", "--esa", "off", "'Desk lamp,Fan'")
    wait_for(lambda: tile("scene", "Good night"), 5, "Good night scene")
    fake_set(FAN, down=True)
    try:
        press("scene", "Good night")
        wait_for(lambda: "1 device(s) didn't answer" in state()["toast"], 12, "partial failure message")
    finally:
        fake_set(FAN, down=False)


def type_and_ok_device(text):
    """type_and_ok, reading the device screen's dialog."""
    wait_for(lambda: device_state()["dialog"], 5, "dialog")
    sh("input", "keyevent", "KEYCODE_MOVE_END", *["KEYCODE_DEL"] * 60)
    sh("input", "text", "'" + text.replace(" ", "%s") + "'")
    key("DPAD_DOWN", wait=0.5)
    for _ in range(2):
        if device_state().get("dialog okFocused") == "true":
            break
        key("DPAD_LEFT", wait=0.4)
    key("DPAD_CENTER", wait=1.5)


def test_device_screen_rename():
    open_app()
    d = open_device("Desk lamp")
    assert d["buttons"].startswith("Turn "), d
    device_button("btnRename")
    type_and_ok_device("Reading lamp")
    wait_for(lambda: device_state()["name"] == "Reading lamp", 5, "new name on the device screen")
    key("BACK", wait=1.5)
    wait_top(MAIN)
    wait_for(lambda: tile("device", "Reading lamp"), 5, "new name on Home")
    assert tile("scene", "Movie night"), "renaming a device broke its scene"


def test_device_screen_remove_cleans_up_its_scenes():
    open_app()
    open_device("Reading lamp")
    device_button("btnRemove")
    choose("Remove it, and its steps in scenes and automations", device_state)
    wait_top(MAIN)
    wait_for(lambda: tile("device", "Reading lamp") is None, 5, "device gone")
    sc = tile("scene", "Movie night")
    assert sc and sc["detail"] == "1 on · 1 off", sc   # the lamp's step went with it


def test_everything_survives_app_restart():
    open_app()
    before = [(t["kind"], t["title"]) for t in state()["tiles"]]
    sh("am", "force-stop", APP)
    open_app()
    assert [(t["kind"], t["title"]) for t in state()["tiles"]] == before


def test_camera_never_shows_password_and_reports_errors():
    open_app()
    press_add("Camera", "Enter its address")
    type_and_ok("127.0.0.1:8554")   # nothing serves RTSP there
    assert state()["dialog"] == "Camera name"
    type_and_ok("Test cam")
    type_and_ok("viewer")
    assert state()["dialog"] == "Stream password"
    type_and_ok("s3cret")
    cam = wait_for(lambda: tile("camera", "Test cam"), 5, "camera tile")
    assert cam["detail"] == "127.0.0.1:8554", cam["detail"]
    assert "s3cret" not in sh("dumpsys", "activity", "top"), "password visible in the app's dump"
    assert "s3cret" not in json.dumps(receiver()), "password visible over adb"
    press("camera", "Test cam", wait=2)
    wait_top(f"{APP}/.CameraActivity")
    out = wait_for(lambda: (o := sh("dumpsys", "activity", "top")) and "state=error" in o and o, 20, "camera error state")
    assert "RTSP" in out and "eufy" in out, "error doesn't explain how to switch RTSP on"
    key("BACK", wait=1.5)
    wait_top(MAIN)


def test_kasa_plug_added_by_address_shows_state():
    open_app()
    kasa_set(True)
    receiver("-a", f"{APP}.ADD_DEVICE", "--es", "host", f"127.0.0.1:{KASA_PORT}")
    wait_device("Test plug", "state", "ON")
    wait_device("Test plug", "detail", "9 W")


def test_kasa_plug_toggles_both_ways():
    open_app()
    kasa_set(True)
    wait_device("Test plug", "state", "ON")
    press("device", "Test plug")
    wait_for(lambda: not kasa_fake()["on"], 5, "Kasa plug to switch off")
    wait_device("Test plug", "state", "OFF")
    press("device", "Test plug")
    wait_for(lambda: kasa_fake()["on"], 5, "Kasa plug to switch on")
    wait_device("Test plug", "state", "ON")
    assert any("set_relay_state" in r for r in kasa_fake()["requests"])


def test_kasa_change_made_elsewhere_shows_up():
    open_app()
    kasa_set(True)
    wait_device("Test plug", "state", "ON")
    kasa_set(False)   # someone pressed the plug's button, or used the Kasa app
    wait_device("Test plug", "state", "OFF", timeout=10)


def test_scene_mixes_kasa_and_shelly():
    open_app()
    kasa_set(True)
    fake_set(FAN, on=False)
    receiver("-a", f"{APP}.ADD_SCENE", "--es", "name", "'Mixed'", "--esa", "on", "Fan", "--esa", "off", "'Test plug'")
    press("scene", "Mixed")
    wait_for(lambda: fake(FAN)["on"] and not kasa_fake()["on"], 6, "both brands to follow the scene")
    wait_for(lambda: "Mixed: done" in state()["toast"], 5, "done message")


def tv_clock():
    """The TV's own local time (schedules run on its clock and time zone)."""
    import datetime
    h, m, sec = map(int, sh("date", "+%H:%M:%S").strip().split(":"))
    return datetime.datetime(2000, 1, 1, h, m, sec)


def test_schedule_tile_shows_next_run_and_toggles():
    open_app()
    receiver("-a", f"{APP}.ADD_SCHEDULE", "--es", "name", "'Night off'", "--es", "at", "23:59", "--esa", "off", "'Test plug'")
    t = wait_for(lambda: tile("schedule", "Night off"), 5, "schedule tile")
    assert t["detail"] == "Every day · 23:59", t["detail"]
    assert t["state"].endswith("23:59"), t["state"]
    press("schedule", "Night off")
    wait_for(lambda: tile("schedule", "Night off")["state"] == "OFF", 5, "schedule switched off")
    assert receiver()["schedules"][0]["enabled"] is False
    press("schedule", "Night off")
    wait_for(lambda: tile("schedule", "Night off")["state"].endswith("23:59"), 5, "schedule back on")
    receiver("-a", f"{APP}.REMOVE", "--es", "name", "'Night off'")


def test_schedule_fires_and_switches_the_plug():
    import datetime
    open_app()
    kasa_set(True)
    now = tv_clock()
    # At least 70 s ahead, so the minute can't roll over while the schedule is being saved.
    at = (now + datetime.timedelta(seconds=70 + (60 - now.second))).strftime("%H:%M")
    receiver("-a", f"{APP}.ADD_SCHEDULE", "--es", "name", "'Fire test'", "--es", "at", at, "--esa", "off", "'Test plug'")
    key("HOME", wait=1)   # the dashboard is not open: the alarm alone must do it
    wait_for(lambda: not kasa_fake()["on"], 210, f"schedule at {at} to switch the plug off (TV clock {tv_clock():%H:%M:%S})")
    receiver("-a", f"{APP}.REMOVE", "--es", "name", "'Fire test'")


def test_tv_volume_tile():
    open_app()
    t = wait_for(lambda: tile("tv-volume"), 5, "volume tile")
    assert re.fullmatch(r"\d+%|Muted|Fixed", t["state"]), t["state"]
    before = t["state"]
    press("tv-volume")
    assert state()["items"] == ["Volume up", "Volume down", "Mute / unmute"], state()["items"]
    choose("Volume up")
    after_up = tile("tv-volume")["state"]
    press("tv-volume")
    choose("Volume down")   # put it back where it was
    after = wait_for(lambda: tile("tv-volume")["state"], 5, "volume state")
    print(f"        volume: {before} -> {after_up} -> {after}", flush=True)
    if before not in ("Fixed",):
        assert after == before, f"volume not restored: {before} -> {after}"


def test_tv_inputs_tile_explains_fire_os():
    open_app()
    press("tv-input")
    wait_for(lambda: "press Input on the remote" in state()["toast"], 5, "the Fire OS explanation")
    assert state()["dialog"] is None
    wait_top(MAIN)


def test_find_devices_finds_the_real_camera():
    open_app()
    press_add("Camera", "Find cameras on Wi-Fi")
    wait_for(lambda: state()["dialog"] == "Cameras found", 25, "the camera scan")
    choose("192.168.1.249")
    type_and_ok("Camera 249")
    type_and_ok("-")   # no login: found cameras start without one
    cam = wait_for(lambda: tile("camera", "Camera 249"), 8, "the camera at 192.168.1.249")
    assert cam["detail"] == "192.168.1.249:554", cam["detail"]
    # Found without a password: opening it must explain, not hang.
    press("camera", "Camera 249", wait=2)
    wait_top(f"{APP}/.CameraActivity")
    out = wait_for(lambda: (o := sh("dumpsys", "activity", "top")) and "state=error" in o and o, 25, "camera error state")
    assert "eufy" in out
    key("BACK", wait=1.5)


def pick_filter(name):
    """Picks a filter pill with the remote."""
    labels = ["all", "plugs", "cameras", "scenes", "automations"]
    for _ in range(25):
        f = state()["focus_raw"]
        if f == "filter:" + name:
            key("DPAD_CENTER", wait=1)
            wait_for(lambda: state()["filter"] == name, 5, f"filter {name}")
            return
        if f.startswith("filter:"):
            key("DPAD_RIGHT" if labels.index(f[7:]) < labels.index(name) else "DPAD_LEFT", wait=0.4)
        elif f.startswith("view:"):
            key("DPAD_DOWN", wait=0.4)
        else:
            key("DPAD_UP", wait=0.4)
    raise AssertionError(f"could not reach filter {name}")


def test_add_menu_and_filters():
    open_app()
    focus_view("btnAdd")
    key("DPAD_CENTER", wait=1)
    assert state()["items"] == ["Plug or sensor", "Camera", "Scene", "Automation", "Room"], state()["items"]
    key("BACK", wait=1)
    for f, kinds in (("plugs", {"device", "sensor"}), ("scenes", {"scene"}), ("cameras", {"camera"}), ("all", None)):
        pick_filter(f)
        got = {t["kind"] for t in state()["tiles"] if t["kind"] != "header"}
        if kinds:
            assert got and got <= kinds, f"filter {f}: {got}"
        else:
            assert {"tv-input", "tv-volume"} <= got, got


def test_device_screen_power_favorite_and_room():
    open_app()
    kasa_set(True)
    open_device("Test plug")
    d = wait_for(lambda: (x := device_state()) and "9.1 W" in x["stats"] and x, 10, "power on the device screen")
    assert d["state"] == "On", d
    device_button("btnToggle")
    wait_for(lambda: not kasa_fake()["on"], 5, "plug off from the device screen")
    wait_for(lambda: device_state()["state"] == "Off", 8, "Off on the device screen")
    device_button("btnToggle")
    wait_for(lambda: kasa_fake()["on"], 5, "plug back on")
    device_button("btnFavorite")
    wait_for(lambda: "★" in device_state()["buttons"], 5, "favorite")
    device_button("btnRoom")
    choose("New room…", device_state)
    type_and_ok_device("Living room")
    wait_for(lambda: "Room: Living room" in device_state()["buttons"], 5, "room set")
    assert "Test plug turned" in device_state()["history"], device_state()["history"]
    key("BACK", wait=1.5)
    wait_top(MAIN)
    wait_for(lambda: tile("device", "Test plug", "fav"), 5, "Test plug under Favorites")
    assert "Favorites" in headers() and "Living room" in headers(), headers()


def test_real_plug_energy_read_only():
    """The device screen of a real Kasa plug shows its energy (read, never switched)."""
    hosts = {x["host"] for x in receiver()["devices"]}
    if "192.168.1.197" not in hosts:
        receiver("-a", f"{APP}.ADD_DEVICE", "--es", "host", "192.168.1.197", "--es", "name", "'Energy plug'")
    name = next(x["name"] for x in receiver()["devices"] if x["host"] == "192.168.1.197")
    open_app()
    open_device(name)
    d = wait_for(lambda: (x := device_state()) and re.search(r"Used this month: [\d.]+ k?Wh", x["stats"]) and x, 15, "energy")
    print(f"        {name}: {d['stats']}", flush=True)
    key("BACK", wait=1.5)
    receiver("-a", f"{APP}.REMOVE", "--es", "name", f"'{name}'")


def test_automation_built_on_tv_runs_when_tv_turns_off():
    open_app()
    kasa_set(True)
    press_add("Automation")
    type_and_ok("TV off lamp off")
    choose("When the TV turns off")
    choose("Any time")
    choose("Every day")
    choose("Turn devices off")
    tick({"Test plug"})
    t = wait_for(lambda: tile("schedule", "TV off lamp off"), 5, "automation tile")
    assert t["detail"] == "When TV turns off", t["detail"]
    # The event as the launcher sends it...
    sh("am", "broadcast", "-n", f"{APP}/.Scheduler", "-a", f"{APP}.TV_EVENT", "--ez", "on", "false")
    wait_for(lambda: not kasa_fake()["on"], 8, "plug off on the TV-off event")
    # ...and for real: put the TV to sleep, the launcher's watcher notices.
    kasa_set(True)
    key("SLEEP", wait=1)
    try:
        wait_for(lambda: not kasa_fake()["on"], 20, "plug off when the TV really went to sleep")
    finally:
        key("WAKEUP", wait=3)
    acts = receiver()["activity"]
    assert sum("TV off lamp off: screen off" in l for l in acts) >= 1, acts[-3:]
    receiver("-a", f"{APP}.REMOVE", "--es", "name", "'TV off lamp off'")


def test_tv_on_with_turn_off_after():
    receiver("-a", f"{APP}.ADD_SCHEDULE", "--es", "name", "'Short light'", "--es", "at", "tv-on", "--es", "autooff", "1",
             "--esa", "on", "'Test plug'")
    kasa_set(False)
    sh("am", "broadcast", "-n", f"{APP}/.Scheduler", "-a", f"{APP}.TV_EVENT", "--ez", "on", "true")
    wait_for(lambda: kasa_fake()["on"], 8, "plug on when the TV turned on")
    wait_for(lambda: receiver()["pending_off"], 5, "a turn-off waiting")
    key("HOME", wait=1)   # the app needn't be open: the alarm does it
    wait_for(lambda: not kasa_fake()["on"], 120, "plug off again after 1 minute")
    wait_for(lambda: not receiver()["pending_off"], 10, "the turn-off done")
    assert any("Turn off after: Test plug" in l for l in receiver()["activity"])
    receiver("-a", f"{APP}.REMOVE", "--es", "name", "'Short light'")


def test_activity_and_settings_on_tv():
    open_app()
    focus_view("btnActivity")
    key("DPAD_CENTER", wait=1)
    assert state()["dialog"] == "Activity"
    # Run with the rest, the earlier tests' switching is listed; run alone, "Nothing yet".
    assert state()["items"], "empty Activity list"
    key("BACK", wait=1)
    focus_view("btnSettings")
    key("DPAD_CENTER", wait=1)
    assert state()["dialog"] == "Settings"
    choose(state()["items"][0])   # Location: cities in the TV's time zone, no typing
    assert state()["dialog"].startswith("Nearest city"), state()["dialog"]
    assert "Other time zone…" in state()["items"] and "Enter coordinates…" in state()["items"], state()["items"]
    choose("San Francisco")
    h = receiver()
    assert abs(h["latitude"] - 37.775) < 1e-6 and abs(h["longitude"] + 122.419) < 1e-6, h
    # Another zone, through Other time zone…
    focus_view("btnSettings")
    key("DPAD_CENTER", wait=1)
    assert state()["items"][0].endswith("San Francisco"), state()["items"][0]
    choose(state()["items"][0])
    choose("Other time zone…")
    assert state()["dialog"] == "Time zone"
    choose("India")
    choose("Bengaluru")
    h = receiver()
    assert abs(h["latitude"] - 12.972) < 1e-6, h


def watcher():
    """The launcher's screensaver timer, from FireHomeWatcher's dump."""
    m = re.search(r"screensaver minutes=(\d+) idle_ms=(\d+) playing=(\w+) front=(\S*) last=(.*)",
                  sh("dumpsys", "activity", "service", "com.gathin.tvhome/.FireHomeWatcher"))
    assert m, "FireHomeWatcher dump"
    return {"minutes": int(m[1]), "idle_ms": int(m[2]), "playing": m[3] == "true", "front": m[4], "last": m[5]}


def clock():
    out = sh("dumpsys", "activity", "top")
    m = re.search(r"ClockActivity state:\n  time=(\S+) date=(.*)\n  weather=(.*)\n", out)
    return {"time": m[1], "date": m[2], "weather": m[3]} if m else None


def set_launcher_minutes(n):
    # A change of value restarts the timer (adb's keys don't reach it; only the remote's do).
    for v in (n + 1, n):
        sh("am", "broadcast", "-n", "com.gathin.tvhome/.ScreensaverReceiver", "-a", "com.gathin.tvhome.SET_SCREENSAVER",
           "--ei", "minutes", str(v))


def test_screensaver_clock_and_idle_timer():
    """Fire OS ignores custom screensavers, so the launcher opens Smart Home's clock after idle minutes."""
    before = watcher()["minutes"]
    try:
        open_app()
        focus_view("btnSettings")
        key("DPAD_CENTER", wait=1)
        assert state()["items"][1].startswith("Screensaver: "), state()["items"]
        choose(state()["items"][1])
        assert state()["dialog"] == "Screensaver"
        pick = before if before in (5, 10, 20, 30) else 10   # the owner's own choice stays as it was
        set_launcher_minutes(0)
        choose(f"Clock after {pick} minutes with nothing playing")
        wait_for(lambda: watcher()["minutes"] == pick, 5, f"the launcher told {pick} minutes")
        if state() and state()["dialog"].startswith("Nearest city"):   # no location yet: offered next
            key("BACK", wait=1)
        # Show it now: big clock, the date, and weather or the hint to set a location.
        focus_view("btnSettings")
        key("DPAD_CENTER", wait=1)
        choose(state()["items"][1])
        choose("Show it now")
        c = wait_for(clock, 8, "the clock")
        assert re.fullmatch(r"\d{1,2}:\d\d", c["time"]), c
        key("DPAD_CENTER", wait=1.5)
        assert clock() is None, "a key closes the clock"
        # Idle for a minute with nothing playing: the launcher opens it.
        key("HOME", wait=1.5)
        set_launcher_minutes(1)
        wait_for(clock, 90, "the clock after a minute idle")
        assert watcher()["last"].startswith("OPEN_CLOCK"), watcher()
        key("BACK", wait=1.5)
    finally:
        set_launcher_minutes(before)


def test_refind_moves_a_plug_back_on_the_tv():
    """A real Kasa plug, given a wrong address, is found again by the TV itself (no switching)."""
    open_app()
    hosts = {d["host"] for d in receiver()["devices"]}
    if "192.168.1.198" not in hosts:
        receiver("-a", f"{APP}.ADD_DEVICE", "--es", "host", "192.168.1.198", "--es", "name", "'Refind plug'")
    name = next(d["name"] for d in receiver()["devices"] if d["host"] == "192.168.1.198")
    receiver("-a", f"{APP}.SET_HOST", "--es", "name", f"'{name}'", "--es", "host", "192.0.2.77")
    wait_for(lambda: next(d for d in receiver()["devices"] if d["name"] == name)["host"] == "192.168.1.198", 90,
             f"the TV to find {name} again at 192.168.1.198")
    receiver("-a", f"{APP}.REMOVE", "--es", "name", f"'{name}'")


def test_back_and_home_from_app():
    open_app()
    key("BACK", wait=2)
    wait_top(ui.HOME)
    open_app()
    key("HOME", wait=2)
    wait_top(ui.HOME)


# ---- runner --------------------------------------------------------------------------------------

def setup():
    subprocess.run(["adb", "connect", TV], capture_output=True)
    procs = []
    try:
        fake(LAMP)
    except Exception:
        procs.append(subprocess.Popen([sys.executable, "-I", os.path.join(ROOT, "tools", "fake_shelly.py")],
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
        wait_for(lambda: _up(), 5, "fake Shelly devices")
    for p in PORTS:
        subprocess.run(["adb", "-s", TV, "reverse", f"tcp:{p}", f"tcp:{p}"], capture_output=True)
    for p in (LAMP, SENSOR, FAN):
        procs.append(subprocess.Popen(["dns-sd", "-P", f"shelly-test-{p}", "_shelly._tcp", "local", str(p),
                                       f"shelly-test-{p}.local", "127.0.0.1"], stdout=subprocess.DEVNULL))
    procs.append(subprocess.Popen(["dns-sd", "-P", f"shellyplug-s-test{GEN1}", "_http._tcp", "local", str(GEN1),
                                   f"shelly-test-{GEN1}.local", "127.0.0.1"], stdout=subprocess.DEVNULL))
    for p, on in ((LAMP, False), (GEN1, True), (FAN, False)):
        fake_set(p, on=on, down=False)
    procs.append(subprocess.Popen([sys.executable, "-I", os.path.join(ROOT, "tools", "fake_kasa.py")],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
    wait_for(lambda: _kasa_up(), 5, "fake Kasa plug")
    subprocess.run(["adb", "-s", TV, "reverse", f"tcp:{KASA_PORT}", f"tcp:{KASA_PORT}"], capture_output=True)
    return procs


def _kasa_up():
    try:
        kasa_fake()
        return True
    except Exception:
        return False


def _up():
    try:
        fake(LAMP)
        return True
    except Exception:
        return False


def main():
    args = sys.argv[1:]
    pattern = args[args.index("-k") + 1] if "-k" in args else ""
    subprocess.run(["adb", "connect", TV], capture_output=True)
    if "com.homecontrol.tv" not in sh("pm", "list", "packages", APP):
        sys.exit("Smart Home is not installed: ./install-smarthome.sh")
    # The owner's setup waits in the app's backup slot (on the TV; camera passwords never leave it) and
    # comes back at the end. A backup left by a run that was cut short is restored first.
    if receiver().get("backup"):
        receiver("-a", f"{APP}.RESTORE")
    saved = receiver("-a", f"{APP}.BACKUP")
    if "error" in saved or not saved.get("backup"):
        sys.exit(f"could not back up your setup, so not touching it: {saved}")
    procs = setup()
    receiver("-a", f"{APP}.RESET")
    tests = [(n, f) for n, f in globals().items() if n.startswith("test_") and callable(f) and pattern in n]
    failed = []
    try:
        for name, fn in tests:
            t0 = time.time()
            try:
                fn()
                print(f"  PASS  {name}  ({time.time() - t0:.0f}s)", flush=True)
            except Exception as e:
                shot = ui.screenshot("smarthome_" + name)
                msg = str(e) or traceback.format_exc(limit=1).strip().split("\n")[-1]
                print(f"  FAIL  {name}  ({time.time() - t0:.0f}s): {msg}\n        screenshot: {os.path.relpath(shot, ROOT)}", flush=True)
                failed.append(name)
                key("BACK", wait=0.5)
    finally:
        back = receiver("-a", f"{APP}.RESTORE")
        if "error" in back:
            print(f"  WARNING  your setup was not restored: {back['error']}", flush=True)
        for p in procs:
            p.terminate()
        for p in PORTS + (KASA_PORT,):
            subprocess.run(["adb", "-s", TV, "reverse", "--remove", f"tcp:{p}"], capture_output=True)
        key("HOME", wait=1)
    print(f"\n{len(tests) - len(failed)} passed, {len(failed)} failed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
