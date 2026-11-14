package com.homecontrol.tv;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * What the sky is doing, for the scene wallpaper (the launcher's home and Smart Home's screensaver both use this, so
 * they show the same scene): the sun's height where you are picks night, dawn, daylight or golden hour, and the
 * weather picks a clear or an overcast daylight scene and the rain, snow, fog or storm drawn over it. The idea is
 * couch-tv's "follow the sun" (github.com/Seagey/couch-tv, MIT; see CREDITS.md). Plain Java, tested on the JVM.
 */
final class Sky {
    /** Scene numbers: the order of the scenes in SceneView. The first five are also the moods the sun and weather pick. */
    static final int NIGHT = 0, GOLDEN = 1, FOG = 2, DAWN = 3, WATER = 4, AUTUMN = 5, SAKURA = 6, AURORA = 7, CABIN = 8, THUNDER = 9;
    static final int COUNT = 10, ALL = (1 << COUNT) - 1;
    /** The mood each scene belongs to: several scenes can share one, and they take turns. */
    static final int[] MOOD = {NIGHT, GOLDEN, FOG, DAWN, WATER, WATER, GOLDEN, NIGHT, NIGHT, FOG};
    /** What falls or hangs over the scene. */
    static final int CLEAR = 0, RAIN = 1, SNOW = 2, MIST = 3, STORM = 4;

    private Sky() {
    }

    /** Scenes turned off in the gallery are skipped: what is nearest in mood and still on is used instead. */
    private static final int[][] INSTEAD = {
            {FOG, WATER, GOLDEN, DAWN},      // night
            {DAWN, WATER, FOG, NIGHT},       // golden hour
            {WATER, GOLDEN, DAWN, NIGHT},    // misty
            {GOLDEN, WATER, FOG, NIGHT},     // dawn
            {FOG, GOLDEN, DAWN, NIGHT},      // clear day
    };

    /** The scenes of a mood that are still on (bit n of off = scene n turned off), in order. */
    private static int[] on(int mood, int off) {
        int[] r = new int[COUNT];
        int n = 0;
        for (int s = 0; s < COUNT; s++) if (MOOD[s] == mood && (off >> s & 1) == 0) r[n++] = s;
        return java.util.Arrays.copyOf(r, n);
    }

    /** The scene to show for a mood: one of its scenes that is on, the next one every turn (30 seconds); if none is, the nearest mood's. */
    static int allow(int mood, int off, long turn) {
        int[] c = on(mood, off);
        if (c.length > 0) return c[(int) Math.floorMod(turn, (long) c.length)];
        for (int m : INSTEAD[mood]) {
            c = on(m, off);
            if (c.length > 0) return c[(int) Math.floorMod(turn, (long) c.length)];
        }
        return mood;   // all off: not allowed by the gallery, but never leave without a scene
    }

    static int allow(int mood, int off) {
        return allow(mood, off, 0);
    }

    /** The sun's height above the horizon in degrees, and its hour angle (negative before noon). */
    static double[] sun(double lat, double lon, long nowMs) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(nowMs);
        int dayOfYear = c.get(Calendar.DAY_OF_YEAR);
        double b = Math.toRadians(360.0 / 365.25 * (dayOfYear - 81));
        double eot = 9.87 * Math.sin(2 * b) - 7.53 * Math.cos(b) - 1.5 * Math.sin(b);   // minutes
        double decl = Math.toRadians(23.45) * Math.sin(b);
        double utc = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0 + c.get(Calendar.SECOND) / 3600.0;
        double solar = ((utc + lon / 15.0 + eot / 60.0) % 24 + 24) % 24;
        double hourAngle = Math.toRadians((solar - 12) * 15);
        double latRad = Math.toRadians(lat);
        double sin = Math.sin(latRad) * Math.sin(decl) + Math.cos(latRad) * Math.cos(decl) * Math.cos(hourAngle);
        return new double[]{Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, sin)))), Math.toDegrees(hourAngle)};
    }

    /** Overcast, rain, snow, fog or storm: the daylight scene is the misty one. */
    static boolean grey(int code) {
        switch (code) {
            case 3: case 45: case 48: case 51: case 53: case 55: case 56: case 57: case 61: case 63: case 65:
            case 66: case 67: case 71: case 73: case 75: case 77: case 80: case 81: case 82: case 85: case 86:
            case 95: case 96: case 99:
                return true;
            default:
                return false;
        }
    }

    /**
     * The scene now. With a place (lat, lon not NaN) it follows the sun: night below -6 degrees, dawn or golden hour
     * below 10 (morning or evening), else daylight, misty when the weather code is grey (-1: unknown). Without one it
     * goes by the clock's hour.
     */
    static int scene(double lat, double lon, long nowMs, int code, int hour) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            if (hour >= 5 && hour < 8) return DAWN;
            if (hour >= 8 && hour < 16) return grey(code) ? FOG : WATER;
            if (hour >= 16 && hour < 19) return GOLDEN;
            return NIGHT;
        }
        double[] s = sun(lat, lon, nowMs);
        if (s[0] < -6) return NIGHT;
        if (s[0] < 10) return s[1] < 0 ? DAWN : GOLDEN;
        return grey(code) ? FOG : WATER;
    }

    /** What to draw over the scene for a weather code. */
    static int fx(int code) {
        switch (code) {
            case 51: case 53: case 55: case 56: case 57: case 61: case 63: case 65: case 66: case 67: case 80: case 81: case 82:
                return RAIN;
            case 71: case 73: case 75: case 77: case 85: case 86:
                return SNOW;
            case 45: case 48:
                return MIST;
            case 95: case 96: case 99:
                return STORM;
            default:
                return CLEAR;
        }
    }
}
