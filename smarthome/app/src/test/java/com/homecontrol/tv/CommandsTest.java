package com.homecontrol.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** home.sh's commands, as ControlReceiver applies them; and camera discovery against a fake camera. */
public class CommandsTest {

    private Home h;

    private static Commands.Args args(String... kv) {
        Map<String, String> s = new HashMap<>();
        Map<String, String[]> l = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i].startsWith("[]")) l.put(kv[i].substring(2), kv[i + 1].split(","));
            else s.put(kv[i], kv[i + 1]);
        }
        return new Commands.Args() {
            @Override public String str(String key) {
                return s.get(key);
            }
            @Override public String[] list(String key) {
                return l.get(key);
            }
        };
    }

    private String run(String action, String... kv) {
        return Commands.apply(h, Commands.P + action, args(kv));
    }

    @Before
    public void home() {
        h = new Home();
        Home.Device p1 = new Home.Device("p1", "Plug 1", "192.168.1.198", 0, true, false);
        p1.brand = Home.KASA;
        Home.Device p2 = new Home.Device("p2", "Plug 2", "192.168.1.197", 0, true, false);
        p2.brand = Home.KASA;
        h.devices.addAll(Arrays.asList(p1, p2));
        h.devices.add(new Home.Device("t", "Bedroom", "10.0.0.9", 3, false, true));
    }

    @Test
    public void scheduleAtTimeByName() {
        assertNull(run("ADD_SCHEDULE", "name", "Night", "at", "23:30", "days", "weekdays", "[]off", "plug 2"));
        Home.Schedule s = h.schedules.get(0);
        assertEquals(23 * 60 + 30, s.minutes);
        assertEquals(0x1F, s.days);
        assertEquals("p2", s.actions.get(0).deviceId);
        assertFalse(s.actions.get(0).on);
    }

    @Test
    public void scheduleAcceptsSingleDigitHour() {
        assertNull(run("ADD_SCHEDULE", "name", "Wake", "at", "7:05", "[]on", "Plug 1"));
        assertEquals(7 * 60 + 5, h.schedules.get(0).minutes);
    }

    @Test
    public void scheduleErrorsSayWhatsWrong() {
        assertEquals("time is HH:MM (24-hour), sunrise, sunset, tv-on or tv-off", run("ADD_SCHEDULE", "name", "x", "at", "25:00", "[]on", "Plug 1"));
        assertEquals("no device called Lamp", run("ADD_SCHEDULE", "name", "x", "at", "10:00", "[]on", "Lamp"));
        assertEquals("Bedroom has no switch", run("ADD_SCHEDULE", "name", "x", "at", "10:00", "[]on", "Bedroom"));
        assertTrue(run("ADD_SCHEDULE", "name", "x", "at", "10:00").startsWith("say what it does"));
        assertTrue(run("ADD_SCHEDULE", "name", "x", "at", "sunset", "[]on", "Plug 1").contains("location"));
        assertTrue(run("ADD_SCHEDULE", "name", "x", "at", "10:00", "days", "someday", "[]on", "Plug 1").startsWith("days is"));
        assertTrue(h.schedules.isEmpty());
    }

    @Test
    public void sunsetScheduleAfterLocation() {
        assertNull(run("SET_LOCATION", "lat", "37.7749", "lon", "-122.4194"));
        assertNull(run("ADD_SCHEDULE", "name", "Evening", "at", "SUNSET", "offset", "-30", "[]on", "Plug 1"));
        Home.Schedule s = h.schedules.get(0);
        assertEquals(Home.Schedule.SUNSET, s.when);
        assertEquals(-30, s.minutes);
        assertTrue(run("ADD_SCHEDULE", "name", "x", "at", "sunrise", "offset", "600", "[]on", "Plug 1").contains("4 hours"));
    }

    @Test
    public void locationIsChecked() {
        assertTrue(run("SET_LOCATION", "lat", "137", "lon", "0").startsWith("latitude"));
        assertTrue(run("SET_LOCATION", "lat", "north", "lon", "0").startsWith("location is two numbers"));
        assertTrue(Double.isNaN(h.latitude));
    }

    @Test
    public void scheduleRunningAScene() {
        assertNull(run("ADD_SCENE", "name", "Movie night", "[]on", "Plug 1", "[]off", "Plug 2"));
        assertNull(run("ADD_SCHEDULE", "name", "Movies at 8", "at", "20:00", "scene", "movie night"));
        assertEquals(h.scenes.get(0).id, h.schedules.get(0).sceneId);
        assertEquals("no scene called Party", run("ADD_SCHEDULE", "name", "x", "at", "20:00", "scene", "Party"));
    }

    @Test
    public void enableDisableRenameRemove() {
        run("ADD_SCHEDULE", "name", "Night", "at", "23:30", "[]off", "Plug 2");
        assertNull(run("SET_ENABLED", "name", "night", "enabled", "false"));
        assertFalse(h.schedules.get(0).enabled);
        assertNull(run("SET_ENABLED", "name", "Night", "enabled", "true"));
        assertTrue(h.schedules.get(0).enabled);
        assertNull(run("RENAME", "name", "Plug 2", "to", "Desk lamp"));
        assertEquals("Desk lamp", h.device("p2").name);
        assertNull(run("REMOVE", "name", "Night"));
        assertTrue(h.schedules.isEmpty());
        assertNull(run("REMOVE", "id", "p1"));   // by id still works
        assertEquals("nothing called Ghost", run("REMOVE", "name", "Ghost"));
    }

    @Test
    public void cameraAddedThenUpdatedByName() {
        assertNull(run("ADD_CAMERA", "name", "Living room", "url", "rtsp://192.168.1.249:554/live0"));
        assertNull(run("ADD_CAMERA", "name", "living room", "url", "rtsp://viewer:p%40ss@192.168.1.249:554/live0"));
        assertEquals(1, h.cameras.size());
        assertEquals("rtsp://viewer:p%40ss@192.168.1.249:554/live0", h.cameras.get(0).url);
        assertTrue(run("ADD_CAMERA", "name", "x", "url", "http://192.168.1.249").contains("rtsp://"));
    }

    @Test
    public void reportHidesCameraPasswordsAndDescribesSchedules() throws Exception {
        run("ADD_CAMERA", "name", "Living room", "url", "rtsp://viewer:s3cret@192.168.1.249:554/live0");
        run("ADD_SCHEDULE", "name", "Night", "at", "23:30", "[]off", "Plug 2");
        String report = Commands.report(h, ZoneId.of("America/Los_Angeles"));
        assertFalse(report.contains("s3cret"));
        assertFalse(report.contains("viewer"));
        JSONObject o = new JSONObject(report);
        assertEquals("192.168.1.249:554", o.getJSONArray("cameras").getJSONObject(0).getString("host"));
        JSONObject s = o.getJSONArray("schedules").getJSONObject(0);
        assertEquals("Every day · 23:30", s.getString("text"));
        assertTrue(s.getString("next").contains("T23:30"));
    }

    @Test
    public void roomsFavoritesAndMove() {
        assertNull(run("SET_ROOM", "name", "plug 1", "room", "Living room"));
        Home.Room r = h.roomNamed("living room");
        assertEquals(r.id, h.device("p1").roomId);
        assertEquals(1, h.rooms.size());
        assertNull(run("SET_ROOM", "name", "Plug 2", "room", "Living room"));   // same room, not a second one
        assertEquals(1, h.rooms.size());
        assertNull(run("SET_FAVORITE", "name", "Plug 1", "favorite", "true"));
        assertTrue(h.device("p1").favorite);
        assertNull(run("RENAME", "name", "Living room", "to", "Lounge"));
        assertEquals("Lounge", r.name);
        assertNull(run("REMOVE", "name", "Lounge"));
        assertTrue(h.rooms.isEmpty());
        assertEquals("", h.device("p1").roomId);   // its devices move to no room
        assertNull(run("SET_HOST", "name", "plug 1", "host", "192.168.1.205"));
        assertEquals("192.168.1.205", h.device("p1").host);
        assertEquals("no device called Lamp", run("SET_HOST", "name", "Lamp", "host", "1.2.3.4"));
        Home back = Home.fromJson(h.toJson());
        assertTrue(back.device("p1").favorite);
    }

    @Test
    public void tvTriggerWithWindowAndAutoOff() {
        assertNull(run("ADD_SCHEDULE", "name", "Lamp with TV", "at", "tv-on", "window", "18:00-23:00", "autooff", "120", "[]on", "Plug 1"));
        Home.Schedule s = h.schedules.get(0);
        assertEquals(Home.Schedule.TV_ON, s.when);
        assertEquals(18 * 60, s.windowFrom);
        assertEquals(23 * 60, s.windowTo);
        assertEquals(120, s.autoOffMinutes);
        assertTrue(run("ADD_SCHEDULE", "name", "x", "at", "tv-off", "window", "late", "[]off", "Plug 1").startsWith("window is"));
        assertTrue(run("ADD_SCHEDULE", "name", "x", "at", "10:00", "autooff", "soon", "[]on", "Plug 1").startsWith("turn off after"));
    }

    @Test
    public void resetClearsEverything() {
        run("SET_LOCATION", "lat", "1", "lon", "2");
        run("ADD_SCHEDULE", "name", "Night", "at", "23:30", "[]off", "Plug 2");
        assertNull(run("RESET"));
        assertTrue(h.devices.isEmpty() && h.schedules.isEmpty() && Double.isNaN(h.latitude));
    }

    @Test
    public void hostOfNeverIncludesTheUser() {
        assertEquals("192.168.1.249:554", Commands.hostOf("rtsp://u:p@192.168.1.249:554/live0"));
        assertEquals("192.168.1.249", Commands.hostOf("rtsp://192.168.1.249/live0"));
        assertEquals("cam.local:8554", Commands.hostOf("RTSP://a@b:c@cam.local:8554/x"));
    }

    // ---- camera discovery ----

    private static ServerSocket fakeCamera(String firstLine) throws IOException {
        ServerSocket s = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!s.isClosed()) {
                try (Socket c = s.accept()) {
                    c.getInputStream().read(new byte[512]);
                    OutputStream o = c.getOutputStream();
                    o.write((firstLine + "\r\nCSeq: 1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    o.flush();
                } catch (IOException ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return s;
    }

    @Test
    public void cameraScanFindsRtspAndIgnoresOtherServers() throws IOException {
        try (ServerSocket cam = fakeCamera("RTSP/1.0 200 OK"); ServerSocket web = fakeCamera("HTTP/1.1 400 Bad Request")) {
            String camHost = "127.0.0.1:" + cam.getLocalPort(), webHost = "127.0.0.1:" + web.getLocalPort();
            List<String> found = CameraScan.scan(Arrays.asList(webHost, camHost, "127.0.0.1:1"), 400);
            assertEquals(Arrays.asList(camHost), found);
        }
    }

    @Test
    public void cameraScanOfAWholeNetworkFinishesQuickly() {
        long t0 = System.currentTimeMillis();
        // 254 addresses that won't answer (TEST-NET-1, reserved for documentation, never routed).
        java.util.List<String> hosts = new java.util.ArrayList<>();
        for (int i = 1; i < 255; i++) hosts.add("192.0.2." + i);
        assertTrue(CameraScan.scan(hosts, 300).isEmpty());
        long took = System.currentTimeMillis() - t0;
        assertTrue("took " + took + " ms", took < 8000);
    }

    @Test
    public void defaultUrlIsEufysPath() {
        assertEquals("rtsp://192.168.1.249:554/live0", CameraScan.defaultUrl("192.168.1.249"));
    }

    /** Read-only, against the real network: KASA_LIVE=1. Lists RTSP cameras on this Mac's network. */
    @Test
    public void liveCameraScan() {
        if (System.getenv("KASA_LIVE") == null) return;
        List<String> n = CameraScan.neighbours();
        System.out.println("LIVE camera scan of " + n.size() + " addresses: " + CameraScan.scan(n, 400));
    }
}
