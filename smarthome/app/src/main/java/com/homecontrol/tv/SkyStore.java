package com.homecontrol.tv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.io.IOException;
import java.util.Calendar;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The sky for the scene wallpaper: your place (Settings → Location) and the current weather code from Open-Meteo.
 * The screensaver reads it here; every half hour (while the phone remote's service runs) it also sends both to the
 * launcher, whose home screen follows the same sun and weather (see Sky). Only the place's coordinates leave the TV,
 * to Open-Meteo, as for the screensaver's weather.
 */
final class SkyStore {
    private static final long STALE_MS = 3 * 60 * 60 * 1000L;
    private static ScheduledExecutorService timer;
    /** In Sky's scene order: night, golden hour, misty, dawn, clear day. */
    static final int COUNT = 5, ALL = 31;
    static final int[] PICTURES = {R.drawable.scene_night, R.drawable.scene_golden, R.drawable.scene_fog,
            R.drawable.scene_dawn, R.drawable.scene_water};
    private static final byte[][] thumbs = new byte[COUNT][], backdrops = new byte[COUNT][];

    private SkyStore() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("sky", Context.MODE_PRIVATE);
    }

    /** Remembers the weather code a fetch found. */
    static void remember(Context c, int code) {
        prefs(c).edit().putInt("code", code).putLong("at", System.currentTimeMillis()).apply();
    }

    /** The weather code, or -1 when unknown or old. */
    static int code(Context c) {
        SharedPreferences p = prefs(c);
        return System.currentTimeMillis() - p.getLong("at", 0) > STALE_MS ? -1 : p.getInt("code", -1);
    }

    /** The wallpapers turned off in the gallery (bit n = scene n). */
    static int off(Context c) {
        return prefs(c).getInt("off", 0) & ALL;
    }

    /** Turns a wallpaper on or off; false when that would leave none on. */
    static synchronized boolean setOn(Context c, int scene, boolean on) {
        if (scene < 0 || scene >= COUNT) return false;
        int off = on ? off(c) & ~(1 << scene) : off(c) | 1 << scene;
        if (off == ALL) return false;
        prefs(c).edit().putInt("off", off).apply();
        refresh(c);   // the launcher hears at once
        return true;
    }

    static int scene(Context c) {
        Home h = Storage.get(c);
        return Sky.allow(Sky.scene(h.latitude, h.longitude, System.currentTimeMillis(), code(c),
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY)), off(c));
    }

    /** A JPEG of a scene for the phone page: small for the gallery, bigger (big) for the page's backdrop. */
    static synchronized byte[] thumb(Context c, int scene, boolean big) {
        if (scene < 0 || scene >= COUNT) return null;
        byte[][] cache = big ? backdrops : thumbs;
        if (cache[scene] == null) {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = big ? 2 : 4;
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeResource(c.getResources(), PICTURES[scene], o);
            if (b == null) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 76, out);
            b.recycle();
            cache[scene] = out.toByteArray();
        }
        return cache[scene];
    }

    /** Fetches the weather every half hour and sends it to the launcher; safe to call again. */
    static synchronized void start(Context c) {
        if (timer != null) return;
        final Context app = c.getApplicationContext();
        timer = Executors.newSingleThreadScheduledExecutor();
        timer.scheduleWithFixedDelay(() -> sync(app), 0, 30, TimeUnit.MINUTES);
    }

    /** After the place changed: look again now. */
    static void refresh(Context c) {
        final Context app = c.getApplicationContext();
        new Thread(() -> sync(app), "sky-sync").start();
    }

    private static void sync(Context c) {
        try {
            Intent i = new Intent("com.gathin.tvhome.SKY").setPackage("com.gathin.tvhome").putExtra("off", off(c));
            Home h = Storage.get(c);
            if (!Double.isNaN(h.latitude) && !Double.isNaN(h.longitude)) {
                try {
                    Weather.Forecast f = Weather.fetch(h.latitude, h.longitude, false);
                    if (f.code >= 0) remember(c, f.code);
                    i.putExtra("lat", h.latitude).putExtra("lon", h.longitude).putExtra("code", f.code);
                } catch (IOException e) {   // offline: the launcher keeps its last weather
                }
            }
            c.sendBroadcast(i);   // the launcher's receiver itself demands the signature permission from the sender
        } catch (RuntimeException ignored) {
        }
    }
}
