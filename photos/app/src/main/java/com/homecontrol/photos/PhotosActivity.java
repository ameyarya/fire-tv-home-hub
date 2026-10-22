package com.homecontrol.photos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.security.MessageDigest;
import java.security.cert.X509Certificate;

/**
 * Photos: the Photo Vault gallery (telegram-photo-vault, running on the Mac) full screen on the TV.
 *
 * - Finds the gallery on the home Wi-Fi by the "Photo Vault" service the Mac announces while sharing is on,
 *   so it follows the Mac's address; the TV can't look up photo-vault.local itself, so pages load by address.
 * - The gallery's certificate is made by mkcert on the Mac, which the TV doesn't know: the first time, the TV
 *   asks the owner to trust it, then only that exact certificate is accepted (anything else asks again).
 * - The remote drives a pointer: arrows move it (faster when held), OK clicks, the pointer at an edge scrolls
 *   the page. With a photo open (the gallery's #viewer), left and right go to the page as arrow keys, which
 *   the gallery uses for previous and next, and Back closes the photo (Escape). Menu switches all arrows to
 *   the page and back. Back otherwise goes back.
 * - The TV signs in with an account made in the gallery's Sharing page on the Mac; the sign-in is kept.
 */
public class PhotosActivity extends Activity {

    private static final String SERVICE = "_https._tcp.", NAME = "Photo Vault";
    private static final String PREFS = "photos", HOST = "host", PIN = "pin";

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private WebView web;
    private Pointer pointer;
    private TextView status;
    private String host;
    private boolean pointerMode = true;
    /** A photo is open in the gallery's viewer (reported by the page, see VIEWER_WATCH). */
    private volatile boolean viewerOpen;

    /** Tells the app when the gallery's viewer opens and closes. */
    private static final String VIEWER_WATCH = "(function(){var v=document.getElementById('viewer');if(!v||v._tv)return;"
            + "v._tv=1;var f=function(){TV.viewer(v.classList.contains('open'));};"
            + "new MutationObserver(f).observe(v,{attributes:true,attributeFilter:['class']});f();})()";

