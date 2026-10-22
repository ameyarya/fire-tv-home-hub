package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The screensaver delay, sent by the Smart Home app's Settings → Screensaver (extra "minutes", 0 = off).
 * FireHomeWatcher reads it and opens Smart Home's clock after that many idle minutes.
 */
public class ScreensaverReceiver extends BroadcastReceiver {

    static final String PREFS = "screensaver", KEY = "minutes";

    @Override
    public void onReceive(Context c, Intent i) {
        int m = Math.max(0, Math.min(240, i.getIntExtra(KEY, 0)));
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, m).apply();
    }
}
