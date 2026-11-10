package com.homecontrol.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

public class EnergyStoreTest {
    private static final ZoneId LA = ZoneId.of("America/Los_Angeles");
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final double EPS = 1e-6;

    private static long ms(ZoneId zone, int y, int mo, int d, int h, int mi, int s) {
        return LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli();
    }

    private static double sum(double[] a) {
        double t = 0;
        for (double v : a) t += v;
        return t;
    }

    @Test
    public void a_steady_draw_over_a_minute_is_its_watt_minutes() {
        EnergyStore s = new EnergyStore();
        long t = ms(UTC, 2026, 11, 10, 10, 0, 0);
        s.record("p", t, 60, UTC);
        s.record("p", t + 60_000, 60, UTC);
        assertEquals(1.0, s.hours(LocalDate.of(2026, 11, 10), "p")[10], EPS);   // 60 W for 1 minute = 1 Wh
        assertEquals(60.0, s.seconds(LocalDate.of(2026, 11, 10), "p")[10], EPS);
    }

    @Test
    public void the_energy_between_two_readings_uses_their_average() {
        EnergyStore s = new EnergyStore();
        long t = ms(UTC, 2026, 11, 10, 10, 0, 0);
        s.record("p", t, 100, UTC);
        s.record("p", t + 300_000, 200, UTC);   // 5 minutes at an average of 150 W
        assertEquals(12.5, s.dayWh(LocalDate.of(2026, 11, 10), "p"), EPS);
    }

    @Test
    public void the_first_reading_alone_records_nothing() {
        EnergyStore s = new EnergyStore();
        s.record("p", ms(UTC, 2026, 11, 10, 10, 0, 0), 500, UTC);
        assertTrue(s.isEmpty());
        assertNull(s.firstDay());
    }

    @Test
    public void a_draw_across_an_hour_boundary_is_split_between_the_hours() {
        EnergyStore s = new EnergyStore();
        s.add("p", ms(UTC, 2026, 11, 10, 10, 58, 0), ms(UTC, 2026, 11, 10, 11, 2, 0), 60, UTC);
        double[] h = s.hours(LocalDate.of(2026, 11, 10), "p");
        assertEquals(2.0, h[10], EPS);
        assertEquals(2.0, h[11], EPS);
        double[] c = s.seconds(LocalDate.of(2026, 11, 10), "p");
        assertEquals(120.0, c[10], EPS);
        assertEquals(120.0, c[11], EPS);
    }

    @Test
    public void a_draw_across_midnight_is_split_between_the_days() {
        EnergyStore s = new EnergyStore();
        s.add("p", ms(UTC, 2026, 11, 10, 23, 58, 0), ms(UTC, 2026, 11, 11, 0, 2, 0), 120, UTC);
        assertEquals(4.0, s.hours(LocalDate.of(2026, 11, 10), "p")[23], EPS);
        assertEquals(4.0, s.hours(LocalDate.of(2026, 11, 11), "p")[0], EPS);
        assertEquals(LocalDate.of(2026, 11, 10), s.firstDay());
    }

    @Test
    public void a_long_draw_fills_every_hour_it_spans() {
        EnergyStore s = new EnergyStore();
        s.add("p", ms(UTC, 2026, 11, 10, 0, 0, 0), ms(UTC, 2026, 11, 10, 6, 0, 0), 10, UTC);   // 6 hours at 10 W
        double[] h = s.hours(LocalDate.of(2026, 11, 10), "p");
        for (int i = 0; i < 6; i++) assertEquals(10.0, h[i], EPS);
        assertEquals(0.0, h[6], EPS);
        assertEquals(60.0, sum(h), EPS);
    }

    @Test
    public void readings_further_apart_than_ten_minutes_are_not_joined() {
        EnergyStore s = new EnergyStore();
        long t = ms(UTC, 2026, 11, 10, 10, 0, 0);
        s.record("p", t, 100, UTC);
        s.record("p", t + EnergyStore.MAX_GAP_MS + 1, 100, UTC);
        assertTrue(s.isEmpty());
        // ten minutes exactly is still joined
        s.record("q", t, 60, UTC);
        s.record("q", t + EnergyStore.MAX_GAP_MS, 60, UTC);
        assertEquals(10.0, s.dayWh(LocalDate.of(2026, 11, 10), "q"), EPS);
    }

    @Test
    public void a_reading_after_a_long_gap_starts_a_fresh_run() {
        EnergyStore s = new EnergyStore();
        long t = ms(UTC, 2026, 11, 10, 10, 0, 0);
        s.record("p", t, 60, UTC);
        s.record("p", t + 3_600_000, 60, UTC);               // an hour later: not joined, but now the latest
        s.record("p", t + 3_600_000 + 60_000, 60, UTC);      // a minute after that: joined
        assertEquals(1.0, s.dayWh(LocalDate.of(2026, 11, 10), "p"), EPS);
    }

    @Test
    public void no_reading_forgets_the_previous_one() {
        EnergyStore s = new EnergyStore();
        long t = ms(UTC, 2026, 11, 10, 10, 0, 0);
        s.record("p", t, 100, UTC);
        s.record("p", t + 30_000, Double.NaN, UTC);
        s.record("p", t + 60_000, 100, UTC);
        assertTrue(s.isEmpty());
    }

