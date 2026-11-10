package com.homecontrol.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * What each plug used, hour by hour: watt-hours per device per hour of each day, and how many seconds of that hour
 * were actually measured (so an hour nobody was watching is not mistaken for an hour of zero use). Readings arrive
 * as watts at a time; the energy between two readings is their average over the time between (readings more than
 * MAX_GAP_MS apart are not joined: nothing is known about the gap). Plain Java, tested on the JVM.
 */
final class EnergyStore {

    static final int KEEP_DAYS = 120;
    /** Readings further apart than this are not bridged. */
    static final long MAX_GAP_MS = 10 * 60_000L;

    /** day -> device -> 24 hours of watt-hours, and 24 hours of measured seconds. */
    private final TreeMap<LocalDate, Map<String, double[]>> wh = new TreeMap<>();
    private final TreeMap<LocalDate, Map<String, double[]>> covered = new TreeMap<>();
    private final Map<String, double[]> last = new HashMap<>();   // device -> {time ms, watts} of its latest reading

    /**
     * A reading: the device draws this many watts now. NaN (no meter, not answering) forgets the previous reading,
     * so the next one does not join across the silence.
     */
    synchronized void record(String device, long nowMs, double watts, ZoneId zone) {
        if (Double.isNaN(watts) || Double.isInfinite(watts) || watts < 0) {
            last.remove(device);
            return;
        }
        double[] prev = last.get(device);
        if (prev != null && nowMs > prev[0] && nowMs - prev[0] <= MAX_GAP_MS) {
            add(device, (long) prev[0], nowMs, (prev[1] + watts) / 2.0, zone);
        }
        if (prev == null || nowMs >= prev[0]) last.put(device, new double[]{nowMs, watts});
    }

    /** Spreads a steady draw over the hours it spans (an hour boundary, or midnight, splits it). */
    synchronized void add(String device, long fromMs, long toMs, double watts, ZoneId zone) {
        if (toMs <= fromMs || watts < 0 || Double.isNaN(watts)) return;
        ZonedDateTime cursor = Instant.ofEpochMilli(fromMs).atZone(zone);
        long cursorMs = fromMs;
        while (cursorMs < toMs) {
            ZonedDateTime nextHour = cursor.truncatedTo(ChronoUnit.HOURS).plusHours(1);
            long endMs = Math.min(toMs, nextHour.toInstant().toEpochMilli());
            if (endMs <= cursorMs) endMs = toMs;   // a clock oddity: never loop
            double seconds = (endMs - cursorMs) / 1000.0;
            LocalDate day = cursor.toLocalDate();
            int hour = cursor.getHour();
            bucket(wh, day, device)[hour] += watts * seconds / 3600.0;
            bucket(covered, day, device)[hour] += seconds;
            cursorMs = endMs;
            cursor = Instant.ofEpochMilli(cursorMs).atZone(zone);
        }
    }

    private static double[] bucket(TreeMap<LocalDate, Map<String, double[]>> m, LocalDate day, String device) {
        return m.computeIfAbsent(day, d -> new HashMap<>()).computeIfAbsent(device, d -> new double[24]);
    }

    /** 24 hours of watt-hours for a device on a day (zeros when nothing was recorded). */
    synchronized double[] hours(LocalDate day, String device) {
        Map<String, double[]> m = wh.get(day);
        double[] a = m == null ? null : m.get(device);
        return a == null ? new double[24] : a.clone();
    }

    /** 24 hours of measured seconds for a device on a day. */
    synchronized double[] seconds(LocalDate day, String device) {
        Map<String, double[]> m = covered.get(day);
        double[] a = m == null ? null : m.get(device);
        return a == null ? new double[24] : a.clone();
    }

    synchronized double dayWh(LocalDate day, String device) {
        double sum = 0;
        for (double v : hours(day, device)) sum += v;
        return sum;
    }

    /** The first day anything was recorded, or null. */
    synchronized LocalDate firstDay() {
        return wh.isEmpty() ? null : wh.firstKey();
    }

    synchronized boolean isEmpty() {
        return wh.isEmpty();
    }

    /** Drops what is older than KEEP_DAYS before today. */
    synchronized void prune(LocalDate today) {
        LocalDate cut = today.minusDays(KEEP_DAYS);
        wh.headMap(cut, false).clear();
        covered.headMap(cut, false).clear();
    }

    /** Forgets one device's history (when it is removed). */
    synchronized void forget(String device) {
        for (Map<String, double[]> m : wh.values()) m.remove(device);
        for (Map<String, double[]> m : covered.values()) m.remove(device);
        last.remove(device);
        wh.values().removeIf(Map::isEmpty);
        covered.values().removeIf(Map::isEmpty);
    }

    synchronized String toJson() {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<LocalDate, Map<String, double[]>> day : wh.entrySet()) {
                JSONObject d = new JSONObject();
                for (Map.Entry<String, double[]> dev : day.getValue().entrySet()) {
                    JSONArray a = new JSONArray(), c = new JSONArray();
                    double[] cov = covered.getOrDefault(day.getKey(), new HashMap<>()).get(dev.getKey());
                    for (int h = 0; h < 24; h++) {
                        a.put(Math.round(dev.getValue()[h] * 1000.0) / 1000.0);
                        c.put(cov == null ? 0 : Math.round(cov[h]));
                    }
                    d.put(dev.getKey(), new JSONObject().put("wh", a).put("s", c));
                }
                o.put(day.getKey().toString(), d);
            }
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    /** Reads what toJson wrote; anything odd is skipped, never an error. */
    static EnergyStore fromJson(String json) {
        EnergyStore s = new EnergyStore();
        try {
            JSONObject o = new JSONObject(json == null || json.isEmpty() ? "{}" : json);
            java.util.Iterator<String> days = o.keys();
            while (days.hasNext()) {
                String key = days.next();
                LocalDate day;
                try {
                    day = LocalDate.parse(key);
                } catch (RuntimeException e) {
                    continue;
                }
                JSONObject d = o.optJSONObject(key);
                if (d == null) continue;
                java.util.Iterator<String> devs = d.keys();
                while (devs.hasNext()) {
                    String id = devs.next();
                    JSONObject e = d.optJSONObject(id);
                    if (e == null) continue;
                    JSONArray a = e.optJSONArray("wh"), c = e.optJSONArray("s");
                    if (a == null) continue;
                    double[] w = bucket(s.wh, day, id), cv = bucket(s.covered, day, id);
                    for (int h = 0; h < 24; h++) {
                        double v = a.optDouble(h, 0);
                        w[h] = Double.isNaN(v) || v < 0 ? 0 : v;
                        double sec = c == null ? 0 : c.optDouble(h, 0);
                        cv[h] = Double.isNaN(sec) || sec < 0 ? 0 : Math.min(sec, 3600);
                    }
                }
            }
        } catch (JSONException ignored) {
        }
        return s;
    }
}
