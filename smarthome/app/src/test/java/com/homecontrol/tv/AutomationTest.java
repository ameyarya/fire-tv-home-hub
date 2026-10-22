package com.homecontrol.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The Apple Home-style additions: TV triggers, time windows, "turn off after", rooms, favorites, energy, activity. */
public class AutomationTest {

    static final ZoneId LA = ZoneId.of("America/Los_Angeles");

    private static ZonedDateTime la(String iso) {
        return LocalDateTime.parse(iso).atZone(LA);
    }

    private static Home.Schedule tv(String when, int from, int to) {
        Home.Schedule s = new Home.Schedule("s", "Lamp with TV");
        s.when = when;
        s.windowFrom = from;
        s.windowTo = to;
        s.actions.add(new Home.Action("d", true));
        return s;
    }

    @Test
    public void tvTriggerMatchesOnlyItsOwnEvent() {
        Home.Schedule s = tv(Home.Schedule.TV_ON, -1, -1);
        assertTrue(Schedules.matchesTvEvent(s, la("2026-10-20T20:00"), true));
        assertFalse(Schedules.matchesTvEvent(s, la("2026-10-20T20:00"), false));
        s.enabled = false;
        assertFalse(Schedules.matchesTvEvent(s, la("2026-10-20T20:00"), true));
    }

    @Test
    public void tvTriggerRespectsWindowAcrossMidnight() {
        Home.Schedule s = tv(Home.Schedule.TV_OFF, 22 * 60, 6 * 60);
        assertTrue(Schedules.matchesTvEvent(s, la("2026-10-20T23:30"), false));
        assertTrue(Schedules.matchesTvEvent(s, la("2026-10-21T02:00"), false));
        assertFalse(Schedules.matchesTvEvent(s, la("2026-10-20T06:00"), false));   // end is exclusive
        assertFalse(Schedules.matchesTvEvent(s, la("2026-10-20T14:00"), false));
    }

    @Test
    public void tvTriggerRespectsDays() {
        Home.Schedule s = tv(Home.Schedule.TV_ON, -1, -1);
        s.days = 0x60;   // weekends; 2026-10-20 is a Tuesday, 2026-10-24 a Saturday
        assertFalse(Schedules.matchesTvEvent(s, la("2026-10-20T20:00"), true));
        assertTrue(Schedules.matchesTvEvent(s, la("2026-10-24T20:00"), true));
    }

    @Test
    public void tvTriggersHaveNoTimedRun() {
        Home h = new Home();
        h.schedules.add(tv(Home.Schedule.TV_ON, -1, -1));
        assertNull(Schedules.next(h.schedules.get(0), h, la("2026-10-20T20:00")));
        assertNull(Schedules.soonest(h, la("2026-10-20T20:00")));
    }

    @Test
    public void autoOffIsScheduledOnlyForWhatTurnedOn() {
        Home h = new Home();
        Home.Schedule s = tv(Home.Schedule.TV_ON, -1, -1);
        s.autoOffMinutes = 30;
        long t0 = la("2026-10-20T20:00").toInstant().toEpochMilli();
        Schedules.scheduleOffs(h, s, Arrays.asList(new Home.Action("d", true), new Home.Action("e", false)), t0);
        assertEquals(1, h.pendingOffs.size());
        assertEquals("d", h.pendingOffs.get(0).deviceId);
        assertEquals(t0 + 30 * 60_000, h.pendingOffs.get(0).atMillis);
    }

    @Test
    public void autoOffExtendsButNeverShortens() {
        Home h = new Home();
        Home.Schedule s = tv(Home.Schedule.TV_ON, -1, -1);
        s.autoOffMinutes = 30;
        long t0 = 1_000_000;
        Schedules.scheduleOffs(h, s, Collections.singletonList(new Home.Action("d", true)), t0);
        Schedules.scheduleOffs(h, s, Collections.singletonList(new Home.Action("d", true)), t0 + 10 * 60_000);
        assertEquals(1, h.pendingOffs.size());
        assertEquals(t0 + 40 * 60_000, h.pendingOffs.get(0).atMillis);   // pushed out
        s.autoOffMinutes = 5;
        Schedules.scheduleOffs(h, s, Collections.singletonList(new Home.Action("d", true)), t0 + 11 * 60_000);
        assertEquals(t0 + 40 * 60_000, h.pendingOffs.get(0).atMillis);   // not cut short
    }

