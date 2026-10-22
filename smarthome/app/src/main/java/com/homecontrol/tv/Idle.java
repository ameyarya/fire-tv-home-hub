package com.homecontrol.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * The screensaver settings: the delay (Settings → Screensaver) and the temperature unit. Kept here to show in Settings, and sent to the clean
 * launcher, whose FireHomeWatcher does the counting and opens ClockActivity.
 */
final class Idle {

    static final String LAUNCHER = "com.gathin.tvhome", ACTION = "com.gathin.tvhome.SET_SCREENSAVER";
    private static final String PREFS = "screensaver", KEY = "minutes", UNIT = "fahrenheit";

    private Idle() {
    }

    static int minutes(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0);
    }

    /** Temperatures in Fahrenheit: the choice in Settings, else what the TV's country uses. */
    static boolean fahrenheit(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.contains(UNIT) ? p.getBoolean(UNIT, false) : Weather.fahrenheit(java.util.Locale.getDefault());
    }

    static void setFahrenheit(Context c, boolean f) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(UNIT, f).apply();
    }

    /** False when the clean launcher isn't installed. 0 turns it off. */
    static boolean set(Context c, int minutes) {
        try {
            c.getPackageManager().getPackageInfo(LAUNCHER, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, minutes).apply();
        c.sendBroadcast(new Intent(ACTION).putExtra(KEY, minutes)
                .setComponent(new ComponentName(LAUNCHER, LAUNCHER + ".ScreensaverReceiver")));
        return true;
    }
}