    @Test
    public void nonsense_readings_are_treated_as_no_reading() {
        EnergyStore s = new EnergyStore();
        long t = ms(UTC, 2026, 11, 10, 10, 0, 0);
        s.record("p", t, 100, UTC);
        s.record("p", t + 10_000, -5, UTC);
        s.record("p", t + 20_000, Double.POSITIVE_INFINITY, UTC);
        s.record("p", t + 30_000, 100, UTC);
        assertTrue(s.isEmpty());
        s.add("p", t, t + 1000, -1, UTC);
        s.add("p", t, t + 1000, Double.NaN, UTC);
        assertTrue(s.isEmpty());
    }

    @Test
    public void the_same_moment_twice_adds_nothing_and_a_clock_going_back_is_ignored() {
        EnergyStore s = new EnergyStore();
        long t = ms(UTC, 2026, 11, 10, 10, 0, 0);
        s.record("p", t, 60, UTC);
        s.record("p", t, 60, UTC);
        assertTrue(s.isEmpty());
        s.record("p", t - 60_000, 60, UTC);   // earlier than the latest reading
        assertTrue(s.isEmpty());
        s.record("p", t + 60_000, 60, UTC);   // joins from the latest (t), not from the earlier one
        assertEquals(1.0, s.dayWh(LocalDate.of(2026, 11, 10), "p"), EPS);
    }

    @Test
    public void plugs_are_kept_apart() {
        EnergyStore s = new EnergyStore();
        long t = ms(UTC, 2026, 11, 10, 10, 0, 0);
        for (String id : new String[]{"a", "b"}) {
            s.record(id, t, 60, UTC);
            s.record(id, t + 60_000, "a".equals(id) ? 60 : 120, UTC);
        }
        LocalDate d = LocalDate.of(2026, 11, 10);
        assertEquals(1.0, s.dayWh(d, "a"), EPS);
        assertEquals(1.5, s.dayWh(d, "b"), EPS);
        assertEquals(0.0, s.dayWh(d, "nobody"), EPS);
        assertEquals(0.0, sum(s.hours(d.plusDays(1), "a")), EPS);
    }

    @Test
    public void the_arrays_handed_out_are_copies() {
        EnergyStore s = new EnergyStore();
        long t = ms(UTC, 2026, 11, 10, 10, 0, 0);
        s.add("p", t, t + 3_600_000, 10, UTC);
        double[] h = s.hours(LocalDate.of(2026, 11, 10), "p");
        h[10] = 999;
        assertEquals(10.0, s.hours(LocalDate.of(2026, 11, 10), "p")[10], EPS);
    }

    @Test
    public void the_hour_the_clock_springs_forward_loses_nothing() {
        // 2026-03-08 in Los Angeles: 02:00 does not exist. 01:30 to 03:30 local is one real hour.
        EnergyStore s = new EnergyStore();
        s.add("p", ms(LA, 2026, 3, 8, 1, 30, 0), ms(LA, 2026, 3, 8, 3, 30, 0), 100, LA);
        LocalDate d = LocalDate.of(2026, 3, 8);
        assertEquals(100.0, s.dayWh(d, "p"), EPS);   // 1 real hour at 100 W
        assertEquals(50.0, s.hours(d, "p")[1], EPS);
        assertEquals(0.0, s.hours(d, "p")[2], EPS);
        assertEquals(50.0, s.hours(d, "p")[3], EPS);
    }

    @Test
    public void the_hour_the_clock_falls_back_counts_both_passes() {
        // 2026-11-01 in Los Angeles: 01:00-02:00 happens twice. 00:30 PDT to 02:30 PST is three real hours.
        EnergyStore s = new EnergyStore();
        s.add("p", ms(LA, 2026, 11, 1, 0, 30, 0) , LocalDateTime.of(2026, 11, 1, 2, 30).atZone(LA).toInstant().toEpochMilli(), 100, LA);
        LocalDate d = LocalDate.of(2026, 11, 1);
        assertEquals(300.0, s.dayWh(d, "p"), EPS);
        assertEquals(200.0, s.hours(d, "p")[1], EPS);
    }

    @Test
    public void old_days_are_dropped_by_prune() {
        EnergyStore s = new EnergyStore();
        LocalDate today = LocalDate.of(2026, 11, 10);
        for (int back : new int[]{0, 1, EnergyStore.KEEP_DAYS, EnergyStore.KEEP_DAYS + 1, 300}) {
            long t = today.minusDays(back).atTime(12, 0).atZone(UTC).toInstant().toEpochMilli();
            s.add("p", t, t + 60_000, 60, UTC);
        }
        s.prune(today);
        assertEquals(today.minusDays(EnergyStore.KEEP_DAYS), s.firstDay());
        assertTrue(s.dayWh(today.minusDays(EnergyStore.KEEP_DAYS), "p") > 0);
        assertEquals(0.0, s.dayWh(today.minusDays(EnergyStore.KEEP_DAYS + 1), "p"), EPS);
        assertEquals(0.0, s.dayWh(today.minusDays(300), "p"), EPS);
        assertTrue(s.dayWh(today, "p") > 0);
    }

