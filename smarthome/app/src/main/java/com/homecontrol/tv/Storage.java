package com.homecontrol.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * The one {@link Home} of the app, kept in private preferences (camera passwords included, which never
 * leave the TV). Main thread only; {@link #changed} tells the screen to redraw after any edit, including
 * one made over adb by {@link ControlReceiver}.
 */
final class Storage {
    private static final String PREFS = "home";
    private static final String KEY = "home.json";
    private static Home home;
    private static final List<Runnable> listeners = new ArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Storage() {
    }

    static Home get(Context ctx) {
        if (home == null) home = Home.fromJson(prefs(ctx).getString(KEY, null));
        return home;
    }

    static void save(Context ctx) {
        prefs(ctx).edit().putString(KEY, get(ctx).toJson()).apply();
        Scheduler.reschedule(ctx);
        for (Runnable r : new ArrayList<>(listeners)) main.post(r);
    }

    private static final String BACKUP = "backup.json";

    /**
     * Keeps a copy of the Home inside the app, for the UI tests: they save the owner's setup here, work on
     * an empty one and put it back. Camera passwords never leave the TV this way. Refuses to overwrite a
     * backup that was never restored (a test run that was cut short).
     */
    static boolean backup(Context ctx) {
        if (prefs(ctx).contains(BACKUP)) return false;
        // The activity history too: test runs would otherwise leave their switching in it.
        prefs(ctx).edit().putString(BACKUP, get(ctx).toJson()).putString(BACKUP + ".activity", ActivityLog.all(ctx)).commit();
        return true;
    }

    static boolean restoreBackup(Context ctx) {
        String json = prefs(ctx).getString(BACKUP, null);
        if (json == null) return false;
        home = Home.fromJson(json);
        save(ctx);
        ActivityLog.replace(ctx, prefs(ctx).getString(BACKUP + ".activity", ""));
        prefs(ctx).edit().remove(BACKUP).remove(BACKUP + ".activity").commit();
        return true;
    }

    static boolean hasBackup(Context ctx) {
        return prefs(ctx).contains(BACKUP);
    }

    static void reset(Context ctx) {
        home = new Home();
        save(ctx);
    }

    static void listen(Runnable r) {
        listeners.add(r);
    }

    static void unlisten(Runnable r) {
        listeners.remove(r);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
