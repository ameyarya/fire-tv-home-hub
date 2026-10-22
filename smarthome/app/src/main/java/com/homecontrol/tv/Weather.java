package com.homecontrol.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Weather for the screensaver, from Open-Meteo: free, no account or key. It is sent only the location
 * picked in Settings (a city's coordinates), and only when the screensaver is showing. Plain Java, so the
 * parsing is tested on the JVM against a real reply.
 */
final class Weather {

    static final class Hour {
        final String time;   // "21:00"
        final double temp;
        final int code;

        Hour(String time, double temp, int code) {
            this.time = time;
            this.temp = temp;
            this.code = code;
        }
    }

    static final class Forecast {
        double temp = Double.NaN, high = Double.NaN, low = Double.NaN;
        int code = -1;
        boolean day = true;
        final List<Hour> hours = new ArrayList<>();
    }

    private Weather() {
    }

    /** Fahrenheit where people use it (the US, Liberia, Myanmar), Celsius elsewhere. */
    static boolean fahrenheit(Locale l) {
        String c = l.getCountry();
        return c.equals("US") || c.equals("LR") || c.equals("MM");
    }

    static String url(double lat, double lon, boolean fahrenheit) {
        return String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                + "&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code&forecast_hours=7"
                + "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto%s",
                lat, lon, fahrenheit ? "&temperature_unit=fahrenheit" : "");
    }

    static Forecast fetch(double lat, double lon, boolean fahrenheit) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url(lat, lon, fahrenheit)).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 64 * 1024) out.write(buf, 0, n);
                return parse(out.toString(StandardCharsets.UTF_8.name()));
            }
        } finally {
            c.disconnect();
        }
    }

    /** The current conditions, today's high and low, and up to 6 hours after the current one. */
    static Forecast parse(String json) throws IOException {
        try {
            JSONObject o = new JSONObject(json);
            Forecast f = new Forecast();
            JSONObject cur = o.optJSONObject("current");
            String now = "";
            if (cur != null) {
                f.temp = cur.optDouble("temperature_2m", Double.NaN);
                f.code = cur.optInt("weather_code", -1);
                f.day = cur.optInt("is_day", 1) == 1;
                now = cur.optString("time", "");
            }
            JSONObject daily = o.optJSONObject("daily");
            if (daily != null) {
                JSONArray hi = daily.optJSONArray("temperature_2m_max"), lo = daily.optJSONArray("temperature_2m_min");
                if (hi != null && hi.length() > 0) f.high = hi.optDouble(0, Double.NaN);
                if (lo != null && lo.length() > 0) f.low = lo.optDouble(0, Double.NaN);
            }
            JSONObject hourly = o.optJSONObject("hourly");
            JSONArray times = hourly != null ? hourly.optJSONArray("time") : null;
            JSONArray temps = hourly != null ? hourly.optJSONArray("temperature_2m") : null;
            JSONArray codes = hourly != null ? hourly.optJSONArray("weather_code") : null;
            String nowHour = now.length() >= 13 ? now.substring(0, 13) : "";
            for (int i = 0; times != null && temps != null && codes != null && i < times.length() && f.hours.size() < 6; i++) {
                String t = times.optString(i, "");
                if (t.length() < 16 || t.substring(0, 13).compareTo(nowHour) <= 0) continue;   // the current hour is "now"
                f.hours.add(new Hour(t.substring(11, 16), temps.optDouble(i, Double.NaN), codes.optInt(i, -1)));
            }
            if (Double.isNaN(f.temp) && f.hours.isEmpty()) throw new IOException("no weather in the reply");
            return f;
        } catch (JSONException e) {
            throw new IOException("not a forecast: " + e.getMessage());
        }
    }

    /** WMO weather codes in a word or two. */
    static String describe(int code) {
        switch (code) {
            case 0: return "Clear";
            case 1: return "Mostly clear";
            case 2: return "Partly cloudy";
            case 3: return "Cloudy";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: case 63: return "Rain";
            case 65: return "Heavy rain";
            case 66: case 67: return "Freezing rain";
            case 71: case 73: return "Snow";
            case 75: return "Heavy snow";
            case 77: return "Snow grains";
            case 80: case 81: return "Showers";
            case 82: return "Heavy showers";
            case 85: case 86: return "Snow showers";
            case 95: return "Thunderstorm";
            case 96: case 99: return "Thunderstorm, hail";
            default: return "";
        }
    }

    static final int SUN = 0, MOON = 1, PARTLY = 2, CLOUD = 3, FOG = 4, RAIN = 5, SNOW = 6, STORM = 7;

    /** Which of the eight icons a code gets (day or night for clear skies). */
    static int icon(int code, boolean day) {
        if (code == 0 || code == 1) return day ? SUN : MOON;
        if (code == 2) return PARTLY;
        if (code == 3) return CLOUD;
        if (code == 45 || code == 48) return FOG;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return SNOW;
        if (code >= 95) return STORM;
        if (code >= 51) return RAIN;
        return CLOUD;
    }

    static String temp(double t) {
        return Double.isNaN(t) ? "–" : Math.round(t) + "°";
    }
}
