package com.homecontrol.tv;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * When schedules run. Plain java.time, so it is tested on the JVM; Scheduler turns the answers into
 * Android alarms and ScheduleReceiver runs what is due.
 */
final class Schedules {

    /**
     * A schedule found late by up to this much still runs (the TV was busy, or the alarm was batched).
     * Later than that it is skipped: switching a plug off hours after midnight would surprise people.
     */
    static final Duration GRACE = Duration.ofMinutes(15);

    private Schedules() {
    }

    /**
     * The first run strictly after {@code after}, or null when it never runs at a known time (disabled, no
     * location for the sun, or a TV trigger, which runs when the TV turns on or off: see matchesTvEvent).
     */
    static ZonedDateTime next(Home.Schedule s, Home h, ZonedDateTime after) {
        if (!s.enabled || s.days == 0 || s.isTvTrigger()) return null;
        ZonedDateTime best = null;
        LocalDate start = after.toLocalDate().minusDays(1);   // a sunset offset can cross midnight
        for (int i = 0; i <= 8; i++) {
            LocalDate day = start.plusDays(i);
            if ((s.days & bit(day.getDayOfWeek())) == 0) continue;
            ZonedDateTime t = timeOn(s, h, day, after.getZone());
            if (t != null && t.isAfter(after) && (best == null || t.isBefore(best))) best = t;
        }
        return best;
    }

    /** Schedules that fell due in (from, to], ignoring any more than GRACE before {@code to}. */
    static List<Home.Schedule> due(Home h, ZonedDateTime from, ZonedDateTime to) {
        ZonedDateTime floor = to.minus(GRACE);
        if (from.isBefore(floor)) from = floor;
        List<Home.Schedule> out = new ArrayList<>();
        for (Home.Schedule s : h.schedules) {
            ZonedDateTime t = next(s, h, from);
            if (t != null && !t.isAfter(to)) out.add(s);
        }
        return out;
    }

    /** Whether an automation runs now because the TV turned on ({@code on}) or off. */
    static boolean matchesTvEvent(Home.Schedule s, ZonedDateTime now, boolean on) {
        if (!s.enabled || !(on ? Home.Schedule.TV_ON : Home.Schedule.TV_OFF).equals(s.when)) return false;
        if ((s.days & bit(now.getDayOfWeek())) == 0) return false;
        return inWindow(now.getHour() * 60 + now.getMinute(), s.windowFrom, s.windowTo);
    }

    /** A time window in minutes after midnight; from > to wraps past midnight (22:00–06:00). -1: always. */
    static boolean inWindow(int minuteOfDay, int from, int to) {
        if (from < 0 || to < 0) return true;
        return from <= to ? minuteOfDay >= from && minuteOfDay < to : minuteOfDay >= from || minuteOfDay < to;
    }

    /** "Turn off after" steps that are due at {@code nowMillis}. */
    static List<Home.PendingOff> dueOffs(Home h, long nowMillis) {
        List<Home.PendingOff> out = new ArrayList<>();
        for (Home.PendingOff p : h.pendingOffs) if (p.atMillis <= nowMillis) out.add(p);
        return out;
    }

    /**
     * After an automation turned devices on: remember to turn them off later. A device already waiting
     * keeps the later of the two times, so a second trigger extends rather than cuts short.
     */
    static void scheduleOffs(Home h, Home.Schedule s, List<Home.Action> ran, long nowMillis) {
        if (s.autoOffMinutes <= 0) return;
        long at = nowMillis + s.autoOffMinutes * 60_000L;
        for (Home.Action a : ran) {
            if (!a.on) continue;
            Home.PendingOff old = null;
            for (Home.PendingOff p : h.pendingOffs) if (p.deviceId.equals(a.deviceId)) old = p;
            if (old != null && old.atMillis >= at) continue;
            if (old != null) h.pendingOffs.remove(old);
            h.pendingOffs.add(new Home.PendingOff(a.deviceId, at));
        }
    }

    /** The earliest run of any schedule after {@code after}, or null. */
    static ZonedDateTime soonest(Home h, ZonedDateTime after) {
        ZonedDateTime best = null;
        for (Home.Schedule s : h.schedules) {
            ZonedDateTime t = next(s, h, after);
            if (t != null && (best == null || t.isBefore(best))) best = t;
        }
        // Pending "turn off after" steps need the alarm too (one already overdue: right away).
        for (Home.PendingOff p : h.pendingOffs) {
            ZonedDateTime t = java.time.Instant.ofEpochMilli(Math.max(p.atMillis, after.toInstant().toEpochMilli() + 1000)).atZone(after.getZone());
            if (best == null || t.isBefore(best)) best = t;
        }
        return best;
    }

    static ZonedDateTime timeOn(Home.Schedule s, Home h, LocalDate day, ZoneId zone) {
        if (Home.Schedule.AT.equals(s.when)) {
            // atStartOfDay + minutes keeps a 02:30 schedule on a spring-forward day at 03:30, not lost.
            return day.atStartOfDay(zone).plusMinutes(s.minutes);
        }
        if (Double.isNaN(h.latitude) || Double.isNaN(h.longitude)) return null;
        ZonedDateTime sun = sun(day, h.latitude, h.longitude, zone, Home.Schedule.SUNRISE.equals(s.when));
        return sun == null ? null : sun.plusMinutes(s.minutes);
    }

