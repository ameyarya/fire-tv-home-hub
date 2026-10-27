package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Fire OS keeps Home for Amazon's launcher: com.amazon.tv.launcher is protected, cannot be disabled, and
 * set-home-activity is ignored. So whenever Amazon's home activity comes to the front (Home button, back
 * out of an app or out of Settings, boot), start this launcher on top of it.
 *
 * Fire OS 8 opens Settings as com.amazon.tv.launcher/.ui.MainSettingsActivity, and Amazon's home activity
 * resumes for a moment behind it as it starts. Home events in that short window are ignored; after it, a
 * home event means Settings was left.
 *
 * It also times the screensaver. Fire OS 8 ignores a chosen screensaver (its Ambient service always shows
 * Amazon's), so after the minutes set in Smart Home's Settings with no key pressed, this opens Smart Home's
 * clock (com.homecontrol.tv/.ClockActivity), unless sound is playing. Keys are only watched, never consumed.
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.FireHomeWatcher
 *   settings put secure accessibility_enabled 1
 */
public class FireHomeWatcher extends AccessibilityService {

    /**
     * At most one launch per this window, so the two homes can't ping-pong. An Amazon home event inside it
     * (Home pressed again quickly) is not dropped: it is looked at again when the window ends.
     */
    private static final long GUARD_MS = 800;
    /** How long Amazon's home may flash up while Settings is starting. */
    private static final long SETTINGS_START_MS = 2000;
    private long lastLaunch = -GUARD_MS;
    /** When Amazon's home last came to the front; compared with MainActivity.resumedAt. */
    private long lastAmazonHome;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable recheck = this::recheck;
    private static long settingsOpenedAt = -SETTINGS_START_MS;

    private static final ComponentName CLOCK = new ComponentName("com.homecontrol.tv", "com.homecontrol.tv.ClockActivity");
    private final Runnable idle = this::idle;
    private SharedPreferences saver;
    private final SharedPreferences.OnSharedPreferenceChangeListener saverChanged = (p, k) -> poke();
    private long lastKey = SystemClock.uptimeMillis();
    private String lastIdle = "not yet";
    /** The window in front, from every app's window events. */
    private String front = "";

    /**
     * The screen turning on and off is only broadcast to receivers registered at runtime, and this service
     * is the one part of the launcher that is always running. It passes each change to the Smart Home app,
     * whose TV on/off automations run on it (com.homecontrol.tv/.Scheduler, TV_EVENT).
     */
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            boolean on = Intent.ACTION_SCREEN_ON.equals(i.getAction());
            if (on) poke();
            else handler.removeCallbacks(idle);
            try {
                sendBroadcast(new Intent("com.homecontrol.tv.TV_EVENT").putExtra("on", on)
                        .setComponent(new ComponentName("com.homecontrol.tv", "com.homecontrol.tv.Scheduler"))
                        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES));
            } catch (RuntimeException ignored) {   // Smart Home not installed
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screen, f);
        IntentFilter r = new IntentFilter(REMOTE);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(remote, r, RECEIVER_EXPORTED);
        else registerReceiver(remote, r);
        saver = getSharedPreferences(ScreensaverReceiver.PREFS, MODE_PRIVATE);
        saver.registerOnSharedPreferenceChangeListener(saverChanged);
        poke();
    }

    // ---- phone remote --------------------------------------------------------------------------------

    /**
     * The Smart Home app's phone remote sends its arrows, OK, Back, Home and typing here: Fire OS lets no
     * ordinary app press keys, and an accessibility service can move focus, select and type instead.
     * Extras: "key" (a KeyEvent code), "text" (typed into the focused field), "open" (a link),
     * "component" (an app to start).
     */
    static final String REMOTE = "com.gathin.tvhome.REMOTE";
    private final BroadcastReceiver remote = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            poke();
            if (i.hasExtra("key")) remoteKey(i.getIntExtra("key", 0));
            if (i.hasExtra("text")) type(i.getStringExtra("text"));
            if (i.getBooleanExtra("readPin", false)) readPin(SystemClock.uptimeMillis() + 6000);
            String open = i.getStringExtra("open");
            if (open != null && open.matches("(?i)https?://\\S+")) {
                Intent v = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(open))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                String host = android.net.Uri.parse(open).getHost();
                if (host != null && (host.endsWith("youtube.com") || host.equals("youtu.be"))) v.setPackage("com.amazon.firetv.youtube");
                try {
                    startActivity(v);
                } catch (RuntimeException e) {
                    v.setPackage(null);
                    try {
                        startActivity(v);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            String comp = i.getStringExtra("component");
            ComponentName cn = comp != null ? ComponentName.unflattenFromString(comp) : null;
            if (cn != null) {
                Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(cn.getPackageName());
                if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(cn.getPackageName());
                if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }
    };

    /**
     * Smart Home pairing with Fire OS's remote service, which shows a PIN on the TV: read it off the screen and
     * hand it back, so nobody has to type it. Looks for a few seconds, only when Smart Home asks.
     */
    private void readPin(long until) {
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo r = w.getRoot();
            if (r == null) continue;
            for (AccessibilityNodeInfo n : r.findAccessibilityNodeInfosByViewId("com.amazon.tv.turnstile:id/pinDisplay")) {
                CharSequence pin = n.getText();
                if (pin == null) continue;
                sendBroadcast(new Intent("com.homecontrol.tv.PAIR_PIN").setComponent(new ComponentName("com.homecontrol.tv",
                        "com.homecontrol.tv.PinReceiver")).putExtra("pin", pin.toString().trim()));
                return;
            }
        }
        if (SystemClock.uptimeMillis() < until) new Handler(Looper.getMainLooper()).postDelayed(() -> readPin(until), 250);
    }

    private void remoteKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BACK: performGlobalAction(GLOBAL_ACTION_BACK); return;
            case KeyEvent.KEYCODE_HOME: performGlobalAction(GLOBAL_ACTION_HOME); return;
            case KeyEvent.KEYCODE_SLEEP: performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN); return;
            case KeyEvent.KEYCODE_DPAD_UP: move(View.FOCUS_UP); return;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(View.FOCUS_DOWN); return;
            case KeyEvent.KEYCODE_DPAD_LEFT: move(View.FOCUS_LEFT); return;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(View.FOCUS_RIGHT); return;
            case KeyEvent.KEYCODE_DPAD_CENTER: select(false); return;
            case KeyEvent.KEYCODE_ENTER: enter(); return;
            case KeyEvent.KEYCODE_MENU: select(true); return;
            case KeyEvent.KEYCODE_DEL: deleteOne(); return;
            default:
        }
    }

    private AccessibilityNodeInfo focused() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        return f != null ? f : firstFocusable(root);
    }

    private static AccessibilityNodeInfo firstFocusable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isFocusable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = firstFocusable(n.getChild(i));
            if (f != null) return f;
        }
        return null;
    }

    private void move(int direction) {
        AccessibilityNodeInfo f = focused();
        if (f == null) return;
        if (!f.isFocused()) {
            f.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            return;
        }
        AccessibilityNodeInfo next = f.focusSearch(direction);
        if (next != null) next.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
    }

    private void select(boolean longPress) {
        int action = longPress ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK;
        for (AccessibilityNodeInfo n = focused(); n != null; n = n.getParent()) {
            if (n.performAction(action)) return;
        }
    }

    /**
     * The text field to type into: the one with the focus, in any window but the keyboard's; else the first one on
     * screen (a browser often has the page focused, not a field), which gets the focus.
     */
    private AccessibilityNodeInfo field() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null) roots.add(active);
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            AccessibilityNodeInfo r = w.getRoot();
            if (r != null && !r.equals(active)) roots.add(r);
        }
        for (AccessibilityNodeInfo r : roots) {
            AccessibilityNodeInfo f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f != null && f.isEditable()) return f;
        }
        for (AccessibilityNodeInfo r : roots) {
            AccessibilityNodeInfo f = firstEditable(r);
            if (f != null) {
                f.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                return f;
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo firstEditable(AccessibilityNodeInfo n) {
        if (n == null || !n.isVisibleToUser()) return null;
        if (n.isEditable() && n.isEnabled()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = firstEditable(n.getChild(i));
            if (f != null) return f;
        }
        return null;
    }

    /** Enter: submits the field being typed in (a search, a link), else selects like OK. */
    private void enter() {
        AccessibilityNodeInfo f = focused();
        if (f != null && f.isEditable() && android.os.Build.VERSION.SDK_INT >= 30
                && f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())) return;
        select(false);
    }

    private void type(String text) {
        if (text == null) return;
        AccessibilityNodeInfo f = field();
        if (f == null) {   // no text field (Netflix, Prime Video draw their own keyboard): Fire OS's remote service types
            sendBroadcast(new Intent("com.homecontrol.tv.TYPE").setComponent(new ComponentName("com.homecontrol.tv",
                    "com.homecontrol.tv.PinReceiver")).putExtra("text", text));
            return;
        }
        CharSequence now = f.getText();
        setText(f, (now == null || f.isShowingHintText() ? "" : now.toString()) + text);
    }

    private void deleteOne() {
        AccessibilityNodeInfo f = field();
        if (f == null || f.getText() == null || f.isShowingHintText()) return;
        String t = f.getText().toString();
        if (!t.isEmpty()) setText(f, t.substring(0, t.length() - 1));
    }

    private static void setText(AccessibilityNodeInfo f, String text) {
        android.os.Bundle b = new android.os.Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    /** Every key restarts the screensaver timer. Returns false: the key goes on as usual. */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) poke();
        return false;
    }

    private int minutes() {
        return saver != null ? saver.getInt(ScreensaverReceiver.KEY, 0) : 0;
    }

    private void poke() {
        lastKey = SystemClock.uptimeMillis();
        handler.removeCallbacks(idle);
        if (minutes() > 0) handler.postDelayed(idle, minutes() * 60_000L);
    }

    private void idle() {
        boolean screenOn = ((PowerManager) getSystemService(POWER_SERVICE)).isInteractive();
        boolean playing = ((AudioManager) getSystemService(AUDIO_SERVICE)).isMusicActive();
        if (CLOCK.getClassName().equals(front)) return;   // already showing
        IdleClock.Action a = IdleClock.decide(minutes(), screenOn, playing || IdleClock.isLiveView(front));
        lastIdle = a + (playing ? " (playing)" : "") + " at " + SystemClock.uptimeMillis();
        if (a == IdleClock.Action.CHECK_AGAIN) handler.postDelayed(idle, IdleClock.RECHECK_MS);
        else if (a == IdleClock.Action.OPEN_CLOCK) {
            try {
                startActivity(new Intent().setComponent(CLOCK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {   // Smart Home not installed
                lastIdle = "no clock: " + e.getClass().getSimpleName();
            }
        }
    }

    /** `dumpsys activity service com.gathin.tvhome/.FireHomeWatcher`: for the UI tests. */
    @Override
    protected void dump(FileDescriptor fd, PrintWriter w, String[] args) {
        w.println("screensaver minutes=" + minutes() + " idle_ms=" + (SystemClock.uptimeMillis() - lastKey)
                + " playing=" + ((AudioManager) getSystemService(AUDIO_SERVICE)).isMusicActive()
                + " front=" + front + " last=" + lastIdle);
    }

    /** Called by the launcher's Settings pill just before it opens Settings, which passes through Amazon's home. */
    static void allowAmazonHome() {
        settingsOpenedAt = SystemClock.uptimeMillis();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence p = event.getPackageName(), cl = event.getClassName();
        // Someone is using the TV: the highlight moved, a list scrolled, a screen opened. Fire OS doesn't pass
        // the remote's buttons to onKeyEvent, so these restart the screensaver timer; the clock itself doesn't.
        if (!CLOCK.getPackageName().equals(p == null ? null : p.toString())) poke();
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (p == null || cl == null) return;
        String pkg = p.toString(), cls = cl.toString();
        if (cls.contains(".")) front = cls;   // activities, not dialogs and views named without a package
        long now = SystemClock.uptimeMillis();
        if (cls.contains("MainSettingsActivity")) {
            settingsOpenedAt = now;
            return;
        }
        boolean amazonHome = ("com.amazon.tv.launcher".equals(pkg) && cls.contains("HomeActivity"))
                || "com.amazon.firehomestarter".equals(pkg);
        if (!amazonHome || now - settingsOpenedAt < SETTINGS_START_MS) return;
        lastAmazonHome = now;
        if (now - lastLaunch < GUARD_MS) {
            handler.removeCallbacks(recheck);
            handler.postAtTime(recheck, lastLaunch + GUARD_MS);
            return;
        }
        showHome();
    }

    /** After the guard: Amazon's home came up after this launcher last did, so it is still in front. */
    private void recheck() {
        if (MainActivity.resumedAt < lastAmazonHome && SystemClock.uptimeMillis() - settingsOpenedAt >= SETTINGS_START_MS) {
            showHome();
        }
    }

    private void showHome() {
        lastLaunch = SystemClock.uptimeMillis();
        startActivity(new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(screen);
        } catch (IllegalArgumentException ignored) {
        }
        handler.removeCallbacks(recheck);
        handler.removeCallbacks(idle);
        try {
            unregisterReceiver(remote);
        } catch (IllegalArgumentException ignored) {
        }
        if (saver != null) saver.unregisterOnSharedPreferenceChangeListener(saverChanged);
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }
}
