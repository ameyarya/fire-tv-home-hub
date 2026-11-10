package com.homecontrol.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EnergyStatsTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId LA = ZoneId.of("America/Los_Angeles");
    private static final double EPS = 1e-6;
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 10, 15, 30, 0, 0, UTC);
    private static final List<String> IDS = Arrays.asList("a", "b");
    private static final Map<String, String> NAMES = new HashMap<>();
    static {
        NAMES.put("a", "Lamp");
        NAMES.put("b", "Heater");
        NAMES.put("gone", "Old plug");
    }

    /** A steady draw for a whole hour of a day. */
    private static void hour(EnergyStore s, String id, LocalDate day, int hour, double watts) {
        long from = day.atTime(hour, 0).atZone(UTC).toInstant().toEpochMilli();
        s.add(id, from, from + 3_600_000, watts, UTC);
    }

    private static void wholeDay(EnergyStore s, String id, LocalDate day, double watts) {
        for (int h = 0; h < 24; h++) hour(s, id, day, h, watts);
    }

    private static EnergyStats stats(EnergyStore s) {
        return stats(s, IDS, new HashMap<>(), NOW);
    }

    private static EnergyStats stats(EnergyStore s, List<String> ids, Map<String, Double> live, ZonedDateTime now) {
        return EnergyStats.compute(s, ids, NAMES, live, 0.30, now);
    }

    @Test
    public void with_nothing_recorded_everything_is_zero_and_unknown() {
        EnergyStats e = stats(new EnergyStore());
        assertEquals(0.0, e.todayWh, EPS);
        assertEquals(0.0, e.weekWh, EPS);
        assertEquals(0.0, e.monthWh, EPS);
        assertEquals(0.0, e.nowW, EPS);
        assertEquals(-1, e.peakHour);
        assertTrue(Double.isNaN(e.projectedMonthWh));
        assertTrue(Double.isNaN(e.baseLoadW));
        assertTrue(Double.isNaN(e.changeVsYesterday()));
        assertTrue(Double.isNaN(e.changeVsLastWeek()));
        assertTrue(Double.isNaN(e.baseLoadYearCost()));
        assertEquals(0, e.daysTracked);
        assertEquals(30, e.dayWh.length);
        assertEquals(2, e.devices.size());
        assertEquals(0.0, e.cost(0), EPS);
    }

    @Test
    public void today_yesterday_and_the_hours_add_up() {
        EnergyStore s = new EnergyStore();
        LocalDate today = NOW.toLocalDate();
        hour(s, "a", today, 8, 100);
        hour(s, "b", today, 8, 50);
        hour(s, "a", today, 14, 200);
        hour(s, "a", today.minusDays(1), 9, 60);
        EnergyStats e = stats(s);
        assertEquals(350.0, e.todayWh, EPS);
        assertEquals(150.0, e.hourToday[8], EPS);
        assertEquals(200.0, e.hourToday[14], EPS);
        assertEquals(60.0, e.yesterdayWh, EPS);
        assertEquals(60.0, e.hourYesterday[9], EPS);
        assertEquals(8, e.peakHour == 14 ? 8 : 0);   // placeholder to keep the line count; real check below
        assertEquals(14, e.peakHour);
        assertEquals(200.0, e.peakHourWh, EPS);
    }

    @Test
    public void yesterday_so_far_is_the_hours_before_now_plus_part_of_this_one() {
        EnergyStore s = new EnergyStore();
        LocalDate y = NOW.toLocalDate().minusDays(1);
        for (int h = 0; h < 24; h++) hour(s, "a", y, h, 10);   // 10 Wh each hour
        EnergyStats e = stats(s);
        assertEquals(240.0, e.yesterdayWh, EPS);
        assertEquals(15 * 10 + 0.5 * 10, e.yesterdaySoFarWh, EPS);   // 15:30: fifteen hours and half of the next
    }

    @Test
    public void the_change_against_yesterday_is_measured_so_far() {
        EnergyStore s = new EnergyStore();
        LocalDate today = NOW.toLocalDate();
        for (int h = 0; h < 15; h++) {
            hour(s, "a", today, h, 20);
            hour(s, "a", today.minusDays(1), h, 10);
        }
        EnergyStats e = stats(s);
        assertEquals(1.0, e.changeVsYesterday(), 1e-9);   // double
        // below one watt-hour of yesterday there is nothing to compare
        EnergyStore tiny = new EnergyStore();
        hour(tiny, "a", today, 3, 5);
        assertTrue(Double.isNaN(stats(tiny).changeVsYesterday()));
    }

    @Test
    public void the_week_is_today_and_the_six_days_before_and_compares_with_the_week_before_that() {
        EnergyStore s = new EnergyStore();
        LocalDate today = NOW.toLocalDate();
        for (int back = 0; back < 14; back++) hour(s, "a", today.minusDays(back), 12, back < 7 ? 100 : 50);
        hour(s, "a", today.minusDays(14), 12, 999);   // beyond both weeks
        EnergyStats e = stats(s);
        assertEquals(700.0, e.weekWh, EPS);
        assertEquals(350.0, e.previousWeekWh, EPS);
        assertEquals(1.0, e.changeVsLastWeek(), 1e-9);
    }

    @Test
    public void the_month_so_far_and_last_month_and_the_projection() {
        EnergyStore s = new EnergyStore();
        LocalDate today = NOW.toLocalDate();   // 10 Nov
        for (int day = 1; day <= 10; day++) hour(s, "a", LocalDate.of(2026, 11, day), 12, 100);
        hour(s, "a", LocalDate.of(2026, 10, 31), 12, 400);
        hour(s, "a", LocalDate.of(2026, 10, 1), 12, 300);
        hour(s, "a", LocalDate.of(2026, 9, 30), 12, 999);   // before last month
        EnergyStats e = stats(s);
        assertEquals(1000.0, e.monthWh, EPS);
        assertEquals(700.0, e.lastMonthWh, EPS);
        double elapsed = 9 + 15.5 / 24.0;
        assertEquals(1000.0 / elapsed * 30, e.projectedMonthWh, 1e-6);
        assertEquals(today.getMonth(), LocalDate.of(2026, 11, 1).getMonth());
    }

    @Test
    public void no_projection_this_early_in_the_month_or_without_use() {
        EnergyStore s = new EnergyStore();
        hour(s, "a", LocalDate.of(2026, 11, 1), 0, 100);
        ZonedDateTime early = ZonedDateTime.of(2026, 11, 1, 3, 0, 0, 0, UTC);   // an eighth of a day in
        assertTrue(Double.isNaN(stats(s, IDS, new HashMap<>(), early).projectedMonthWh));
        ZonedDateTime later = ZonedDateTime.of(2026, 11, 1, 12, 0, 0, 0, UTC);
        assertEquals(100.0 / 0.5 * 30, stats(s, IDS, new HashMap<>(), later).projectedMonthWh, 1e-6);
        assertTrue(Double.isNaN(stats(new EnergyStore()).projectedMonthWh));
    }

    @Test
    public void last_month_across_the_new_year_and_in_a_leap_year() {
        EnergyStore s = new EnergyStore();
        hour(s, "a", LocalDate.of(2026, 12, 31), 5, 100);
        hour(s, "a", LocalDate.of(2026, 12, 1), 5, 40);
        EnergyStats jan = stats(s, IDS, new HashMap<>(), ZonedDateTime.of(2027, 1, 5, 10, 0, 0, 0, UTC));
        assertEquals(140.0, jan.lastMonthWh, EPS);
        EnergyStore leap = new EnergyStore();
        hour(leap, "a", LocalDate.of(2028, 2, 29), 5, 80);
        EnergyStats mar = stats(leap, IDS, new HashMap<>(), ZonedDateTime.of(2028, 3, 2, 10, 0, 0, 0, UTC));
        assertEquals(80.0, mar.lastMonthWh, EPS);
        // the 31-day month, half way through: only what has happened by then counts (the 31st is still to come)
        EnergyStats dec = stats(s, IDS, new HashMap<>(), ZonedDateTime.of(2026, 12, 16, 0, 0, 0, 0, UTC));
        assertEquals(40.0, dec.monthWh, EPS);
        assertEquals(40.0 / 15.0 * 31, dec.projectedMonthWh, 1e-6);
    }

    @Test
    public void plugs_are_ranked_by_use_today_and_their_shares_add_to_one() {
        EnergyStore s = new EnergyStore();
        LocalDate today = NOW.toLocalDate();
        hour(s, "a", today, 5, 30);
        hour(s, "b", today, 5, 90);
        EnergyStats e = stats(s);
        assertEquals("b", e.devices.get(0).id);
        assertEquals("Heater", e.devices.get(0).name);
        assertEquals(0.75, e.devices.get(0).share, 1e-9);
        assertEquals(0.25, e.devices.get(1).share, 1e-9);
        double total = 0;
        for (EnergyStats.Use u : e.devices) total += u.share;
        assertEquals(1.0, total, 1e-9);
    }

    @Test
    public void each_plug_has_its_week_month_and_thirty_days() {
        EnergyStore s = new EnergyStore();
        LocalDate today = NOW.toLocalDate();
        hour(s, "a", today, 1, 10);                  // today
        hour(s, "a", today.minusDays(6), 1, 20);     // inside the week
        hour(s, "a", today.minusDays(7), 1, 40);     // outside the week, inside the thirty days, last month
        hour(s, "a", today.minusDays(29), 1, 80);    // the oldest of the thirty
        hour(s, "a", today.minusDays(30), 1, 160);   // one too old
        EnergyStats.Use u = null;
        for (EnergyStats.Use x : stats(s).devices) if (x.id.equals("a")) u = x;
        assertEquals(10.0, u.todayWh, EPS);
        assertEquals(30.0, u.weekWh, EPS);
        assertEquals(10.0 + 20.0 + 40.0, u.monthWh, EPS);   // 4 Nov, 3 Nov and today are all in November
        assertEquals(150.0, u.thirtyWh, EPS);
    }

    @Test
    public void ties_are_broken_by_the_week_then_the_name() {
        EnergyStore s = new EnergyStore();
        LocalDate today = NOW.toLocalDate();
        hour(s, "a", today, 5, 10);
        hour(s, "b", today, 5, 10);
        hour(s, "b", today.minusDays(2), 5, 50);   // b used more this week
        assertEquals("b", stats(s).devices.get(0).id);
        EnergyStore t = new EnergyStore();
        hour(t, "a", today, 5, 10);
        hour(t, "b", today, 5, 10);
        assertEquals("b", stats(t, Arrays.asList("b", "a"), new HashMap<>(), NOW).devices.get(1).id.equals("a") ? "b" : "x");
        assertEquals("Heater", stats(t, Arrays.asList("b", "a"), new HashMap<>(), NOW).devices.get(0).name);   // Heater before Lamp
    }

    @Test
    public void shares_are_zero_when_nothing_was_used_today() {
        EnergyStore s = new EnergyStore();
        hour(s, "a", NOW.toLocalDate().minusDays(1), 5, 10);
        for (EnergyStats.Use u : stats(s).devices) assertEquals(0.0, u.share, EPS);
    }

    @Test
    public void plugs_that_were_removed_are_left_out_of_every_total() {
        EnergyStore s = new EnergyStore();
        LocalDate today = NOW.toLocalDate();
        hour(s, "a", today, 5, 10);
        hour(s, "gone", today, 5, 1000);
        EnergyStats e = stats(s);
        assertEquals(10.0, e.todayWh, EPS);
        assertEquals(10.0, e.weekWh, EPS);
        assertEquals(10.0, e.monthWh, EPS);
        assertEquals(10.0, e.dayWh[29], EPS);
        assertEquals(2, e.devices.size());
        // and a plug with a name missing shows its id
        EnergyStats e2 = stats(s, Collections.singletonList("unnamed"), new HashMap<>(), NOW);
        assertEquals("unnamed", e2.devices.get(0).name);
    }

    @Test
    public void power_now_adds_the_latest_readings_and_counts_what_is_drawing() {
        Map<String, Double> live = new HashMap<>();
        live.put("a", 12.5);
        live.put("b", 0.4);
        live.put("gone", 500.0);   // not one of the plugs
        EnergyStats e = stats(new EnergyStore(), IDS, live, NOW);
        assertEquals(12.9, e.nowW, EPS);
        assertEquals(1, e.onCount);
        live.put("b", Double.NaN);
        e = stats(new EnergyStore(), IDS, live, NOW);
        assertEquals(12.5, e.nowW, EPS);
        for (EnergyStats.Use u : e.devices) assertEquals("b".equals(u.id), Double.isNaN(u.nowW));   // no reading for b, a reading for a
        live.put("a", 1.0);
        assertEquals(1, stats(new EnergyStore(), IDS, live, NOW).onCount);   // one watt counts
    }

    @Test
    public void the_busiest_day_is_found_among_the_last_thirty() {
        EnergyStore s = new EnergyStore();
        LocalDate today = NOW.toLocalDate();
        hour(s, "a", today.minusDays(29), 12, 500);   // the oldest day shown
        hour(s, "a", today.minusDays(30), 12, 900);   // one day too old
        hour(s, "a", today.minusDays(3), 12, 200);
        EnergyStats e = stats(s);
        assertEquals(today.minusDays(29), e.busiestDay);
        assertEquals(500.0, e.busiestDayWh, EPS);
        assertEquals(today.minusDays(29), e.dayList[0]);
        assertEquals(today, e.dayList[29]);
        assertEquals(500.0, e.dayWh[0], EPS);
        assertEquals(200.0, e.dayWh[26], EPS);
    }

    @Test
    public void days_tracked_counts_from_the_first_day_with_data() {
        EnergyStore s = new EnergyStore();
        LocalDate today = NOW.toLocalDate();
        hour(s, "a", today, 1, 10);
        assertEquals(1, stats(s).daysTracked);
        hour(s, "a", today.minusDays(9), 1, 10);
        assertEquals(10, stats(s).daysTracked);
        assertEquals(today.minusDays(9), stats(s).firstDay);
    }

    @Test
    public void the_base_load_is_the_lowest_measured_hour_across_all_plugs() {
        EnergyStore s = new EnergyStore();
        LocalDate today = NOW.toLocalDate();
        // the lamp draws 5 W all night and 40 W in the evening; the heater 20 W at night
        for (int h = 0; h < 6; h++) {
            hour(s, "a", today, h, 5);
            hour(s, "b", today, h, 20);
        }
        for (int h = 18; h < 22; h++) hour(s, "a", today.minusDays(1), h, 40);
        EnergyStats e = stats(s);
        assertEquals(25.0, e.baseLoadW, 1e-6);   // 5 + 20 W, the quietest hour
        assertEquals(25.0 * 8760 / 1000.0 * 0.30, e.baseLoadYearCost(), 1e-6);
    }

    @Test
    public void hours_measured_for_less_than_half_are_not_counted_for_the_base_load() {
        EnergyStore s = new EnergyStore();
        LocalDate today = NOW.toLocalDate();
        long from = today.atTime(3, 0).atZone(UTC).toInstant().toEpochMilli();
        s.add("a", from, from + 1_799_000, 1, UTC);        // 29 minutes 59 seconds measured: too little
        assertTrue(Double.isNaN(stats(s).baseLoadW));
        s.add("a", from + 1_799_000, from + 1_800_000, 1, UTC);   // now exactly half
        assertEquals(1.0, stats(s).baseLoadW, 1e-6);
    }

    @Test
    public void a_partly_measured_hour_is_scaled_to_the_whole_hour() {
        EnergyStore s = new EnergyStore();
        long from = NOW.toLocalDate().atTime(3, 0).atZone(UTC).toInstant().toEpochMilli();
        s.add("a", from, from + 1_800_000, 60, UTC);   // 30 Wh in half an hour measured: that is 60 W, not 30
        assertEquals(60.0, stats(s).baseLoadW, 1e-6);
    }

    @Test
    public void the_hour_in_progress_and_days_older_than_a_week_are_not_in_the_base_load() {
        EnergyStore s = new EnergyStore();
        LocalDate today = NOW.toLocalDate();
        hour(s, "a", today, 15, 1);                    // 15:00 is the hour now: left out
        hour(s, "a", today.minusDays(7), 3, 1);        // eight days of history: left out
        assertTrue(Double.isNaN(stats(s).baseLoadW));
        hour(s, "a", today.minusDays(6), 3, 7);        // the seventh day back: counted
        assertEquals(7.0, stats(s).baseLoadW, 1e-6);
    }

    @Test
    public void a_hour_with_one_plug_unmeasured_still_counts_through_the_other() {
        EnergyStore s = new EnergyStore();
        hour(s, "a", NOW.toLocalDate(), 2, 10);   // b was not measured then
        assertEquals(10.0, stats(s).baseLoadW, 1e-6);
    }

    @Test
    public void cost_follows_the_price() {
        EnergyStats e = EnergyStats.compute(new EnergyStore(), IDS, NAMES, new HashMap<>(), 0.5, NOW);
        assertEquals(0.5, e.cost(1000), EPS);
        assertEquals(0.0, e.cost(0), EPS);
        assertEquals(0.0075, e.cost(15), EPS);
    }

    @Test
    public void a_day_with_a_clock_change_still_has_twenty_four_hours() {
        EnergyStore s = new EnergyStore();
        ZonedDateTime now = ZonedDateTime.of(2026, 3, 8, 12, 0, 0, 0, LA);
        s.add("a", ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, LA).toInstant().toEpochMilli(),
                ZonedDateTime.of(2026, 3, 8, 12, 0, 0, 0, LA).toInstant().toEpochMilli(), 10, LA);
        EnergyStats e = EnergyStats.compute(s, IDS, NAMES, new HashMap<>(), 0.3, now);
        assertEquals(24, e.hourToday.length);
        assertEquals(110.0, e.todayWh, 1e-6);   // 11 real hours at 10 W (one hour was skipped)
    }

    @Test
    public void the_json_for_the_phone_has_every_number_and_nulls_where_unknown() throws Exception {
        EnergyStore s = new EnergyStore();
        LocalDate today = NOW.toLocalDate();
        hour(s, "a", today, 14, 100);
        Map<String, Double> live = new HashMap<>();
        live.put("a", 100.0);
        JSONObject o = new JSONObject(stats(s, IDS, live, NOW).toJson());
        assertEquals(100.0, o.getDouble("nowW"), EPS);
        assertEquals(100.0, o.getDouble("todayWh"), EPS);
        assertEquals(24, o.getJSONArray("hourToday").length());
        assertEquals(24, o.getJSONArray("hourYesterday").length());
        assertEquals(30, o.getJSONArray("dayWh").length());
        assertEquals(30, o.getJSONArray("days").length());
        assertEquals(2, o.getJSONArray("devices").length());
        assertEquals("a", o.getJSONArray("devices").getJSONObject(0).getString("id"));
        assertEquals(14, o.getInt("peakHour"));
        assertTrue(o.isNull("yesterdayWh") == false);
        assertEquals(0.3, o.getDouble("price"), EPS);
        // nothing recorded: unknowns are null, not NaN (which is not JSON)
        JSONObject empty = new JSONObject(stats(new EnergyStore()).toJson());
        assertTrue(empty.isNull("baseLoadW"));
        assertTrue(empty.isNull("projectedMonthWh"));
        assertTrue(empty.isNull("busiestDay"));
        assertTrue(empty.isNull("firstDay"));
        assertTrue(empty.getJSONArray("devices").getJSONObject(0).isNull("nowW"));
    }

    @Test
    public void the_time_of_day_does_not_change_the_hour_totals() {
        EnergyStore s = new EnergyStore();
        LocalDate today = NOW.toLocalDate();
        hour(s, "a", today, 23, 40);
        for (ZonedDateTime now : new ZonedDateTime[]{ZonedDateTime.of(LocalDateTime.of(2026, 11, 10, 23, 59, 59), UTC),
                ZonedDateTime.of(LocalDateTime.of(2026, 11, 10, 0, 0, 0), UTC)}) {
            EnergyStats e = stats(s, IDS, new HashMap<>(), now);
            assertEquals(40.0, e.todayWh, EPS);
            assertEquals(now.getHour(), e.currentHour);
        }
    }
}
