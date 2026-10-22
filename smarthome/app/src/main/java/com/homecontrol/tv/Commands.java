package com.homecontrol.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * The set-up commands home.sh sends (through ControlReceiver), applied to a Home. Everything except
 * adding a device by address, which has to ask the network. Plain Java so it is tested on the JVM.
 * Each returns null on success or a message saying what was wrong.
 */
final class Commands {

    /** The extras of one command. */
    interface Args {
        String str(String key);

        String[] list(String key);
    }

    static final String P = "com.homecontrol.tv.";

    private Commands() {
    }

    static String apply(Home h, String action, Args a) {
        switch (action) {
            case P + "ADD_SCENE":
                return addScene(h, a);
            case P + "ADD_SCHEDULE":
                return addSchedule(h, a);
            case P + "SET_LOCATION":
                return setLocation(h, a);
            case P + "ADD_CAMERA":
                return addCamera(h, a);
            case P + "SET_ENABLED":
                return setEnabled(h, a);
            case P + "RENAME":
                return rename(h, a);
            case P + "ADD_ROOM": {
                String name = trim(a.str("name"));
                if (name.isEmpty()) return "a room needs a name";
                if (h.roomNamed(name) == null) h.rooms.add(new Home.Room(Home.newId(), name));
                return null;
            }
            case P + "SET_ROOM":
                return setRoom(h, a);
            case P + "SET_FAVORITE":
                return setFavorite(h, a);
            case P + "SET_HOST": {
                Home.Device d = deviceNamed(h, trim(a.str("name")));
                if (d == null) return "no device called " + trim(a.str("name"));
                if (trim(a.str("host")).isEmpty()) return "no address given";
                d.host = trim(a.str("host"));
                return null;
            }
            case P + "REMOVE":
                return remove(h, a);
            case P + "RESET": {
                Home empty = new Home();
                h.devices.clear();
                h.cameras.clear();
                h.scenes.clear();
                h.schedules.clear();
                h.rooms.clear();
                h.pendingOffs.clear();
                h.latitude = empty.latitude;
                h.longitude = empty.longitude;
                return null;
            }
            default:
                return null;   // a plain read
        }
    }

    private static String addScene(Home h, Args a) {
        String name = trim(a.str("name"));
        if (name.isEmpty()) return "a scene needs a name";
        Home.Scene s = new Home.Scene(Home.newId(), name);
        String err = actions(h, s.actions, a);
        if (err != null) return err;
        if (s.actions.isEmpty()) return "a scene needs at least one device to switch (--on / --off)";
        h.scenes.add(s);
        return null;
    }

    private static String addSchedule(Home h, Args a) {
        String name = trim(a.str("name"));
        if (name.isEmpty()) return "a schedule needs a name";
        Home.Schedule s = new Home.Schedule(Home.newId(), name);
        String at = trim(a.str("at")).toLowerCase(Locale.ROOT);
        if (at.equals(Home.Schedule.TV_ON) || at.equals(Home.Schedule.TV_OFF)) {
            s.when = at;
            String window = trim(a.str("window"));
            if (!window.isEmpty()) {
                int[] w = parseWindow(window);
                if (w == null) return "window is HH:MM-HH:MM, like 22:00-06:00";
                s.windowFrom = w[0];
                s.windowTo = w[1];
            }
        } else if (at.equals(Home.Schedule.SUNRISE) || at.equals(Home.Schedule.SUNSET)) {
            if (Double.isNaN(h.latitude)) return "sunrise and sunset need your location first (home.sh location <lat> <lon>)";
            s.when = at;
            try {
                s.minutes = trim(a.str("offset")).isEmpty() ? 0 : Integer.parseInt(trim(a.str("offset")));
            } catch (NumberFormatException e) {
                return "offset is minutes, like -30 or 15";
            }
            if (Math.abs(s.minutes) > 240) return "offset can be at most 4 hours either way";
        } else {
            try {
                LocalTime t = LocalTime.parse(at.length() == 4 ? "0" + at : at);
                s.minutes = t.getHour() * 60 + t.getMinute();
            } catch (DateTimeParseException e) {
                return "time is HH:MM (24-hour), sunrise, sunset, tv-on or tv-off";
            }
        }
        String autoOff = trim(a.str("autooff"));
        if (!autoOff.isEmpty()) {
            try {
                s.autoOffMinutes = Integer.parseInt(autoOff);
            } catch (NumberFormatException e) {
                return "turn off after is minutes, like 30";
            }
            if (s.autoOffMinutes < 0 || s.autoOffMinutes > 24 * 60) return "turn off after is 1 to 1440 minutes";
        }
        String days = trim(a.str("days"));
        s.days = days.isEmpty() ? Home.Schedule.EVERY_DAY : Schedules.parseDays(days);
        if (s.days < 0) return "days is daily, weekdays, weekends or a list like mon,wed,fri";
        String scene = trim(a.str("scene"));
        if (!scene.isEmpty()) {
            Home.Scene sc = sceneNamed(h, scene);
            if (sc == null) return "no scene called " + scene;
            s.sceneId = sc.id;
        }
        String err = actions(h, s.actions, a);
        if (err != null) return err;
        if (s.sceneId.isEmpty() && s.actions.isEmpty()) return "say what it does: --on / --off devices, or --scene";
        h.schedules.add(s);
        return null;
    }

