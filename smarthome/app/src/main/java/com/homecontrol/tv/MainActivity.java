package com.homecontrol.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Home screen, laid out like Apple Home: a summary line, filters (All, Plugs, Cameras, Scenes,
 * Automations), then Favorites, Scenes, Cameras, each room's devices, Automations and the TV itself.
 * OK switches a plug, runs a scene, opens a camera or pauses an automation; holding OK (or Menu) opens
 * the device screen or the item's menu. + Add, Activity and Settings sit at the top. Device states are
 * polled every few seconds while the screen is open, straight from each device.
 */
public class MainActivity extends Activity {

    static final int COLUMNS = 4;
    private static final long POLL_MS = 4000;
    static final String[] FILTERS = {"all", "plugs", "cameras", "scenes", "automations"};

    /** One cell of the grid. */
    static final class Tile {
        static final int HEADER = 0, ITEM = 1;
        final int type;
        /** scene, device, sensor, camera, schedule, tv-input, tv-volume (or header) */
        final String kind;
        final String id;
        final String title;
        /** Which section it is in: the same device can be under Favorites and under its room. */
        String section = "";
        String detail = "";
        String state = "";
        int icon;

        Tile(int type, String kind, String id, String title) {
            this.type = type;
            this.kind = kind;
            this.id = id;
            this.title = title;
        }

        String key() {
            return section + ":" + kind + ":" + id;
        }
    }

