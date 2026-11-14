package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * Rain, snow, mist or a storm drawn over the home screen's scene, from the weather Smart Home sent (SkyStore, Sky).
 * Light on purpose: a few dozen lines or dots, a handful of soft clouds, no layers, and nothing drawn at all in clear
 * weather. Like couch-tv's ambient weather (github.com/Seagey/couch-tv, MIT; see CREDITS.md).
 */
public final class WeatherFxView extends View {

    private static final int RAIN_N = 70, STORM_N = 110, SNOW_N = 45, MIST_N = 4;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), flash = new Paint();
    private final Random random = new Random();
    private final Handler main = new Handler(Looper.getMainLooper());
    private float[] x = new float[STORM_N], y = new float[STORM_N], v = new float[STORM_N], s = new float[STORM_N];
    private int type = Sky.CLEAR, count;
    private boolean running;
    private long last, nextFlash, flashAt;

    private final Runnable recheck = new Runnable() {
        @Override
        public void run() {
            setType(SkyStore.fx(getContext()));
            if (running) main.postDelayed(this, 60_000);
        }
    };

    public WeatherFxView(Context c) {
        this(c, null);
    }

    public WeatherFxView(Context c, AttributeSet a) {
        super(c, a);
        setWillNotDraw(false);
    }

    private static WeatherFxView active;

    /** Look at the weather again now (Smart Home just sent it). */
    static void recheck() {
        WeatherFxView v = active;
        if (v != null) v.main.post(() -> v.setType(SkyStore.fx(v.getContext())));
    }

    /** Starts watching the weather; call from onResume. */
    void start() {
        if (running) return;
        running = true;
        active = this;
        recheck.run();
    }

    /** Stops drawing; call from onPause. */
    void stop() {
        running = false;
        if (active == this) active = null;
        main.removeCallbacks(recheck);
        type = Sky.CLEAR;
    }

    private void setType(int t) {
        if (t == type) return;
        type = t;
        count = t == Sky.RAIN ? RAIN_N : t == Sky.STORM ? STORM_N : t == Sky.SNOW ? SNOW_N : t == Sky.MIST ? MIST_N : 0;
        for (int i = 0; i < count; i++) scatter(i, true);
        last = 0;
        nextFlash = System.currentTimeMillis() + 5_000 + random.nextInt(9_000);
        invalidate();
    }

    private void scatter(int i, boolean anywhere) {
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        x[i] = random.nextFloat() * (w + 200) - 100;
        y[i] = anywhere ? random.nextFloat() * h : -60;
        if (type == Sky.SNOW) {
            v[i] = 0.05f + random.nextFloat() * 0.08f;   // px per ms
            s[i] = 2f + random.nextFloat() * 3.5f;
        } else if (type == Sky.MIST) {
            v[i] = 0.01f + random.nextFloat() * 0.015f;
            s[i] = 0.5f + random.nextFloat() * 0.5f;     // share of the width
            y[i] = h * (0.35f + random.nextFloat() * 0.5f);
        } else {
            v[i] = 1.1f + random.nextFloat() * 0.7f;
            s[i] = 25f + random.nextFloat() * 23f;       // length
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (type == Sky.CLEAR || !running) return;
        int w = getWidth(), h = getHeight();
        long now = System.currentTimeMillis();
        float dt = last == 0 ? 16 : Math.min(64, now - last);
        last = now;
        float k = w / 1920f;
        if (type == Sky.MIST) {
            for (int i = 0; i < count; i++) {
                x[i] += v[i] * dt;
                float r = s[i] * w * 0.6f;
                if (x[i] - r > w) x[i] = -r;
                paint.setShader(new RadialGradient(x[i], y[i], r, 0x30FFFFFF, 0, Shader.TileMode.CLAMP));
                c.drawCircle(x[i], y[i], r, paint);
            }
            paint.setShader(null);
        } else if (type == Sky.SNOW) {
            paint.setColor(0xCCFFFFFF);
            for (int i = 0; i < count; i++) {
                y[i] += v[i] * dt;
                x[i] += (float) Math.sin((now + i * 400) / 1100.0) * 0.03f * dt;
                if (y[i] > h + 10) scatter(i, false);
                c.drawCircle(x[i], y[i], s[i] * k, paint);
            }
        } else {   // rain and storm
            boolean storm = type == Sky.STORM;
            paint.setColor(storm ? 0xA0D8E6F5 : 0x88E4EEF8);
            paint.setStrokeWidth(Math.max(1.5f, 1.8f * k));
            for (int i = 0; i < count; i++) {
                float len = s[i] * k, dy = v[i] * dt * k * 12f;
                y[i] += dy;
                x[i] -= dy * 0.18f;
                if (y[i] > h + 20) scatter(i, false);
                c.drawLine(x[i], y[i], x[i] + len * 0.18f, y[i] - len, paint);
            }
            if (storm) {
                if (now >= nextFlash) {
                    flashAt = now;
                    nextFlash = now + 6_000 + random.nextInt(9_000);
                }
                float f = 1f - (now - flashAt) / 380f;
                if (f > 0) {
                    flash.setColor(((int) (f * 70) << 24) | 0xFFFFFF);
                    c.drawRect(0, 0, w, h, flash);
                }
            }
        }
        postInvalidateOnAnimation();
    }
}
