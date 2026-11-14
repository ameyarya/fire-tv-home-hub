package com.homecontrol.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

public class SkyTest {
    // Los Angeles, in November (UTC-8): local 12:00 is 20:00 UTC
    private static final double LAT = 34.05, LON = -118.24;

    private static long utc(int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(2026, month - 1, day, hour, minute);
        return c.getTimeInMillis();
    }

    @Test
    public void the_sun_is_high_at_noon_and_below_the_horizon_at_midnight() {
        assertTrue(Sky.sun(LAT, LON, utc(11, 14, 20, 0))[0] > 30);
        assertTrue(Sky.sun(LAT, LON, utc(11, 14, 8, 0))[0] < -30);
    }

    @Test
    public void the_hour_angle_is_negative_in_the_morning_and_positive_in_the_afternoon() {
        assertTrue(Sky.sun(LAT, LON, utc(11, 14, 16, 0))[1] < 0);
        assertTrue(Sky.sun(LAT, LON, utc(11, 14, 23, 0))[1] > 0);
    }

    @Test
    public void the_scene_follows_the_sun() {
        assertEquals(Sky.NIGHT, Sky.scene(LAT, LON, utc(11, 14, 9, 0), 0, 1));        // 1 am
        assertEquals(Sky.DAWN, Sky.scene(LAT, LON, utc(11, 14, 14, 50), 0, 7));       // 6:50 am
        assertEquals(Sky.WATER, Sky.scene(LAT, LON, utc(11, 14, 20, 0), 0, 12));      // noon, clear
        assertEquals(Sky.GOLDEN, Sky.scene(LAT, LON, utc(11, 15, 0, 40), 0, 16));     // 4:40 pm
        assertEquals(Sky.NIGHT, Sky.scene(LAT, LON, utc(11, 15, 4, 0), 0, 20));       // 8 pm
    }

    @Test
    public void daylight_is_misty_when_it_is_grey_or_wet() {
        long noon = utc(11, 14, 20, 0);
        assertEquals(Sky.WATER, Sky.scene(LAT, LON, noon, 1, 12));
        assertEquals(Sky.FOG, Sky.scene(LAT, LON, noon, 3, 12));
        assertEquals(Sky.FOG, Sky.scene(LAT, LON, noon, 61, 12));
        assertEquals(Sky.FOG, Sky.scene(LAT, LON, noon, 95, 12));
        assertEquals(Sky.WATER, Sky.scene(LAT, LON, noon, -1, 12));
    }

    @Test
    public void without_a_place_it_goes_by_the_hour() {
        assertEquals(Sky.NIGHT, Sky.scene(Double.NaN, Double.NaN, 0, 0, 2));
        assertEquals(Sky.DAWN, Sky.scene(Double.NaN, Double.NaN, 0, 0, 6));
        assertEquals(Sky.WATER, Sky.scene(Double.NaN, Double.NaN, 0, 0, 12));
        assertEquals(Sky.FOG, Sky.scene(Double.NaN, Double.NaN, 0, 63, 12));
        assertEquals(Sky.GOLDEN, Sky.scene(Double.NaN, Double.NaN, 0, 0, 17));
        assertEquals(Sky.NIGHT, Sky.scene(Double.NaN, Double.NaN, 0, 0, 22));
    }

    @Test
    public void the_weather_picks_what_is_drawn_over_the_scene() {
        assertEquals(Sky.CLEAR, Sky.fx(0));
        assertEquals(Sky.CLEAR, Sky.fx(3));
        assertEquals(Sky.RAIN, Sky.fx(63));
        assertEquals(Sky.RAIN, Sky.fx(81));
        assertEquals(Sky.SNOW, Sky.fx(73));
        assertEquals(Sky.MIST, Sky.fx(45));
        assertEquals(Sky.STORM, Sky.fx(96));
        assertEquals(Sky.CLEAR, Sky.fx(-1));
    }

    private static int off(int... scenes) {
        int m = 0;
        for (int s : scenes) m |= 1 << s;
        return m;
    }

    @Test
    public void a_mood_with_every_scene_off_is_replaced_by_the_nearest_mood_that_is_on() {
        assertEquals(Sky.NIGHT, Sky.allow(Sky.NIGHT, 0));
        assertEquals(Sky.FOG, Sky.allow(Sky.NIGHT, off(Sky.NIGHT, Sky.AURORA, Sky.CABIN)));
        assertEquals(Sky.WATER, Sky.allow(Sky.NIGHT, off(Sky.NIGHT, Sky.AURORA, Sky.CABIN, Sky.FOG)));
        assertEquals(Sky.DAWN, Sky.allow(Sky.GOLDEN, off(Sky.GOLDEN, Sky.SAKURA)));
        assertEquals(Sky.GOLDEN, Sky.allow(Sky.FOG, off(Sky.FOG, Sky.THUNDER, Sky.WATER, Sky.AUTUMN, Sky.DAWN)));
        assertEquals(Sky.FOG, Sky.allow(Sky.FOG, 0, 0));
        assertEquals(Sky.THUNDER, Sky.allow(Sky.FOG, 0, 1));
        assertEquals(Sky.THUNDER, Sky.allow(Sky.FOG, off(Sky.FOG), 0));
        assertEquals(Sky.NIGHT, Sky.allow(Sky.WATER, Sky.ALL & ~(1 << Sky.NIGHT)));   // only night left
        assertEquals(Sky.WATER, Sky.allow(Sky.WATER, Sky.ALL));                       // none on: unchanged
    }

    @Test
    public void scenes_of_one_mood_take_turns_by_the_turn_and_skip_the_ones_that_are_off() {
        assertEquals(Sky.NIGHT, Sky.allow(Sky.NIGHT, 0, 0));
        assertEquals(Sky.AURORA, Sky.allow(Sky.NIGHT, 0, 1));
        assertEquals(Sky.CABIN, Sky.allow(Sky.NIGHT, 0, 2));
        assertEquals(Sky.NIGHT, Sky.allow(Sky.NIGHT, 0, 3));
        assertEquals(Sky.CABIN, Sky.allow(Sky.NIGHT, off(Sky.AURORA), 1));
        assertEquals(Sky.AUTUMN, Sky.allow(Sky.WATER, off(Sky.WATER), 7));
        assertEquals(Sky.DAWN, Sky.allow(Sky.DAWN, 0, 4));
    }

    @Test
    public void every_scene_belongs_to_one_of_the_five_moods() {
        assertEquals(Sky.COUNT, Sky.MOOD.length);
        for (int m : Sky.MOOD) assertTrue(m >= 0 && m <= Sky.WATER);
        for (int s = 0; s <= Sky.WATER; s++) assertEquals(s, Sky.MOOD[s]);
    }
}
