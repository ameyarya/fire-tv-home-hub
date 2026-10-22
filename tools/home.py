#!/usr/bin/env python3
"""Set up the Smart Home dashboard on the TV from the Mac. The TV only shows and switches things.

  ./home.sh status                             what the dashboard holds, schedules with their next run
  ./home.sh find [--dry-run]                   find Kasa plugs (added) and cameras (listed) on the Wi-Fi
  ./home.sh add <ip> [--name NAME]             add one Shelly or Kasa device by address
  ./home.sh move <name> <ip>                   a device has a new address (the app also finds it by itself)
  ./home.sh room <device or camera> <room>     put it in a room (made if new)
  ./home.sh favorite <name> [--off]            show it under Favorites on the TV
  ./home.sh clear-activity                     empty the Activity history
  ./home.sh rename <name> <new name>
  ./home.sh remove <name>                      a device, scene, schedule or camera
  ./home.sh scene <name> [--on A,B] [--off C]
  ./home.sh schedule <name> --at 23:30|sunrise|sunset|tv-on|tv-off [--offset -30] [--window 22:00-06:00]
                     [--days daily|weekdays|weekends|mon,fri] [--autooff 30] (--on A,B | --off C | --scene NAME)
  Everything here can also be done on the TV: Smart Home, + at the top, or hold OK on a tile.
  ./home.sh enable <schedule> / disable <schedule>
  ./home.sh location <latitude> <longitude>    needed for sunrise and sunset
  ./home.sh camera <name> <ip> [--user U] [--path /live0]   asks for the password without showing it
  ./home.sh camera-check <ip> [--user U] [--path /live0]    tries a stream login from the Mac; prints only the result
  ./home.sh camera-setup <name> <ip>          asks both values, tries them both ways round, adds the one that works

Names with spaces go in quotes: ./home.sh schedule "Lamp off" --at 23:30 --off "Desk lamp"
"""
import argparse, getpass, json, os, re, shlex, socket, subprocess, sys, time
from urllib.parse import quote

TV = os.environ.get("TV_HOST", "192.168.1.248") + ":5555"
APP = "com.homecontrol.tv"
P = APP + "."


# ---- talking to the app ----------------------------------------------------------------------------

def adb(*args):
    return subprocess.run(["adb", "-s", TV, *args], capture_output=True, text=True, stdin=subprocess.DEVNULL, timeout=60)


def q(s):
    """Quote one value for the TV's shell: adb joins the arguments into a single command line."""
    return "'" + str(s).replace("'", "'\\''") + "'"


def send(action=None, strings=(), lists=()):
    """One broadcast to the app's ControlReceiver. Returns the Home it reports, or exits with its error."""
    cmd = ["am", "broadcast", "-n", f"{APP}/.ControlReceiver"]
    if action:
        cmd += ["-a", P + action]
    for k, v in strings:
        cmd += ["--es", k, q(v)]
    for k, items in lists:
        # --esa splits on commas, so a name with a comma can't be in a list; say so instead of guessing.
        for it in items:
            if "," in it:
                sys.exit(f"names in a list can't contain commas: {it!r}")
        cmd += ["--esa", k, q(",".join(items))]
    r = adb("shell", " ".join(cmd))
    out = (r.stdout or "") + (r.stderr or "")
    m = re.search(r'result=(-?\d+), data="(.*)"\s*$', out.strip(), re.S)
    if not m:
        if "not found" in out or "device offline" in out or r.returncode != 0:
            sys.exit(f"can't reach the TV at {TV}: is ADB debugging on? ({out.strip()[:200]})")
        sys.exit("the Smart Home app didn't answer; is it installed? (./install-smarthome.sh)")
    data = json.loads(m.group(2).replace('\\"', '"'))
    if "error" in data:
        sys.exit(data["error"])
    return data


def split(text):
    return [s.strip() for s in text.split(",") if s.strip()] if text else []


# ---- Kasa discovery from the Mac --------------------------------------------------------------------

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
    return out.decode(errors="replace")


def broadcast_addresses():
    out = set()
    try:
        text = subprocess.run(["ifconfig"], capture_output=True, text=True).stdout
        out.update(re.findall(r"inet [\d.]+ netmask \S+ broadcast ([\d.]+)", text))
    except OSError:
        pass
    out.add("255.255.255.255")
    return sorted(out)