    @Test
    public void noAutoOffWhenNotAsked() {
        Home h = new Home();
        Schedules.scheduleOffs(h, tv(Home.Schedule.TV_ON, -1, -1), Collections.singletonList(new Home.Action("d", true)), 0);
        assertTrue(h.pendingOffs.isEmpty());
    }

    @Test
    public void pendingOffsArmTheAlarmAndFallDue() {
        Home h = new Home();
        h.devices.add(new Home.Device("d", "Lamp", "10.0.0.2", 0, true, false));
        ZonedDateTime now = la("2026-10-20T20:00");
        long at = now.plusMinutes(30).toInstant().toEpochMilli();
        h.pendingOffs.add(new Home.PendingOff("d", at));
        assertEquals(now.plusMinutes(30), Schedules.soonest(h, now));
        assertTrue(Schedules.dueOffs(h, at - 1).isEmpty());
        assertEquals(1, Schedules.dueOffs(h, at).size());
        // Survives a restart of the app.
        assertEquals(at, Home.fromJson(h.toJson()).pendingOffs.get(0).atMillis);
    }

    @Test
    public void overduePendingOffArmsForNow() {
        Home h = new Home();
        h.devices.add(new Home.Device("d", "Lamp", "10.0.0.2", 0, true, false));
        ZonedDateTime now = la("2026-10-20T20:00");
        h.pendingOffs.add(new Home.PendingOff("d", now.minusHours(1).toInstant().toEpochMilli()));
        ZonedDateTime t = Schedules.soonest(h, now);
        assertTrue(t.isAfter(now) && t.isBefore(now.plusSeconds(5)));
    }

    @Test
    public void removingADeviceDropsItsPendingOff() {
        Home h = new Home();
        h.devices.add(new Home.Device("d", "Lamp", "10.0.0.2", 0, true, false));
        h.pendingOffs.add(new Home.PendingOff("d", 5));
        h.removeDevice("d");
        assertTrue(h.pendingOffs.isEmpty());
    }

    @Test
    public void describeTvTriggersAndAutoOff() {
        Home.Schedule s = tv(Home.Schedule.TV_OFF, 22 * 60, 6 * 60);
        s.autoOffMinutes = 30;
        assertEquals("When TV turns off · 22:00–06:00 · off after 30 min", Schedules.describe(s));
        Home.Schedule on = tv(Home.Schedule.TV_ON, -1, -1);
        on.autoOffMinutes = 120;
        assertEquals("When TV turns on · off after 2 h", Schedules.describe(on));
    }

    @Test
    public void windowParsing() {
        assertEquals(Arrays.toString(new int[]{1320, 360}), Arrays.toString(Commands.parseWindow("22:00-06:00")));
        assertEquals(Arrays.toString(new int[]{540, 1020}), Arrays.toString(Commands.parseWindow("9:00 – 17:00")));
        assertNull(Commands.parseWindow("10:00-10:00"));
        assertNull(Commands.parseWindow("late"));
    }

    @Test
    public void roomsAndFavoritesSurviveSaveAndLoad() {
        Home h = new Home();
        h.rooms.add(new Home.Room("r", "Living room"));
        Home.Device d = new Home.Device("d", "Lamp", "10.0.0.2", 0, true, false);
        d.roomId = "r";
        d.favorite = true;
        h.devices.add(d);
        Home.Camera c = new Home.Camera("c", "Door", "rtsp://x/live0");
        c.roomId = "r";
        h.cameras.add(c);
        Home.Scene sc = new Home.Scene("s", "Night");
        sc.favorite = true;
        sc.actions.add(new Home.Action("d", false));
        h.scenes.add(sc);
        Home b = Home.fromJson(h.toJson());
        assertEquals("Living room", b.room(b.device("d").roomId).name);
        assertTrue(b.device("d").favorite);
        assertEquals("r", b.camera("c").roomId);
        assertTrue(b.scene("s").favorite);
    }

    @Test
    public void removingARoomMovesItsDevicesOut() {
        Home h = new Home();
        h.rooms.add(new Home.Room("r", "Bedroom"));
        Home.Device d = new Home.Device("d", "Lamp", "10.0.0.2", 0, true, false);
        d.roomId = "r";
        h.devices.add(d);
        h.removeRoom("r");
        assertEquals("", d.roomId);
        assertTrue(h.rooms.isEmpty());
    }

