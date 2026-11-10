package com.homecontrol.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the energy history (EnergyStore) in the app's private storage and feeds it: every reading the Home screen
 * and the device screen take goes in, and while the phone remote's service runs a sampler asks every plug once a
 * minute, so the history goes on with the TV off. The dashboard (EnergyActivity, the phone page) reads the numbers
 * from here (EnergyStats). Readings and totals never leave the TV.
 */
final class EnergyLog {
    private static final String PREFS = "energy", KEY = "history.json";
    private static final long SAVE_EVERY_MS = 120_000, SAMPLE_EVERY_S = 60;
    /** A reading older than this is not "now". */
    static final long LIVE_MS = 180_000;

    private static EnergyStore store;
    private static final Map<String, double[]> live = new HashMap<>();   // device -> {time ms, watts}
    private static long savedAt;
    private static ScheduledExecutorService sampler;

    private EnergyLog() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized EnergyStore store(Context c) {
        if (store == null) store = EnergyStore.fromJson(prefs(c).getString(KEY, ""));
        return store;
    }

    /** A plug's reading, in watts; NaN when it did not answer or has no meter. Any thread. */
    static void record(Context c, String device, double watts) {
        long now = System.currentTimeMillis();
        store(c).record(device, now, watts, ZoneId.systemDefault());
        synchronized (live) {
            if (Double.isNaN(watts)) live.remove(device);
            else live.put(device, new double[]{now, watts});
        }
        saveSoon(c, now);
    }

    private static synchronized void saveSoon(Context c, long now) {
        if (now - savedAt >= SAVE_EVERY_MS) flush(c);
    }

    /** Writes the history now (and drops what is older than the keep time). */
    static synchronized void flush(Context c) {
        EnergyStore s = store(c);
        s.prune(LocalDate.now());
        prefs(c).edit().putString(KEY, s.toJson()).apply();
        savedAt = System.currentTimeMillis();
    }

    /** Forgets everything that was recorded (Settings; the dashboard starts again). */
    static synchronized void reset(Context c) {
        store = new EnergyStore();
        synchronized (live) {
            live.clear();
        }
        prefs(c).edit().remove(KEY).apply();
    }

    /** The numbers for the dashboard. The Home is passed in so the caller decides which thread reads it. */
    static EnergyStats stats(Context c, Home h) {
        List<String> ids = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        for (Home.Device d : h.devices) {
            if (!d.hasSwitch) continue;
            ids.add(d.id);
            names.put(d.id, d.name);
        }
        Map<String, Double> now = new HashMap<>();
        long t = System.currentTimeMillis();
        synchronized (live) {
            for (Map.Entry<String, double[]> e : live.entrySet()) {
                if (t - (long) e.getValue()[0] <= LIVE_MS) now.put(e.getKey(), e.getValue()[1]);
            }
        }
        return EnergyStats.compute(store(c), ids, names, now, h.price, ZonedDateTime.now());
    }

    /** The dashboard's numbers as JSON, for the phone page. */
    static String json(Context c) throws JSONException {
        return HomeApi.onMain(() -> {
            try {
                return stats(c, Storage.get(c)).toJson();
            } catch (JSONException e) {
                return "{}";
            }
        });
    }

    /** Asks every plug for its power and records it. Blocking: not on the main thread. */
    static void sampleNow(Context c) {
        List<Home.Device> devices = HomeApi.onMain(() -> {
            List<Home.Device> list = new ArrayList<>();
            for (Home.Device d : Storage.get(c).devices) if (d.hasSwitch) list.add(d);
            return list;
        });
        if (devices == null) return;
        for (Home.Device d : devices) {
            try {
                record(c, d.id, Devices.status(d).watts);
            } catch (IOException e) {
                record(c, d.id, Double.NaN);
            }
        }
    }

    /** The once-a-minute sampler: started with the phone remote's service. */
    static synchronized void startSampler(Context c) {
        if (sampler != null) return;
        Context app = c.getApplicationContext();
        sampler = Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleWithFixedDelay(() -> {
            try {
                sampleNow(app);
            } catch (RuntimeException ignored) {   // one bad round must not stop the sampler
            }
        }, 5, SAMPLE_EVERY_S, TimeUnit.SECONDS);
    }

    static synchronized void stopSampler(Context c) {
        if (sampler != null) {
            sampler.shutdownNow();
            sampler = null;
        }
        flush(c);
    }
}
