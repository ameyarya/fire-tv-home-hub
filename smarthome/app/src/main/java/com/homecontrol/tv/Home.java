package com.homecontrol.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Everything the app knows about the home: rooms, devices, cameras, scenes and automations. Plain Java
 * with org.json so it can be unit tested on the JVM; {@link Storage} keeps it in the app's private
 * preferences on the TV.
 */
final class Home {

    static final String SHELLY = "shelly", KASA = "kasa";

    static final class Room {
        final String id;
        String name;

        Room(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** A Shelly device (switch and/or temperature and humidity sensor) or a Kasa plug. */
    static final class Device {
        final String id;
        /** SHELLY or KASA: which protocol talks to it. */
        String brand = SHELLY;
        /**
         * Permanent identity reported by the device itself (Kasa deviceId, Shelly id or MAC), so it can be
         * found again when the router hands it a new address. Empty for devices added before this existed.
         */
        String uid = "";
        String name;
        /** host or host:port, e.g. 192.168.1.40 */
        String host;
        /** Shelly API generation: 1 for the original HTTP API, 2 for RPC (Plus, Pro, Gen3, Gen4). 0 for Kasa. */
        int gen;
        boolean hasSwitch;
        boolean hasSensor;
        /** A Room id, or empty for the default room. */
        String roomId = "";
        boolean favorite;

        Device(String id, String name, String host, int gen, boolean hasSwitch, boolean hasSensor) {
            this.id = id;
            this.name = name;
            this.host = host;
            this.gen = gen;
            this.hasSwitch = hasSwitch;
            this.hasSensor = hasSensor;
        }
    }

    static final class Camera {
        final String id;
        String name;
        /** rtsp://user:pass@host:554/path; stays on the TV, in the app's private storage. */
        String url;
        String roomId = "";
        boolean favorite;

        Camera(String id, String name, String url) {
            this.id = id;
            this.name = name;
            this.url = url;
        }
    }

    /** One device and the state the scene puts it in. */
    static final class Action {
        final String deviceId;
        final boolean on;

        Action(String deviceId, boolean on) {
            this.deviceId = deviceId;
            this.on = on;
        }
    }

    static final class Scene {
        final String id;
        String name;
        boolean favorite;
        final List<Action> actions = new ArrayList<>();

        Scene(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /**
     * An automation: runs actions (or a scene) when its trigger happens and its conditions hold.
     * Triggers: a time of day, sunrise/sunset plus an offset, or the TV turning on or off. Conditions:
     * weekdays, and for the TV triggers a time window. Devices it turns on can be turned off again after
     * a number of minutes. Times are local to the TV's time zone; see Schedules for when it runs.
     * (Called Schedule since before it could do more than times.)
     */
    static final class Schedule {
        static final String AT = "at", SUNRISE = "sunrise", SUNSET = "sunset", TV_ON = "tv-on", TV_OFF = "tv-off";
        /** Monday = bit 0 ... Sunday = bit 6. */
        static final int EVERY_DAY = 0x7F;

        final String id;
        String name;
        boolean enabled = true;
        /** AT, SUNRISE, SUNSET, TV_ON or TV_OFF. */
        String when = AT;
        /** AT: minutes after midnight. SUNRISE/SUNSET: offset in minutes, negative = before. Unused for TV triggers. */
        int minutes;
        int days = EVERY_DAY;
        /** TV triggers: only between these minutes after midnight (may wrap past midnight); -1 = any time. */
        int windowFrom = -1, windowTo = -1;
        /** Turn the devices it switched on back off after this many minutes; 0 = leave them on. */
        int autoOffMinutes;
        /** A scene to run, or empty to run {@link #actions}. */
        String sceneId = "";
        final List<Action> actions = new ArrayList<>();

        Schedule(String id, String name) {
            this.id = id;
            this.name = name;
        }

        boolean isTvTrigger() {
            return TV_ON.equals(when) || TV_OFF.equals(when);
        }
    }

    /** A device an automation switched on, to switch off at {@link #atMillis} (Schedule.autoOffMinutes). */
    static final class PendingOff {
        final String deviceId;
        final long atMillis;

        PendingOff(String deviceId, long atMillis) {
            this.deviceId = deviceId;
            this.atMillis = atMillis;
        }
    }

    final List<Room> rooms = new ArrayList<>();
    final List<Device> devices = new ArrayList<>();
    final List<Camera> cameras = new ArrayList<>();
    final List<Scene> scenes = new ArrayList<>();
    final List<Schedule> schedules = new ArrayList<>();
    final List<PendingOff> pendingOffs = new ArrayList<>();
    /**
     * The order of the sections, on the TV and the phone page (the TV keeps Favorites first and itself last).
     * "remotes" (the TV remote) is only on the phone page.
     */
    static final List<String> SECTIONS = java.util.Arrays.asList("remotes", "scenes", "cameras", "plugs", "automations");
    final List<String> sections = new ArrayList<>(SECTIONS);
    /** For sunrise and sunset automations; NaN until set (Settings → Location, or home.sh location). */
    double latitude = Double.NaN, longitude = Double.NaN;

    static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    Room room(String id) {
        for (Room r : rooms) if (r.id.equals(id)) return r;
        return null;
    }

    Room roomNamed(String name) {
        for (Room r : rooms) if (r.name.equalsIgnoreCase(name.trim()) || r.id.equals(name)) return r;
        return null;
    }

    Scene scene(String id) {
        for (Scene s : scenes) if (s.id.equals(id)) return s;
        return null;
    }

    Schedule schedule(String id) {
        for (Schedule s : schedules) if (s.id.equals(id)) return s;
        return null;
    }

    Camera camera(String id) {
        for (Camera c : cameras) if (c.id.equals(id)) return c;
        return null;
    }

    Device deviceByUid(String uid) {
        if (uid == null || uid.isEmpty()) return null;
        for (Device d : devices) if (uid.equals(d.uid)) return d;
        return null;
    }

    Device device(String id) {
        for (Device d : devices) if (d.id.equals(id)) return d;
        return null;
    }

    Device deviceAt(String host) {
        for (Device d : devices) if (d.host.equalsIgnoreCase(host)) return d;
        return null;
    }

    /**
     * Removes a device and every scene and automation step that used it; a scene or automation left with
     * nothing to do goes too.
     */
    void removeDevice(String id) {
        devices.removeIf(d -> d.id.equals(id));
        pendingOffs.removeIf(p -> p.deviceId.equals(id));
        for (Scene s : scenes) s.actions.removeIf(a -> a.deviceId.equals(id));
        List<String> gone = new ArrayList<>();
        for (Scene s : scenes) if (s.actions.isEmpty()) gone.add(s.id);
        scenes.removeIf(s -> s.actions.isEmpty());
        for (String sceneId : gone) removeScene(sceneId);
        for (Schedule s : schedules) s.actions.removeIf(a -> a.deviceId.equals(id));
        schedules.removeIf(s -> s.sceneId.isEmpty() && s.actions.isEmpty());
    }

    /** Removes a scene and the automations that ran it. */
    void removeScene(String id) {
        scenes.removeIf(s -> s.id.equals(id));
        schedules.removeIf(s -> s.sceneId.equals(id));
    }

    /** Removes a room; what was in it moves to the default room. */
    void removeRoom(String id) {
        rooms.removeIf(r -> r.id.equals(id));
        for (Device d : devices) if (d.roomId.equals(id)) d.roomId = "";
        for (Camera c : cameras) if (c.roomId.equals(id)) c.roomId = "";
    }

    /**
     * Moves an item one place earlier (by = -1) or later (+1) in its list: the order tiles are shown in, on the TV
     * and on the phone. kind: device, scene, camera or automation. False when it can't move that way.
     */
    boolean move(String kind, String id, int by) {
        if ("section".equals(kind)) {
            int i = sections.indexOf(id), j = i + by;
            if (i < 0 || (by != -1 && by != 1) || j < 0 || j >= sections.size()) return false;
            java.util.Collections.swap(sections, i, j);
            return true;
        }
        List<?> list = "device".equals(kind) ? devices : "scene".equals(kind) ? scenes
                : "camera".equals(kind) ? cameras : "automation".equals(kind) ? schedules : null;
        if (list == null || (by != -1 && by != 1)) return false;
        for (int i = 0; i < list.size(); i++) {
            Object x = list.get(i);
            String xid = x instanceof Device ? ((Device) x).id : x instanceof Scene ? ((Scene) x).id
                    : x instanceof Camera ? ((Camera) x).id : ((Schedule) x).id;
            if (!xid.equals(id)) continue;
            int j = i + by;
            if (j < 0 || j >= list.size()) return false;
            java.util.Collections.swap(list, i, j);
            return true;
        }
        return false;
    }

    // ---- JSON ----------------------------------------------------------------------------------

    private static JSONArray actionsJson(List<Action> actions) throws JSONException {
        JSONArray as = new JSONArray();
        for (Action a : actions) as.put(new JSONObject().put("device", a.deviceId).put("on", a.on));
        return as;
    }

    private void readActions(JSONArray as, List<Action> into) {
        for (int j = 0; as != null && j < as.length(); j++) {
            JSONObject a = as.optJSONObject(j);
            if (a != null && device(a.optString("device")) != null) into.add(new Action(a.optString("device"), a.optBoolean("on")));
        }
    }

    /** An automation from JSON (as saved, and as the phone page sends it); id: the one to give it. */
    Schedule readSchedule(JSONObject s, String id) {
        Home h = this;
        Schedule x = new Schedule(id, s.optString("name", "Automation"));
        x.enabled = s.optBoolean("enabled", true);
        String when = s.optString("when", Schedule.AT);
        switch (when) {
            case Schedule.SUNRISE: case Schedule.SUNSET: case Schedule.TV_ON: case Schedule.TV_OFF:
                x.when = when;
                break;
            default:
                x.when = Schedule.AT;
        }
        x.minutes = s.optInt("minutes", 0);
        x.days = s.optInt("days", Schedule.EVERY_DAY) & Schedule.EVERY_DAY;
        x.windowFrom = s.optInt("window_from", -1);
        x.windowTo = s.optInt("window_to", -1);
        if (x.windowFrom < 0 || x.windowTo < 0 || x.windowFrom >= 1440 || x.windowTo >= 1440) x.windowFrom = x.windowTo = -1;
        x.autoOffMinutes = Math.max(0, s.optInt("auto_off", 0));
        x.sceneId = h.scene(s.optString("scene", "")) != null ? s.optString("scene") : "";
        h.readActions(s.optJSONArray("actions"), x.actions);
        return x;
    }

    String toJson() {
        try {
            JSONObject o = new JSONObject();
            JSONArray rs = new JSONArray();
            for (Room r : rooms) rs.put(new JSONObject().put("id", r.id).put("name", r.name));
            JSONArray ds = new JSONArray();
            for (Device d : devices) {
                ds.put(new JSONObject().put("id", d.id).put("brand", d.brand).put("uid", d.uid).put("name", d.name).put("host", d.host)
                        .put("gen", d.gen).put("switch", d.hasSwitch).put("sensor", d.hasSensor).put("room", d.roomId)
                        .put("favorite", d.favorite));
            }
            JSONArray cs = new JSONArray();
            for (Camera c : cameras) {
                cs.put(new JSONObject().put("id", c.id).put("name", c.name).put("url", c.url).put("room", c.roomId).put("favorite", c.favorite));
            }
            JSONArray ss = new JSONArray();
            for (Scene s : scenes) {
                ss.put(new JSONObject().put("id", s.id).put("name", s.name).put("favorite", s.favorite).put("actions", actionsJson(s.actions)));
            }
            JSONArray sch = new JSONArray();
            for (Schedule s : schedules) {
                sch.put(new JSONObject().put("id", s.id).put("name", s.name).put("enabled", s.enabled).put("when", s.when)
                        .put("minutes", s.minutes).put("days", s.days).put("window_from", s.windowFrom).put("window_to", s.windowTo)
                        .put("auto_off", s.autoOffMinutes).put("scene", s.sceneId).put("actions", actionsJson(s.actions)));
            }
            JSONArray po = new JSONArray();
            for (PendingOff p : pendingOffs) po.put(new JSONObject().put("device", p.deviceId).put("at", p.atMillis));
            o.put("version", 2).put("rooms", rs).put("devices", ds).put("cameras", cs).put("scenes", ss)
                    .put("schedules", sch).put("pending_off", po).put("sections", new JSONArray(sections));
            if (!Double.isNaN(latitude) && !Double.isNaN(longitude)) o.put("latitude", latitude).put("longitude", longitude);
            return o.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Never throws: a damaged file gives an empty home rather than an app that won't open. */
    static Home fromJson(String json) {
        Home h = new Home();
        if (json == null || json.isEmpty()) return h;
        try {
            JSONObject o = new JSONObject(json);
            JSONArray rs = o.optJSONArray("rooms");
            for (int i = 0; rs != null && i < rs.length(); i++) {
                JSONObject r = rs.optJSONObject(i);
                if (r != null && !r.optString("name").trim().isEmpty()) h.rooms.add(new Room(r.optString("id", newId()), r.optString("name").trim()));
            }
            JSONArray ds = o.optJSONArray("devices");
            for (int i = 0; ds != null && i < ds.length(); i++) {
                JSONObject d = ds.optJSONObject(i);
                if (d == null || d.optString("host").isEmpty()) continue;
                Device dev = new Device(d.optString("id", newId()), d.optString("name", d.optString("host")),
                        d.optString("host"), d.optInt("gen", 2), d.optBoolean("switch", true), d.optBoolean("sensor", false));
                dev.brand = KASA.equals(d.optString("brand")) ? KASA : SHELLY;   // saved before Kasa support: Shelly
                dev.uid = d.optString("uid", "");
                dev.roomId = h.room(d.optString("room", "")) != null ? d.optString("room") : "";
                dev.favorite = d.optBoolean("favorite", false);
                h.devices.add(dev);
            }
            JSONArray cs = o.optJSONArray("cameras");
            for (int i = 0; cs != null && i < cs.length(); i++) {
                JSONObject c = cs.optJSONObject(i);
                if (c == null || c.optString("url").isEmpty()) continue;
                Camera cam = new Camera(c.optString("id", newId()), c.optString("name", "Camera"), c.optString("url"));
                cam.roomId = h.room(c.optString("room", "")) != null ? c.optString("room") : "";
                cam.favorite = c.optBoolean("favorite", false);
                h.cameras.add(cam);
            }
            JSONArray ss = o.optJSONArray("scenes");
            for (int i = 0; ss != null && i < ss.length(); i++) {
                JSONObject s = ss.optJSONObject(i);
                if (s == null) continue;
                Scene scene = new Scene(s.optString("id", newId()), s.optString("name", "Scene"));
                scene.favorite = s.optBoolean("favorite", false);
                h.readActions(s.optJSONArray("actions"), scene.actions);
                if (!scene.actions.isEmpty()) h.scenes.add(scene);
            }
            JSONArray sch = o.optJSONArray("schedules");
            for (int i = 0; sch != null && i < sch.length(); i++) {
                JSONObject s = sch.optJSONObject(i);
                if (s == null) continue;
                Schedule x = h.readSchedule(s, s.optString("id", newId()));
                if ((!x.sceneId.isEmpty() || !x.actions.isEmpty()) && x.days != 0) h.schedules.add(x);
            }
            JSONArray secs = o.optJSONArray("sections");
            if (secs != null) {   // the saved order, then any section added since
                List<String> order = new ArrayList<>();
                for (int i = 0; i < secs.length(); i++) {
                    String k = secs.optString(i);
                    if (SECTIONS.contains(k) && !order.contains(k)) order.add(k);
                }
                for (String k : SECTIONS) {   // at its usual place, as near as the saved order allows
                    if (!order.contains(k)) order.add(Math.min(SECTIONS.indexOf(k), order.size()), k);
                }
                h.sections.clear();
                h.sections.addAll(order);
            }
            JSONArray po = o.optJSONArray("pending_off");
            for (int i = 0; po != null && i < po.length(); i++) {
                JSONObject p = po.optJSONObject(i);
                if (p != null && h.device(p.optString("device")) != null) h.pendingOffs.add(new PendingOff(p.optString("device"), p.optLong("at")));
            }
            if (o.has("latitude") && o.has("longitude")) {
                h.latitude = o.optDouble("latitude", Double.NaN);
                h.longitude = o.optDouble("longitude", Double.NaN);
            }
        } catch (JSONException ignored) {
            return new Home();
        }
        return h;
    }
}
