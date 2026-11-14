package com.homecontrol.tv;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The screensaver: the launcher's scenes with the glass clock and the date, muted to a faint tint like a phone's
 * lock screen, and the weather below. Its brightness follows the room: the TV's light sensor (without one,
 * the time of day: dim from 22:00 to 06:00). Any key closes it, back to what was on screen.
 *
 * Fire OS 8 ignores the chosen screensaver (its Ambient service always shows Amazon's), so the clean launcher
 * opens this instead: its FireHomeWatcher counts the minutes since the last remote key and, when nothing is
 * playing, starts this activity. The delay is picked in Settings → Screensaver (see Editors).
 *
 * Weather comes from Open-Meteo (see Weather) for the location in Settings, refreshed every 15 minutes
 * while the clock shows; without a location, it shows the clock alone with a hint.
 */
public class ClockActivity extends Activity {

    private static final long WEATHER_MS = 15 * 60_000;
    private static final int[] ICONS = {R.drawable.wx_sun, R.drawable.wx_moon, R.drawable.wx_partly, R.drawable.wx_cloud,
            R.drawable.wx_fog, R.drawable.wx_rain, R.drawable.wx_snow, R.drawable.wx_storm};

    private final Handler main = new Handler(Looper.getMainLooper());
    private SceneView scene;
    private SensorManager sensors;
    private Sensor light;
    private float level = -1, lux = -1;
    private View content, weatherBox;
    private TextView time, date, wxTemp, wxText, hint;
    private ImageView wxIcon;
    private LinearLayout hours;
    private Weather.Forecast forecast;
    private String weatherState = "none";
    private long weatherAt;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            showTime();
            // Next whole second: the clock changes on the minute, not up to a second late.
            main.postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
        }
    };
    private final Runnable weather = new Runnable() {
        @Override
        public void run() {
            loadWeather();
            main.postDelayed(this, WEATHER_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.dream_home);
        // No FLAG_KEEP_SCREEN_ON: the TV still goes to sleep after its usual time.
        scene = findViewById(R.id.scene);
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        light = sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
        content = findViewById(R.id.content);
        weatherBox = findViewById(R.id.weather);
        time = findViewById(R.id.time);
        date = findViewById(R.id.date);
        wxTemp = findViewById(R.id.wxTemp);
        wxText = findViewById(R.id.wxText);
        wxIcon = findViewById(R.id.wxIcon);
        hours = findViewById(R.id.hours);
        hint = findViewById(R.id.hint);
    }

    @Override
    protected void onResume() {
        super.onResume();
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) c.hide(WindowInsets.Type.systemBars());
        scene.start();
        if (light != null) sensors.registerListener(lightListener, light, SensorManager.SENSOR_DELAY_NORMAL);
        level = -1;   // the first light reading sets the brightness outright
        if (light == null) setLevel(timeLevel(LocalTime.now()));
        main.post(tick);
        main.post(weather);
    }

    @Override
    protected void onPause() {
        main.removeCallbacksAndMessages(null);
        sensors.unregisterListener(lightListener);
        scene.stop();
        super.onPause();
        finish();   // gone once anything else comes up; the launcher opens it again next time
    }

    /** Any key closes it. Home and the TV's own keys still do what they always do. */
    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        finish();
        return true;
    }

    private void showTime() {
        ZonedDateTime now = ZonedDateTime.now();
        boolean h24 = DateFormat.is24HourFormat(this);
        time.setText(now.format(DateTimeFormatter.ofPattern(h24 ? "HH:mm" : "h:mm", Locale.getDefault())));
        date.setText(now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())));
        scene.invalidate();
        if (light == null) setLevel(timeLevel(now.toLocalTime()));
    }

    /** Dark room 0.22 up to 0.8 in daylight, on a log scale; each reading moves it a tenth of the way (no flicker). */
    private final SensorEventListener lightListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent e) {
            lux = e.values[0];
            float target = 0.22f + 0.58f * (float) Math.min(1.0, Math.log10(1 + Math.max(0, lux)) / Math.log10(401));
            setLevel(level < 0 ? target : level + 0.1f * (target - level));
        }

        @Override
        public void onAccuracyChanged(Sensor s, int accuracy) {
        }
    };

    private void setLevel(float l) {
        level = l;
        scene.setLevel(l);
        content.setAlpha(Math.max(0.35f, l + 0.15f));   // the weather dims with the picture
    }

    private static float timeLevel(LocalTime t) {
        return night(t) ? 0.25f : 0.6f;
    }

    static boolean night(LocalTime t) {
        return t.getHour() >= 22 || t.getHour() < 6;
    }

    private void loadWeather() {
        Home h = Storage.get(this);
        if (Double.isNaN(h.latitude)) {
            weatherState = "no location";
            hint.setText("Weather: choose your city in Smart Home → Settings → Location");
            hint.setVisibility(View.VISIBLE);
            weatherBox.setVisibility(View.GONE);
            return;
        }
        double lat = h.latitude, lon = h.longitude;
        boolean f = Idle.fahrenheit(this);
        new Thread(() -> {
            Weather.Forecast got;
            try {
                got = Weather.fetch(lat, lon, f);
            } catch (IOException e) {
                got = null;
            }
            Weather.Forecast result = got;
            main.post(() -> {
                if (result != null) {
                    forecast = result;
                    if (result.code >= 0) SkyStore.remember(this, result.code);
                    weatherAt = System.currentTimeMillis();
                    weatherState = "ok";
                } else if (forecast == null) {
                    weatherState = "unavailable";
                }
                showWeather();
            });
        }).start();
    }

    private void showWeather() {
        hint.setVisibility(View.GONE);
        if (forecast == null) {
            weatherBox.setVisibility(View.GONE);
            return;
        }
        weatherBox.setVisibility(View.VISIBLE);
        wxIcon.setImageResource(ICONS[Weather.icon(forecast.code, forecast.day)]);
        wxTemp.setText(Weather.temp(forecast.temp));
        String text = Weather.describe(forecast.code);
        if (!Double.isNaN(forecast.high)) text += (text.isEmpty() ? "" : "  ·  ") + "H " + Weather.temp(forecast.high) + "  L " + Weather.temp(forecast.low);
        wxText.setText(text);
        hours.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        boolean h24 = DateFormat.is24HourFormat(this);
        for (int i = 0; i < Math.min(5, forecast.hours.size()); i++) {
            Weather.Hour hr = forecast.hours.get(i);
            View v = inf.inflate(R.layout.item_hour, hours, false);
            LocalTime t = LocalTime.parse(hr.time);
            ((TextView) v.findViewById(R.id.hTime)).setText(t.format(DateTimeFormatter.ofPattern(h24 ? "HH:mm" : "h a", Locale.getDefault())));
            ((ImageView) v.findViewById(R.id.hIcon)).setImageResource(ICONS[Weather.icon(hr.code, !night(t))]);
            ((TextView) v.findViewById(R.id.hTemp)).setText(Weather.temp(hr.temp));
            hours.addView(v);
        }
    }

    /** `dumpsys activity top` while it shows: for the UI tests. */
    @Override
    public void dump(String prefix, FileDescriptor fd, PrintWriter w, String[] args) {
        super.dump(prefix, fd, w, args);
        w.println("ClockActivity state:");
        w.println("  time=" + (time != null ? time.getText() : "") + " date=" + (date != null ? date.getText() : ""));
        w.println("  weather=" + weatherState + (forecast != null ? " temp=" + Weather.temp(forecast.temp)
                + " text=" + Weather.describe(forecast.code) + " hours=" + forecast.hours.size() : ""));
        w.println("  alpha=" + (content != null ? content.getAlpha() : "") + " level=" + level + " lux=" + lux);
    }
}
