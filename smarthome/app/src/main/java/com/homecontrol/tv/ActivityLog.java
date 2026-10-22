package com.homecontrol.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * What happened, newest last: switches from the dashboard, scenes, automations and their "turn off
 * after" steps. Kept in the app (the system log rolls over within minutes on a Fire TV), up to KEEP lines,
 * shown under Activity on the TV and by home.sh status.
 */
final class ActivityLog {

    static final int KEEP = 100;
    private static final String PREFS = "activity";
    private static final String KEY = "lines";
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private ActivityLog() {
    }

    static synchronized void add(Context ctx, String text) {
        SharedPreferences p = prefs(ctx);
        List<String> lines = append(split(p.getString(KEY, "")), LocalDateTime.now().format(STAMP) + "  " + text, KEEP);
        p.edit().putString(KEY, String.join("\n", lines)).apply();
    }

    /** Everything, for the UI tests' backup slot (Storage.backup), and putting it back. */
    static synchronized String all(Context ctx) {
        return prefs(ctx).getString(KEY, "");
    }

    static synchronized void replace(Context ctx, String all) {
        prefs(ctx).edit().putString(KEY, all).commit();
    }

    /** The newest {@code n} lines, newest last. */
    static synchronized List<String> recent(Context ctx, int n) {
        List<String> all = split(prefs(ctx).getString(KEY, ""));
        return new ArrayList<>(all.subList(Math.max(0, all.size() - n), all.size()));
    }

    /** Lines that mention a name, newest last (the device screen's "Recent activity"). */
    static List<String> about(List<String> lines, String name) {
        List<String> out = new ArrayList<>();
        for (String l : lines) if (l.contains(name)) out.add(l);
        return out;
    }

    static List<String> append(List<String> lines, String line, int keep) {
        List<String> out = new ArrayList<>(lines);
        out.add(line.replace('\n', ' '));
        while (out.size() > keep) out.remove(0);
        return out;
    }

    static List<String> split(String all) {
        List<String> out = new ArrayList<>();
        for (String l : all.split("\n")) if (!l.isEmpty()) out.add(l);
        return out;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
