#!/usr/bin/env python3
"""Fake Shelly devices for testing the Smart Home app without hardware.

Each device answers the same local HTTP API as the real thing (Gen2+ RPC or Gen1), on 127.0.0.1 only.
The TV reaches them through `adb reverse`, so nothing is opened on the Mac's firewall.

  python3 tools/fake_shelly.py            serve until stopped
Test hooks (not part of the Shelly API):
  GET /_state                             this device's state and the requests it has seen
  GET /_set?on=true|false&down=true|false change state behind the app's back, or play dead
"""
import json, sys, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

DEVICES = {
    8081: {"gen": 3, "name": "Desk lamp", "model": "S3PL-00112EU", "switch": True, "on": False, "watts": 0.0},
    8082: {"gen": 1, "name": None, "model": "SHPLG-S", "switch": True, "on": True, "watts": 61.0, "load": 61.0},
    8083: {"gen": 3, "name": "Bedroom", "model": "S3SN-0U12A", "switch": False, "temp": 21.4, "rh": 47.0},
    8084: {"gen": 3, "name": "Fan", "model": "S3PL-00112EU", "switch": True, "on": False, "watts": 0.0},
}


def make_handler(port):
    dev = DEVICES[port]
    dev.setdefault("requests", [])
    dev.setdefault("down", False)

    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def reply(self, obj, code=200):
            body = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            u = urlparse(self.path)
            q = {k: v[0] for k, v in parse_qs(u.query).items()}
            if u.path == "/_state":
                return self.reply(dev)
            if u.path == "/_set":
                if "on" in q: dev["on"] = q["on"] == "true"; dev["watts"] = dev.get("load", 40.0) if dev["on"] else 0.0
                if "down" in q: dev["down"] = q["down"] == "true"
                if "temp" in q: dev["temp"] = float(q["temp"])
                return self.reply(dev)
            if dev["down"]:
                self.close_connection = True
                return   # no reply at all, like a device that lost power
            dev["requests"].append(self.path)
            del dev["requests"][:-50]
            if dev["gen"] >= 2:
                if u.path == "/shelly":
                    return self.reply({"name": dev["name"], "id": f"fake-{port}", "mac": f"AABBCC{port}", "model": dev["model"], "gen": dev["gen"], "app": "Fake"})
                if u.path == "/rpc/Shelly.GetStatus":
                    st = {"sys": {}}
                    if dev["switch"]:
                        st["switch:0"] = {"id": 0, "output": dev["on"], "apower": dev["watts"]}
                    else:
                        st["temperature:0"] = {"id": 0, "tC": dev["temp"]}
                        st["humidity:0"] = {"id": 0, "rh": dev["rh"]}
                    return self.reply(st)
                if u.path == "/rpc/Switch.Set" and dev["switch"]:
                    was = dev["on"]; dev["on"] = q.get("on") == "true"; dev["watts"] = 40.0 if dev["on"] else 0.0
                    return self.reply({"was_on": was})
            else:
                if u.path == "/shelly":
                    return self.reply({"type": dev["model"], "mac": f"AABBCCDD{port}", "auth": False})
                if u.path == "/status":
                    return self.reply({"relays": [{"ison": dev["on"]}], "meters": [{"power": dev["watts"]}]})
                if u.path == "/relay/0":
                    dev["on"] = q.get("turn") == "on"; dev["watts"] = dev.get("load", 40.0) if dev["on"] else 0.0
                    return self.reply({"ison": dev["on"]})
            self.reply({"code": 404}, 404)
    return H


def main():
    servers = []
    for port in DEVICES:
        s = ThreadingHTTPServer(("127.0.0.1", port), make_handler(port))
        threading.Thread(target=s.serve_forever, daemon=True).start()
        servers.append(s)
    print("fake Shelly devices on 127.0.0.1:" + ",".join(map(str, DEVICES)), flush=True)
    threading.Event().wait()


if __name__ == "__main__":
    main()
