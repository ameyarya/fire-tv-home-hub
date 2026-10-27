package com.homecontrol.tv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Real remote keys through Fire OS's own remote service (com.amazon.tv.turnstile, what Amazon's Fire TV phone app
 * uses): unlike the launcher's accessibility service, these reach apps that draw their own screens, like Netflix.
 *
 * The service starts on demand (a POST to the TV's DIAL server on 8009) and then answers https on 8080,. It wants pairing once: it shows a PIN on the TV, which the launcher's accessibility
 * service reads off the screen and hands back (PinReceiver), so pairing needs nobody. Only ever on this TV (127.0.0.1).
 */
final class AmazonRemote {

    private static final String BASE = "https://127.0.0.1:8080/v1/", WAKE = "http://127.0.0.1:8009/apps/FireTVRemote";
    private static final String API_KEY = "0987654321";   // the service's fixed key, the same in every Fire TV app
    private static final String PREFS = "amazon_remote", TOKEN = "token";
    private static final Object PIN_LOCK = new Object();
    private static String pin;
    private static long pairingUntil;

    private AmazonRemote() {
    }

    /** The service's action for a remote key, or null when it has none. */
    static String action(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: return "dpad_up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "dpad_down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "dpad_left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "dpad_right";
            case KeyEvent.KEYCODE_DPAD_CENTER: return "select";
            case KeyEvent.KEYCODE_BACK: return "back";
            case KeyEvent.KEYCODE_HOME: return "home";
            case KeyEvent.KEYCODE_MENU: return "menu";
            case KeyEvent.KEYCODE_SLEEP: return "sleep";
            default: return null;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Presses a key; false when the service isn't there or won't pair, so the caller can fall back. */
    static boolean key(Context c, int code) {
        String action = action(code);
        if (action == null) return false;
        return send(c, "FireTV?action=" + action, null);
    }

    /** POSTs to the service: wakes it if it's asleep, pairs if there's no token yet or it was dropped. */
    private static synchronized boolean send(Context c, String path, String body) {
        String token = prefs(c).getString(TOKEN, null);
        if (token == null && (token = pair(c)) == null) return false;
        int code = post(path, body, token);
        if (code < 0) {   // asleep: wake it and try once more
            post(WAKE, "", null);
            SystemClock.sleep(800);
            code = post(path, body, token);
        }
        if (code == 403) {   // forgotten (Settings → reset, or unpaired): pair again
            prefs(c).edit().remove(TOKEN).apply();
            if ((token = pair(c)) == null) return false;
            code = post(path, body, token);
        }
        return code >= 200 && code < 300;
    }

    /** Asks for a PIN, which the launcher reads off the screen (PinReceiver); returns the token, or null. */
    private static String pair(Context c) {
        synchronized (PIN_LOCK) {
            pin = null;
            pairingUntil = SystemClock.uptimeMillis() + 8000;
        }
        if (post("FireTV/pin/display", "{\"friendlyName\":\"Smart Home\"}", null) < 0) {
            post(WAKE, "", null);
            SystemClock.sleep(800);
            if (post("FireTV/pin/display", "{\"friendlyName\":\"Smart Home\"}", null) < 0) return null;
        }
        c.sendBroadcast(new Intent("com.gathin.tvhome.REMOTE").setPackage("com.gathin.tvhome").putExtra("readPin", true));
        String p;
        synchronized (PIN_LOCK) {
            while ((p = pin) == null && SystemClock.uptimeMillis() < pairingUntil) {
                try {
                    PIN_LOCK.wait(Math.max(1, pairingUntil - SystemClock.uptimeMillis()));
                } catch (InterruptedException e) {
                    return null;
                }
            }
            pairingUntil = 0;
        }
        if (p == null) return null;
        String[] reply = new String[1];
        if (post("FireTV/pin/verify", "{\"pin\":\"" + p + "\"}", null, reply) != 200) return null;
        try {
            String token = new org.json.JSONObject(reply[0]).getString("description");
            prefs(c).edit().putString(TOKEN, token).apply();
            return token;
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    /** From the launcher (PinReceiver): the PIN on the screen, only while pairing. */
    static void pinSeen(String p) {
        synchronized (PIN_LOCK) {
            if (p == null || !p.matches("\\d{4,8}") || SystemClock.uptimeMillis() > pairingUntil) return;
            pin = p;
            PIN_LOCK.notifyAll();
        }
    }

    private static int post(String path, String body, String token) {
        return post(path, body, token, null);
    }

    /** The HTTP status, or -1 when the service doesn't answer. */
    private static int post(String path, String body, String token, String[] reply) {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(path.startsWith("http") ? path : BASE + path).openConnection();
            if (h instanceof HttpsURLConnection) {   // the service's own certificate, on this TV
                ((HttpsURLConnection) h).setSSLSocketFactory(trustLocal());
                ((HttpsURLConnection) h).setHostnameVerifier((host, s) -> "127.0.0.1".equals(host));
            }
            h.setConnectTimeout(1500);
            h.setReadTimeout(4000);
            h.setRequestMethod("POST");
            h.setRequestProperty("X-Api-Key", API_KEY);
            if (token != null) h.setRequestProperty("X-Client-Token", token);
            h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            h.setDoOutput(true);
            byte[] b = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
            try (OutputStream o = h.getOutputStream()) {
                o.write(b);
            }
            int code = h.getResponseCode();
            if (reply != null) {
                try (InputStream in = code < 400 ? h.getInputStream() : h.getErrorStream()) {
                    reply[0] = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
                }
            }
            return code;
        } catch (IOException | RuntimeException e) {
            return -1;
        } finally {
            if (h != null) h.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static SSLSocketFactory local;

    private static synchronized SSLSocketFactory trustLocal() throws IOException {
        if (local != null) return local;
        TrustManager[] any = {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String type) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String type) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, any, null);
            return local = ctx.getSocketFactory();
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }
}