    private final List<Tile> tiles = new ArrayList<>();
    private final Map<String, Shelly.Status> statuses = new HashMap<>();
    private final Set<String> offline = new HashSet<>();
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::pollNow;
    private final Runnable onStorageChanged = () -> {
        home = Storage.get(this);   // a reset (over adb) replaces the whole Home
        statuses.keySet().retainAll(ids());
        offline.retainAll(ids());
        rebuild();
        pollNow();
    };
    private Adapter adapter;
    private RecyclerView grid;
    private TextView status;
    private LinearLayout filterRow;
    private String filter = "all";
    private boolean firstShow = true;
    private Home home;
    private Prompts prompts;
    private Editors editors;
    /** The re-find scan (see Refind). */
    private Discovery refindDiscovery;
    private long lastRefind = -Refind.MIN_INTERVAL_MS;
    /** Devices from before identities were kept that have been asked for theirs this session. */
    private final Set<String> askedUid = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RemoteService.start(this);
        setContentView(R.layout.activity_main);
        home = Storage.get(this);
        prompts = new Prompts(this);
        editors = new Editors(this, prompts, io);
        status = findViewById(R.id.status);
        grid = findViewById(R.id.grid);
        filterRow = findViewById(R.id.filters);
        findViewById(R.id.btnAdd).setOnClickListener(v -> editors.addMenu());
        findViewById(R.id.btnActivity).setOnClickListener(v -> editors.activity());
        findViewById(R.id.btnSettings).setOnClickListener(v -> editors.settings());
        // Down from the top buttons goes to the chosen filter, not whichever pill happens to sit below.
        for (int id : new int[]{R.id.btnAdd, R.id.btnActivity, R.id.btnSettings}) {
            findViewById(id).setOnKeyListener((v, keyCode, event) -> {
                if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN || event.getAction() != KeyEvent.ACTION_DOWN) return false;
                focusFilter();
                return true;
            });
        }
        String[] labels = {getString(R.string.filter_all), getString(R.string.filter_plugs), getString(R.string.cameras),
                getString(R.string.scenes), getString(R.string.schedules)};
        for (int i = 0; i < FILTERS.length; i++) {
            TextView pill = (TextView) getLayoutInflater().inflate(R.layout.item_filter, filterRow, false);
            pill.setText(labels[i]);
            pill.setTag(FILTERS[i]);
            String f = FILTERS[i];
            pill.setOnClickListener(v -> setFilter(f));
            filterRow.addView(pill);
        }
        GridLayoutManager glm = new GridLayoutManager(this, COLUMNS);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return tiles.get(position).type == Tile.HEADER ? COLUMNS : 1;
            }
        });
        grid.setLayoutManager(glm);
        grid.setItemAnimator(null);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        rebuild();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Storage.listen(onStorageChanged);
        // Changes made while in the background (another screen, over adb, an automation) were not drawn; catch up.
        onStorageChanged.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Storage.unlisten(onStorageChanged);
        main.removeCallbacks(poll);
        editors.stop();
        if (refindDiscovery != null) refindDiscovery.stop();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void setFilter(String f) {
        filter = f;
        rebuild();
    }

    // ---- building the grid -----------------------------------------------------------------------

    private void rebuild() {
        tiles.clear();
        boolean all = filter.equals("all");
        if (all) {
            List<Runnable> favs = new ArrayList<>();
            for (Home.Scene s : home.scenes) if (s.favorite) favs.add(() -> sceneTile(s, "fav"));
            for (Home.Device d : home.devices) if (d.favorite) favs.add(() -> deviceTile(d, "fav"));
            for (Home.Camera c : home.cameras) if (c.favorite) favs.add(() -> cameraTile(c, "fav"));
            if (!favs.isEmpty()) {
                header(getString(R.string.favorites));
                for (Runnable r : favs) r.run();
            }
        }
        // The owner's section order (Home.sections, shared with the phone page).
        for (String section : home.sections) {
            switch (section) {
                case "scenes": sceneSection(all); break;
                case "cameras": cameraSection(all); break;
                case "plugs": plugSection(all); break;
                case "automations": automationSection(all); break;
                default:   // "remotes": the phone page's
            }
        }
        if (all) {
            header(getString(R.string.tv));
            item("tv-input", "", getString(R.string.inputs), R.drawable.ic_tv, "tv");
            item("tv-volume", "", getString(R.string.volume), R.drawable.ic_volume, "tv").state = TvControls.volumeText(this);
        }
        applyStatuses();
        adapter.notifyDataSetChanged();
        afterRebuild();
    }

    private void sceneSection(boolean all) {
        if ((all || filter.equals("scenes")) && !home.scenes.isEmpty()) {
            header(getString(R.string.scenes));
            for (Home.Scene s : home.scenes) sceneTile(s, "scenes");
        }
    }

    private void cameraSection(boolean all) {
        if ((all || filter.equals("cameras")) && !home.cameras.isEmpty()) {
            header(getString(R.string.cameras));
            for (Home.Camera c : home.cameras) cameraTile(c, "cameras");
        }
    }

    private void plugSection(boolean all) {
        if (all || filter.equals("plugs")) {
            // One section per room, in the owner's order; devices without a room last.
            for (Home.Room r : home.rooms) roomSection(r.id, r.name);
            roomSection("", home.rooms.isEmpty() ? getString(R.string.devices) : getString(R.string.no_room));
        }
    }

    private void automationSection(boolean all) {
        if ((all || filter.equals("automations")) && !home.schedules.isEmpty()) {
            header(getString(R.string.schedules));
            ZonedDateTime now = ZonedDateTime.now();
            for (Home.Schedule s : home.schedules) {
                Tile t = item("schedule", s.id, s.name, R.drawable.ic_schedule, "automations");
                t.detail = Schedules.describe(s);
                ZonedDateTime next = Schedules.next(s, home, now);
                t.state = !s.enabled ? getString(R.string.off)
                        : s.isTvTrigger() ? getString(R.string.on)
                        : next == null ? getString(R.string.schedule_no_location)
                        : next.toLocalDate().equals(now.toLocalDate()) ? next.toLocalTime().withSecond(0).toString()
                        : next.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
                            + " " + next.toLocalTime().withSecond(0);
            }
        }
    }

    private void afterRebuild() {
        for (int i = 0; i < filterRow.getChildCount(); i++) {
            View pill = filterRow.getChildAt(i);
            pill.setSelected(filter.equals(pill.getTag()));
        }
        // After a rebuild the focused tile may be gone (removed, or everything reset): never leave the
        // remote with nothing to move, put focus back on the first tile (or the top row when the grid is empty).
        main.post(() -> {
            View f = getCurrentFocus();
            boolean inGrid = f != null && grid.findContainingItemView(f) != null;
            // A tile, filter or button still on screen keeps focus, except on first showing, where it starts on the tiles.
            if (f != null && f.isAttachedToWindow() && f.isShown() && (inGrid || !firstShow)) return;
            firstShow = false;
            if (prompts.showing()) return;
            if (tiles.isEmpty()) {
                findViewById(R.id.btnAdd).requestFocus();
                return;
            }
            grid.scrollToPosition(0);
            main.post(() -> {
                RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(firstItem());
                if (vh != null) vh.itemView.findViewById(R.id.card).requestFocus();
            });
        });
    }

    private void header(String title) {
        tiles.add(new Tile(Tile.HEADER, "header", title, title));
    }

    private void roomSection(String roomId, String name) {
        List<Home.Device> in = new ArrayList<>();
        for (Home.Device d : home.devices) if (d.roomId.equals(roomId)) in.add(d);
        if (in.isEmpty()) return;
        header(name);
        for (Home.Device d : in) deviceTile(d, "room:" + roomId);
    }

    private void deviceTile(Home.Device d, String section) {
        item(d.hasSwitch ? "device" : "sensor", d.id, d.name, d.hasSwitch ? R.drawable.ic_plug : R.drawable.ic_sensor, section);
    }

    private void sceneTile(Home.Scene s, String section) {
        item("scene", s.id, s.name, R.drawable.ic_scene, section).detail = sceneSummary(s);
    }

    private void cameraTile(Home.Camera c, String section) {
        item("camera", c.id, c.name, R.drawable.ic_camera, section).detail = Commands.hostOf(c.url);
    }

    private Tile item(String kind, String id, String title, int icon, String section) {
        Tile t = new Tile(Tile.ITEM, kind, id, title);
        t.icon = icon;
        t.section = section;
        tiles.add(t);
        return t;
    }

    /** Whether any tile sits in a row above this one. */
    private boolean itemAbove(int pos) {
        GridLayoutManager.SpanSizeLookup spans = ((GridLayoutManager) grid.getLayoutManager()).getSpanSizeLookup();
        int row = spans.getSpanGroupIndex(pos, COLUMNS);
        for (int i = 0; i < pos; i++) {
            if (tiles.get(i).type == Tile.ITEM && spans.getSpanGroupIndex(i, COLUMNS) < row) return true;
        }
        return false;
    }

    private void focusFilter() {
        for (int i = 0; i < filterRow.getChildCount(); i++) {
            View pill = filterRow.getChildAt(i);
            if (filter.equals(pill.getTag())) {
                pill.requestFocus();
                return;
            }
        }
    }

    private int firstItem() {
        for (int i = 0; i < tiles.size(); i++) if (tiles.get(i).type == Tile.ITEM) return i;
        return 0;
    }

    /** Writes the latest device states into the tiles and the summary line. */
    private void applyStatuses() {
        int on = 0;
        double watts = 0;
        boolean anyWatts = false;
        for (Home.Device d : home.devices) {
            Shelly.Status st = statuses.get(d.id);
            if (st == null || offline.contains(d.id) || !d.hasSwitch) continue;
            if (st.on) on++;
            if (!Double.isNaN(st.watts)) { watts += st.watts; anyWatts = true; }
        }
        for (Tile t : tiles) {
            if (!t.kind.equals("device") && !t.kind.equals("sensor")) continue;
            Home.Device d = home.device(t.id);
            Shelly.Status st = statuses.get(t.id);
            if (offline.contains(t.id)) {
                t.state = getString(R.string.offline);
                t.detail = d != null ? d.host : "";
            } else if (st == null) {
                t.state = "…";
                t.detail = d != null ? roomOf(d) : "";
            } else if (t.kind.equals("device")) {
                t.state = getString(st.on ? R.string.on : R.string.off);
                t.detail = Double.isNaN(st.watts) ? roomOf(d) : String.format(Locale.US, "%.0f W", st.watts);
            } else {
                t.state = Double.isNaN(st.tempC) ? "" : String.format(Locale.US, "%.1f°", st.tempC);
                t.detail = Double.isNaN(st.humidity) ? "" : String.format(Locale.US, "%.0f%% humidity", st.humidity);
            }
        }
        status.setText(summary(on, anyWatts ? watts : Double.NaN));
    }

    /** "2 on · 9 W now · Next: Night at 23:30", like the status line at the top of Apple Home. */
    private String summary(int on, double watts) {
        if (home.devices.isEmpty() && home.cameras.isEmpty()) return getString(R.string.status_empty_tv);
        List<String> parts = new ArrayList<>();
        int plugs = 0;
        for (Home.Device d : home.devices) if (d.hasSwitch) plugs++;
        if (plugs > 0) parts.add(on == 0 ? "Everything off" : on + (on == 1 ? " plug on" : " plugs on"));
        if (!Double.isNaN(watts)) parts.add(String.format(Locale.US, "%.0f W now", watts));
        if (!offline.isEmpty()) parts.add(offline.size() + " not responding");
        ZonedDateTime now = ZonedDateTime.now(), best = null;
        Home.Schedule next = null;
        for (Home.Schedule s : home.schedules) {
            ZonedDateTime t = Schedules.next(s, home, now);
            if (t != null && (best == null || t.isBefore(best))) { best = t; next = s; }
        }
        if (next != null) {
            parts.add("Next: " + next.name + " at " + best.toLocalTime().withSecond(0)
                    + (best.toLocalDate().equals(now.toLocalDate()) ? "" : " " + best.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())));
        }
        return String.join(" · ", parts);
    }

    private String roomOf(Home.Device d) {
        Home.Room r = home.room(d.roomId);
        return r != null ? r.name : "";
    }

    private String sceneSummary(Home.Scene s) {
        int on = 0, off = 0;
        for (Home.Action a : s.actions) if (a.on) on++; else off++;
        StringBuilder b = new StringBuilder();
        if (on > 0) b.append(on).append(" on");
        if (off > 0) b.append(b.length() > 0 ? " · " : "").append(off).append(" off");
        return b.toString();
    }

    // ---- talking to devices ----------------------------------------------------------------------

    private void pollNow() {
        main.removeCallbacks(poll);
        for (Home.Device d : new ArrayList<>(home.devices)) refresh(d);
        main.postDelayed(poll, POLL_MS);
    }

    private void refresh(Home.Device d) {
        io.execute(() -> {
            Shelly.Status st;
            try {
                st = Devices.status(d);
            } catch (IOException e) {
                st = null;
            }
            final Shelly.Status got = st;
            main.post(() -> {
                if (got == null) offline.add(d.id);
                else {
                    offline.remove(d.id);
                    statuses.put(d.id, got);
                    if (d.uid.isEmpty() && askedUid.add(d.id)) learnUid(d);
                }
                applyStatuses();
                adapter.notifyDataSetChanged();
                if (got == null) maybeRefind();
            });
        });
    }

    /** A device added before identities were kept: ask it once, so it can be found again later. */
    private void learnUid(Home.Device d) {
        io.execute(() -> {
            try {
                String uid = Devices.probe(d.host).uid;
                if (uid != null && !uid.isEmpty()) main.post(() -> {
                    d.uid = uid;
                    Storage.save(this);
                });
            } catch (IOException ignored) {
            }
        });
    }

    /** Something stopped answering: look for it at a new address (at most once a minute). */
    private void maybeRefind() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!Refind.due(offline, now, lastRefind)) return;
        lastRefind = now;
        Set<String> lost = new HashSet<>(offline);
        java.util.concurrent.Future<Set<String>> kasa = io.submit(() -> Kasa.discover(3000));
        if (refindDiscovery != null) refindDiscovery.stop();
        refindDiscovery = new Discovery(this);
        refindDiscovery.scan(shellyHosts -> io.execute(() -> {
            Set<String> kasaHosts;
            try {
                kasaHosts = kasa.get();
            } catch (Exception e) {
                kasaHosts = new HashSet<>();
            }
            Map<String, String> found = Refind.scan(kasaHosts, shellyHosts);
            main.post(() -> {
                List<Home.Device> moved = Refind.apply(home, lost, found);
                if (moved.isEmpty()) return;
                for (Home.Device d : moved) {
                    offline.remove(d.id);
                    statuses.remove(d.id);
                    ActivityLog.add(this, d.name + " found again at " + d.host);
                }
                Storage.save(this);   // redraws and polls the new addresses
            });
        }));
    }

    private void toggle(Home.Device d) {
        Shelly.Status cur = statuses.get(d.id);
        boolean want = cur == null || !cur.on;
        // Show the new state at once; the poll corrects it if the device didn't follow.
        statuses.put(d.id, new Shelly.Status(want, cur != null ? cur.watts : Double.NaN, Double.NaN, Double.NaN));
        applyStatuses();
        adapter.notifyDataSetChanged();
        io.execute(() -> {
            try {
                Devices.set(d, want);
                ActivityLog.add(this, d.name + " turned " + (want ? "on" : "off") + " from the TV");
            } catch (IOException e) {
                ActivityLog.add(this, d.name + " didn't answer (turn " + (want ? "on" : "off") + ")");
            }
            refresh(d);
        });
    }

    private void runScene(Home.Scene s) {
        AtomicInteger pending = new AtomicInteger(s.actions.size());
        AtomicInteger failed = new AtomicInteger();
        for (Home.Action a : s.actions) {
            Home.Device d = home.device(a.deviceId);
            io.execute(() -> {
                try {
                    if (d != null) Devices.set(d, a.on);
                    else failed.incrementAndGet();
                } catch (IOException e) {
                    failed.incrementAndGet();
                }
                if (d != null) refresh(d);
                if (pending.decrementAndGet() == 0) {
                    ActivityLog.add(this, "Scene " + s.name + ": " + (s.actions.size() - failed.get()) + "/" + s.actions.size() + " done");
                    main.post(() -> prompts.say(failed.get() == 0 ? getString(R.string.scene_done, s.name)
                            : getString(R.string.scene_partial, s.name, failed.get())));
                }
            });
        }
    }

    static Home.Device deviceFrom(String host, Shelly.Info info) {
        // Unnamed devices: model and the last part of the address, e.g. "SHPLG-S 41" for 192.168.1.41.
        String name = info.name != null ? info.name : info.model + " " + host.replaceAll(":\\d+$", "").replaceAll(".*\\.", "");
        Home.Device d = new Home.Device(Home.newId(), name, host, info.gen, info.hasSwitch || !info.hasSensor, info.hasSensor);
        d.brand = info.brand;
        d.uid = info.uid;
        return d;
    }

    // ---- OK and hold-OK on tiles -------------------------------------------------------------------

    private void onClick(Tile t) {
        switch (t.kind) {
            case "device": {
                Home.Device d = home.device(t.id);
                if (d != null) toggle(d);
                break;
            }
            case "sensor":
                startActivity(new Intent(this, DeviceActivity.class).putExtra(DeviceActivity.EXTRA_ID, t.id));
                break;
            case "scene": {
                Home.Scene s = home.scene(t.id);
                if (s != null) runScene(s);
                break;
            }
            case "camera":
                startActivity(new Intent(this, CameraActivity.class).putExtra(CameraActivity.EXTRA_ID, t.id));
                break;
            case "schedule": {
                Home.Schedule s = home.schedule(t.id);
                if (s == null) break;
                s.enabled = !s.enabled;
                prompts.say(getString(s.enabled ? R.string.schedule_on : R.string.schedule_off, s.name));
                Storage.save(this);
                break;
            }
            case "tv-input": {
                if (TvControls.isFireOs(this)) {
                    prompts.say(getString(R.string.fire_inputs));
                    break;
                }
                List<TvControls.Input> ins = TvControls.inputs(this);
                if (ins.isEmpty()) {
                    prompts.say(getString(R.string.no_inputs));
                    break;
                }
                List<String> names = new ArrayList<>();
                for (TvControls.Input in : ins) names.add(in.label);
                prompts.list(getString(R.string.inputs), names, i -> {
                    if (!TvControls.switchTo(this, ins.get(i))) prompts.say(getString(R.string.no_inputs));
                });
                break;
            }
            case "tv-volume":
                prompts.list(getString(R.string.volume), Arrays.asList(getString(R.string.volume_up), getString(R.string.volume_down),
                        getString(R.string.mute)), i -> {
                    TvControls.adjust(this, i == 0 ? android.media.AudioManager.ADJUST_RAISE
                            : i == 1 ? android.media.AudioManager.ADJUST_LOWER : android.media.AudioManager.ADJUST_TOGGLE_MUTE);
                    rebuild();
                });
                break;
            default:
        }
    }

    private void onMenu(Tile t) {
        switch (t.kind) {
            case "device": case "sensor":
                startActivity(new Intent(this, DeviceActivity.class).putExtra(DeviceActivity.EXTRA_ID, t.id));
                break;
            case "scene": {
                Home.Scene s = home.scene(t.id);
                if (s != null) editors.sceneMenu(s);
                break;
            }
            case "camera": {
                Home.Camera c = home.camera(t.id);
                if (c != null) editors.cameraMenu(c);
                break;
            }
            case "schedule": {
                Home.Schedule s = home.schedule(t.id);
                if (s != null) editors.automationMenu(s);
                break;
            }
            default:
        }
    }

    private Set<String> ids() {
        Set<String> ids = new HashSet<>();
        for (Home.Device d : home.devices) ids.add(d.id);
        return ids;
    }

    /** `adb shell dumpsys activity top`: what the screen shows, for tests/smarthome_test.py. */
    @Override
    public void dump(String prefix, FileDescriptor fd, PrintWriter w, String[] args) {
        super.dump(prefix, fd, w, args);
        w.println(prefix + "SmartHome state:");
        w.println(prefix + "  status=" + status.getText());
        w.println(prefix + "  filter=" + filter);
        prompts.dump(prefix, w);
        View f = getCurrentFocus();
        View item = f != null ? grid.findContainingItemView(f) : null;
        String focus = item != null ? "tile:" + grid.getChildAdapterPosition(item)
                : f != null && f.getId() != View.NO_ID ? "view:" + getResources().getResourceEntryName(f.getId())
                : f != null && f.getTag() != null ? "filter:" + f.getTag() : "none";
        w.println(prefix + "  focus=" + focus);
        GridLayoutManager.SpanSizeLookup spans = ((GridLayoutManager) grid.getLayoutManager()).getSpanSizeLookup();
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            w.println(prefix + "  tile " + i + " row=" + spans.getSpanGroupIndex(i, COLUMNS) + " col=" + spans.getSpanIndex(i, COLUMNS)
                    + " kind=" + t.kind + " id=" + t.id + " section=" + t.section + " title=" + t.title
                    + " | state=" + t.state + " | detail=" + t.detail);
        }
    }

    // ---- tiles -----------------------------------------------------------------------------------

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        Adapter() {
            setHasStableIds(true);
        }

        @Override
        public long getItemId(int position) {
            return tiles.get(position).key().hashCode();
        }

        @Override
        public int getItemViewType(int position) {
            return tiles.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            View v = inf.inflate(viewType == Tile.HEADER ? R.layout.item_header : R.layout.item_tile, parent, false);
            RecyclerView.ViewHolder vh = new RecyclerView.ViewHolder(v) { };
            if (viewType == Tile.ITEM) {
                View card = v.findViewById(R.id.card);
                card.setOnFocusChangeListener((view, focused) ->
                        view.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(110).start());
                card.setOnClickListener(view -> {
                    int pos = vh.getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) onClick(tiles.get(pos));
                });
                card.setOnLongClickListener(view -> {
                    int pos = vh.getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) onMenu(tiles.get(pos));
                    return true;
                });
                card.setOnKeyListener((view, keyCode, event) -> {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.getAction() == KeyEvent.ACTION_DOWN) {
                        int pos = vh.getBindingAdapterPosition();
                        // The grid can't move focus over a section heading into the filters above it on its
                        // own (it gets stuck on the top row): from a tile with no tile above, go to the filters.
                        if (pos != RecyclerView.NO_POSITION && !itemAbove(pos)) {
                            focusFilter();
                            return true;
                        }
                        return false;
                    }
                    if (keyCode != KeyEvent.KEYCODE_MENU) return false;
                    if (event.getAction() == KeyEvent.ACTION_UP) view.performLongClick();
                    return true;
                });
            }
            return vh;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int position) {
            Tile t = tiles.get(position);
            if (t.type == Tile.HEADER) {
                ((TextView) vh.itemView).setText(t.title);
                return;
            }
            View v = vh.itemView;
            ((TextView) v.findViewById(R.id.title)).setText(t.title);
            ((TextView) v.findViewById(R.id.detail)).setText(t.detail);
            TextView state = v.findViewById(R.id.state);
            state.setText(t.state);
            ImageView icon = v.findViewById(R.id.icon);
            icon.setImageResource(t.icon);
            boolean on = getString(R.string.on).equals(t.state) && (t.kind.equals("device"));
            // A plug that is on glows green, as accessories that are on light up in Apple Home.
            icon.setImageTintList(on ? android.content.res.ColorStateList.valueOf(0xFF4CD08A)
                    : getResources().getColorStateList(R.drawable.pill_text, getTheme()));
        }

        @Override
        public int getItemCount() {
            return tiles.size();
        }
    }
}