    @Test
    public void pruning_an_empty_store_is_fine() {
        EnergyStore s = new EnergyStore();
        s.prune(LocalDate.of(2026, 1, 1));
        assertTrue(s.isEmpty());
    }

    @Test
    public void forgetting_a_plug_leaves_the_others() {
        EnergyStore s = new EnergyStore();
        long t = ms(UTC, 2026, 11, 10, 10, 0, 0);
        s.add("a", t, t + 3_600_000, 10, UTC);
        s.add("b", t, t + 3_600_000, 20, UTC);
        s.forget("a");
        LocalDate d = LocalDate.of(2026, 11, 10);
        assertEquals(0.0, s.dayWh(d, "a"), EPS);
        assertEquals(20.0, s.dayWh(d, "b"), EPS);
        s.forget("b");
        assertTrue(s.isEmpty());
        s.forget("never-there");
    }

    @Test
    public void saved_and_loaded_it_is_the_same() {
        EnergyStore s = new EnergyStore();
        long t = ms(UTC, 2026, 11, 10, 10, 20, 0);
        s.add("a", t, t + 5_400_000, 123.456, UTC);
        s.add("b", t + 86_400_000, t + 86_400_000 + 1_000_000, 7, UTC);
        EnergyStore back = EnergyStore.fromJson(s.toJson());
        for (LocalDate d : new LocalDate[]{LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 11)}) {
            for (String id : new String[]{"a", "b"}) {
                for (int h = 0; h < 24; h++) {
                    assertEquals(s.hours(d, id)[h], back.hours(d, id)[h], 0.001);
                    assertEquals(Math.round(s.seconds(d, id)[h]), back.seconds(d, id)[h], 0.5);
                }
            }
        }
        assertEquals(s.firstDay(), back.firstDay());
    }

    @Test
    public void a_saved_fall_back_hour_keeps_its_energy() {
        EnergyStore s = new EnergyStore();
        s.add("p", ms(LA, 2026, 11, 1, 0, 30, 0), LocalDateTime.of(2026, 11, 1, 2, 30).atZone(LA).toInstant().toEpochMilli(), 100, LA);
        EnergyStore back = EnergyStore.fromJson(s.toJson());
        assertEquals(200.0, back.hours(LocalDate.of(2026, 11, 1), "p")[1], 0.001);
        assertTrue(back.seconds(LocalDate.of(2026, 11, 1), "p")[1] <= 3600);   // a measured hour is never more than an hour
    }

    @Test
    public void damaged_saves_are_survived() {
        for (String bad : new String[]{null, "", "   ", "not json", "[]", "{\"x\":1}", "{\"2026-11-10\":5}", "{\"2026-13-45\":{\"a\":{\"wh\":[1]}}}",
                "{\"2026-11-10\":{\"a\":5}}", "{\"2026-11-10\":{\"a\":{\"s\":[1,2]}}}"}) {
            EnergyStore s = EnergyStore.fromJson(bad);
            assertTrue("bad save: " + bad, s.isEmpty());
        }
    }

    @Test
    public void odd_numbers_in_a_save_are_cleaned() {
        EnergyStore s = EnergyStore.fromJson("{\"2026-11-10\":{\"a\":{\"wh\":[-5,\"x\",3,null],\"s\":[99999,-1,10]}}}");
        double[] h = s.hours(LocalDate.of(2026, 11, 10), "a");
        assertEquals(0.0, h[0], EPS);   // negative energy is dropped
        assertEquals(0.0, h[1], EPS);
        assertEquals(3.0, h[2], EPS);
        assertEquals(0.0, h[20], EPS);  // short arrays are padded with zeros
        double[] c = s.seconds(LocalDate.of(2026, 11, 10), "a");
        assertEquals(3600.0, c[0], EPS);   // never more than an hour measured
        assertEquals(0.0, c[1], EPS);
        assertEquals(10.0, c[2], EPS);
    }

    @Test
    public void readings_from_several_threads_add_up() throws Exception {
        EnergyStore s = new EnergyStore();
        long t = ms(UTC, 2026, 11, 10, 10, 0, 0);
        Thread[] threads = new Thread[4];
        for (int i = 0; i < threads.length; i++) {
            final String id = "p" + i;
            threads[i] = new Thread(() -> {
                for (int k = 0; k <= 100; k++) s.record(id, t + k * 1000L, 360, UTC);   // 100 s at 360 W = 10 Wh
            });
            threads[i].start();
        }
        for (Thread th : threads) th.join();
        for (int i = 0; i < 4; i++) assertEquals(10.0, s.dayWh(LocalDate.of(2026, 11, 10), "p" + i), 1e-6);
    }

    @Test
    public void an_empty_store_says_so() {
        EnergyStore s = new EnergyStore();
        assertTrue(s.isEmpty());
        assertFalse(EnergyStore.fromJson(s.toJson()).firstDay() != null);
        assertEquals("{}", s.toJson());
    }
}
