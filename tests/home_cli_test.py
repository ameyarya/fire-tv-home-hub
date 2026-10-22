#!/usr/bin/env python3
"""Tests for home.sh (tools/home.py) on the Mac alone: a fake `adb` stands in for the TV.

The fake parses each `adb shell` command line the way the TV's shell would (POSIX quoting), records the
broadcast's extras and answers like `am broadcast` does, so these tests check that names with spaces
and quotes, and passwords full of symbols, reach the app intact. Kasa discovery runs against a fake
plug on 127.0.0.1.

  python3 -I tests/home_cli_test.py
"""
import contextlib, importlib.util, io, json, os, shlex, socket, stat, sys, tempfile, threading, unittest
from unittest import mock

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.environ.setdefault("TV_HOST", "192.0.2.1")   # never reached: adb is faked below
spec = importlib.util.spec_from_file_location("home", os.path.join(ROOT, "tools", "home.py"))
home = importlib.util.module_from_spec(spec)
spec.loader.exec_module(home)

FAKE_ADB = r'''#!/usr/bin/env python3
import json, os, shlex, sys
log, state = os.environ["FAKE_LOG"], os.environ["FAKE_STATE"]
args = sys.argv[1:]
if args[:1] == ["-s"]:
    args = args[2:]
if args[:1] == ["connect"]:
    print("already connected"); sys.exit(0)
if args[:1] != ["shell"]:
    sys.exit(0)
argv = shlex.split(" ".join(args[1:]))          # what the TV's shell hands to `am`
extras, i, action = {}, 0, None
while i < len(argv):
    if argv[i] == "-a": action = argv[i + 1]; i += 2
    elif argv[i] == "--es": extras[argv[i + 1]] = argv[i + 2]; i += 3
    elif argv[i] == "--esa": extras[argv[i + 1]] = argv[i + 2].split(",") if argv[i + 2] else []; i += 3
    else: i += 1
with open(log, "a") as f:
    f.write(json.dumps({"action": action, "extras": extras}) + "\n")
reply = json.load(open(state))
data = json.dumps(reply.get("next_error") and {"error": reply["next_error"]} or reply["home"])
print("Broadcasting: Intent { act=%s }" % action)
print('Broadcast completed: result=1, data="%s"' % data)
'''

HOME = {"devices": [{"id": "p1", "name": "Plug 1", "host": "192.168.1.198", "brand": "kasa", "switch": True},
                    {"id": "p2", "name": "Plug 2", "host": "192.168.1.197", "brand": "kasa", "switch": True}],
        "cameras": [{"id": "c", "name": "Living room", "url": "(hidden)", "host": "192.168.1.249:554"}],
        "scenes": [],
        "schedules": [{"id": "s", "name": "Lamp off", "enabled": True, "text": "Every day · 23:30",
                       "next": "2026-10-19T23:30-07:00[America/Los_Angeles]", "scene": "",
                       "actions": [{"device": "p2", "on": False}]}]}


class CliTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        adb = os.path.join(self.dir, "adb")
        with open(adb, "w") as f:
            f.write(FAKE_ADB)
        os.chmod(adb, os.stat(adb).st_mode | stat.S_IEXEC)
        self.log = os.path.join(self.dir, "log")
        self.state = os.path.join(self.dir, "state")
        self.set_reply()
        self.env = mock.patch.dict(os.environ, {"PATH": self.dir + os.pathsep + os.environ["PATH"],
                                                "FAKE_LOG": self.log, "FAKE_STATE": self.state})
        self.env.start()

    def tearDown(self):
        self.env.stop()

    def set_reply(self, error=None):
        with open(self.state, "w") as f:
            json.dump({"home": HOME, "next_error": error}, f)

    def run_cli(self, *argv):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            home.main(list(argv))
        return out.getvalue()

    def sent(self):
        if not os.path.exists(self.log):
            return []
        with open(self.log) as f:
            return [r for r in map(json.loads, f) if r["action"]]

    def test_status_shows_devices_schedules_and_cameras(self):
        out = self.run_cli("status")
        self.assertIn("Plug 1", out)
        self.assertIn("Every day · 23:30", out)
        self.assertIn("off Plug 2", out)
        self.assertIn("192.168.1.249:554", out)
        self.assertEqual(self.sent(), [])          # a read sends no action

    def test_schedule_with_spaces_and_quotes_arrives_intact(self):
        self.run_cli("schedule", "Kid's \"night\" off", "--at", "23:30", "--days", "weekdays",
                     "--off", "Plug 2, Desk lamp", "--on", "Plug 1")
        s = self.sent()[-1]
        self.assertEqual(s["action"], "com.homecontrol.tv.ADD_SCHEDULE")
        self.assertEqual(s["extras"]["name"], "Kid's \"night\" off")
        self.assertEqual(s["extras"]["at"], "23:30")
        self.assertEqual(s["extras"]["days"], "weekdays")
        self.assertEqual(s["extras"]["off"], ["Plug 2", "Desk lamp"])
        self.assertEqual(s["extras"]["on"], ["Plug 1"])

    def test_sunset_schedule_sends_offset(self):
        self.run_cli("schedule", "Evening", "--at", "sunset", "--offset", "-30", "--scene", "Movie night")
        e = self.sent()[-1]["extras"]
        self.assertEqual((e["at"], e["offset"], e["scene"]), ("sunset", "-30", "Movie night"))
        self.assertEqual(e["on"], [])

    def test_app_errors_reach_the_user(self):
        self.set_reply(error="no device called Lamp")
        with self.assertRaises(SystemExit) as cm:
            self.run_cli("schedule", "x", "--at", "10:00", "--on", "Lamp")
        self.assertEqual(str(cm.exception), "no device called Lamp")

    def test_camera_password_is_encoded_sent_once_and_never_printed(self):
        pw = "p@ss w'rd:\"x/%"
        with mock.patch.object(home.getpass, "getpass", return_value=pw):
            out = self.run_cli("camera", "Living room", "192.168.1.249", "--user", "viewer")
        url = self.sent()[-1]["extras"]["url"]
        self.assertEqual(url, "rtsp://viewer:p%40ss%20w%27rd%3A%22x%2F%25@192.168.1.249:554/live0")
        from urllib.parse import unquote
        self.assertEqual(unquote(url.split(":")[2].split("@")[0]), pw)   # decodes back to exactly what was typed
        self.assertNotIn(pw, out)
        self.assertNotIn("p%40ss", out)

    def test_camera_without_password(self):
        with mock.patch.object(home.getpass, "getpass", return_value=""):
            self.run_cli("camera", "Porch", "192.168.1.50", "--path", "stream1")
        self.assertEqual(self.sent()[-1]["extras"]["url"], "rtsp://192.168.1.50:554/stream1")

    def test_enable_disable_rename_remove_location(self):
        self.run_cli("disable", "Lamp off")
        self.run_cli("enable", "Lamp off")
        self.run_cli("rename", "Plug 1", "Desk lamp")
        self.run_cli("remove", "Living room")
        self.run_cli("location", "37.7749", "-122.4194")
        got = [(s["action"].split(".")[-1], s["extras"]) for s in self.sent()]
        self.assertEqual(got, [
            ("SET_ENABLED", {"name": "Lamp off", "enabled": "false"}),
            ("SET_ENABLED", {"name": "Lamp off", "enabled": "true"}),
            ("RENAME", {"name": "Plug 1", "to": "Desk lamp"}),
            ("REMOVE", {"name": "Living room"}),
            ("SET_LOCATION", {"lat": "37.7749", "lon": "-122.4194"}),
        ])

    def test_move_room_favorite_and_tv_automation(self):
        self.run_cli("move", "Plug 1", "192.168.1.205")
        self.run_cli("room", "Plug 1", "Living room")
        self.run_cli("favorite", "Plug 1")
        self.run_cli("favorite", "Plug 2", "--off")
        self.run_cli("schedule", "Lamp with TV", "--at", "tv-on", "--window", "18:00-23:00", "--autooff", "120", "--on", "Plug 1")
        got = [(x["action"].split(".")[-1], x["extras"]) for x in self.sent()]
        self.assertEqual(got[:4], [("SET_HOST", {"name": "Plug 1", "host": "192.168.1.205"}),
                                   ("SET_ROOM", {"name": "Plug 1", "room": "Living room"}),
                                   ("SET_FAVORITE", {"name": "Plug 1", "favorite": "true"}),
                                   ("SET_FAVORITE", {"name": "Plug 2", "favorite": "false"})])
        e = got[4][1]
        self.assertEqual((e["at"], e["window"], e["autooff"], e["on"]), ("tv-on", "18:00-23:00", "120", ["Plug 1"]))

    def test_comma_in_a_list_name_is_refused(self):
        with self.assertRaises(SystemExit):
            home.send("ADD_SCENE", [("name", "x")], [("on", ["Lamp, left"])])

    def test_find_adds_only_new_plugs(self):
        plugs = {"192.168.1.198": {"alias": "Plug 1", "model": "EP25(US)", "relay_state": 1},
                 "192.168.1.250": {"alias": "Plug 3", "model": "EP25(US)", "relay_state": 0}}
        with mock.patch.object(home, "kasa_discover", return_value=plugs), mock.patch.object(home, "find_cameras", return_value=["192.168.1.249"]):
            out = self.run_cli("find")
        self.assertIn("camera-setup", out)
        adds = [s["extras"]["host"] for s in self.sent() if s["action"].endswith("ADD_DEVICE")]
        self.assertEqual(adds, ["192.168.1.250"])
        self.assertIn("Plug 3", out)

    def test_find_dry_run_changes_nothing(self):
        with mock.patch.object(home, "kasa_discover", return_value={"192.168.1.250": {"alias": "Plug 3", "relay_state": 0}}), \
                mock.patch.object(home, "find_cameras", return_value=[]):
            out = self.run_cli("find", "--dry-run")
        self.assertIn("Plug 3", out)
        self.assertFalse(os.path.exists(self.log))   # adb was never called

    def test_kasa_discovery_against_a_fake_plug(self):
        srv = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        srv.bind(("127.0.0.1", 0))
        port = srv.getsockname()[1]

        def answer():
            data, addr = srv.recvfrom(4096)
            assert home.dec(data) == '{"system":{"get_sysinfo":{}}}'
            srv.sendto(home.enc(json.dumps({"system": {"get_sysinfo": {"alias": "Fake", "relay_state": 1}}})), addr)
        threading.Thread(target=answer, daemon=True).start()
        found = home.kasa_discover(timeout=1.5, targets=["127.0.0.1"], port=port)
        srv.close()
        self.assertEqual(list(found), ["127.0.0.1"])
        self.assertEqual(found["127.0.0.1"]["alias"], "Fake")

    def test_xor_matches_python_kasa(self):
        # Same reference bytes as the app's KasaTest (python-kasa 0.11).
        self.assertEqual(home.enc('{"system":{"get_sysinfo":{}}}').hex(),
                         "d0f281f88bff9af7d5ef94b6d1b4c09fec95e68fe187e8caf08bf68bf6")


if __name__ == "__main__":
    unittest.main(verbosity=1)