def kasa_discover(timeout=4.0, targets=None, port=9999):
    """{ip: sysinfo} for every Kasa plug answering the UDP broadcast."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.bind(("", 0))
    msg = enc('{"system":{"get_sysinfo":{}}}')
    for _ in range(3):
        for t in targets or broadcast_addresses():
            try:
                s.sendto(msg, (t, port))
            except OSError:
                pass
    found, end = {}, time.time() + timeout
    while time.time() < end:
        s.settimeout(max(0.05, end - time.time()))
        try:
            data, (ip, _) = s.recvfrom(8192)
        except (socket.timeout, OSError):
            break
        try:
            info = json.loads(dec(data))["system"]["get_sysinfo"]
            if "relay_state" in info:
                found[ip] = info
        except (ValueError, KeyError, TypeError):
            pass
    s.close()
    return found


# ---- camera discovery from the Mac -----------------------------------------------------------------

def find_cameras(timeout=0.5):
    """Addresses on this Mac's /24 networks that answer RTSP on port 554."""
    import concurrent.futures
    text = subprocess.run(["ifconfig"], capture_output=True, text=True).stdout
    mine = re.findall(r"inet (\d+\.\d+\.\d+)\.(\d+) netmask \S+ broadcast", text)
    hosts = [f"{net}.{i}" for net, me in mine for i in range(1, 255) if str(i) != me]

    def rtsp(h):
        try:
            with socket.create_connection((h, 554), timeout=timeout) as s:
                s.settimeout(timeout * 3)
                s.sendall(f"OPTIONS rtsp://{h}/ RTSP/1.0\r\nCSeq: 1\r\n\r\n".encode())
                return s.recv(64).startswith(b"RTSP/1.0")
        except OSError:
            return False
    with concurrent.futures.ThreadPoolExecutor(64) as ex:
        return [h for h, ok in zip(hosts, ex.map(rtsp, hosts)) if ok]


# ---- camera check ---------------------------------------------------------------------------------

def rtsp_describe(host, port, path, user, password, timeout=5):
    """DESCRIBE with Digest (or Basic) login, as cameras expect. Returns (status code, SDP text).
    Both requests go over one connection: some cameras (eufy) issue a new challenge per connection."""
    import hashlib, base64
    url = f"rtsp://{host}:{port}{path}"
    sock = socket.create_connection((host, int(port)), timeout=timeout)

    def ask(auth=None, cseq=1):
        req = f"DESCRIBE {url} RTSP/1.0\r\nCSeq: {cseq}\r\nAccept: application/sdp\r\n"
        if auth:
            req += f"Authorization: {auth}\r\n"
        sock.sendall((req + "\r\n").encode())
        data, s_end = b"", time.time() + timeout
        while b"\r\n\r\n" not in data and time.time() < s_end:
            chunk = sock.recv(4096)
            if not chunk:
                break
            data += chunk
        head, _, body = data.partition(b"\r\n\r\n")
        m = re.search(rb"Content-Length:\s*(\d+)", head, re.I)
        while m and len(body) < int(m.group(1)):
            chunk = sock.recv(4096)
            if not chunk:
                break
            body += chunk
        text = head.decode(errors="replace")
        code = int(text.split(" ")[1]) if text.startswith("RTSP/") else 0
        return code, text, body.decode(errors="replace")

    try:
        code, head, body = ask()
        if code != 401:
            return code, body
        www = re.search(r"WWW-Authenticate:\s*(\w+)\s+(.*)", head, re.I)
        scheme, params = www.group(1), dict(re.findall(r'(\w+)="([^"]*)"', www.group(2)))
        if scheme.lower() == "basic":
            auth = "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()
        else:
            md5 = lambda x: hashlib.md5(x.encode()).hexdigest()
            ha1 = md5(f"{user}:{params.get('realm', '')}:{password}")
            resp = md5(f"{ha1}:{params.get('nonce', '')}:{md5('DESCRIBE:' + url)}")
            auth = (f'Digest username="{user}", realm="{params.get("realm", "")}", nonce="{params.get("nonce", "")}", '
                    f'uri="{url}", response="{resp}"')
        code, _, body = ask(auth, 2)
        return code, body
    finally:
        sock.close()


def camera_check(host, user, path, port):
    password = getpass.getpass("Stream password (not shown): ")
    path = path if path.startswith("/") else "/" + path
    try:
        code, sdp = rtsp_describe(host, port, path, user, password)
    except OSError as e:
        print(f"Can't reach the camera at {host}:{port}: {e}")
        return 1
    if code == 200:
        video = re.findall(r"a=rtpmap:\d+ ([\w.-]+)/", sdp)
        print(f"Login accepted. The camera offers: {', '.join(video) or 'a stream'} at {path}")
    elif code == 401:
        print("Login refused (401): the camera doesn't accept this username and password.")
    elif code == 404:
        print(f"No stream at {path} (404). eufy uses /live0; check RTSP is on in the eufy app.")
    else:
        print(f"The camera answered {code}.")
    # Second opinion from ffprobe, if installed: it does its own login and fetches the stream.
    if subprocess.run(["which", "ffprobe"], capture_output=True).returncode == 0:
        auth = quote(user, safe="") + ":" + quote(password, safe="") + "@"
        r = subprocess.run(["ffprobe", "-v", "error", "-rtsp_transport", "tcp", "-select_streams", "v:0",
                            "-show_entries", "stream=codec_name,width,height", "-of", "csv=p=0",
                            f"rtsp://{auth}{host}:{port}{path}"], capture_output=True, text=True, timeout=30)
        out = (r.stdout or r.stderr).strip().replace(password, "***").replace(quote(password, safe=""), "***")
        print("ffprobe: " + (f"video {out}" if r.returncode == 0 else f"failed: {out[:200]}"))
    return 0 if code == 200 else 1


