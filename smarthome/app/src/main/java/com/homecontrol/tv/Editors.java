package com.homecontrol.tv;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Everything you can add or change on the TV, with the remote: the + menu (plug or sensor, camera, scene,
 * automation, room), the automation builder, the scene editor, rooms, Settings and Activity. Each is a
 * chain of Prompts; every change is saved with Storage.save, which redraws the screens.
 */
final class Editors {

    private final Activity a;
    private final Prompts p;
    private final ExecutorService io;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Discovery discovery;

    Editors(Activity activity, Prompts prompts, ExecutorService io) {
        this.a = activity;
        this.p = prompts;
        this.io = io;
    }

    private Home home() {
        return Storage.get(a);
    }

    private void save() {
        Storage.save(a);
    }

    void stop() {
        if (discovery != null) discovery.stop();
    }

    // ---- + -------------------------------------------------------------------------------------

    void addMenu() {
        p.list("Add", Arrays.asList("Plug or sensor", "Camera", "Scene", "Automation", "Room"), i -> {
            switch (i) {
                case 0: addDevice(); break;
                case 1: addCamera(); break;
                case 2: editScene(null); break;
                case 3: editAutomation(null); break;
                default: newRoom(r -> p.say(r.name + " added"));
            }
        });
    }

    void addDevice() {
        p.list("Add a plug or sensor", Arrays.asList("Find on Wi-Fi", "Enter its address"), i -> {
            if (i == 0) findDevices();
            else p.askText(a.getString(R.string.add_by_ip), "", a.getString(R.string.add_by_ip_hint), this::addByIp);
        });
    }

    /** Kasa plugs (UDP broadcast) and Shelly devices (mDNS), at the same time. */
    void findDevices() {
        p.say(a.getString(R.string.finding));
        if (discovery != null) discovery.stop();
        discovery = new Discovery(a);
        Future<Set<String>> kasa = io.submit(() -> Kasa.discover(4000));
        discovery.scan(hosts -> io.execute(() -> {
            List<Home.Device> found = new ArrayList<>();
            for (String host : hosts) {
                try {
                    found.add(MainActivity.deviceFrom(host, Shelly.probe(host)));
                } catch (IOException ignored) {
                }
            }
            Set<String> kasaHosts;
            try {
                kasaHosts = kasa.get();
            } catch (Exception e) {
                kasaHosts = new HashSet<>();
            }
            for (String host : kasaHosts) {
                try {
                    found.add(MainActivity.deviceFrom(host, Kasa.probe(host)));
                } catch (IOException ignored) {
                }
            }
            main.post(() -> {
                Home h = home();
                int n = 0;
                for (Home.Device d : found) {
                    if (h.deviceAt(d.host) == null && h.deviceByUid(d.uid) == null) {
                        h.devices.add(d);
                        n++;
                    }
                }
                p.say(n == 0 ? a.getString(R.string.found_none) : a.getString(R.string.found_n, n));
                if (n > 0) save();
            });
        }));
    }

    void addByIp(String host) {
        String h = host.trim().replaceFirst("^https?://", "").replaceAll("/.*$", "");
        if (home().deviceAt(h) != null) {
            p.say(a.getString(R.string.already_added, h));
            return;
        }
        io.execute(() -> {
            try {
                Home.Device d = MainActivity.deviceFrom(h, Devices.probe(h));
                main.post(() -> {
                    Home.Device same = home().deviceByUid(d.uid);
                    if (same != null) same.host = d.host;   // known device at a new address
                    else home().devices.add(d);
                    save();
                });
            } catch (IOException e) {
                main.post(() -> p.say(a.getString(R.string.not_shelly, h)));
            }
        });
    }

    // ---- cameras ---------------------------------------------------------------------------------

    void addCamera() {
        p.list("Add a camera", Arrays.asList("Find cameras on Wi-Fi", "Enter its address"), i -> {
            if (i == 1) {
                p.askText("Camera address", "", "192.168.1.249", host -> cameraDetails(host.trim(), null));
                return;
            }
            p.say("Looking for cameras on your Wi-Fi…");
            io.execute(() -> {
                List<String> found = CameraScan.scan(CameraScan.neighbours(), 400);
                main.post(() -> {
                    List<String> fresh = new ArrayList<>();
                    for (String h : found) {
                        boolean known = false;
                        for (Home.Camera c : home().cameras) known |= Commands.hostOf(c.url).replaceAll(":554$", "").equals(h);
                        if (!known) fresh.add(h);
                    }
                    if (fresh.isEmpty()) {
                        p.say(found.isEmpty() ? "No cameras answered. Is RTSP switched on in the camera's app?" : "Those cameras are already added");
                        return;
                    }
                    p.list("Cameras found", fresh, k -> cameraDetails(fresh.get(k), null));
                });
            });
        });
    }

