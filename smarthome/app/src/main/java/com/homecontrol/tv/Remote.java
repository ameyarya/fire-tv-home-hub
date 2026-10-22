package com.homecontrol.tv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.os.PowerManager;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The phone remote: a page on the TV's own address that any phone on the Wi-Fi opens (no app to install).
 * Settings → Phone remote turns it on and shows the link as a QR code; the link carries a secret, so only
 * phones that saw it can use it, and "New link" retires the old one.
 *
 * Only a fixed set of commands is offered: remote keys, typing into the field on screen, opening a web link,
 * and starting an installed TV app. Fire OS lets no ordinary app press keys (and refuses ADB connections from
 * the TV itself), so play/pause, seeking and volume go to the playing app and the TV's audio from here, and
 * arrows, OK, Back, Home and typing go to the clean launcher's accessibility service (FireHomeWatcher), which
 * moves focus, selects and types for them.
 */
final class Remote {

    static final int PORT = 8765, HTTPS_PORT = 8443;
    static final String HOST = "smart-home";
    private static final String PREFS = "remote", ON = "on", TOKEN = "token", CODE = "code", AWAKE = "awake";

    /** The keys the page may press: the remote's buttons, plus Enter and Delete for typing. */
    static final int[] KEYS = {3, 4, 19, 20, 21, 22, 23, 24, 25, 66, 67, 82, 85, 89, 90, 164, 223, 224};
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");

    private static Remote instance;

    private static final String LAUNCHER = "com.gathin.tvhome", TO_LAUNCHER = "com.gathin.tvhome.REMOTE";

    private final Context app;
    volatile String lastError = "";

    private Remote(Context c) {
        app = c.getApplicationContext();
    }

    private void toLauncher(String extra, Object value) {
        Intent i = new Intent(TO_LAUNCHER).setPackage(LAUNCHER);
        if (value instanceof Integer) i.putExtra(extra, (Integer) value);
        else i.putExtra(extra, (String) value);
        app.sendBroadcast(i);
    }

    /** The clean launcher's FireHomeWatcher is on: it does the arrows, OK, Back, Home and typing. */
    boolean launcherReady() {
        String on = android.provider.Settings.Secure.getString(app.getContentResolver(), "enabled_accessibility_services");
        return on != null && on.contains(LAUNCHER + "/");
    }

    static synchronized Remote get(Context c) {
        if (instance == null) instance = new Remote(c);
        return instance;
    }

    // ---- settings ----------------------------------------------------------------------------------

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isOn(Context c) {
        return prefs(c).getBoolean(ON, false);
    }

    static void setOn(Context c, boolean on) {
        prefs(c).edit().putBoolean(ON, on).apply();
        if (on) {
            token(c);
            RemoteService.start(c);
        } else {
            c.stopService(new Intent(c, RemoteService.class));
        }
    }

    /** Keep the TV's processor and Wi-Fi awake while it's off, so the phone page still answers (uses more power). */
    static boolean keepAwake(Context c) {
        return prefs(c).getBoolean(AWAKE, false);
    }

    static void setKeepAwake(Context c, boolean on) {
        prefs(c).edit().putBoolean(AWAKE, on).apply();
        if (isOn(c)) c.startForegroundService(new Intent(c, RemoteService.class));   // takes or drops the locks
    }

    static String token(Context c) {
        String t = prefs(c).getString(TOKEN, null);
        if (t == null) t = newToken(c);
        return t;
    }

    /** A new secret: links shown before stop working. */
    static String newToken(Context c) {
        String abc = "abcdefghijkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) b.append(abc.charAt(r.nextInt(abc.length())));
        String code = String.format(java.util.Locale.US, "%04d", r.nextInt(10000));
        prefs(c).edit().putString(TOKEN, b.toString()).putString(CODE, code).apply();
        return b.toString();
    }

    /** The 4 digits a phone enters once at http://smart-home.local:8765 (shown in Settings → Phone remote). */
    static String code(Context c) {
        token(c);
        String code = prefs(c).getString(CODE, null);
        if (code == null) {
            newToken(c);
            code = prefs(c).getString(CODE, "");
        }
        return code;
    }