    static int bit(DayOfWeek d) {
        return 1 << (d.getValue() - 1);   // Monday = 1
    }

    /**
     * Sunrise or sunset on a local date (the "Almanac for Computers" method, about a minute from
     * astronomical tables at ordinary latitudes). Null when the sun doesn't rise or set that day.
     */
    static ZonedDateTime sun(LocalDate date, double lat, double lng, ZoneId zone, boolean rise) {
        double lngHour = lng / 15.0;
        // The method works in UTC days; try the UTC dates around the local one and keep the one that lands on it.
        for (int shift = -1; shift <= 1; shift++) {
            LocalDate utcDay = date.plusDays(shift);
            Double ut = sunUt(utcDay.getDayOfYear(), lat, lngHour, rise);
            if (ut == null) return null;
            ZonedDateTime t = utcDay.atStartOfDay(ZoneOffset.UTC).plusSeconds(Math.round(ut * 3600)).withZoneSameInstant(zone);
            if (t.toLocalDate().equals(date)) return t;
        }
        return null;
    }

    private static Double sunUt(int dayOfYear, double lat, double lngHour, boolean rise) {
        final double zenith = 90.833;
        double t = dayOfYear + ((rise ? 6 : 18) - lngHour) / 24.0;
        double m = 0.9856 * t - 3.289;
        double l = norm(m + 1.916 * sin(m) + 0.020 * sin(2 * m) + 282.634, 360);
        double ra = norm(Math.toDegrees(Math.atan(0.91764 * tan(l))), 360);
        ra += Math.floor(l / 90) * 90 - Math.floor(ra / 90) * 90;
        ra /= 15;
        double sinDec = 0.39782 * sin(l);
        double cosDec = Math.cos(Math.asin(sinDec));
        double cosH = (cos(zenith) - sinDec * sin(lat)) / (cosDec * cos(lat));
        if (cosH > 1 || cosH < -1) return null;   // polar night or midnight sun
        double h = (rise ? 360 - Math.toDegrees(Math.acos(cosH)) : Math.toDegrees(Math.acos(cosH))) / 15;
        double local = h + ra - 0.06571 * t - 6.622;
        return norm(local - lngHour, 24);
    }

    private static double sin(double deg) {
        return Math.sin(Math.toRadians(deg));
    }

    private static double cos(double deg) {
        return Math.cos(Math.toRadians(deg));
    }

    private static double tan(double deg) {
        return Math.tan(Math.toRadians(deg));
    }

    private static double norm(double v, double range) {
        v %= range;
        return v < 0 ? v + range : v;
    }

    // ---- words for the dashboard and home.sh ------------------------------------------------------

    /** "Every day · 00:00", "Weekdays · sunset −30 min", "When TV turns off · 22:00–06:00 · off after 30 min". */
    static String describe(Home.Schedule s) {
        String when;
        if (Home.Schedule.AT.equals(s.when)) {
            when = LocalTime.MIDNIGHT.plusMinutes(s.minutes).toString();
        } else if (s.isTvTrigger()) {
            when = (Home.Schedule.TV_ON.equals(s.when) ? "When TV turns on" : "When TV turns off")
                    + (s.windowFrom < 0 ? "" : " · " + hhmm(s.windowFrom) + "–" + hhmm(s.windowTo));
        } else {
            when = s.when + (s.minutes == 0 ? "" : (s.minutes > 0 ? " +" : " −") + Math.abs(s.minutes) + " min");
        }
        String text = s.isTvTrigger() ? (s.days == Home.Schedule.EVERY_DAY ? when : days(s.days) + " · " + when)
                : days(s.days) + " · " + when;
        return s.autoOffMinutes > 0 ? text + " · off after " + duration(s.autoOffMinutes) : text;
    }

    static String hhmm(int minuteOfDay) {
        return LocalTime.MIDNIGHT.plusMinutes(minuteOfDay).toString();
    }

    static String duration(int minutes) {
        return minutes % 60 == 0 ? (minutes / 60) + " h" : minutes + " min";
    }

    static String days(int mask) {
        if (mask == Home.Schedule.EVERY_DAY) return "Every day";
        if (mask == 0x1F) return "Weekdays";
        if (mask == 0x60) return "Weekends";
        StringBuilder b = new StringBuilder();
        for (DayOfWeek d : DayOfWeek.values()) {
            if ((mask & bit(d)) == 0) continue;
            if (b.length() > 0) b.append(", ");
            String n = d.name().substring(0, 3);
            b.append(n.charAt(0)).append(n.substring(1).toLowerCase(Locale.ROOT));
        }
        return b.toString();
    }

    /** "daily", "weekdays", "weekends" or a list like "mon,wed,fri"; -1 when it can't be read. */
    static int parseDays(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        switch (t) {
            case "daily": case "every day": case "everyday": return Home.Schedule.EVERY_DAY;
            case "weekdays": return 0x1F;
            case "weekends": return 0x60;
            default:
        }
        int mask = 0;
        for (String part : t.split("[,\\s]+")) {
            if (part.isEmpty()) continue;
            boolean ok = false;
            for (DayOfWeek d : DayOfWeek.values()) {
                if (d.name().toLowerCase(Locale.ROOT).startsWith(part) && part.length() >= 2) {
                    mask |= bit(d);
                    ok = true;
                }
            }
            if (!ok) return -1;
        }
        return mask == 0 ? -1 : mask;
    }
}
