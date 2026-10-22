package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * apps.sh's way in: Fire OS 8 keeps adb out of Android/data, so config.json can't be pushed there.
 * Guarded by android.permission.DUMP, which adb's shell has and ordinary apps can't get.
 *
 *   am broadcast -n com.gathin.tvhome/.LayoutReceiver                          current layout as JSON
 *   am broadcast -n ... -a com.gathin.tvhome.SET_LAYOUT --esa dock a,b --esa hidden c,d
 *   am broadcast -n ... -a com.gathin.tvhome.RESET_LAYOUT                       back to config.xml
 *
 * Every call answers with the layout in force afterwards (the broadcast's result data). Home picks a
 * change up when it is next started with --ez reload true.
 */
public class LayoutReceiver extends BroadcastReceiver {

    static final String SET = "com.gathin.tvhome.SET_LAYOUT";
    static final String RESET = "com.gathin.tvhome.RESET_LAYOUT";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        boolean ok = true;
        if (SET.equals(intent.getAction())) {
            Config cfg = Config.load(ctx);
            List<String> dock = list(intent, "dock", cfg.dockApps);
            List<String> hidden = list(intent, "hidden", cfg.hiddenApps);
            ok = Config.saveLayout(ctx, dock, hidden);
        } else if (RESET.equals(intent.getAction())) {
            File dir = Config.dir(ctx);
            File file = dir != null ? new File(dir, Config.FILE_NAME) : null;
            ok = file == null || !file.exists() || file.delete();
        }
        Config now = Config.load(ctx);
        try {
            JSONObject out = new JSONObject();
            out.put("ok", ok);
            out.put("dock_apps", new JSONArray(now.dockApps));
            out.put("hidden_apps", new JSONArray(now.hiddenApps));
            setResultData(out.toString());
        } catch (Exception e) {
            setResultData("{\"ok\":false}");
        }
        setResultCode(ok ? 1 : 0);
    }

    /** An --esa extra; an empty one (--esa dock '') clears the list, a missing one keeps it. */
    private static List<String> list(Intent intent, String key, List<String> keep) {
        if (!intent.hasExtra(key)) return keep;
        String[] items = intent.getStringArrayExtra(key);
        List<String> out = new ArrayList<>();
        if (items != null) for (String s : items) if (s != null && !s.trim().isEmpty()) out.add(s.trim());
        return out;
    }
}
