package com.homecontrol.tv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The doorbell rang: show the door camera. Full screen when nothing's playing (TV off, screensaver, home screen),
 * or a small picture over whatever is playing, which keeps the sound and the remote. Either counts down and goes
 * away by itself, after the time set on the phone page.
 */
final class Doorbell {

    private static final String PREFS = "doorbell", SECONDS = "seconds";
    private static final int DEFAULT_SECONDS = 30;
    private static final long REPEAT_MS = 10_000, TICK_MS = 250;
    private static final long FRAME_MS = 100;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long lastRing = -REPEAT_MS;
    private static FrameLayout window;
    private static ImageView picture;
    private static TextView timer;
    private static long pictureEnd;
    private static volatile Thread reader;
    private static final Runnable TICK = Doorbell::tick;

    private Doorbell() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int seconds(Context c) {
        return prefs(c).getInt(SECONDS, DEFAULT_SECONDS);
    }

    static boolean setSeconds(Context c, int seconds) {
        if (!Mjpeg.isDoorbellSeconds(seconds)) return false;
        prefs(c).edit().putInt(SECONDS, seconds).apply();
        return true;
    }

    static boolean playing(Context c) {
        return c.getSystemService(AudioManager.class).isMusicActive();
    }

    /** Null when shown, otherwise why not. */
    static String ring(Context c, String id) {
        Home.Camera cam = HomeApi.camera(c, id);
        if (cam == null) return "No such camera";
        synchronized (Doorbell.class) {
            long now = SystemClock.uptimeMillis();
            if (now - lastRing < REPEAT_MS) return null;   // one ring, heard twice
            lastRing = now;
        }
        boolean on = c.getSystemService(PowerManager.class).isInteractive();
        boolean inPicture = Mjpeg.doorbellPicture(on, playing(c), cam.url);
        long showMs = seconds(c) * 1000L;
        ActivityLog.add(c, "Doorbell: " + cam.name + (inPicture ? " shown as a picture" : " shown full screen"));
        if (inPicture) {
            MAIN.post(() -> showPicture(c.getApplicationContext(), cam.url, showMs));
            return null;
        }
        if (!on) {
            c.getSystemService(PowerManager.class).newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "SmartHome:doorbell").acquire(5000);
        }
        try {
            c.startActivity(new Intent(c, CameraActivity.class).putExtra(CameraActivity.EXTRA_ID, cam.id)
                    .putExtra(CameraActivity.EXTRA_CLOSE_AFTER_MS, showMs)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch (RuntimeException e) {
            return "The TV wouldn't open it: " + e.getMessage();
        }
        return null;
    }

    private static void showPicture(Context app, String url, long showMs) {
        pictureEnd = SystemClock.uptimeMillis() + showMs;
        MAIN.removeCallbacks(TICK);
        if (window != null) {   // already up: just stays longer
            tick();
            return;
        }
        DisplayMetrics screen = app.getResources().getDisplayMetrics();
        int w = screen.widthPixels / 3, h = w * 3 / 4, margin = screen.widthPixels / 40;
        float corner = w / 16f;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = margin;
        lp.y = margin;
        FrameLayout box = new FrameLayout(app);
        box.setBackgroundColor(0xFF000000);
        box.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), corner);
            }
        });
        box.setClipToOutline(true);
        ImageView view = new ImageView(app);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        TextView time = new TextView(app);
        time.setBackgroundResource(R.drawable.glass_panel);
        time.setTextColor(0xFFFFFFFF);
        time.setTextSize(16);
        time.setPadding(h / 20, h / 40, h / 20, h / 40);
        FrameLayout.LayoutParams timeAt = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        timeAt.setMargins(0, 0, h / 20, h / 20);
        box.addView(time, timeAt);
        try {
            app.getSystemService(WindowManager.class).addView(box, lp);
        } catch (RuntimeException e) {
            ActivityLog.add(app, "Doorbell: couldn't show the picture: " + e.getMessage());
            return;
        }
        window = box;
        picture = view;
        timer = time;
        tick();
        Thread thread = new Thread(() -> readFrames(url, view), "doorbell-picture");
        reader = thread;
        thread.start();
    }

    /** Counts down on the picture, and takes it away at zero. */
    private static void tick() {
        long left = pictureEnd - SystemClock.uptimeMillis();
        if (left <= 0) {
            hidePicture();
            return;
        }
        timer.setText(Mjpeg.countdown(left));
        MAIN.postDelayed(TICK, TICK_MS);
    }

    private static void readFrames(String url, ImageView view) {
        Thread self = Thread.currentThread();
        long[] last = {0};
        while (reader == self) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                Mjpeg.read(c.getInputStream(), jpeg -> {
                    long now = SystemClock.uptimeMillis();
                    if (now - last[0] >= FRAME_MS) {
                        last[0] = now;
                        Bitmap frame = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                        if (frame != null) {
                            MAIN.post(() -> {
                                if (picture == view) view.setImageBitmap(frame);
                            });
                        }
                    }
                    return reader == self;
                });
            } catch (IOException e) {
                // the camera dropped: try again until the picture's time is up
            } finally {
                if (c != null) c.disconnect();
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static void hidePicture() {
        Thread thread = reader;
        reader = null;
        if (thread != null) thread.interrupt();
        if (window != null) {
            try {
                window.getContext().getSystemService(WindowManager.class).removeView(window);
            } catch (RuntimeException e) {
                // already gone
            }
            window = null;
            timer = null;
            picture = null;
        }
    }
}