    /** "22:00-06:00" -> {1320, 360}; null when unreadable. */
    static int[] parseWindow(String text) {
        String[] parts = text.replace("–", "-").split("-");
        if (parts.length != 2) return null;
        try {
            LocalTime f = LocalTime.parse(pad(parts[0].trim())), t = LocalTime.parse(pad(parts[1].trim()));
            int from = f.getHour() * 60 + f.getMinute(), to = t.getHour() * 60 + t.getMinute();
            return from == to ? null : new int[]{from, to};
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String pad(String hhmm) {
        return hhmm.length() == 4 ? "0" + hhmm : hhmm;
    }

    /** --es name <device or camera> --es room <room name, created if new; empty = default room>. */
    private static String setRoom(Home h, Args a) {
        String key = trim(a.str("name")), roomName = trim(a.str("room"));
        String roomId = "";
        if (!roomName.isEmpty()) {
            Home.Room r = h.roomNamed(roomName);
            if (r == null) {
                r = new Home.Room(Home.newId(), roomName);
                h.rooms.add(r);
            }
            roomId = r.id;
        }
        Home.Device d = deviceNamed(h, key);
        if (d != null) { d.roomId = roomId; return null; }
        for (Home.Camera c : h.cameras) if (c.name.equalsIgnoreCase(key) || c.id.equals(key)) { c.roomId = roomId; return null; }
        return "no device or camera called " + key;
    }

    private static String setFavorite(Home h, Args a) {
        String key = trim(a.str("name"));
        boolean fav = !"false".equalsIgnoreCase(trim(a.str("favorite")));
        Home.Device d = deviceNamed(h, key);
        if (d != null) { d.favorite = fav; return null; }
        Home.Scene sc = sceneNamed(h, key);
        if (sc != null) { sc.favorite = fav; return null; }
        for (Home.Camera c : h.cameras) if (c.name.equalsIgnoreCase(key) || c.id.equals(key)) { c.favorite = fav; return null; }
        return "nothing called " + key;
    }

    private static String setLocation(Home h, Args a) {
        try {
            double lat = Double.parseDouble(trim(a.str("lat")));
            double lon = Double.parseDouble(trim(a.str("lon")));
            if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return "latitude is -90..90 and longitude -180..180";
            h.latitude = lat;
            h.longitude = lon;
            return null;
        } catch (NumberFormatException e) {
            return "location is two numbers: latitude longitude, like 37.77 -122.42";
        }
    }

    private static String addCamera(Home h, Args a) {
        String name = trim(a.str("name"));
        String url = trim(a.str("url"));
        if (name.isEmpty()) return "a camera needs a name";
        if (!url.toLowerCase(Locale.ROOT).startsWith("rtsp://") || url.length() <= "rtsp://".length()) {
            return "the camera address starts with rtsp://";
        }
        Home.Camera existing = null;
        for (Home.Camera c : h.cameras) if (c.name.equalsIgnoreCase(name)) existing = c;
        if (existing != null) existing.url = url;   // same name: new address or password
        else h.cameras.add(new Home.Camera(Home.newId(), name, url));
        return null;
    }

    private static String setEnabled(Home h, Args a) {
        Home.Schedule s = scheduleNamed(h, trim(a.str("name")));
        if (s == null) return "no schedule called " + trim(a.str("name"));
        s.enabled = !"false".equalsIgnoreCase(trim(a.str("enabled")));
        return null;
    }

    private static String rename(Home h, Args a) {
        String from = trim(a.str("name")), to = trim(a.str("to"));
        if (to.isEmpty()) return "new name is empty";
        Home.Device d = deviceNamed(h, from);
        if (d != null) { d.name = to; return null; }
        Home.Scene sc = sceneNamed(h, from);
        if (sc != null) { sc.name = to; return null; }
        Home.Schedule s = scheduleNamed(h, from);
        if (s != null) { s.name = to; return null; }
        for (Home.Camera c : h.cameras) if (c.name.equalsIgnoreCase(from) || c.id.equals(from)) { c.name = to; return null; }
        Home.Room r = h.roomNamed(from);
        if (r != null) { r.name = to; return null; }
        return "nothing called " + from;
    }

    /** By id (as before) or by name, for any kind of thing. */
    private static String remove(Home h, Args a) {
        String key = trim(a.str("id")).isEmpty() ? trim(a.str("name")) : trim(a.str("id"));
        Home.Device d = deviceNamed(h, key);
        if (d != null) { h.removeDevice(d.id); return null; }
        Home.Scene sc = sceneNamed(h, key);
        if (sc != null) { h.removeScene(sc.id); return null; }
        Home.Schedule s = scheduleNamed(h, key);
        if (s != null) { h.schedules.remove(s); return null; }
        for (Home.Camera c : h.cameras) {
            if (c.name.equalsIgnoreCase(key) || c.id.equals(key)) { h.cameras.remove(c); return null; }
        }
        Home.Room r = h.roomNamed(key);
        if (r != null) { h.removeRoom(r.id); return null; }
        return "nothing called " + key;
    }

    /** --on and --off: devices by name or id. Unknown names are an error, not silently skipped. */
    private static String actions(Home h, List<Home.Action> out, Args a) {
        for (String key : new String[]{"on", "off"}) {
            String[] names = a.list(key);
            if (names == null) continue;
            for (String n : names) {
                if (trim(n).isEmpty()) continue;
                Home.Device d = deviceNamed(h, trim(n));
                if (d == null) return "no device called " + trim(n);
                if (!d.hasSwitch) return d.name + " has no switch";
                out.add(new Home.Action(d.id, key.equals("on")));
            }
        }
        return null;
    }

    static Home.Device deviceNamed(Home h, String key) {
        for (Home.Device d : h.devices) if (d.id.equals(key) || d.name.equalsIgnoreCase(key)) return d;
        return null;
    }

    static Home.Scene sceneNamed(Home h, String key) {
        for (Home.Scene s : h.scenes) if (s.id.equals(key) || s.name.equalsIgnoreCase(key)) return s;
        return null;
    }

    static Home.Schedule scheduleNamed(Home h, String key) {
        for (Home.Schedule s : h.schedules) if (s.id.equals(key) || s.name.equalsIgnoreCase(key)) return s;
        return null;
    }

    /**
     * What home.sh shows: the Home as JSON with camera addresses hidden (they hold passwords), plus each
     * schedule in words and its next run.
     */
    static String report(Home h, ZoneId zone) {
        try {
            JSONObject o = new JSONObject(h.toJson());
            JSONArray cams = o.optJSONArray("cameras");
            for (int i = 0; cams != null && i < cams.length(); i++) {
                JSONObject c = cams.getJSONObject(i);
                String url = c.optString("url");
                c.put("url", "(hidden)");
                c.put("host", hostOf(url));
            }
            JSONArray sch = o.optJSONArray("schedules");
            ZonedDateTime now = ZonedDateTime.now(zone);
            for (int i = 0; sch != null && i < sch.length(); i++) {
                Home.Schedule s = h.schedules.get(i);
                ZonedDateTime next = Schedules.next(s, h, now);
                sch.getJSONObject(i).put("text", Schedules.describe(s)).put("next", next == null ? "" : next.toString());
            }
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    /** The host:port of a stream address, without user or password. */
    static String hostOf(String url) {
        String s = url.replaceFirst("^[a-zA-Z]+://", "");
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int at = s.lastIndexOf('@');
        return at >= 0 ? s.substring(at + 1) : s;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
