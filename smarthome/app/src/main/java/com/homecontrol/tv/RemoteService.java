package com.homecontrol.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * Keeps the phone remote's server running while it's on (Settings → Phone remote). A foreground service, so
 * Fire OS doesn't stop it in the background; started from the app, at boot and when the TV turns on.
 */
public class RemoteService extends Service {

    private static final String CHANNEL = "remote";
    private RemoteServer server;
    private android.os.PowerManager.WakeLock cpu;
    private android.net.wifi.WifiManager.WifiLock wifi;

    static void start(Context c) {
        if (!Remote.isOn(c)) return;
        try {
            c.startForegroundService(new Intent(c, RemoteService.class));
        } catch (RuntimeException ignored) {   // not allowed right now; the next start point tries again
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Phone remote", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle("Phone remote is on")
                .build();
        startForeground(1, n);
        server = new RemoteServer(this);
        server.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Remote.isOn(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        server.start();
        awake(Remote.keepAwake(this));
        return START_STICKY;
    }

    /**
     * "Phone remote while the TV is off": a partial wake lock and a Wi-Fi lock keep the processor and Wi-Fi up with
     * the screen off, so the page still answers. Costs a few watts while the TV is off.
     */
    private void awake(boolean on) {
        if (on && cpu == null) {
            cpu = getSystemService(android.os.PowerManager.class)
                    .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "SmartHome:remote");
            cpu.acquire();
            wifi = ((android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE))
                    .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SmartHome:remote");
            wifi.acquire();
        } else if (!on && cpu != null) {
            cpu.release();
            wifi.release();
            cpu = null;
            wifi = null;
        }
    }

    @Override
    public void onDestroy() {
        awake(false);
        server.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