    /** Name, stream username and password for a camera at host; existing: change those of that camera. */
    void cameraDetails(String host, Home.Camera existing) {
        p.askText(a.getString(R.string.camera_name), existing != null ? existing.name : "", "Living room", name ->
                p.askText("Stream username (type - if it has none)", "", "from the camera's RTSP settings", user -> {
                    if (user.equals("-")) {
                        saveCamera(existing, name, "rtsp://" + host + (host.contains(":") ? "" : ":554") + "/live0");
                        return;
                    }
                    p.ask("Stream password", "", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, pass ->
                            saveCamera(existing, name, "rtsp://" + enc(user) + ":" + enc(pass) + "@" + host
                                    + (host.contains(":") ? "" : ":554") + "/live0"));
                }));
    }

    private void saveCamera(Home.Camera existing, String name, String url) {
        if (existing != null) {
            existing.name = name;
            existing.url = url;
        } else {
            home().cameras.add(new Home.Camera(Home.newId(), name, url));
        }
        save();
        p.say(name + " saved. Open it to check the picture.");
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    static final java.util.Map<String, String> SECTION_NAMES = new java.util.HashMap<String, String>() {{
        put("remotes", "Remotes (phone page)");
        put("scenes", "Scenes");
        put("cameras", "Cameras");
        put("plugs", "Plugs & lights");
        put("automations", "Automations");
    }};

    /** Settings → Section order: pick a section, move it up or down (shared with the phone page). */
    void sectionOrder() {
        List<String> names = new ArrayList<>();
        for (String k : home().sections) names.add(SECTION_NAMES.get(k));
        p.list("Section order (pick one to move)", names, i -> {
            String key = home().sections.get(i);
            p.list(SECTION_NAMES.get(key), Arrays.asList("Move up", "Move down"), m -> {
                move("section", key, m == 0 ? -1 : 1);
                sectionOrder();
            });
        });
    }

    /** Tiles show in the stored order, on the TV and on the phone page; this moves one a place. */
    void move(String kind, String id, int by) {
        if (home().move(kind, id, by)) save();
        else p.say(by < 0 ? "Already first" : "Already last");
    }

    void cameraMenu(Home.Camera c) {
        List<String> items = new ArrayList<>(Arrays.asList("Room: " + roomName(c.roomId),
                c.favorite ? "Remove from Favorites" : "Add to Favorites", a.getString(R.string.rename),
                "Change login or address", "Move earlier", "Move later", a.getString(R.string.remove)));
        p.list(c.name, items, i -> {
            switch (i) {
                case 0: pickRoom(c.roomId, id -> { c.roomId = id; save(); }); break;
                case 1: c.favorite = !c.favorite; save(); break;
                case 2: p.askText(a.getString(R.string.rename), c.name, "", n -> { c.name = n; save(); }); break;
                case 3: p.askText("Camera address", Commands.hostOf(c.url).replaceAll(":554$", ""), "", h -> cameraDetails(h.trim(), c)); break;
                case 4: case 5: move("camera", c.id, i == 4 ? -1 : 1); break;
                default: home().cameras.remove(c); save();
            }
        });
    }

    // ---- rooms -----------------------------------------------------------------------------------

    String roomName(String id) {
        Home.Room r = home().room(id);
        return r != null ? r.name : "No room";
    }

    void newRoom(Consumer<Home.Room> done) {
        p.askText("New room", "", "Living room", name -> {
            Home.Room r = home().roomNamed(name);
            if (r == null) {
                r = new Home.Room(Home.newId(), name);
                home().rooms.add(r);
                save();
            }
            done.accept(r);
        });
    }

    void pickRoom(String current, Consumer<String> done) {
        List<Home.Room> rooms = new ArrayList<>(home().rooms);
        List<String> names = new ArrayList<>();
        for (Home.Room r : rooms) names.add((r.id.equals(current) ? "✓ " : "") + r.name);
        names.add((current.isEmpty() ? "✓ " : "") + "No room");
        names.add("New room…");
        p.list("Room", names, i -> {
            if (i < rooms.size()) done.accept(rooms.get(i).id);
            else if (i == rooms.size()) done.accept("");
            else newRoom(r -> done.accept(r.id));
        });
    }

    void roomsMenu() {
        List<Home.Room> rooms = new ArrayList<>(home().rooms);
        List<String> names = new ArrayList<>();
        for (Home.Room r : rooms) names.add(r.name);
        names.add("New room…");
        p.list("Rooms", names, i -> {
            if (i == rooms.size()) {
                newRoom(r -> roomsMenu());
                return;
            }
            Home.Room r = rooms.get(i);
            p.list(r.name, Arrays.asList(a.getString(R.string.rename), a.getString(R.string.remove)), k -> {
                if (k == 0) p.askText(a.getString(R.string.rename), r.name, "", n -> { r.name = n; save(); });
                else { home().removeRoom(r.id); save(); p.say(r.name + " removed; its devices moved to No room"); }
            });
        });
    }

    // ---- scenes ----------------------------------------------------------------------------------

    private List<Home.Device> switches() {
        List<Home.Device> out = new ArrayList<>();
        for (Home.Device d : home().devices) if (d.hasSwitch) out.add(d);
        return out;
    }

    private static List<String> names(List<Home.Device> ds) {
        List<String> out = new ArrayList<>();
        for (Home.Device d : ds) out.add(d.name);
        return out;
    }

    /** New scene (null) or change one: name, what turns on, what turns off. */
    void editScene(Home.Scene existing) {
        List<Home.Device> sw = switches();
        if (sw.isEmpty()) {
            p.say(a.getString(R.string.scene_empty));
            return;
        }
        boolean[] on = new boolean[sw.size()], off = new boolean[sw.size()];
        if (existing != null) {
            for (Home.Action x : existing.actions) {
                for (int i = 0; i < sw.size(); i++) if (sw.get(i).id.equals(x.deviceId)) { if (x.on) on[i] = true; else off[i] = true; }
            }
        }
        p.askText(a.getString(R.string.scene_name), existing != null ? existing.name : "", "Movie night", name ->
                p.checks(a.getString(R.string.scene_turn_on), names(sw), on, onPicks ->
                        p.checks(a.getString(R.string.scene_turn_off), names(sw), off, offPicks -> {
                            List<Home.Action> acts = new ArrayList<>();
                            for (int i = 0; i < sw.size(); i++) {
                                if (onPicks[i]) acts.add(new Home.Action(sw.get(i).id, true));
                                else if (offPicks[i]) acts.add(new Home.Action(sw.get(i).id, false));
                            }
                            if (acts.isEmpty()) {
                                p.say(a.getString(R.string.scene_empty));
                                return;
                            }
                            Home.Scene s = existing != null ? existing : new Home.Scene(Home.newId(), name);
                            s.name = name;
                            s.actions.clear();
                            s.actions.addAll(acts);
                            if (existing == null) home().scenes.add(s);
                            save();
                        })));
    }

    void sceneMenu(Home.Scene s) {
        p.list(s.name, Arrays.asList("Edit", s.favorite ? "Remove from Favorites" : "Add to Favorites", "Move earlier", "Move later",
                a.getString(R.string.remove)), i -> {
            if (i == 0) editScene(s);
            else if (i == 1) { s.favorite = !s.favorite; save(); }
            else if (i == 2 || i == 3) move("scene", s.id, i == 2 ? -1 : 1);
            else { home().removeScene(s.id); save(); }
        });
    }

    // ---- automations -----------------------------------------------------------------------------

    static final String[] TRIGGERS = {"At a time of day", "At sunrise", "At sunset", "When the TV turns on", "When the TV turns off"};
    static final String[] TRIGGER_KEYS = {Home.Schedule.AT, Home.Schedule.SUNRISE, Home.Schedule.SUNSET, Home.Schedule.TV_ON, Home.Schedule.TV_OFF};
    static final String[] OFFSETS = {"1 h before", "30 min before", "15 min before", "Exactly then", "15 min after", "30 min after", "1 h after"};
    static final int[] OFFSET_MIN = {-60, -30, -15, 0, 15, 30, 60};
    static final String[] WINDOWS = {"Any time", "In the evening (18:00–23:00)", "At night (22:00–06:00)", "In the daytime (06:00–18:00)"};
    static final int[][] WINDOW_MIN = {{-1, -1}, {18 * 60, 23 * 60}, {22 * 60, 6 * 60}, {6 * 60, 18 * 60}};
    static final String[] DAYS = {"Every day", "Weekdays", "Weekends", "Choose days…"};
    static final String[] WEEK = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
    static final String[] DOES = {"Run a scene", "Turn devices on", "Turn devices off", "Turn some on and some off"};
    static final String[] AUTO_OFF = {"Leave them on", "Turn off after 5 min", "Turn off after 15 min", "Turn off after 30 min",
            "Turn off after 1 h", "Turn off after 2 h"};
    static final int[] AUTO_OFF_MIN = {0, 5, 15, 30, 60, 120};

    /** New automation (null) or change one, step by step. Replaces the old one only when finished. */
    void editAutomation(Home.Schedule existing) {
        if (switches().isEmpty()) {
            p.say("Add a plug first: + → Plug or sensor");
            return;
        }
        Home.Schedule s = new Home.Schedule(existing != null ? existing.id : Home.newId(), "");
        p.askText("Automation name", existing != null ? existing.name : "", "Lamp off at night", name -> {
            s.name = name;
            p.list("When", Arrays.asList(TRIGGERS), t -> {
                s.when = TRIGGER_KEYS[t];
                Runnable next = () -> chooseDays(s, () -> chooseActions(s, () -> finish(s, existing)));
                if (t == 0) {
                    pickTime("Time", m -> { s.minutes = m; next.run(); });
                } else if (t <= 2) {
                    Runnable sun = () -> p.list(TRIGGERS[t], Arrays.asList(OFFSETS), o -> { s.minutes = OFFSET_MIN[o]; next.run(); });
                    if (Double.isNaN(home().latitude)) askLocation(sun);
                    else sun.run();
                } else {
                    p.list("Only", Arrays.asList(WINDOWS), w -> {
                        s.windowFrom = WINDOW_MIN[w][0];
                        s.windowTo = WINDOW_MIN[w][1];
                        next.run();
                    });
                }
            });
        });
    }

    /** Hours, then minutes in fives: quick with a remote. */
    void pickTime(String title, Consumer<Integer> done) {
        List<String> hours = new ArrayList<>();
        for (int h = 0; h < 24; h++) hours.add(String.format(java.util.Locale.US, "%02d:00", h));
        p.list(title + ": hour", hours, h -> {
            List<String> mins = new ArrayList<>();
            for (int m = 0; m < 60; m += 5) mins.add(String.format(java.util.Locale.US, "%02d:%02d", h, m));
            p.list(title + ": minute", mins, m -> done.accept(h * 60 + m * 5));
        });
    }

    private void chooseDays(Home.Schedule s, Runnable next) {
        p.list("Days", Arrays.asList(DAYS), d -> {
            if (d == 0) s.days = Home.Schedule.EVERY_DAY;
            else if (d == 1) s.days = 0x1F;
            else if (d == 2) s.days = 0x60;
            if (d < 3) {
                next.run();
                return;
            }
            p.checks("Days", Arrays.asList(WEEK), new boolean[7], ticks -> {
                int mask = 0;
                for (int i = 0; i < 7; i++) if (ticks[i]) mask |= 1 << i;
                if (mask == 0) {
                    p.say("Pick at least one day");
                    return;
                }
                s.days = mask;
                next.run();
            });
        });
    }

    private void chooseActions(Home.Schedule s, Runnable next) {
        List<Home.Device> sw = switches();
        p.list("Then", Arrays.asList(DOES), k -> {
            if (k == 0) {
                List<Home.Scene> scenes = new ArrayList<>(home().scenes);
                if (scenes.isEmpty()) {
                    p.say("Make a scene first: + → Scene");
                    return;
                }
                List<String> sn = new ArrayList<>();
                for (Home.Scene x : scenes) sn.add(x.name);
                p.list("Scene", sn, i -> { s.sceneId = scenes.get(i).id; autoOff(s, next); });
                return;
            }
            boolean[] on = new boolean[sw.size()], off = new boolean[sw.size()];
            Consumer<boolean[]> offStep = offs -> {
                for (int i = 0; i < sw.size(); i++) {
                    if (on[i]) s.actions.add(new Home.Action(sw.get(i).id, true));
                    else if (offs[i]) s.actions.add(new Home.Action(sw.get(i).id, false));
                }
                if (s.actions.isEmpty()) {
                    p.say("Pick at least one device");
                    return;
                }
                autoOff(s, next);
            };
            if (k == 1) p.checks("Turn on", names(sw), on, ons -> offStep.accept(new boolean[sw.size()]));
            else if (k == 2) p.checks("Turn off", names(sw), off, offStep);
            else p.checks("Turn on", names(sw), on, ons -> p.checks("Turn off", names(sw), off, offStep));
        });
    }

    private void autoOff(Home.Schedule s, Runnable next) {
        boolean turnsOn = false;
        for (Home.Action x : Scheduler.actionsOf(home(), s)) turnsOn |= x.on;
        if (!turnsOn) {
            next.run();
            return;
        }
        p.list("And then", Arrays.asList(AUTO_OFF), i -> { s.autoOffMinutes = AUTO_OFF_MIN[i]; next.run(); });
    }

    private void finish(Home.Schedule s, Home.Schedule existing) {
        Home h = home();
        if (existing != null) {
            s.enabled = existing.enabled;
            int at = h.schedules.indexOf(existing);
            h.schedules.set(at < 0 ? h.schedules.size() : at, s);
        } else {
            h.schedules.add(s);
        }
        save();
        p.say(s.name + ": " + Schedules.describe(s));
    }

    void automationMenu(Home.Schedule s) {
        p.list(s.name, Arrays.asList("Edit", s.enabled ? "Pause" : "Resume", a.getString(R.string.rename), "Move earlier", "Move later",
                a.getString(R.string.remove)), i -> {
            if (i == 0) editAutomation(s);
            else if (i == 1) { s.enabled = !s.enabled; save(); }
            else if (i == 2) p.askText(a.getString(R.string.rename), s.name, "", n -> { s.name = n; save(); });
            else if (i == 3 || i == 4) move("automation", s.id, i == 3 ? -1 : 1);
            else { home().schedules.remove(s); save(); }
        });
    }

    // ---- settings and activity --------------------------------------------------------------------

    void settings() {
        Home h = home();
        String city = Double.isNaN(h.latitude) ? "" : Places.nearest(h.latitude, h.longitude);
        String loc = Double.isNaN(h.latitude) ? "not set" : !city.isEmpty() ? city
                : String.format(java.util.Locale.US, "%.2f, %.2f", h.latitude, h.longitude);
        int mins = Idle.minutes(a);
        String saver = mins == 0 ? "off" : "clock after " + mins + " min";
        String unit = Idle.fahrenheit(a) ? "°F" : "°C";
        p.list("Settings", Arrays.asList("Location (for sunrise, sunset and weather): " + loc, "Screensaver: " + saver,
                "Temperature: " + unit, "Phone remote: " + (Remote.isOn(a) ? "on" : "off"),
                "Phone remote when the TV is off: " + (Remote.keepAwake(a) ? "available" : "off"), "Rooms",
                "Section order", "About"), i -> {
            if (i == 0) askLocation(() -> { });
            else if (i == 1) screensaverMenu();
            else if (i == 2) p.list("Temperature", Arrays.asList("Fahrenheit (°F)", "Celsius (°C)"), u -> {
                Idle.setFahrenheit(a, u == 0);
                p.say("Temperature: " + (u == 0 ? "°F" : "°C"));
            });
            else if (i == 3) phoneRemote();
            else if (i == 4) p.list("Phone remote when the TV is off", java.util.Arrays.asList(
                    "Available (keeps Wi-Fi on; uses a few watts while off)", "Off (the TV sleeps fully)"), w -> {
                Remote.setKeepAwake(a, w == 0);
                p.say(w == 0 ? "Phone remote stays available" : "The TV sleeps fully");
            });
            else if (i == 5) roomsMenu();
            else if (i == 6) sectionOrder();
            else p.tell("Smart Home", h.devices.size() + " devices · " + h.cameras.size() + " cameras · " + h.scenes.size() + " scenes · "
                    + h.schedules.size() + " automations · " + h.rooms.size() + " rooms\n\nEverything runs on this TV and your Wi-Fi; nothing goes to a cloud. "
                    + "The screensaver's weather comes from Open-Meteo, which is sent only your city's location.");
        });
    }

    /** The phone remote: on, its link as a QR code, a new link, or off. */
    private void phoneRemote() {
        if (!Remote.isOn(a)) Remote.setOn(a, true);
        p.qr("Phone remote", Remote.url(a), "On your phone (same Wi-Fi), scan this code, or open\n"
                + Remote.shortUrl() + "\nand enter the code " + Remote.code(a) + ".\n\nThen Share → Add to Home Screen to keep it.",
                Arrays.asList("Change code", "New link (old ones stop working)", "Turn off"), i -> {
                    if (i == 0) {
                        p.askText("Change code", Remote.code(a), "4 digits", n -> {
                            if (!Remote.setCode(a, n)) p.say("The code is 4 digits");
                            phoneRemote();
                        });
                    } else if (i == 1) {
                        Remote.newToken(a);
                        phoneRemote();
                    } else {
                        Remote.setOn(a, false);
                        p.say("Phone remote off");
                    }
                });
    }

    private void screensaverMenu() {
        int[] choices = {0, 2, 5, 10, 20, 30};
        List<String> items = new ArrayList<>();
        items.add("Show it now");
        for (int m : choices) items.add(m == 0 ? "Off" : "Clock after " + m + " minutes with nothing playing");
        p.list("Screensaver", items, i -> {
            if (i == 0) {
                a.startActivity(new android.content.Intent(a, ClockActivity.class));
                return;
            }
            int m = choices[i - 1];
            if (!Idle.set(a, m)) {
                p.tell("Screensaver", "The clean launcher opens the clock, and it isn't installed on this TV.");
                return;
            }
            p.say(m == 0 ? "Screensaver off" : "Clock after " + m + " minutes");
            if (m > 0 && Double.isNaN(home().latitude)) askLocation(() -> { });
        });
    }

    /**
     * Location for sunrise and sunset, picked from lists: cities in the TV's own time zone first, then any
     * time zone, then typed coordinates for anyone who wants them.
     */
    void askLocation(Runnable then) {
        Places.Zone mine = Places.forTv(java.time.ZoneId.systemDefault());
        if (mine == null) {
            pickZone(then);
            return;
        }
        pickCity(mine, then, true);
    }

    private void pickCity(Places.Zone z, Runnable then, boolean offerOthers) {
        List<String> names = new ArrayList<>();
        for (Places.City c : z.cities) names.add(c.name);
        if (offerOthers) names.add("Other time zone…");
        names.add("Enter coordinates…");
        p.list("Nearest city · " + z.label, names, i -> {
            if (i < z.cities.size()) {
                Places.City c = z.cities.get(i);
                setLocation(c.lat, c.lon);
                p.say("Location: " + c.name);
                then.run();
            } else if (offerOthers && i == z.cities.size()) {
                pickZone(then);
            } else {
                askCoordinates(then);
            }
        });
    }

    private void pickZone(Runnable then) {
        List<Places.Zone> zones = new ArrayList<>(Places.ZONES.values());
        List<String> labels = new ArrayList<>();
        for (Places.Zone z : zones) labels.add(z.label);
        labels.add("Enter coordinates…");
        p.list("Time zone", labels, i -> {
            if (i < zones.size()) pickCity(zones.get(i), then, false);
            else askCoordinates(then);
        });
    }

    private void setLocation(double lat, double lon) {
        Home h = home();
        h.latitude = lat;
        h.longitude = lon;
        save();
    }

    private void askCoordinates(Runnable then) {
        Home h = home();
        String now = Double.isNaN(h.latitude) ? "" : h.latitude + ", " + h.longitude;
        p.askText("Latitude, longitude", now, "37.77, -122.42", text -> {
            String[] parts = text.split("[,\\s]+");
            try {
                double lat = Double.parseDouble(parts[0]), lon = Double.parseDouble(parts[1]);
                if (Math.abs(lat) > 90 || Math.abs(lon) > 180) throw new NumberFormatException();
                setLocation(lat, lon);
                then.run();
            } catch (RuntimeException e) {
                p.say("Two numbers, like 37.77, -122.42 (a maps app shows them for your town)");
            }
        });
    }

    void activity() {
        List<String> lines = ActivityLog.recent(a, 50);
        Collections.reverse(lines);
        if (lines.isEmpty()) lines.add("Nothing yet");
        p.list("Activity", lines, i -> { });
    }
}
