package com.homecontrol.tv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs automations. Keeps one alarm set for the next timed automation or "turn off after" step, and
 * runs what is due when it fires. TV on/off automations run when the clean launcher's FireHomeWatcher
 * (always bound, as an accessibility service) reports the screen turning on or off with TV_EVENT.
 * Re-armed after every change to the Home (Storage.save), at boot, after an app update and when the clock
 * or time zone changes. Timed runs missed while the TV was off are skipped, except within Schedules.GRACE.
 *
 * TV_EVENT is accepted from any app; the worst a stray one does is run an automation the owner set for
 * the TV turning on or off.
 */
public class Scheduler extends BroadcastReceiver {

    static final String FIRE = "com.homecontrol.tv.SCHEDULE_FIRE";
    static final String TV_EVENT = "com.homecontrol.tv.TV_EVENT";
    private static final String TAG = "SmartHome.Scheduler";
    private static final String PREFS = "scheduler";
    private static final String LAST_CHECK = "last_check";

    static void reschedule(Context ctx) {
        Context app = ctx.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(app, 0, new Intent(app, Scheduler.class).setAction(FIRE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        ZonedDateTime next = Schedules.soonest(Storage.get(app), ZonedDateTime.now(ZoneId.systemDefault()));
        if (next == null) {
            am.cancel(pi);
            return;
        }
        long at = next.toInstant().toEpochMilli();
        Log.i(TAG, "armed for " + next);
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);   // within a few minutes
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    /** One thing to do: an automation's actions, or a "turn off after" step (schedule null). */
    private static final class Job {
        final Home.Schedule schedule;
        final List<Home.Action> actions;
        final String label;
        final List<Home.Action> done = new ArrayList<>();

        Job(Home.Schedule schedule, List<Home.Action> actions, String label) {
            this.schedule = schedule;
            this.actions = actions;
            this.label = label;
        }
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Context app = ctx.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        String action = intent.getAction() == null ? "" : intent.getAction();
        Home home = Storage.get(app);
        List<Job> jobs = new ArrayList<>();
        if (DnsRescue.CHECK.equals(action)) {
            DnsRescue.check(app);
            return;
        }
        if (TV_EVENT.equals(action) || Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            RemoteService.start(app);   // the phone remote, if it's on
            if (!TV_EVENT.equals(action) || intent.getBooleanExtra("on", false)) DnsRescue.soon(app);   // power-cut trap
        }
        if (TV_EVENT.equals(action)) {
            boolean on = intent.getBooleanExtra("on", false);
            for (Home.Schedule s : home.schedules) {
                if (Schedules.matchesTvEvent(s, now, on)) jobs.add(new Job(s, actionsOf(home, s), s.name));
            }
            Log.i(TAG, "TV turned " + (on ? "on" : "off") + ", " + jobs.size() + " automation(s)");
        } else if (FIRE.equals(action)) {
            long last = prefs.getLong(LAST_CHECK, now.toInstant().toEpochMilli() - Schedules.GRACE.toMillis());
            ZonedDateTime from = Instant.ofEpochMilli(last).atZone(now.getZone());
            for (Home.Schedule s : Schedules.due(home, from, now)) jobs.add(new Job(s, actionsOf(home, s), s.name));
            for (Home.PendingOff p : Schedules.dueOffs(home, now.toInstant().toEpochMilli())) {
                Home.Device d = home.device(p.deviceId);
                home.pendingOffs.remove(p);
                if (d != null) {
                    List<Home.Action> off = new ArrayList<>();
                    off.add(new Home.Action(d.id, false));
                    jobs.add(new Job(null, off, "Turn off after: " + d.name));
                }
            }
            prefs.edit().putLong(LAST_CHECK, now.toInstant().toEpochMilli()).apply();
        } else {
            // Boot, update, clock or zone change: start counting from now (missed runs are not replayed).
            prefs.edit().putLong(LAST_CHECK, now.toInstant().toEpochMilli()).apply();
            reschedule(app);
            return;
        }
        if (jobs.isEmpty()) {
            if (FIRE.equals(action)) Storage.save(app);   // also re-arms the alarm
            return;
        }
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        String context = "screen " + (pm.isInteractive() ? "on" : "off") + ", network " + (online(app) ? "up" : "down");
        // Snapshot the devices on the main thread; the network work happens off it.
        List<List<Home.Device>> devices = new ArrayList<>();
        for (Job j : jobs) {
            List<Home.Device> ds = new ArrayList<>();
            for (Home.Action a : j.actions) ds.add(home.device(a.deviceId));
            devices.add(ds);
        }
        PendingResult result = goAsync();
        new Thread(() -> {
            for (int k = 0; k < jobs.size(); k++) {
                Job j = jobs.get(k);
                for (int i = 0; i < j.actions.size(); i++) {
                    Home.Device d = devices.get(k).get(i);
                    if (d != null && setWithRetry(d, j.actions.get(i).on)) j.done.add(j.actions.get(i));
                }
                ActivityLog.add(app, j.label + ": " + context + ", " + j.done.size() + "/" + j.actions.size() + " done");
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                Home h = Storage.get(app);
                long t = System.currentTimeMillis();
                for (Job j : jobs) if (j.schedule != null) Schedules.scheduleOffs(h, j.schedule, j.done, t);
                Storage.save(app);   // keeps pending "turn off after" steps, redraws, re-arms
                result.finish();
            });
        }).start();
    }

    private static boolean setWithRetry(Home.Device d, boolean on) {
        for (int attempt = 0; attempt < 2; attempt++) {   // one retry: Wi-Fi may be waking up
            try {
                Devices.set(d, on);
                return true;
            } catch (IOException e) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    private static boolean online(Context app) {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        android.net.Network n = cm.getActiveNetwork();
        android.net.NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
        return c != null && c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    static List<Home.Action> actionsOf(Home h, Home.Schedule s) {
        if (!s.sceneId.isEmpty()) {
            Home.Scene scene = h.scene(s.sceneId);
            return scene != null ? scene.actions : new ArrayList<>();
        }
        return s.actions;
    }
}