    private class Bridge {
        @android.webkit.JavascriptInterface
        public void viewer(boolean open) {
            viewerOpen = open;
        }
    }
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        android.webkit.CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new Client());
        web.addJavascriptInterface(new Bridge(), "TV");
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        pointer = new Pointer(this);
        root.addView(pointer, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(this);
        status.setTextColor(0xCCFFFFFF);
        status.setTextSize(22);
        status.setGravity(Gravity.CENTER);
        status.setPadding(80, 0, 80, 0);
        root.addView(status, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        say("Looking for Photo Vault on the Wi-Fi…");
        find();
    }

    private void say(String text) {
        status.setText(text);
        status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---- finding the Mac ---------------------------------------------------------------------------

    private void find() {
        nsd = getSystemService(NsdManager.class);
        String last = prefs.getString(HOST, null);
        // Not found within a few seconds: the last address that worked, if any.
        main.postDelayed(() -> {
            if (host != null) return;
            if (last != null) open(last);
            else say("Photo Vault isn't answering on the Wi-Fi.\n\nOn the Mac, check the gallery is running and "
                    + "Sharing → Share on home Wi-Fi is on, then open Photos again.");
        }, 6000);
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onServiceFound(NsdServiceInfo info) {
                if (!info.getServiceName().startsWith(NAME)) return;
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override public void onServiceResolved(NsdServiceInfo r) {
                        String ip = r.getHost() != null ? r.getHost().getHostAddress() : null;
                        if (ip != null && !ip.contains(":")) main.post(() -> open(ip + (r.getPort() == 443 ? "" : ":" + r.getPort())));
                    }
                    @Override public void onResolveFailed(NsdServiceInfo r, int code) { }
                });
            }
            @Override public void onServiceLost(NsdServiceInfo info) { }
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onStartDiscoveryFailed(String type, int code) { }
            @Override public void onStopDiscoveryFailed(String type, int code) { }
        };
        nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery);
    }

    private void open(String address) {
        if (address.equals(host)) return;
        host = address;
        say("Opening Photo Vault…");
        web.loadUrl("https://" + address + "/");
    }

    // ---- the page ----------------------------------------------------------------------------------

    private class Client extends WebViewClient {
        @Override
        public void onPageFinished(WebView v, String url) {
            if (url.startsWith("https://")) {
                say("");
                v.evaluateJavascript(VIEWER_WATCH, null);
                prefs.edit().putString(HOST, host).apply();
            }
        }

        /** Links to photo-vault.local go to the address the gallery was found at. */
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if ("photo-vault.local".equalsIgnoreCase(u.getHost()) && host != null) {
                v.loadUrl(u.toString().replaceFirst("(?i)photo-vault\\.local(:\\d+)?", host));
                return true;
            }
            return false;
        }

        @Override
        public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
            String fp = fingerprint(error.getCertificate());
            String pinned = prefs.getString(PIN, null);
            if (fp != null && fp.equals(pinned)) {
                handler.proceed();
                return;
            }
            SslCertificate c = error.getCertificate();
            String who = c.getIssuedTo().getUName();
            String msg = (pinned == null ? "The first time, check this is your Mac's Photo Vault:\n\n"
                    : "Photo Vault's certificate has changed. Trust the new one only if you set the gallery up again:\n\n")
                    + who + "\n\nSHA-256 " + (fp == null ? "?" : fp.substring(0, 23) + "…");
            new AlertDialog.Builder(PhotosActivity.this)
                    .setTitle("Trust Photo Vault?")
                    .setMessage(msg)
                    .setPositiveButton("Trust", (d, w) -> {
                        prefs.edit().putString(PIN, fp).apply();
                        handler.proceed();
                    })
                    .setNegativeButton("Cancel", (d, w) -> {
                        handler.cancel();
                        say("Not opened: the certificate wasn't trusted.");
                    })
                    .setCancelable(false)
                    .show();
        }
    }

    private static String fingerprint(SslCertificate c) {
        try {
            X509Certificate x = c.getX509Certificate();
            if (x == null) return null;
            byte[] d = MessageDigest.getInstance("SHA-256").digest(x.getEncoded());
            StringBuilder b = new StringBuilder();
            for (byte by : d) b.append(String.format("%02X:", by));
            return b.substring(0, b.length() - 1);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- the remote --------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_MENU) {
            if (down && e.getRepeatCount() == 0) {
                pointerMode = !pointerMode;
                pointer.setVisibility(pointerMode ? View.VISIBLE : View.GONE);
                Toast.makeText(this, pointerMode ? "Arrows move the pointer" : "Arrows go to the page (Menu: pointer)",
                        Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (!down) return true;
            if (viewerOpen) pageKey(KeyEvent.KEYCODE_ESCAPE);   // closes the photo (or zooms back out)
            else if (web.canGoBack()) web.goBack();
            else finish();
            return true;
        }
        if (!pointerMode || status.getVisibility() == View.VISIBLE) return super.dispatchKeyEvent(e);
        if (viewerOpen && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            // Previous and next photo: the gallery's ArrowLeft / ArrowRight.
            web.requestFocus();
            web.dispatchKeyEvent(e);
            return true;
        }
        float step = Math.min(8 + e.getRepeatCount() * 6, 48) * getResources().getDisplayMetrics().density / 2;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: if (down) pointer.move(0, -step); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (down) pointer.move(0, step); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: if (down) pointer.move(-step, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) pointer.move(step, 0); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (down && e.getRepeatCount() == 0) tap(pointer.x, pointer.y);
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void pageKey(int code) {
        web.requestFocus();
        long t = SystemClock.uptimeMillis();
        web.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        web.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    private void tap(float x, float y) {
        long t = SystemClock.uptimeMillis();
        touch(t, t, MotionEvent.ACTION_DOWN, x, y);
        touch(t, t + 60, MotionEvent.ACTION_UP, x, y);
    }

    /** The pointer pushed past an edge: drag the page under it, as a finger would. */
    private void drag(float x, float y, float dx, float dy) {
        long t = SystemClock.uptimeMillis();
        touch(t, t, MotionEvent.ACTION_DOWN, x, y);
        for (int i = 1; i <= 4; i++) touch(t, t + i * 12, MotionEvent.ACTION_MOVE, x - dx * i / 4, y - dy * i / 4);
        touch(t, t + 60, MotionEvent.ACTION_UP, x - dx, y - dy);
    }

    private void touch(long downAt, long at, int action, float x, float y) {
        MotionEvent m = MotionEvent.obtain(downAt, at, action, x, y, 0);
        m.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(m);
        m.recycle();
    }

    /** The on-screen pointer. */
    private class Pointer extends View {
        float x = -1, y = -1;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);

        Pointer(Activity a) {
            super(a);
            fill.setColor(0xEEFFFFFF);
            ring.setColor(0x99000000);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(4);
        }

        void move(float dx, float dy) {
            float nx = x + dx, ny = y + dy, edge = 2;
            float w = getWidth(), h = getHeight();
            if (ny < edge) drag(x, h / 2, 0, -h / 3);          // scroll up
            else if (ny > h - edge) drag(x, h / 2, 0, h / 3);   // scroll down
            else if (nx < edge) drag(w / 2, y, -w / 3, 0);
            else if (nx > w - edge) drag(w / 2, y, w / 3, 0);
            x = Math.max(edge, Math.min(w - edge, nx));
            y = Math.max(edge, Math.min(h - edge, ny));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (x < 0) {
                x = getWidth() / 2f;
                y = getHeight() / 2f;
            }
            float r = 11 * getResources().getDisplayMetrics().density / 2;
            c.drawCircle(x, y, r, fill);
            c.drawCircle(x, y, r, ring);
        }
    }

    @Override
    protected void onDestroy() {
        try {
            if (discovery != null) nsd.stopServiceDiscovery(discovery);
        } catch (IllegalArgumentException ignored) {
        }
        main.removeCallbacksAndMessages(null);
        web.destroy();
        super.onDestroy();
    }
}