def camera_setup(name, host, path, port):
    """One command for the whole camera: both values asked here (the password hidden), tried in both orders
    and on eufy's two stream paths, and the combination that logs in is added to the TV."""
    first = input("Stream username: ").strip()
    second = getpass.getpass("Stream password (not shown): ")
    if not first or not second:
        print("Both are needed.")
        return 1
    tries = [(first, second, "as entered"), (second, first, "swapped round")]
    paths = [path] + [p for p in ("/live0", "/live1") if p != path]
    for user, password, how in tries:
        for p in paths:
            try:
                code, _ = rtsp_describe(host, port, p, user, password)
            except OSError as e:
                print(f"Can't reach the camera at {host}:{port}: {e}")
                return 1
            if code == 200:
                print(f"Login accepted ({how}, stream {p}). Adding it to the TV...")
                auth = quote(user, safe="") + ":" + quote(password, safe="") + "@"
                subprocess.run(["adb", "connect", TV], capture_output=True)
                show(send("ADD_CAMERA", [("name", name), ("url", f"rtsp://{auth}{host}:{port}{p}")]))
                print("Open Smart Home on the TV and choose the camera.")
                return 0
    print("The camera refused both orders on /live0 and /live1 (401).")
    print("In the eufy app: re-enter the RTSP username and password, tap Save, wait 30 s, and run this again.")
    return 1


# ---- commands ---------------------------------------------------------------------------------------

def show(home):
    on = lambda a: ("on " if a["on"] else "off ")
    names = {d["id"]: d["name"] for d in home.get("devices", [])}
    print("Devices:" if home.get("devices") else "Devices: none (./home.sh find)")
    rooms = {r["id"]: r["name"] for r in home.get("rooms", [])}
    for d in home.get("devices", []):
        kind = "sensor" if d.get("sensor") and not d.get("switch") else d.get("brand", "shelly")
        extra = ("  [" + rooms[d["room"]] + "]" if d.get("room") in rooms else "") + ("  ★" if d.get("favorite") else "")
        print(f"  {d['name']:<22} {kind:<7} {d['host']}{extra}")
    for title, key in (("Scenes", "scenes"), ("Schedules", "schedules")):
        if home.get(key):
            print(title + ":")
        for s in home.get(key, []):
            does = ", ".join(on(a) + names.get(a["device"], "?") for a in s.get("actions", []))
            if s.get("scene"):
                does = "scene " + next((x["name"] for x in home.get("scenes", []) if x["id"] == s["scene"]), "?")
            extra = ""
            if key == "schedules":
                state = "" if s.get("enabled", True) else "  [disabled]"
                nxt = s.get("next", "")[:16].replace("T", " ")
                extra = f"  {s.get('text', '')}" + (f"  next {nxt}" if nxt else "") + state
            print(f"  {s['name']:<22} {does}{extra}")
    if home.get("cameras"):
        print("Cameras:")
    for c in home.get("cameras", []):
        print(f"  {c['name']:<22} {c.get('host', '')}")
    if "latitude" in home:
        print(f"Location: {home['latitude']}, {home['longitude']}")
    if home.get("activity"):
        print("Recent activity:")
        for line in home["activity"][-8:]:
            print("  " + line)


