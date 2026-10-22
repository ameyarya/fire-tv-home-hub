#!/usr/bin/env python3
"""A fake Kasa EP25 for testing the Smart Home app without touching real plugs.

Speaks the plug's local protocol (XOR-scrambled JSON over TCP, 4-byte length prefix) on 127.0.0.1:9998
only; the TV reaches it through `adb reverse tcp:9998 tcp:9998`. UDP discovery can't go through adb, so
the UI tests add it by address; discovery itself is covered by the JVM unit tests and the read-only live check.

Test hooks on 127.0.0.1:9997 (HTTP): GET /_state, GET /_set?on=true|false
"""
import json, socketserver, struct, threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

STATE = {"on": True, "alias": "Test plug", "requests": []}


def enc(s):
    k, out = 171, bytearray()
    for b in s.encode():
        k ^= b
        out.append(k)
    return bytes(out)


def dec(b):
    k, out = 171, bytearray()
    for c in b:
        out.append(k ^ c)
        k = c
    return out.decode()


def reply(req):
    q = json.loads(req)
    if "set_relay_state" in q.get("system", {}):
        STATE["on"] = q["system"]["set_relay_state"]["state"] == 1
        return {"system": {"set_relay_state": {"err_code": 0}}}
    r = {"system": {"get_sysinfo": {"alias": STATE["alias"], "model": "EP25(US)", "relay_state": int(STATE["on"]),
                                    "feature": "TIM:ENE", "err_code": 0}}}
    if "emeter" in q:
        r["emeter"] = {"get_realtime": {"power_mw": 9100 if STATE["on"] else 0, "err_code": 0}}
    return r


class Plug(socketserver.BaseRequestHandler):
    def handle(self):
        head = self.request.recv(4)
        if len(head) < 4:
            return
        n = struct.unpack(">I", head)[0]
        if n > 65536:
            return   # an HTTP probe from the Shelly check, not our protocol
        body = b""
        while len(body) < n:
            chunk = self.request.recv(n - len(body))
            if not chunk:
                return
            body += chunk
        req = dec(body)
        STATE["requests"] = (STATE["requests"] + [req])[-50:]
        out = enc(json.dumps(reply(req)))
        self.request.sendall(struct.pack(">I", len(out)) + out)


class Hooks(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        if u.path == "/_set" and "on" in q:
            STATE["on"] = q["on"] == "true"
        body = json.dumps(STATE).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    socketserver.ThreadingTCPServer.allow_reuse_address = True
    plug = socketserver.ThreadingTCPServer(("127.0.0.1", 9998), Plug)
    threading.Thread(target=plug.serve_forever, daemon=True).start()
    print("fake Kasa plug on 127.0.0.1:9998, hooks on :9997", flush=True)
    HTTPServer(("127.0.0.1", 9997), Hooks).serve_forever()
