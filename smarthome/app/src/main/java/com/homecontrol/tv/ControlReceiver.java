package com.homecontrol.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.IOException;

/**
 * Set-up from the Mac (home.sh) and the UI tests, over adb. Guarded by android.permission.DUMP, which
 * adb's shell has and ordinary apps can't get. Every call answers with the Home as JSON (Commands.report),
 * with camera addresses hidden because they hold passwords.
 *
 *   am broadcast -n com.homecontrol.tv/.ControlReceiver                                  read
 *   ... -a com.homecontrol.tv.ADD_DEVICE --es host 192.168.1.198 [--es name Lamp]
 *   ... -a com.homecontrol.tv.ADD_SCENE --es name 'Movie night' --esa on Lamp --esa off 'TV plug'
 *   ... -a com.homecontrol.tv.ADD_SCHEDULE --es name 'Lamp off' --es at 23:30 --es days daily --esa off Lamp
 *   ... -a com.homecontrol.tv.SET_LOCATION --es lat 37.77 --es lon -122.42
 *   ... -a com.homecontrol.tv.ADD_CAMERA --es name 'Living room' --es url rtsp://...
 *   ... -a com.homecontrol.tv.SET_ENABLED --es name 'Lamp off' --es enabled false
 *   ... -a com.homecontrol.tv.RENAME --es name 'Plug 1' --es to Lamp
 *   ... -a com.homecontrol.tv.REMOVE --es name <name or id>
 *   ... -a com.homecontrol.tv.ADD_ROOM --es name Bedroom
 *   ... -a com.homecontrol.tv.SET_ROOM --es name 'Plug 1' --es room Bedroom   (room created if new)
 *   ... -a com.homecontrol.tv.SET_FAVORITE --es name 'Plug 1' --es favorite true|false
 *   ... -a com.homecontrol.tv.SET_HOST --es name 'Plug 1' --es host 192.168.1.198
 *   ... -a com.homecontrol.tv.RESET
 *   ... -a com.homecontrol.tv.CLEAR_ACTIVITY                 (home.sh clear-activity)
 *   ... -a com.homecontrol.tv.HTTPS_CERT             (https-setup.sh: the phone page's certificate)
 *   ... -a com.homecontrol.tv.REMOTE_CODE --es code 1234   (the phone remote's 4-digit code)
 *   ... -a com.homecontrol.tv.REMOTE_LINK            (the phone remote's secret: setup for the voice stick)
 *   ... -a com.homecontrol.tv.BACKUP / RESTORE      (UI tests: keep the owner's setup on the TV meanwhile)
 */
public class ControlReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction() == null ? "" : intent.getAction();
        if (action.equals(Commands.P + "HTTPS_CERT")) {   // from https-setup.sh: --es p12 <base64> --es ca <base64 of the PEM>
            String p12 = intent.getStringExtra("p12"), ca64 = intent.getStringExtra("ca");
            String ca = ca64 == null ? null
                    : new String(android.util.Base64.decode(ca64, android.util.Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8);
            if (p12 == null || ca == null || !ca.contains("BEGIN CERTIFICATE")) {
                setResultCode(0);
                return;
            }
            Remote.setCertificate(ctx, p12, ca);
            ctx.stopService(new Intent(ctx, RemoteService.class));   // serve it from now on
            RemoteService.start(ctx);
            setResultData("{\"https\":true}");
            setResultCode(1);
            return;
        }
        if (action.equals(Commands.P + "REMOTE_AWAKE")) {   // phone remote while the TV is off: --ez on true|false
            Remote.setKeepAwake(ctx, intent.getBooleanExtra("on", false));
            setResultData("{\"awake\":" + Remote.keepAwake(ctx) + "}");
            setResultCode(1);
            return;
        }
        if (action.equals(Commands.P + "REMOTE_CODE")) {
            setResultCode(Remote.setCode(ctx, intent.getStringExtra("code")) ? 1 : 0);
            return;
        }
        if (action.equals(Commands.P + "REMOTE_LINK")) {   // the phone remote's secret, for the voice stick's setup
            try {
                setResultData(new org.json.JSONObject().put("on", Remote.isOn(ctx)).put("address", Remote.address())
                        .put("secret", Remote.token(ctx)).toString());
                setResultCode(1);
            } catch (org.json.JSONException e) {
                setResultCode(0);
            }
            return;
        }
        PendingResult result = goAsync();
        new Thread(() -> {
            String[] error = {null};
            if (action.equals(Commands.P + "CLEAR_ACTIVITY")) {
                ActivityLog.replace(ctx, "");
            } else if (action.equals(Commands.P + "BACKUP") || action.equals(Commands.P + "RESTORE")) {
                onMain(ctx, () -> {
                    boolean ok = action.endsWith("BACKUP") ? Storage.backup(ctx) : Storage.restoreBackup(ctx);
                    if (!ok) error[0] = action.endsWith("BACKUP") ? "a backup is already waiting to be restored" : "no backup to restore";
                });
            } else if (action.equals(Commands.P + "ADD_DEVICE")) {
                try {
                    String host = intent.getStringExtra("host");
                    if (host == null || host.trim().isEmpty()) throw new IOException("no host");
                    Shelly.Info info = Devices.probe(host.trim());
                    Home.Device d = MainActivity.deviceFrom(host.trim(), info);
                    String name = intent.getStringExtra("name");
                    if (name != null && !name.trim().isEmpty()) d.name = name.trim();
                    onMain(ctx, () -> {
                        Home h = Storage.get(ctx);
                        Home.Device same = h.deviceByUid(d.uid);
                        if (same != null) same.host = d.host;   // known device at a new address
                        else if (h.deviceAt(d.host) == null) h.devices.add(d);
                        Storage.save(ctx);
                    });
                } catch (IOException e) {
                    error[0] = "no Shelly or Kasa device answered: " + e.getMessage();
                }
            } else {
                Commands.Args args = new Commands.Args() {
                    @Override public String str(String key) {
                        return intent.getStringExtra(key);
                    }
                    @Override public String[] list(String key) {
                        return intent.getStringArrayExtra(key);
                    }
                };
                onMain(ctx, () -> {
                    error[0] = Commands.apply(Storage.get(ctx), action, args);
                    if (error[0] == null && !action.isEmpty()) Storage.save(ctx);
                });
            }
            onMain(ctx, () -> {
                result.setResultCode(error[0] == null ? 1 : 0);
                String report = Commands.report(Storage.get(ctx), java.time.ZoneId.systemDefault());
                if (Storage.hasBackup(ctx)) report = report.replaceFirst("^\\{", "{\"backup\":true,");
                report = report.replaceFirst("^\\{", "{\"activity\":" + new org.json.JSONArray(ActivityLog.recent(ctx, 30)) + ",");
                result.setResultData(error[0] == null ? report
                        : "{\"error\":\"" + error[0].replace("\"", "'") + "\"}");
                result.finish();
            });
        }).start();
    }

    /** Runs on the main thread and waits, so Storage is only touched there. */
    private static void onMain(Context ctx, Runnable r) {
        Object lock = new Object();
        boolean[] done = {false};
        new android.os.Handler(ctx.getMainLooper()).post(() -> {
            try {
                r.run();
            } finally {
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            while (!done[0]) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }
}
