package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Smart Home tells the launcher which wallpapers are turned off ("off") and, when it knows them, where you are and
 * what the weather is ("lat", "lon", "code"), every half hour and whenever the gallery changes; see SkyStore.
 */
public class SkyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (i.hasExtra("off")) SkyStore.saveOff(c, i.getIntExtra("off", 0) & 31);
        try {
            update(c, i);
        } finally {
            SceneView.recheck();
            WeatherFxView.recheck();
        }
    }

    private static void update(Context c, Intent i) {
        double lat = i.getDoubleExtra("lat", Double.NaN), lon = i.getDoubleExtra("lon", Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return;
        SkyStore.save(c, lat, lon, i.getIntExtra("code", -1));
    }
}
