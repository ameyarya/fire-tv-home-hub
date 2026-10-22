package com.homecontrol.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

public class SchedulesTest {

    static final ZoneId LA = ZoneId.of("America/Los_Angeles");

    /** Reference times from the astral library (python, independent of this code), local time, HH:MM. */
    static final String[][] ASTRAL = {
            {"37.7749", "-122.4194", "America/Los_Angeles", "2026-10-19", "07:22", "18:26"},
            {"37.7749", "-122.4194", "America/Los_Angeles", "2026-06-21", "05:48", "20:34"},
            {"40.7128", "-74.006", "America/New_York", "2026-03-08", "07:19", "18:54"},     // DST starts that day
            {"28.6139", "77.209", "Asia/Kolkata", "2026-01-15", "07:15", "17:45"},
            {"-33.8688", "151.2093", "Australia/Sydney", "2026-12-21", "05:40", "20:05"},
            {"64.1466", "-21.9426", "Atlantic/Reykjavik", "2026-12-21", "11:23", "15:28"},
    };

    @Test
    public void sunriseAndSunsetWithinThreeMinutesOfAstral() {
        for (String[] c : ASTRAL) {
            LocalDate day = LocalDate.parse(c[3]);
            ZoneId z = ZoneId.of(c[2]);
            double lat = Double.parseDouble(c[0]), lon = Double.parseDouble(c[1]);
            assertClose(c[2] + " " + c[3] + " sunrise", day.atTime(LocalTime.parse(c[4])).atZone(z),
                    Schedules.sun(day, lat, lon, z, true));
            assertClose(c[2] + " " + c[3] + " sunset", day.atTime(LocalTime.parse(c[5])).atZone(z),
                    Schedules.sun(day, lat, lon, z, false));
        }
    }

    private static void assertClose(String what, ZonedDateTime want, ZonedDateTime got) {
        assertTrue(what + ": no time", got != null);
        long diff = Math.abs(Duration.between(want, got).toMinutes());
        assertTrue(what + ": " + got.toLocalTime() + " vs astral " + want.toLocalTime(), diff <= 3);
    }

    @Test
    public void polarNightHasNoSunrise() {
        assertNull(Schedules.sun(LocalDate.of(2026, 12, 21), 78.22, 15.65, ZoneId.of("Arctic/Longyearbyen"), true));
    }

    private static Home.Schedule at(String hhmm, int days) {
        Home.Schedule s = new Home.Schedule("s", "Lamp off");
        LocalTime t = LocalTime.parse(hhmm);
        s.minutes = t.getHour() * 60 + t.getMinute();
        s.days = days;
        s.actions.add(new Home.Action("d", false));
        return s;
    }

    private static ZonedDateTime la(String iso) {
        return java.time.LocalDateTime.parse(iso).atZone(LA);
    }

    @Test
    public void nextDailyRunIsLaterTodayOrTomorrow() {
        Home h = new Home();
        Home.Schedule s = at("23:30", Home.Schedule.EVERY_DAY);
        assertEquals(la("2026-10-19T23:30"), Schedules.next(s, h, la("2026-10-19T20:00")));
        assertEquals(la("2026-10-20T23:30"), Schedules.next(s, h, la("2026-10-19T23:30")));   // strictly after
    }

    @Test
    public void weekdaysSkipTheWeekend() {
        Home h = new Home();
        Home.Schedule s = at("07:00", Schedules.parseDays("weekdays"));
        // 2026-10-23 is a Friday.
        assertEquals(la("2026-10-26T07:00"), Schedules.next(s, h, la("2026-10-23T08:00")));
    }

    @Test
    public void disabledOrNoDaysNeverRuns() {
        Home h = new Home();
        Home.Schedule s = at("07:00", Home.Schedule.EVERY_DAY);
        s.enabled = false;
        assertNull(Schedules.next(s, h, la("2026-10-19T00:00")));
        s.enabled = true;
        s.days = 0;
        assertNull(Schedules.next(s, h, la("2026-10-19T00:00")));
    }

    @Test
    public void springForwardGapMovesToTheNextValidMinute() {
        Home h = new Home();
        Home.Schedule s = at("02:30", Home.Schedule.EVERY_DAY);
        // 2026-03-08 02:00 → 03:00 in Los Angeles: 02:30 doesn't exist that night.
        ZonedDateTime t = Schedules.next(s, h, la("2026-03-08T00:00"));
        assertEquals(LocalDate.of(2026, 3, 8), t.toLocalDate());
        assertEquals(LocalTime.of(3, 30), t.toLocalTime());
    }

    @Test
    public void fallBackRunsOnce() {
        Home h = new Home();
        h.schedules.add(at("01:30", Home.Schedule.EVERY_DAY));
        // 2026-11-01 01:00-02:00 happens twice in Los Angeles; the schedule must fire once that night.
        ZonedDateTime from = la("2026-11-01T00:00");
        ZonedDateTime first = Schedules.next(h.schedules.get(0), h, from);
        ZonedDateTime second = Schedules.next(h.schedules.get(0), h, first);
        assertEquals(LocalDate.of(2026, 11, 2), second.toLocalDate());
    }