    /** The address to type: the name, without the secret. */
    static String shortUrl(Context c) {
        return hasHttps(c) ? "https://" + HOST + ".local:" + HTTPS_PORT : "http://" + HOST + ".local:" + PORT;
    }

    static String url(Context c) {
        if (hasHttps(c)) return shortUrl(c) + "/r/" + token(c);   // the certificate names smart-home.local
        String ip = address();
        return "http://" + (ip == null ? "<TV address>" : ip) + ":" + PORT + "/r/" + token(c);
    }

    // ---- HTTPS ---------------------------------------------------------------------------------------
    // The certificate comes from the Mac (https-setup.sh): a home certificate authority signs one for
    // smart-home.local and the TV's address; phones trust that authority once, from /setup. Kept private.

    private static final String P12 = "p12", CA = "ca";
    static final String P12_PASSWORD = "smart-home";   // only wraps the file in the app's private storage

    static boolean hasHttps(Context c) {
        return prefs(c).contains(P12);
    }

    static void setCertificate(Context c, String p12Base64, String caPem) {
        prefs(c).edit().putString(P12, p12Base64).putString(CA, caPem).apply();
    }

    static byte[] p12(Context c) {
        String b = prefs(c).getString(P12, null);
        return b == null ? null : android.util.Base64.decode(b, android.util.Base64.DEFAULT);
    }

    /** The home authority, for phones to trust (served at /ca.pem). */
    static String caPem(Context c) {
        return prefs(c).getString(CA, "");
    }

    /** The TV's address on the home network (Wi-Fi or Ethernet). */
    static String address() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (java.net.SocketException ignored) {
        }
        return null;
    }

    // ---- commands ----------------------------------------------------------------------------------

    static boolean allowedKey(int code) {
        for (int k : KEYS) if (k == code) return true;
        return false;
    }

    /** Power toggles: asleep → wake up, awake → sleep. */
    int powerKey() {
        return ((PowerManager) app.getSystemService(Context.POWER_SERVICE)).isInteractive() ? 223 : 224;
    }

    void key(int code) {
        AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
                return;
            case KeyEvent.KEYCODE_VOLUME_UP:
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                return;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                return;
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                return;
            case KeyEvent.KEYCODE_WAKEUP:
                return;   // asleep, the TV isn't listening anyway
            default:
                toLauncher("key", code);
        }
    }

    /** Types into whatever text field has focus on the TV. */
    void type(String text) {
        if (text != null && !text.isEmpty() && text.length() <= 500) toLauncher("text", text);
    }

    /** A web link: YouTube links go to the YouTube app, others to whatever opens them. */
    boolean open(String url) {
        url = url.trim();
        if (!url.matches("(?i)https?://\\S+") || url.length() > 2000) return false;
        toLauncher("open", url);
        return true;
    }

    boolean launch(String component) {
        for (String[] a : apps()) {
            if (a[1].equals(component)) {
                toLauncher("component", component);
                return true;
            }
        }
        return false;
    }

    /** The TV apps on the home screen: {label, package/activity}. */
    List<String[]> apps() {
        List<String[]> out = new ArrayList<>();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : app.getPackageManager().queryIntentActivities(i, 0)) {
            String pkg = r.activityInfo.packageName;
            if (!PACKAGE.matcher(pkg).matches() || pkg.equals(app.getPackageName())) continue;
            out.add(new String[]{String.valueOf(r.loadLabel(app.getPackageManager())), pkg + "/" + r.activityInfo.name});
        }
        out.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));
        return out;
    }

    JSONObject status() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("launcher", launcherReady());
        o.put("awake", ((PowerManager) app.getSystemService(Context.POWER_SERVICE)).isInteractive());
        o.put("error", lastError);
        return o;
    }

    JSONArray appsJson() throws JSONException {
        JSONArray a = new JSONArray();
        for (String[] x : apps()) a.put(new JSONObject().put("name", x[0]).put("id", x[1]));
        return a;
    }

}
