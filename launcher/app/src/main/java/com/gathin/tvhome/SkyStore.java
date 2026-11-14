package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

/**
 * Where you are and the weather there, as Smart Home last sent it (SkyReceiver); the home screen's scene follows
 * from them (see Sky). With nothing sent yet, or nothing for three hours, the clock's hour alone decides.
 */
final class SkyStore {
    private static final long STALE_MS = 3 * 60 * 60 * 1000L;

    private SkyStore() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("sky", Context.MODE_PRIVATE);
    }

    /** The wallpapers turned off in the gallery (bit n = scene n), from Smart Home. */
    static void saveOff(Context c, int off) {
        prefs(c).edit().putInt("off", off).apply();
    }

    static void save(Context c, double lat, double lon, int code) {
        prefs(c).edit().putString("lat", String.valueOf(lat)).putString("lon", String.valueOf(lon))
                .putInt("code", code).putLong("at", System.currentTimeMillis()).apply();
    }

    private static double number(Context c, String key) {
        try {
            return Double.parseDouble(prefs(c).getString(key, ""));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** The weather code, or -1 when unknown or old. */
    static int code(Context c) {
        SharedPreferences p = prefs(c);
        return System.currentTimeMillis() - p.getLong("at", 0) > STALE_MS ? -1 : p.getInt("code", -1);
    }

    static int scene(Context c) {
        return Sky.allow(Sky.scene(number(c, "lat"), number(c, "lon"), System.currentTimeMillis(), code(c),
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY)), prefs(c).getInt("off", 0));
    }

    static int fx(Context c) {
        return Sky.fx(code(c));
    }
}