    @Test
    public void sunsetWithOffsetNeedsALocation() {
        Home h = new Home();
        Home.Schedule s = new Home.Schedule("s", "Lamp on");
        s.when = Home.Schedule.SUNSET;
        s.minutes = -30;
        s.actions.add(new Home.Action("d", true));
        assertNull(Schedules.next(s, h, la("2026-10-19T12:00")));
        h.latitude = 37.7749;
        h.longitude = -122.4194;
        ZonedDateTime t = Schedules.next(s, h, la("2026-10-19T12:00"));
        // astral sunset 18:26, minus 30 minutes
        assertTrue(t.toString(), Math.abs(Duration.between(la("2026-10-19T17:56"), t).toMinutes()) <= 3);
    }

    @Test
    public void dueFindsWhatFellInTheWindow() {
        Home h = new Home();
        h.schedules.add(at("23:30", Home.Schedule.EVERY_DAY));
        assertEquals(1, Schedules.due(h, la("2026-10-19T23:29"), la("2026-10-19T23:30")).size());
        assertEquals(0, Schedules.due(h, la("2026-10-19T23:30"), la("2026-10-19T23:31")).size());   // already ran
    }

    @Test
    public void lateAlarmWithinGraceStillRuns() {
        Home h = new Home();
        h.schedules.add(at("23:30", Home.Schedule.EVERY_DAY));
        assertEquals(1, Schedules.due(h, la("2026-10-19T23:29"), la("2026-10-19T23:40")).size());
    }

    @Test
    public void runsMissedForHoursAreSkipped() {
        Home h = new Home();
        h.schedules.add(at("00:00", Home.Schedule.EVERY_DAY));
        // TV asleep from 23:00 to 07:00: the midnight "off" must not fire at 07:00.
        List<Home.Schedule> due = Schedules.due(h, la("2026-10-19T23:00"), la("2026-10-20T07:00"));
        assertTrue(due.isEmpty());
    }

    @Test
    public void soonestPicksTheEarliestSchedule() {
        Home h = new Home();
        h.schedules.add(at("23:30", Home.Schedule.EVERY_DAY));
        h.schedules.add(at("21:00", Home.Schedule.EVERY_DAY));
        assertEquals(la("2026-10-19T21:00"), Schedules.soonest(h, la("2026-10-19T20:00")));
        assertNull(Schedules.soonest(new Home(), la("2026-10-19T20:00")));
    }

    @Test
    public void describeAndParseDays() {
        assertEquals("Every day · 23:30", Schedules.describe(at("23:30", Home.Schedule.EVERY_DAY)));
        assertEquals("Weekdays · 07:00", Schedules.describe(at("07:00", 0x1F)));
        assertEquals("Mon, Wed, Fri · 07:00", Schedules.describe(at("07:00", Schedules.parseDays("mon,wed,fri"))));
        Home.Schedule s = at("00:00", 0x60);
        s.when = Home.Schedule.SUNSET;
        s.minutes = -30;
        assertEquals("Weekends · sunset −30 min", Schedules.describe(s));
        assertEquals(-1, Schedules.parseDays("someday"));
        assertEquals(-1, Schedules.parseDays("m"));   // too short to tell Monday from nothing
        assertEquals(Home.Schedule.EVERY_DAY, Schedules.parseDays("Daily"));
    }

    @Test
    public void schedulesSurviveSaveAndLoad() {
        Home h = new Home();
        h.devices.add(new Home.Device("d", "Lamp", "10.0.0.2", 0, true, false));
        Home.Schedule s = at("23:30", 0x1F);
        s.enabled = false;
        h.schedules.add(s);
        h.latitude = 37.7;
        h.longitude = -122.4;
        Home back = Home.fromJson(h.toJson());
        Home.Schedule b = back.schedules.get(0);
        assertEquals(23 * 60 + 30, b.minutes);
        assertEquals(0x1F, b.days);
        assertEquals(false, b.enabled);
        assertEquals("d", b.actions.get(0).deviceId);
        assertEquals(37.7, back.latitude, 1e-9);
    }

    @Test
    public void removingADeviceOrSceneCleansSchedules() {
        Home h = new Home();
        h.devices.add(new Home.Device("d", "Lamp", "10.0.0.2", 0, true, false));
        h.schedules.add(at("23:30", Home.Schedule.EVERY_DAY));
        Home.Scene sc = new Home.Scene("sc", "Night");
        sc.actions.add(new Home.Action("d", false));
        h.scenes.add(sc);
        Home.Schedule viaScene = new Home.Schedule("s2", "Night at 11");
        viaScene.sceneId = "sc";
        h.schedules.add(viaScene);
        h.removeDevice("d");
        assertTrue(h.scenes.isEmpty());
        assertTrue("schedules left: " + h.schedules.size(), h.schedules.isEmpty());
    }

    @Test
    public void schedulerRunsTheSceneOrTheActions() {
        Home h = new Home();
        h.devices.add(new Home.Device("d", "Lamp", "10.0.0.2", 0, true, false));
        Home.Scene sc = new Home.Scene("sc", "Night");
        sc.actions.add(new Home.Action("d", true));
        h.scenes.add(sc);
        Home.Schedule viaScene = new Home.Schedule("s", "x");
        viaScene.sceneId = "sc";
        assertTrue(Scheduler.actionsOf(h, viaScene).get(0).on);
        Home.Schedule direct = at("01:00", Home.Schedule.EVERY_DAY);
        assertEquals(false, Scheduler.actionsOf(h, direct).get(0).on);
    }
}
