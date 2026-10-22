package com.homecontrol.tv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Gets the TV out of the power-cut trap. After a power cut the TV can start with its clock months behind;
 * Private DNS (AdGuard, set in the debloat) then fails its secure check, so nothing resolves, so Amazon's
 * time service can't be reached to fix the clock, and the TV stays offline.
 *
 * When Private DNS is broken, this switches it to Automatic for a moment: the network revalidates, Amazon's
 * time service sets the clock (seconds, tested on this TV), and once the clock has jumped forward, AdGuard
 * goes back on. If AdGuard still fails with the right time (AdGuard itself down), it stays on Automatic and
 * tries again in an hour. Each step is written to Activity.
 *
 * Changing Private DNS needs WRITE_SECURE_SETTINGS, granted once by install-smarthome.sh over adb.
 */
final class DnsRescue {

    static final String CHECK = "com.homecontrol.tv.DNS_CHECK";
    private static final String PREFS = "dns_rescue", RESCUED = "rescued", WALL = "wall", UP = "elapsed", FAILS = "fails",
            RESTORED = "restored";
    private static final long RETRY_MS = 60 * 60_000;
    private static final String MODE = "private_dns_mode", HOSTNAME = "hostname", AUTOMATIC = "opportunistic";

    private DnsRescue() {
    }

    /** Checks after boot and when the TV turns on: validation takes a little while to fail. */
    static void soon(Context c) {
        later(c, 45_000);
        later(c, 180_000);
    }

    static void later(Context c, long ms) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        int code = (int) (ms / 1000);   // separate alarms for separate delays
        PendingIntent pi = PendingIntent.getBroadcast(c, 9000 + code,
                new Intent(CHECK).setClass(c, Scheduler.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + ms, pi);
    }

    static void check(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String mode = Settings.Global.getString(c.getContentResolver(), MODE);
        Boolean broken = privateDnsBroken(c);
        if (broken == null) return;   // no network yet; the next check looks again
        try {
            if (!p.getBoolean(RESCUED, false)) {
                if (!HOSTNAME.equals(mode) || !broken) {
                    if (HOSTNAME.equals(mode)) p.edit().putInt(FAILS, 0).apply();   // working: all clear
                    return;
                }
                // Failing again right after AdGuard went back on, with the clock already right: AdGuard
                // itself is down, so wait an hour before trying it again.
                boolean again = SystemClock.elapsedRealtime() - p.getLong(RESTORED, -RETRY_MS) < 5 * 60_000;
                int fails = again ? p.getInt(FAILS, 0) + 1 : 0;
                Settings.Global.putString(c.getContentResolver(), MODE, AUTOMATIC);
                p.edit().putBoolean(RESCUED, true).putInt(FAILS, fails).putLong(WALL, System.currentTimeMillis())
                        .putLong(UP, SystemClock.elapsedRealtime()).apply();
                ActivityLog.add(c, fails > 0 ? "Internet: AdGuard DNS not answering, using automatic DNS for an hour"
                        : "Internet: AdGuard DNS failing (TV clock wrong?), using automatic DNS for now");
                later(c, fails > 0 ? RETRY_MS : 60_000);
                return;
            }
            if (!AUTOMATIC.equals(mode)) {   // the owner changed it meanwhile: leave it to them
                p.edit().putBoolean(RESCUED, false).apply();
                return;
            }
            // AdGuard goes back on once the clock has jumped forward (fixed), or after the hour's wait.
            long wallMoved = System.currentTimeMillis() - p.getLong(WALL, 0);
            long upMoved = SystemClock.elapsedRealtime() - p.getLong(UP, 0);
            boolean clockJumped = wallMoved - upMoved > 60L * 60_000;
            if (clockJumped || p.getInt(FAILS, 0) > 0 || upMoved > 30 * 60_000) {
                Settings.Global.putString(c.getContentResolver(), MODE, HOSTNAME);
                p.edit().putBoolean(RESCUED, false).putLong(RESTORED, SystemClock.elapsedRealtime()).apply();
                ActivityLog.add(c, clockJumped ? "Internet: TV clock fixed, AdGuard DNS back on" : "Internet: AdGuard DNS back on");
                later(c, 90_000);   // failing again then: see above
            } else {
                later(c, 60_000);
            }
        } catch (SecurityException e) {
            ActivityLog.add(c, "Internet: AdGuard DNS failing; run ./install-smarthome.sh once so Smart Home may fix it");
        }
    }

    /**
     * True when the current network isn't validated (with Private DNS on a hostname, that's what a failing
     * DNS server looks like; the public API has no "Private DNS broken"); null without a network.
     */
    private static Boolean privateDnsBroken(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        Network n = cm.getActiveNetwork();
        NetworkCapabilities caps = n != null ? cm.getNetworkCapabilities(n) : null;
        if (caps == null) return null;
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}
