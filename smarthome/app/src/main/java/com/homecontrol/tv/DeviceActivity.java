package com.homecontrol.tv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One device, like an accessory's page in Apple Home: big state, power now, energy today and this month
 * (Kasa plugs keep both), and buttons to switch it, favorite it, move it to a room, rename or remove it,
 * with its recent activity underneath.
 */
public class DeviceActivity extends Activity {

    static final String EXTRA_ID = "device";
    private static final long POLL_MS = 3000, ENERGY_MS = 30_000;

    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::poll;
    private final Runnable onStorageChanged = this::bind;
    private Prompts prompts;
    private Editors editors;
    private String id;
    private Shelly.Status status;
    private boolean offline;
    private double[] energy = {Double.NaN, Double.NaN};
    private long energyAt;
    private TextView name, where, bigState, btnToggle, btnFavorite, btnRoom, history;
    private ImageView icon;
    private final TextView[] statValues = new TextView[3];
    private final TextView[] statLabels = new TextView[3];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device);
        id = getIntent().getStringExtra(EXTRA_ID);
        prompts = new Prompts(this);
        editors = new Editors(this, prompts, io);
        name = findViewById(R.id.name);
        where = findViewById(R.id.where);
        bigState = findViewById(R.id.bigState);
        icon = findViewById(R.id.bigIcon);
        btnToggle = findViewById(R.id.btnToggle);
        btnFavorite = findViewById(R.id.btnFavorite);
        btnRoom = findViewById(R.id.btnRoom);
        history = findViewById(R.id.history);
        LinearLayout stats = findViewById(R.id.stats);
        for (int i = 0; i < 3; i++) {
            View s = getLayoutInflater().inflate(R.layout.item_stat, stats, false);
            statValues[i] = s.findViewById(R.id.value);
            statLabels[i] = s.findViewById(R.id.label);
            stats.addView(s);
        }
        btnToggle.setOnClickListener(v -> toggle());
        btnFavorite.setOnClickListener(v -> {
            Home.Device d = device();
            if (d == null) return;
            d.favorite = !d.favorite;
            Storage.save(this);
        });
        btnRoom.setOnClickListener(v -> {
            Home.Device d = device();
            if (d != null) editors.pickRoom(d.roomId, r -> { d.roomId = r; Storage.save(this); });
        });
        findViewById(R.id.btnMove).setOnClickListener(v -> {   // its place among the tiles, on the TV and the phone
            Home.Device d = device();
            if (d != null) prompts.list("Move " + d.name, Arrays.asList("Move earlier", "Move later"),
                    i -> editors.move("device", d.id, i == 0 ? -1 : 1));
        });
        findViewById(R.id.btnRename).setOnClickListener(v -> {
            Home.Device d = device();
            if (d != null) prompts.askText(getString(R.string.rename), d.name, "", n -> { d.name = n; Storage.save(this); });
        });
        findViewById(R.id.btnRemove).setOnClickListener(v -> {
            Home.Device d = device();
            if (d == null) return;
            prompts.list("Remove " + d.name + "?", Arrays.asList("Remove it, and its steps in scenes and automations", "Keep it"), i -> {
                if (i != 0) return;
                Storage.get(this).removeDevice(d.id);
                Storage.save(this);
                ActivityLog.add(this, d.name + " removed");
                finish();
            });
        });
        bind();
        btnToggle.requestFocus();
    }

    private Home.Device device() {
        return Storage.get(this).device(id);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Storage.listen(onStorageChanged);
        poll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Storage.unlisten(onStorageChanged);
        main.removeCallbacks(poll);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void poll() {
        main.removeCallbacks(poll);
        Home.Device d = device();
        if (d == null) {
            finish();
            return;
        }
        boolean wantEnergy = d.hasSwitch && System.currentTimeMillis() - energyAt > ENERGY_MS;
        io.execute(() -> {
            Shelly.Status st = null;
            double[] en = null;
            try {
                st = Devices.status(d);
                if (wantEnergy) en = Devices.energy(d);
            } catch (IOException ignored) {
            }
            Shelly.Status got = st;
            double[] gotEnergy = en;
            main.post(() -> {
                offline = got == null;
                if (got != null) status = got;
                if (gotEnergy != null) {
                    energy = gotEnergy;
                    energyAt = System.currentTimeMillis();
                }
                bind();
            });
        });
        main.postDelayed(poll, POLL_MS);
    }

    private void toggle() {
        Home.Device d = device();
        if (d == null || !d.hasSwitch) return;
        boolean want = status == null || !status.on;
        status = new Shelly.Status(want, status != null ? status.watts : Double.NaN, Double.NaN, Double.NaN);
        bind();
        io.execute(() -> {
            try {
                Devices.set(d, want);
                ActivityLog.add(this, d.name + " turned " + (want ? "on" : "off") + " from the TV");
            } catch (IOException e) {
                ActivityLog.add(this, d.name + " didn't answer (turn " + (want ? "on" : "off") + ")");
            }
            main.post(this::poll);
        });
    }

    private void bind() {
        Home.Device d = device();
        if (d == null) return;
        name.setText(d.name);
        Home.Room r = Storage.get(this).room(d.roomId);
        String brand = Home.KASA.equals(d.brand) ? "Kasa" : "Shelly";
        where.setText((r != null ? r.name + " · " : "") + brand + " · " + d.host);
        icon.setImageResource(d.hasSwitch ? R.drawable.ic_plug : R.drawable.ic_sensor);
        boolean on = status != null && status.on && !offline;
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(on ? 0xFF4CD08A : 0xFFFFFFFF));
        if (offline) bigState.setText(R.string.offline);
        else if (status == null) bigState.setText("…");
        else if (d.hasSwitch) bigState.setText(status.on ? "On" : "Off");
        else bigState.setText(Double.isNaN(status.tempC) ? "" : String.format(Locale.US, "%.1f°", status.tempC));
        btnToggle.setVisibility(d.hasSwitch ? View.VISIBLE : View.GONE);
        btnToggle.setText(status != null && status.on ? "Turn off" : "Turn on");
        btnFavorite.setText(d.favorite ? "★ Favorite" : "☆ Favorite");
        btnRoom.setText("Room: " + (r != null ? r.name : "none"));
        if (d.hasSwitch) {
            stat(0, status == null || Double.isNaN(status.watts) ? "–" : String.format(Locale.US, "%.1f W", status.watts), "Power now");
            stat(1, wh(energy[0]), "Used today");
            stat(2, wh(energy[1]), "Used this month");
        } else {
            stat(0, status == null || Double.isNaN(status.tempC) ? "–" : String.format(Locale.US, "%.1f °C", status.tempC), "Temperature");
            stat(1, status == null || Double.isNaN(status.humidity) ? "–" : String.format(Locale.US, "%.0f %%", status.humidity), "Humidity");
            stat(2, "", "");
        }
        List<String> lines = ActivityLog.about(ActivityLog.recent(this, ActivityLog.KEEP), d.name);
        Collections.reverse(lines);
        history.setText(lines.isEmpty() ? "Nothing yet" : String.join("\n", lines.subList(0, Math.min(6, lines.size()))));
    }

    private void stat(int i, String value, String label) {
        statValues[i].setText(value);
        statLabels[i].setText(label);
    }

    static String wh(double v) {
        if (Double.isNaN(v)) return "–";
        return v >= 1000 ? String.format(Locale.US, "%.2f kWh", v / 1000) : String.format(Locale.US, "%.0f Wh", v);
    }

    /** For tests/smarthome_test.py. */
    @Override
    public void dump(String prefix, FileDescriptor fd, PrintWriter w, String[] args) {
        super.dump(prefix, fd, w, args);
        w.println(prefix + "Device state:");
        w.println(prefix + "  name=" + name.getText());
        w.println(prefix + "  where=" + where.getText());
        w.println(prefix + "  state=" + bigState.getText());
        List<String> stats = new ArrayList<>();
        for (int i = 0; i < 3; i++) stats.add(statLabels[i].getText() + ": " + statValues[i].getText());
        w.println(prefix + "  stats=" + String.join(" | ", stats));
        w.println(prefix + "  buttons=" + btnToggle.getText() + " | " + btnFavorite.getText() + " | " + btnRoom.getText());
        View f = getCurrentFocus();
        w.println(prefix + "  focus=" + (f != null && f.getId() != View.NO_ID ? getResources().getResourceEntryName(f.getId()) : "none"));
        w.println(prefix + "  history=" + history.getText().toString().replace('\n', '/'));
        prompts.dump(prefix, w);
    }
}