    @Test
    public void unknownRoomOnLoadFallsBackToNoRoom() {
        Home h = Home.fromJson("{\"rooms\":[],\"devices\":[{\"id\":\"d\",\"host\":\"10.0.0.2\",\"room\":\"gone\"}]}");
        assertEquals("", h.device("d").roomId);
    }

    @Test
    public void savedBeforeRoomsStillLoads() {
        // What version 1 of the app saved: no rooms, favorites, windows or pending offs.
        String v1 = "{\"version\":1,\"setup_on_tv\":false,\"devices\":[{\"id\":\"p\",\"brand\":\"kasa\",\"uid\":\"U\",\"name\":\"Plug 1\","
                + "\"host\":\"192.168.1.198\",\"gen\":0,\"switch\":true,\"sensor\":false}],\"cameras\":[],\"scenes\":[],"
                + "\"schedules\":[{\"id\":\"s\",\"name\":\"Night\",\"enabled\":true,\"when\":\"at\",\"minutes\":1410,\"days\":127,"
                + "\"scene\":\"\",\"actions\":[{\"device\":\"p\",\"on\":false}]}]}";
        Home h = Home.fromJson(v1);
        assertEquals("Plug 1", h.device("p").name);
        assertEquals("", h.device("p").roomId);
        assertFalse(h.device("p").favorite);
        Home.Schedule s = h.schedules.get(0);
        assertEquals(-1, s.windowFrom);
        assertEquals(0, s.autoOffMinutes);
        assertTrue(h.pendingOffs.isEmpty());
    }

    /** The reply a real EP25 (Plug 2) gave on 2026-10-20, read-only. */
    static final String REAL_ENERGY = "{\"emeter\": {\"get_daystat\": {\"day_list\": [{\"year\": 2026, \"month\": 10, \"day\": 19, \"energy_wh\": 53}, "
            + "{\"year\": 2026, \"month\": 10, \"day\": 20, \"energy_wh\": 4}], \"err_code\": 0}, \"get_monthstat\": {\"month_list\": "
            + "[{\"year\": 2026, \"month\": 10, \"energy_wh\": 57}, {\"year\": 2026, \"month\": 1, \"energy_wh\": 74}], \"err_code\": 0}}}";

    @Test
    public void energyFromARealPlug() throws Exception {
        double[] e = Kasa.parseEnergy(new JSONObject(REAL_ENERGY), LocalDate.of(2026, 10, 20));
        assertEquals(4, e[0], 0.001);
        assertEquals(57, e[1], 0.001);
    }

    @Test
    public void energyOlderFirmwareInKwhAndNothingYetToday() throws Exception {
        String old = "{\"emeter\":{\"get_daystat\":{\"day_list\":[{\"year\":2026,\"month\":10,\"day\":19,\"energy\":0.053}]},"
                + "\"get_monthstat\":{\"month_list\":[{\"year\":2026,\"month\":10,\"energy\":1.25}]}}}";
        double[] e = Kasa.parseEnergy(new JSONObject(old), LocalDate.of(2026, 10, 20));
        assertEquals(0, e[0], 0.001);        // no entry for today yet: nothing used
        assertEquals(1250, e[1], 0.001);
    }

    @Test
    public void energyWithoutMeterIsUnknown() throws Exception {
        double[] e = Kasa.parseEnergy(new JSONObject("{\"system\":{}}"), LocalDate.of(2026, 10, 20));
        assertTrue(Double.isNaN(e[0]) && Double.isNaN(e[1]));
    }

    @Test
    public void energyText() {
        assertEquals("4 Wh", DeviceActivity.wh(4));
        assertEquals("1.25 kWh", DeviceActivity.wh(1250));
        assertEquals("–", DeviceActivity.wh(Double.NaN));
    }

    @Test
    public void activityLogKeepsTheNewestAndFindsByName() {
        List<String> lines = ActivityLog.split("");
        for (int i = 0; i < 5; i++) lines = ActivityLog.append(lines, "Plug " + (i % 2 + 1) + " event " + i, 3);
        assertEquals(Arrays.asList("Plug 1 event 2", "Plug 2 event 3", "Plug 1 event 4"), lines);
        assertEquals(Arrays.asList("Plug 1 event 2", "Plug 1 event 4"), ActivityLog.about(lines, "Plug 1"));
        assertEquals("a b", ActivityLog.append(Collections.emptyList(), "a\nb", 5).get(0));   // one line per entry
    }
}