def main(argv=None):
    ap = argparse.ArgumentParser(prog="home.sh", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd")
    sub.add_parser("status")
    f = sub.add_parser("find")
    f.add_argument("--dry-run", action="store_true", help="only list what answers; change nothing")
    a = sub.add_parser("add")
    a.add_argument("host")
    a.add_argument("--name", default="")
    r = sub.add_parser("rename")
    r.add_argument("name")
    r.add_argument("to")
    rm = sub.add_parser("remove")
    rm.add_argument("name")
    sc = sub.add_parser("scene")
    sc.add_argument("name")
    sc.add_argument("--on", default="")
    sc.add_argument("--off", default="")
    sh = sub.add_parser("schedule")
    sh.add_argument("name")
    sh.add_argument("--at", required=True)
    sh.add_argument("--offset", default="")
    sh.add_argument("--days", default="daily")
    sh.add_argument("--on", default="")
    sh.add_argument("--off", default="")
    sh.add_argument("--scene", default="")
    sh.add_argument("--window", default="")
    sh.add_argument("--autooff", default="")
    mv = sub.add_parser("move")
    mv.add_argument("name")
    mv.add_argument("host")
    rm_ = sub.add_parser("room")
    rm_.add_argument("name")
    rm_.add_argument("room")
    sub.add_parser("clear-activity")
    fv = sub.add_parser("favorite")
    fv.add_argument("name")
    fv.add_argument("--off", action="store_true")
    for name in ("enable", "disable"):
        sub.add_parser(name).add_argument("name")
    loc = sub.add_parser("location")
    loc.add_argument("lat")
    loc.add_argument("lon")
    cam = sub.add_parser("camera")
    cam.add_argument("name")
    cam.add_argument("host")
    cam.add_argument("--user", default="")
    cam.add_argument("--path", default="/live0")
    cam.add_argument("--port", default="554")
    cs = sub.add_parser("camera-setup")
    cs.add_argument("name")
    cs.add_argument("host")
    cs.add_argument("--path", default="/live0")
    cs.add_argument("--port", default="554")
    chk = sub.add_parser("camera-check")
    chk.add_argument("host")
    chk.add_argument("--user", default="")
    chk.add_argument("--path", default="/live0")
    chk.add_argument("--port", default="554")
    args = ap.parse_args(argv)

    if args.cmd == "camera-setup":
        sys.exit(camera_setup(args.name, args.host, args.path, args.port))
    if args.cmd == "camera-check":
        sys.exit(camera_check(args.host, args.user, args.path, args.port))
    if args.cmd in (None, "status"):
        subprocess.run(["adb", "connect", TV], capture_output=True)
        return show(send())
    if args.cmd == "find":
        plugs = kasa_discover()
        cams = find_cameras()
        for h in cams:
            print(f"  camera                 RTSP      {h}   add it: ./home.sh camera-setup \"Name\" {h}")
        if not plugs:
            print("No Kasa plugs answered. Are they on this Wi-Fi?")
            return
        for ip, info in sorted(plugs.items()):
            print(f"  {info.get('alias', '?'):<22} {info.get('model', '')}  {ip}  {'on' if info.get('relay_state') else 'off'}")
        if args.dry_run:
            return
        subprocess.run(["adb", "connect", TV], capture_output=True)
        known = {d["host"] for d in send().get("devices", [])}
        for ip in sorted(plugs):
            if ip not in known:
                send("ADD_DEVICE", [("host", ip)])
        return show(send())
    subprocess.run(["adb", "connect", TV], capture_output=True)
    if args.cmd == "add":
        home = send("ADD_DEVICE", [("host", args.host)] + ([("name", args.name)] if args.name else []))
    elif args.cmd == "rename":
        home = send("RENAME", [("name", args.name), ("to", args.to)])
    elif args.cmd == "remove":
        home = send("REMOVE", [("name", args.name)])
    elif args.cmd == "scene":
        home = send("ADD_SCENE", [("name", args.name)], [("on", split(args.on)), ("off", split(args.off))])
    elif args.cmd == "schedule":
        strings = [("name", args.name), ("at", args.at), ("days", args.days)]
        if args.offset:
            strings.append(("offset", args.offset))
        if args.scene:
            strings.append(("scene", args.scene))
        if args.window:
            strings.append(("window", args.window))
        if args.autooff:
            strings.append(("autooff", args.autooff))
        home = send("ADD_SCHEDULE", strings, [("on", split(args.on)), ("off", split(args.off))])
    elif args.cmd in ("enable", "disable"):
        home = send("SET_ENABLED", [("name", args.name), ("enabled", "true" if args.cmd == "enable" else "false")])
    elif args.cmd == "move":
        home = send("SET_HOST", [("name", args.name), ("host", args.host)])
    elif args.cmd == "room":
        home = send("SET_ROOM", [("name", args.name), ("room", args.room)])
    elif args.cmd == "clear-activity":
        home = send("CLEAR_ACTIVITY")
    elif args.cmd == "favorite":
        home = send("SET_FAVORITE", [("name", args.name), ("favorite", "false" if args.off else "true")])
    elif args.cmd == "location":
        home = send("SET_LOCATION", [("lat", args.lat), ("lon", args.lon)])
    elif args.cmd == "camera":
        # The password is typed here, never echoed or saved on the Mac; it goes to the app once over adb.
        password = getpass.getpass(f"Stream password for {args.name} (Enter for none): ")
        auth = ""
        if args.user or password:
            auth = quote(args.user, safe="") + (":" + quote(password, safe="") if password else "") + "@"
        path = args.path if args.path.startswith("/") else "/" + args.path
        home = send("ADD_CAMERA", [("name", args.name), ("url", f"rtsp://{auth}{args.host}:{args.port}{path}")])
    show(home)


if __name__ == "__main__":
    main()
