package com.homecontrol.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Smart Home on a phone: the phone page's Home tab (served by RemoteServer, behind the same secret as the
 * remote) reads the home and switches devices, runs scenes and turns automations on and off, as the TV app
 * does. The Home is only touched on the main thread (as everywhere in the app); devices are asked off it.
 */
final class HomeApi {

    private static final ExecutorService IO = Executors.newFixedThreadPool(6);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private HomeApi() {
    }

    /** Runs on the main thread and waits for the answer. */
    private static <T> T onMain(Supplier<T> s) {
        Object[] out = {null};
        CountDownLatch done = new CountDownLatch(1);
        MAIN.post(() -> {
            try {
                out[0] = s.get();
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        @SuppressWarnings("unchecked") T t = (T) out[0];
        return t;
    }

    /** Rooms, devices with their state now, scenes and automations. */
    static JSONObject home(Context c) throws JSONException {
        JSONObject o = onMain(() -> {
            try {
                return snapshot(Storage.get(c));
            } catch (JSONException e) {
                return null;
            }
        });
        if (o == null) throw new JSONException("no home");
        // Ask every device at once; a slow one shows as not answering rather than holding up the page.
        Home copy = onMain(() -> Storage.get(c));
        List<Home.Device> devices = onMain(() -> new ArrayList<>(copy.devices));
        List<Future<Shelly.Status>> asks = new ArrayList<>();
        for (Home.Device d : devices) asks.add(IO.submit(() -> Devices.status(d)));
        JSONArray list = o.getJSONArray("devices");
        for (int i = 0; i < devices.size(); i++) {
            JSONObject j = list.getJSONObject(i);
            try {
                Shelly.Status st = asks.get(i).get(4, TimeUnit.SECONDS);
                j.put("online", true).put("on", st.on);
                if (!Double.isNaN(st.watts)) j.put("watts", Math.round(st.watts * 10) / 10.0);
                if (!Double.isNaN(st.tempC)) j.put("tempC", st.tempC);
                if (!Double.isNaN(st.humidity)) j.put("humidity", st.humidity);
            } catch (Exception e) {
                j.put("online", false);
            }
        }
        return o;
    }

    private static JSONObject snapshot(Home h) throws JSONException {
        JSONObject o = new JSONObject();
        JSONArray rooms = new JSONArray(), devices = new JSONArray(), scenes = new JSONArray(), autos = new JSONArray();
        for (Home.Room r : h.rooms) rooms.put(new JSONObject().put("id", r.id).put("name", r.name));
        for (Home.Device d : h.devices) {
            devices.put(new JSONObject().put("id", d.id).put("name", d.name).put("room", d.roomId)
                    .put("switch", d.hasSwitch).put("sensor", d.hasSensor).put("favorite", d.favorite));
        }
        JSONArray cameras = new JSONArray();
        for (Home.Camera cam : h.cameras) {   // name and room only: the stream address holds the camera's password
            cameras.put(new JSONObject().put("id", cam.id).put("name", cam.name).put("room", cam.roomId));
        }
        for (Home.Scene s : h.scenes) {
            int on = 0;
            JSONArray acts = new JSONArray();
            for (Home.Action a : s.actions) {
                if (a.on) on++;
                acts.put(new JSONObject().put("device", a.deviceId).put("on", a.on));
            }
            scenes.put(new JSONObject().put("id", s.id).put("name", s.name).put("actions", s.actions.size())
                    .put("onCount", on).put("steps", acts));
        }
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault());
        for (Home.Schedule s : h.schedules) {
            JSONArray acts = new JSONArray();
            for (Home.Action x : s.actions) acts.put(new JSONObject().put("device", x.deviceId).put("on", x.on));
            // describe: the TV's wording; the rest is what the phone's editor edits (as Home.readSchedule reads it)
            JSONObject a = new JSONObject().put("id", s.id).put("name", s.name).put("enabled", s.enabled)
                    .put("describe", Schedules.describe(s)).put("when", s.when).put("minutes", s.minutes).put("days", s.days)
                    .put("window_from", s.windowFrom).put("window_to", s.windowTo).put("auto_off", s.autoOffMinutes)
                    .put("scene", s.sceneId).put("actions", acts);
            ZonedDateTime next = s.enabled ? Schedules.next(s, h, now) : null;
            if (next != null) a.put("next", next.format(fmt));
            autos.put(a);
        }
        return o.put("rooms", rooms).put("devices", devices).put("scenes", scenes).put("automations", autos).put("sections", new JSONArray(h.sections))
                .put("cameras", cameras);
    }

    /** Switches a device; false when it doesn't answer. */
    static boolean device(Context c, String id, boolean on) {
        Home.Device d = onMain(() -> Storage.get(c).device(id));
        if (d == null) return false;
        try {
            Devices.set(d, on);
            ActivityLog.add(c, d.name + " turned " + (on ? "on" : "off") + " from a phone");
            return true;
        } catch (IOException e) {
            ActivityLog.add(c, d.name + " didn't answer (turn " + (on ? "on" : "off") + " from a phone)");
            return false;
        }
    }

    /** Runs a scene's actions together; the number that worked. -1 for no such scene. */
    static int scene(Context c, String id) {
        Home.Scene s = onMain(() -> {
            for (Home.Scene x : Storage.get(c).scenes) if (x.id.equals(id)) return x;
            return null;
        });
        if (s == null) return -1;
        AtomicInteger ok = new AtomicInteger();
        List<Future<?>> all = new ArrayList<>();
        for (Home.Action a : s.actions) {
            Home.Device d = onMain(() -> Storage.get(c).device(a.deviceId));
            if (d == null) continue;
            all.add(IO.submit(() -> {
                try {
                    Devices.set(d, a.on);
                    ok.incrementAndGet();
                } catch (IOException ignored) {
                }
            }));
        }
        for (Future<?> f : all) {
            try {
                f.get(6, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        ActivityLog.add(c, "Scene " + s.name + " from a phone: " + ok.get() + "/" + s.actions.size() + " done");
        return ok.get();
    }

    /** Turns an automation on or off; false for no such automation. */
    static boolean automation(Context c, String id, boolean on) {
        Boolean found = onMain(() -> {
            for (Home.Schedule s : Storage.get(c).schedules) {
                if (!s.id.equals(id)) continue;
                s.enabled = on;
                Storage.save(c);
                Scheduler.reschedule(c);
                ActivityLog.add(c, "Automation " + s.name + " turned " + (on ? "on" : "off") + " from a phone");
                return true;
            }
            return false;
        });
        return Boolean.TRUE.equals(found);
    }

    /** Moves a tile a place (Home.move); false when it can't. */
    static boolean move(Context c, String kind, String id, int by) {
        Boolean ok = onMain(() -> {
            if (!Storage.get(c).move(kind, id, by)) return false;
            Storage.save(c);
            return true;
        });
        return Boolean.TRUE.equals(ok);
    }

    /** Creates or replaces an automation from the phone's editor; null when saved, else what's wrong. */
    static String saveAutomation(Context c, String json) {
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (JSONException e) {
            return "not an automation";
        }
        return onMain(() -> {
            Home h = Storage.get(c);
            String id = o.optString("id", "");
            Home.Schedule old = null;
            for (Home.Schedule s : h.schedules) if (s.id.equals(id)) old = s;
            Home.Schedule s = h.readSchedule(o, old != null ? old.id : Home.newId());
            if (s.name.trim().isEmpty()) return "Give it a name";
            if (s.days == 0) return "Pick at least one day";
            if (s.sceneId.isEmpty() && s.actions.isEmpty()) return "Choose a scene or at least one device";
            if (old != null) h.schedules.set(h.schedules.indexOf(old), s);
            else h.schedules.add(s);
            Storage.save(c);
            Scheduler.reschedule(c);
            ActivityLog.add(c, "Automation " + s.name + (old != null ? " changed" : " added") + " from a phone");
            return null;
        });
    }

    static boolean deleteAutomation(Context c, String id) {
        Boolean ok = onMain(() -> {
            Home h = Storage.get(c);
            for (Home.Schedule s : h.schedules) {
                if (!s.id.equals(id)) continue;
                h.schedules.remove(s);
                Storage.save(c);
                Scheduler.reschedule(c);
                ActivityLog.add(c, "Automation " + s.name + " removed from a phone");
                return true;
            }
            return false;
        });
        return Boolean.TRUE.equals(ok);
    }

    /**
     * A camera tile on the phone: its live view full screen on the TV, turning the TV on first if it's off.
     * Phone browsers can't play RTSP, so the TV shows it. Needs the "display over other apps" app-op
     * (install-smarthome.sh) to open from the background. Null when shown, else what's wrong.
     */
    static Home.Camera camera(Context c, String id) {
        return onMain(() -> {
            for (Home.Camera x : Storage.get(c).cameras) if (x.id.equals(id)) return x;
            return null;
        });
    }

    @SuppressWarnings("deprecation")
    static String showCamera(Context c, String id) {
        Home.Camera cam = onMain(() -> {
            for (Home.Camera x : Storage.get(c).cameras) if (x.id.equals(id)) return x;
            return null;
        });
        if (cam == null) return "No such camera";
        android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
        if (!pm.isInteractive()) {   // TV off: switch the screen on
            pm.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "SmartHome:camera").acquire(5000);
        }
        try {
            c.startActivity(new android.content.Intent(c, CameraActivity.class).putExtra(CameraActivity.EXTRA_ID, cam.id)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch (RuntimeException e) {
            return "The TV wouldn't open it: " + e.getMessage();
        }
        ActivityLog.add(c, "Camera " + cam.name + " shown on the TV from a phone");
        return null;
    }

    // ---- adding, as the TV's + Add does (Editors) ------------------------------------------------------

    /** Kasa (broadcast) and Shelly (mDNS) on the Wi-Fi, about 8 s; adds the new ones. Their names, or null on failure. */
    static JSONArray findDevices(Context c) {
        java.util.concurrent.Future<java.util.Set<String>> kasa = IO.submit(() -> Kasa.discover(4000));
        List<String> shelly = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        MAIN.post(() -> new Discovery(c).scan(hosts -> {
            shelly.addAll(hosts);
            done.countDown();
        }));
        try {
            done.await(Discovery.SCAN_MS + 3000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        List<Home.Device> found = new ArrayList<>();
        for (String host : shelly) {
            try {
                found.add(MainActivity.deviceFrom(host, Shelly.probe(host)));
            } catch (IOException ignored) {
            }
        }
        try {
            for (String host : kasa.get(6, TimeUnit.SECONDS)) {
                try {
                    found.add(MainActivity.deviceFrom(host, Kasa.probe(host)));
                } catch (IOException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return onMain(() -> {
            Home h = Storage.get(c);
            JSONArray added = new JSONArray();
            for (Home.Device d : found) {
                if (h.deviceAt(d.host) == null && h.deviceByUid(d.uid) == null) {
                    h.devices.add(d);
                    added.put(d.name);
                    ActivityLog.add(c, d.name + " added from a phone");
                }
            }
            if (added.length() > 0) Storage.save(c);
            return added;
        });
    }

    /** A plug or sensor at an address; null when added, else what's wrong. */
    static String addDevice(Context c, String host) {
        String h = host.trim().replaceFirst("^https?://", "").replaceAll("/.*$", "");
        if (h.isEmpty()) return "Enter its address";
        Home.Device d;
        try {
            d = MainActivity.deviceFrom(h, Devices.probe(h));
        } catch (IOException e) {
            return "No Kasa or Shelly device answered at " + h;
        }
        return onMain(() -> {
            Home home = Storage.get(c);
            Home.Device same = home.deviceByUid(d.uid);
            if (same != null) same.host = d.host;   // known device at a new address
            else if (home.deviceAt(d.host) != null) return d.host + " is already added";
            else home.devices.add(d);
            Storage.save(c);
            ActivityLog.add(c, d.name + " added from a phone");
            return null;
        });
    }

    /** Creates or replaces a scene: {id?, name, steps: [{device, on}]}; null when saved, else what's wrong. */
    static String saveScene(Context c, String json) {
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (JSONException e) {
            return "not a scene";
        }
        return onMain(() -> {
            Home h = Storage.get(c);
            String name = o.optString("name", "").trim();
            if (name.isEmpty()) return "Give it a name";
            List<Home.Action> acts = new ArrayList<>();
            JSONArray steps = o.optJSONArray("steps");
            for (int i = 0; steps != null && i < steps.length(); i++) {
                JSONObject a = steps.optJSONObject(i);
                if (a != null && h.device(a.optString("device")) != null) acts.add(new Home.Action(a.optString("device"), a.optBoolean("on")));
            }
            if (acts.isEmpty()) return "Choose at least one device to turn on or off";
            Home.Scene s = h.scene(o.optString("id", ""));
            boolean added = s == null;
            if (added) {
                s = new Home.Scene(Home.newId(), name);
                h.scenes.add(s);
            }
            s.name = name;
            s.actions.clear();
            s.actions.addAll(acts);
            Storage.save(c);
            ActivityLog.add(c, "Scene " + name + (added ? " added" : " changed") + " from a phone");
            return null;
        });
    }

    static boolean deleteScene(Context c, String id) {
        Boolean ok = onMain(() -> {
            Home h = Storage.get(c);
            Home.Scene s = h.scene(id);
            if (s == null) return false;
            h.removeScene(id);
            Storage.save(c);
            Scheduler.reschedule(c);
            ActivityLog.add(c, "Scene " + s.name + " removed from a phone");
            return true;
        });
        return Boolean.TRUE.equals(ok);
    }

    /** A camera: {name, host, user, pass}, the RTSP address built as on the TV (Editors.cameraDetails). */
    static String addCamera(Context c, String json) {
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (JSONException e) {
            return "not a camera";
        }
        String name = o.optString("name", "").trim(), host = o.optString("host", "").trim();
        String user = o.optString("user", ""), pass = o.optString("pass", "");
        if (name.isEmpty()) return "Give it a name";
        if (host.isEmpty()) return "Enter the camera's address";
        String port = host.contains(":") ? "" : ":554";
        String url = user.isEmpty() ? "rtsp://" + host + port + "/live0"
                : "rtsp://" + enc(user) + ":" + enc(pass) + "@" + host + port + "/live0";
        return onMain(() -> {
            Storage.get(c).cameras.add(new Home.Camera(Home.newId(), name, url));
            Storage.save(c);
            ActivityLog.add(c, "Camera " + name + " added from a phone");
            return null;
        });
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    /** Renames a plug, sensor, camera or scene from the phone; null when done, else what's wrong. */
    static String rename(Context c, String kind, String id, String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return "Give it a name";
        if (n.length() > 40) return "That name is too long";
        return onMain(() -> {
            Home h = Storage.get(c);
            String old = null;
            if ("device".equals(kind)) {
                Home.Device d = h.device(id);
                if (d != null) { old = d.name; d.name = n; }
            } else if ("camera".equals(kind)) {
                for (Home.Camera x : h.cameras) if (x.id.equals(id)) { old = x.name; x.name = n; }
            } else if ("scene".equals(kind)) {
                for (Home.Scene x : h.scenes) if (x.id.equals(id)) { old = x.name; x.name = n; }
            }
            if (old == null) return "It isn't there any more";
            Storage.save(c);
            ActivityLog.add(c, old + " renamed to " + n + " from a phone");
            return null;
        });
    }

    static String addRoom(Context c, String name) {
        String n = name.trim();
        if (n.isEmpty()) return "Give it a name";
        return onMain(() -> {
            Home h = Storage.get(c);
            if (h.roomNamed(n) != null) return n + " already exists";
            h.rooms.add(new Home.Room(Home.newId(), n));
            Storage.save(c);
            return null;
        });
    }

    /**
     * Settings for the phone page (the TV's own Settings screens are hidden): location, screensaver, °C/°F, the
     * pairing code, and the page while the TV is off. Cities come grouped by time zone, the TV's own first.
     */
    static JSONObject settings(Context c) throws JSONException {
        Home h = onMain(() -> Storage.get(c));
        JSONObject o = new JSONObject();
        boolean set = !Double.isNaN(h.latitude);
        o.put("location", set ? new JSONObject().put("name", Places.nearest(h.latitude, h.longitude))
                .put("lat", h.latitude).put("lon", h.longitude) : JSONObject.NULL);
        o.put("screensaver", Idle.minutes(c)).put("fahrenheit", Idle.fahrenheit(c))
                .put("code", Remote.code(c)).put("awake", Remote.keepAwake(c));
        JSONArray zones = new JSONArray();
        Places.Zone mine = Places.forTv(java.time.ZoneId.systemDefault());
        List<Places.Zone> order = new ArrayList<>();
        if (mine != null) order.add(mine);
        for (Places.Zone z : Places.ZONES.values()) if (z != mine) order.add(z);
        for (Places.Zone z : order) {
            JSONArray cities = new JSONArray();
            for (Places.City x : z.cities) cities.put(new JSONObject().put("name", x.name).put("lat", x.lat).put("lon", x.lon));
            zones.put(new JSONObject().put("zone", z.label).put("cities", cities));
        }
        return o.put("zones", zones);
    }

    /** Saves the settings the phone sent (any of them); null when saved, else what's wrong. */
    static String saveSettings(Context c, String json) {
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (JSONException e) {
            return "not settings";
        }
        if (o.has("code") && !Remote.setCode(c, o.optString("code"))) return "The code is 4 digits";
        if (o.has("screensaver") && !Idle.set(c, o.optInt("screensaver"))) return "The clean launcher (which opens the clock) isn't installed";
        if (o.has("fahrenheit")) Idle.setFahrenheit(c, o.optBoolean("fahrenheit"));
        if (o.has("awake")) Remote.setKeepAwake(c, o.optBoolean("awake"));
        if (o.has("lat") && o.has("lon")) {
            double lat = o.optDouble("lat"), lon = o.optDouble("lon");
            if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return "Not a place on Earth";
            onMain(() -> {
                Home h = Storage.get(c);
                h.latitude = lat;
                h.longitude = lon;
                Storage.save(c);
                Scheduler.reschedule(c);   // sunrise and sunset move
                return null;
            });
        }
        return null;
    }

    static JSONArray activity(Context c) {
        return new JSONArray(ActivityLog.recent(c, 40));
    }
}
