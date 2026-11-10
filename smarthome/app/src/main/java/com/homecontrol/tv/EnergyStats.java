package com.homecontrol.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The numbers on the energy dashboard, worked out from an EnergyStore: power now, today, yesterday, the week, the
 * month and where it is heading, hour by hour and day by day, each plug's share, the busiest hour, and the
 * always-on base load. The same numbers go to the TV screen and to the phone page (toJson). Plain Java, tested on
 * the JVM.
 */
final class EnergyStats {

    /** One plug's use. */
    static final class Use {
        final String id, name;
        double nowW = Double.NaN, todayWh, weekWh, monthWh, thirtyWh;
        /** Share of today's total, 0..1 (0 when nothing was used). */
        double share;

        Use(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    double nowW;                 // all plugs together, right now (readings from the last few minutes)
    int onCount;                 // plugs drawing power now
    double todayWh, yesterdayWh, yesterdaySoFarWh, weekWh, previousWeekWh, monthWh, lastMonthWh, projectedMonthWh = Double.NaN;
    double[] hourToday = new double[24], hourYesterday = new double[24];
    LocalDate[] dayList = new LocalDate[0];   // the last 30 days, oldest first, ending today
    double[] dayWh = new double[0];
    final List<Use> devices = new ArrayList<>();
    int peakHour = -1;           // the hour of today with the most use, -1 when nothing yet
    double peakHourWh;
    LocalDate busiestDay;        // of the last 30 days
    double busiestDayWh;
    double baseLoadW = Double.NaN;   // the lowest hourly average draw lately: what runs all the time
    LocalDate firstDay;          // when recording began
    int daysTracked;
    double price;                // per kWh
    int currentHour;

    private EnergyStats() {
    }

    /** Wh to the cost of it. */
    double cost(double wh) {
        return wh / 1000.0 * price;
    }

    /**
     * @param store the recorded use
     * @param ids   the plugs to count, in display order (removed plugs' history is left out of every total)
     * @param names the plugs' names by id
     * @param live  the latest watts by id (NaN or missing: no reading)
     * @param price money per kWh
     */
    static EnergyStats compute(EnergyStore store, List<String> ids, Map<String, String> names, Map<String, Double> live,
                               double price, ZonedDateTime now) {
        EnergyStats s = new EnergyStats();
        ZoneId zone = now.getZone();
        LocalDate today = now.toLocalDate(), yesterday = today.minusDays(1);
        s.price = price;
        s.currentHour = now.getHour();
        s.firstDay = store.firstDay();
        s.daysTracked = s.firstDay == null ? 0 : (int) java.time.temporal.ChronoUnit.DAYS.between(s.firstDay, today) + 1;

        for (String id : ids) {
            Use u = new Use(id, names.getOrDefault(id, id));
            Double w = live.get(id);
            if (w != null && !Double.isNaN(w)) {
                u.nowW = w;
                s.nowW += w;
                if (w >= 1.0) s.onCount++;
            }
            double[] t = store.hours(today, id), y = store.hours(yesterday, id);
            for (int h = 0; h < 24; h++) {
                s.hourToday[h] += t[h];
                s.hourYesterday[h] += y[h];
                u.todayWh += t[h];
            }
            for (int d = 0; d < 7; d++) u.weekWh += store.dayWh(today.minusDays(d), id);
            for (int d = 0; d < 30; d++) u.thirtyWh += store.dayWh(today.minusDays(d), id);
            for (LocalDate d = today.withDayOfMonth(1); !d.isAfter(today); d = d.plusDays(1)) u.monthWh += store.dayWh(d, id);
            s.devices.add(u);
        }
        for (double v : s.hourToday) s.todayWh += v;
        for (double v : s.hourYesterday) s.yesterdayWh += v;
        for (int h = 0; h < s.currentHour; h++) s.yesterdaySoFarWh += s.hourYesterday[h];
        double minuteFraction = (now.getMinute() * 60 + now.getSecond()) / 3600.0;
        s.yesterdaySoFarWh += s.hourYesterday[s.currentHour] * minuteFraction;

        s.dayList = new LocalDate[30];
        s.dayWh = new double[30];
        for (int i = 0; i < 30; i++) {
            LocalDate d = today.minusDays(29 - i);
            s.dayList[i] = d;
            for (String id : ids) s.dayWh[i] += store.dayWh(d, id);
            if (s.dayWh[i] > s.busiestDayWh) {
                s.busiestDayWh = s.dayWh[i];
                s.busiestDay = d;
            }
        }
        for (int i = 29; i > 22; i--) s.weekWh += s.dayWh[i];
        for (int d = 7; d < 14; d++) for (String id : ids) s.previousWeekWh += store.dayWh(today.minusDays(d), id);
        for (LocalDate d = today.withDayOfMonth(1); !d.isAfter(today); d = d.plusDays(1)) {
            for (String id : ids) s.monthWh += store.dayWh(d, id);
        }
        LocalDate lastMonthStart = today.withDayOfMonth(1).minusMonths(1);
        for (LocalDate d = lastMonthStart; d.getMonth() == lastMonthStart.getMonth(); d = d.plusDays(1)) {
            for (String id : ids) s.lastMonthWh += store.dayWh(d, id);
        }
        double elapsedDays = (today.getDayOfMonth() - 1) + (now.getHour() + minuteFraction) / 24.0;
        if (elapsedDays >= 0.25 && s.monthWh > 0) s.projectedMonthWh = s.monthWh / elapsedDays * today.lengthOfMonth();

        double best = 0;
        for (int h = 0; h < 24; h++) {
            if (s.hourToday[h] > best) {
                best = s.hourToday[h];
                s.peakHour = h;
            }
        }
        s.peakHourWh = best;

        for (Use u : s.devices) u.share = s.todayWh > 0 ? u.todayWh / s.todayWh : 0;
        Collections.sort(s.devices, (a, b) -> {
            int c = Double.compare(b.todayWh, a.todayWh);
            if (c != 0) return c;
            c = Double.compare(b.weekWh, a.weekWh);
            return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
        });
        s.baseLoadW = baseLoad(store, ids, today, now);
        return s;
    }

    /**
     * The lowest average draw (all plugs together) over the hours of the last 7 days that were measured for at least
     * half their length: what is still running when everything else is off. The hour in progress is left out.
     */
    static double baseLoad(EnergyStore store, List<String> ids, LocalDate today, ZonedDateTime now) {
        double lowest = Double.NaN;
        for (int d = 0; d < 7; d++) {
            LocalDate day = today.minusDays(d);
            for (int h = 0; h < 24; h++) {
                if (d == 0 && h >= now.getHour()) continue;
                double total = 0, bestCover = 0;
                for (String id : ids) {
                    double cover = store.seconds(day, id)[h];
                    bestCover = Math.max(bestCover, cover);
                    if (cover > 0) total += store.hours(day, id)[h] / (cover / 3600.0);
                }
                if (bestCover < 1800) continue;   // not measured for half the hour
                if (Double.isNaN(lowest) || total < lowest) lowest = total;
            }
        }
        return lowest;
    }

    /** The change from yesterday so far to today so far, as a fraction (0.25 = a quarter more); NaN when there is nothing to compare. */
    double changeVsYesterday() {
        return yesterdaySoFarWh < 1.0 ? Double.NaN : (todayWh - yesterdaySoFarWh) / yesterdaySoFarWh;
    }

    /** The same, this week against the one before. */
    double changeVsLastWeek() {
        return previousWeekWh < 1.0 ? Double.NaN : (weekWh - previousWeekWh) / previousWeekWh;
    }

    /** What the base load costs over a year, money. NaN without a base load. */
    double baseLoadYearCost() {
        return Double.isNaN(baseLoadW) ? Double.NaN : baseLoadW * 24 * 365 / 1000.0 * price;
    }

    String toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("nowW", round(nowW)).put("onCount", onCount).put("price", price).put("hour", currentHour)
                .put("todayWh", round(todayWh)).put("yesterdayWh", round(yesterdayWh)).put("yesterdaySoFarWh", round(yesterdaySoFarWh))
                .put("weekWh", round(weekWh)).put("previousWeekWh", round(previousWeekWh)).put("monthWh", round(monthWh))
                .put("lastMonthWh", round(lastMonthWh)).put("peakHour", peakHour).put("peakHourWh", round(peakHourWh))
                .put("busiestDay", busiestDay == null ? JSONObject.NULL : busiestDay.toString()).put("busiestDayWh", round(busiestDayWh))
                .put("firstDay", firstDay == null ? JSONObject.NULL : firstDay.toString()).put("daysTracked", daysTracked);
        o.put("projectedMonthWh", Double.isNaN(projectedMonthWh) ? JSONObject.NULL : round(projectedMonthWh));
        o.put("baseLoadW", Double.isNaN(baseLoadW) ? JSONObject.NULL : round(baseLoadW));
        JSONArray ht = new JSONArray(), hy = new JSONArray(), dw = new JSONArray(), dl = new JSONArray(), dv = new JSONArray();
        for (int h = 0; h < 24; h++) {
            ht.put(round(hourToday[h]));
            hy.put(round(hourYesterday[h]));
        }
        for (int i = 0; i < dayWh.length; i++) {
            dw.put(round(dayWh[i]));
            dl.put(dayList[i].toString());
        }
        for (Use u : devices) {
            dv.put(new JSONObject().put("id", u.id).put("name", u.name).put("nowW", Double.isNaN(u.nowW) ? JSONObject.NULL : round(u.nowW))
                    .put("todayWh", round(u.todayWh)).put("weekWh", round(u.weekWh)).put("monthWh", round(u.monthWh)).put("thirtyWh", round(u.thirtyWh)).put("share", Math.round(u.share * 1000) / 1000.0));
        }
        return o.put("hourToday", ht).put("hourYesterday", hy).put("dayWh", dw).put("days", dl).put("devices", dv).toString();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
