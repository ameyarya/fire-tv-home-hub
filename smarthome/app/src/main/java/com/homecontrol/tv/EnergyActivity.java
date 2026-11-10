package com.homecontrol.tv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The energy dashboard: power now, today, the month and where it is heading, the last 7 days, a chart by hour or
 * by day (today, 7 days, 30 days), each plug's share, the busiest hour and day, what runs all the time, and the
 * electricity price behind the money figures. Numbers come from EnergyStats; the phone page shows the same ones.
 */
public class EnergyActivity extends Activity {

    private static final String MONEY = "$";
    private static final long REFRESH_MS = 5_000, SAMPLE_MS = 15_000;
    private static final int GREEN = 0xFF4CD08A, ORANGE = 0xFFFFB454, DIM = 0xA6FFFFFF;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SceneView scene;
    private Prompts prompts;
    private float d;
    private int range = 0;   // 0 today, 1 the last 7 days, 2 the last 30 days
    private long sampledAt;
    private EnergyStats stats;

    private TextView priceChip;
    private final TextView[] rangePills = new TextView[3];
    private final TextView[] cardValue = new TextView[4], cardLine1 = new TextView[4], cardLine2 = new TextView[4];
    private TextView chartTitle, chartNote, devicesTitle, insightValue[] = new TextView[4], insightNote[] = new TextView[4];
    private BarChartView chart;
    private LinearLayout deviceRows;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (now - sampledAt >= SAMPLE_MS) {
                sampledAt = now;
                io.execute(() -> {
                    EnergyLog.sampleNow(getApplicationContext());
                    main.post(EnergyActivity.this::bind);
                });
            }
            bind();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        d = getResources().getDisplayMetrics().density;
        prompts = new Prompts(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        scene = new SceneView(this);
        scene.backdrop(0, 0.36f);
        root.addView(scene, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(56), dp(24), dp(56), dp(18));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // header: Back, title, range pills, the price
        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = pill("‹  Back");
        back.setOnClickListener(v -> finish());
        head.addView(back);
        TextView title = text("Energy", 34, 0xFFFFFFFF, 300);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
        tp.setMarginStart(dp(18));
        head.addView(title, tp);
        String[] names = {"Today", "7 days", "30 days"};
        for (int i = 0; i < 3; i++) {
            final int which = i;
            TextView p = (TextView) getLayoutInflater().inflate(R.layout.item_filter, head, false);
            p.setText(names[i]);
            p.setOnClickListener(v -> {
                range = which;
                bind();
            });
            rangePills[i] = p;
            head.addView(p);
        }
        priceChip = pill("");
        priceChip.setOnClickListener(v -> askPrice());
        head.addView(priceChip);
        page.addView(head);

        // four headline cards
        LinearLayout cards = row();
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = dp(12);
        page.addView(cards, cp);
        String[] labels = {"NOW", "TODAY", "THIS MONTH", "LAST 7 DAYS"};
        for (int i = 0; i < 4; i++) {
            LinearLayout card = card();
            card.addView(text(labels[i], 11, DIM, 500, 0.16f));
            cardValue[i] = text("–", 30, 0xFFFFFFFF, 300);
            card.addView(cardValue[i]);
            cardLine1[i] = text("", 13, 0xCCFFFFFF, 400);
            cardLine2[i] = text("", 13, DIM, 400);
            card.addView(cardLine1[i]);
            card.addView(cardLine2[i]);
            cards.addView(card, weight(1, i == 0 ? 0 : 10));
        }

        // the chart and the plugs
        LinearLayout mid = row();
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, 0, 1);
        mp.topMargin = dp(12);
        page.addView(mid, mp);
        LinearLayout chartCard = card();
        LinearLayout chartHead = row();
        chartTitle = text("", 14, 0xFFFFFFFF, 500);
        chartHead.addView(chartTitle, new LinearLayout.LayoutParams(0, -2, 1));
        chartNote = text("", 12, DIM, 400);
        chartHead.addView(chartNote);
        chartCard.addView(chartHead);
        chart = new BarChartView(this);
        chartCard.addView(chart, new LinearLayout.LayoutParams(-1, 0, 1));
        mid.addView(chartCard, weight(3, 0));
        LinearLayout devCard = card();
        devicesTitle = text("By plug", 14, 0xFFFFFFFF, 500);
        devCard.addView(devicesTitle);
        deviceRows = new LinearLayout(this);
        deviceRows.setOrientation(LinearLayout.VERTICAL);
        devCard.addView(deviceRows, new LinearLayout.LayoutParams(-1, -2));
        mid.addView(devCard, weight(2, 10));

        // four things worth knowing
        LinearLayout insights = row();
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
        ip.topMargin = dp(12);
        page.addView(insights, ip);
        String[] il = {"PEAK HOUR TODAY", "ALWAYS ON", "BUSIEST DAY", "RECORDING"};
        for (int i = 0; i < 4; i++) {
            LinearLayout card = card();
            card.addView(text(il[i], 11, DIM, 500, 0.16f));
            insightValue[i] = text("–", 18, 0xFFFFFFFF, 400);
            insightNote[i] = text("", 12, DIM, 400);
            card.addView(insightValue[i]);
            card.addView(insightNote[i]);
            insights.addView(card, weight(1, i == 0 ? 0 : 10));
        }
        setContentView(root);
        rangePills[0].requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        scene.start();
        sampledAt = 0;
        main.post(refresh);
    }

    @Override
    protected void onPause() {
        main.removeCallbacksAndMessages(null);
        scene.stop();
        EnergyLog.flush(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void askPrice() {
        Home h = Storage.get(this);
        prompts.ask("Electricity price per kWh", String.format(Locale.US, "%.3f", h.price).replaceAll("0+$", "").replaceAll("\\.$", ""),
                "e.g. 0.30", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, text -> {
                    double v;
                    try {
                        v = Double.parseDouble(text.replace(',', '.'));
                    } catch (NumberFormatException e) {
                        v = Double.NaN;
                    }
                    if (!Home.validPrice(v)) {
                        prompts.say("A price per kWh, like 0.30");
                        return;
                    }
                    h.price = v;
                    Storage.save(this);
                    bind();
                });
    }

    // ---- showing the numbers ------------------------------------------------------------------------

    private void bind() {
        Home h = Storage.get(this);
        EnergyStats e = EnergyLog.stats(this, h);
        stats = e;
        boolean h24 = DateFormat.is24HourFormat(this);
        priceChip.setText(MONEY + trimPrice(e.price) + " / kWh");
        for (int i = 0; i < 3; i++) rangePills[i].setSelected(i == range);

        cardValue[0].setText(EnergyFmt.power(e.nowW));
        cardLine1[0].setText(e.onCount == 0 ? "Everything is idle" : e.onCount + (e.onCount == 1 ? " plug drawing power" : " plugs drawing power"));
        cardLine2[0].setText(e.nowW < 1 ? "" : "≈ " + EnergyFmt.money(e.cost(e.nowW * 24), MONEY) + " a day at this rate");

        cardValue[1].setText(EnergyFmt.energy(e.todayWh));
        cardLine1[1].setText(EnergyFmt.money(e.cost(e.todayWh), MONEY) + " so far");
        compareLine(cardLine2[1], e.changeVsYesterday(), "vs yesterday so far");

        cardValue[2].setText(EnergyFmt.energy(e.monthWh));
        cardLine1[2].setText(EnergyFmt.money(e.cost(e.monthWh), MONEY) + " so far");
        cardLine2[2].setTextColor(DIM);
        cardLine2[2].setText(Double.isNaN(e.projectedMonthWh) ? "Too early to project"
                : "On track for " + EnergyFmt.energy(e.projectedMonthWh) + " · " + EnergyFmt.money(e.cost(e.projectedMonthWh), MONEY));

        cardValue[3].setText(EnergyFmt.energy(e.weekWh));
        cardLine1[3].setText(EnergyFmt.money(e.cost(e.weekWh), MONEY));
        compareLine(cardLine2[3], e.changeVsLastWeek(), "vs the week before");

        bindChart(e, h24);
        bindDevices(e);

        insightValue[0].setText(e.peakHour < 0 ? "–" : EnergyFmt.hour(e.peakHour, h24));
        insightNote[0].setText(e.peakHour < 0 ? "Nothing used yet today" : EnergyFmt.energy(e.peakHourWh) + " in that hour");
        insightValue[1].setText(Double.isNaN(e.baseLoadW) ? "Measuring…" : EnergyFmt.power(e.baseLoadW));
        insightNote[1].setText(Double.isNaN(e.baseLoadW) ? "Needs a few quiet hours of readings"
                : "≈ " + EnergyFmt.money(e.baseLoadYearCost(), MONEY) + " a year, even when idle");
        insightValue[2].setText(e.busiestDay == null || e.busiestDayWh <= 0 ? "–" : EnergyFmt.day(e.busiestDay));
        insightNote[2].setText(e.busiestDay == null || e.busiestDayWh <= 0 ? "Of the last 30 days" : EnergyFmt.energy(e.busiestDayWh) + " · " + EnergyFmt.money(e.cost(e.busiestDayWh), MONEY));
        insightValue[3].setText(e.firstDay == null ? "Starting now" : e.daysTracked + (e.daysTracked == 1 ? " day" : " days"));
        insightNote[3].setText(e.firstDay == null ? "Readings come in every minute" : "Since " + EnergyFmt.day(e.firstDay));
    }

    private void compareLine(TextView v, double change, String what) {
        if (Double.isNaN(change)) {
            v.setTextColor(DIM);
            v.setText("Nothing to compare yet");
            return;
        }
        v.setTextColor(change > 0.005 ? ORANGE : change < -0.005 ? GREEN : DIM);   // less is good
        v.setText(EnergyFmt.change(change) + " " + what);
    }

    private void bindChart(EnergyStats e, boolean h24) {
        if (range == 0) {
            String[] ticks = new String[24];
            for (int h = 0; h < 24; h++) ticks[h] = EnergyFmt.hourTick(h, h24);
            chartTitle.setText("Today, by hour");
            chart.set(e.hourToday, e.hourYesterday, ticks, e.currentHour);
            chartNote.setText("— same hour yesterday");
        } else if (range == 1) {
            double[] now = new double[7], before = new double[7];
            String[] ticks = new String[7];
            double sum = 0;
            for (int i = 0; i < 7; i++) {
                now[i] = e.dayWh[23 + i];
                before[i] = e.dayWh[16 + i];
                sum += now[i];
                ticks[i] = e.dayList[23 + i].getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.US);
            }
            chartTitle.setText("Last 7 days");
            chart.set(now, before, ticks, 6);
            chartNote.setText("Average " + EnergyFmt.energy(sum / 7) + " a day · — the week before");
        } else {
            String[] ticks = new String[30];
            double sum = 0;
            for (int i = 0; i < 30; i++) {
                LocalDate day = e.dayList[i];
                ticks[i] = day.getDayOfMonth() % 5 == 0 || i == 29 ? String.valueOf(day.getDayOfMonth()) : "";
                sum += e.dayWh[i];
            }
            chartTitle.setText("Last 30 days");
            chart.set(e.dayWh, null, ticks, 29);
            chartNote.setText("Average " + EnergyFmt.energy(sum / 30) + " a day");
        }
    }

    private void bindDevices(EnergyStats e) {
        deviceRows.removeAllViews();
        devicesTitle.setText(range == 0 ? "By plug, today" : range == 1 ? "By plug, 7 days" : "By plug, 30 days");
        double total = 0;
        for (EnergyStats.Use u : e.devices) total += use(u);
        java.util.List<EnergyStats.Use> sorted = new java.util.ArrayList<>(e.devices);
        sorted.sort((a, b) -> Double.compare(use(b), use(a)));
        int shown = 0;
        for (EnergyStats.Use u : sorted) {
            if (shown == 5 && sorted.size() > 6) break;
            LinearLayout line = row();
            line.addView(text(u.name, 14, 0xFFFFFFFF, 400), new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(text(EnergyFmt.energy(use(u)) + "  ·  " + EnergyFmt.money(e.cost(use(u)), MONEY), 13, 0xCCFFFFFF, 400));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(9);
            deviceRows.addView(line, lp);
            LinearLayout track = row();
            track.setBackgroundColor(0x1FFFFFFF);
            double share = total > 0 ? use(u) / total : 0;
            View fill = new View(this);
            fill.setBackgroundColor(u.nowW >= 1 ? GREEN : 0xCCFFFFFF);
            track.addView(fill, new LinearLayout.LayoutParams(0, -1, (float) share));
            track.addView(new View(this), new LinearLayout.LayoutParams(0, -1, (float) (1 - share)));
            deviceRows.addView(track, new LinearLayout.LayoutParams(-1, dp(4)));
            shown++;
        }
        if (sorted.size() > 6) {
            double rest = 0;
            for (int i = shown; i < sorted.size(); i++) rest += use(sorted.get(i));
            TextView more = text((sorted.size() - shown) + " more · " + EnergyFmt.energy(rest), 12, DIM, 400);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = dp(8);
            deviceRows.addView(more, lp);
        }
        if (sorted.isEmpty()) deviceRows.addView(text("No plugs yet. Add one from the Home screen.", 13, DIM, 400));
    }

    private double use(EnergyStats.Use u) {
        return range == 0 ? u.todayWh : range == 1 ? u.weekWh : u.thirtyWh;
    }

    private static String trimPrice(double p) {
        String s = String.format(Locale.US, "%.3f", p).replaceAll("0+$", "");
        if (s.endsWith(".")) s += "00";
        else if (s.length() - s.indexOf('.') == 2) s += "0";
        return s;
    }

    // ---- small builders -----------------------------------------------------------------------------

    private int dp(int v) {
        return (int) (v * d);
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundResource(R.drawable.glass_card);
        l.setPadding(dp(16), dp(12), dp(16), dp(12));
        return l;
    }

    private LinearLayout.LayoutParams weight(float w, int startMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, w);
        p.setMarginStart(dp(startMargin));
        if (startMargin == 0 && w == 1) p.height = -2;
        return p;
    }

    private TextView text(String s, int sp, int color, int weight) {
        return text(s, sp, color, weight, 0f);
    }

    private TextView text(String s, int sp, int color, int weight, float spacing) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(getResources().getFont(R.font.outfit));
        t.setFontVariationSettings("'wght' " + weight);
        t.setLetterSpacing(spacing);
        t.setSingleLine(true);
        return t;
    }

    private TextView pill(String s) {
        TextView t = (TextView) getLayoutInflater().inflate(R.layout.item_filter, null);
        t.setText(s);
        t.setBackgroundResource(R.drawable.pill_bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(42));
        p.setMarginStart(dp(10));
        t.setLayoutParams(p);
        return t;
    }

    /** `dumpsys activity top`: the numbers on screen, for tests/smarthome_test.py. */
    @Override
    public void dump(String prefix, FileDescriptor fd, PrintWriter w, String[] args) {
        super.dump(prefix, fd, w, args);
        w.println("EnergyActivity state:");
        w.println("  range=" + range + " price=" + priceChip.getText());
        for (int i = 0; i < 4; i++) w.println("  card" + i + "=" + cardValue[i].getText() + " | " + cardLine1[i].getText() + " | " + cardLine2[i].getText());
        w.println("  chart=" + chartTitle.getText() + " | " + chartNote.getText());
        for (int i = 0; i < deviceRows.getChildCount(); i++) {
            View v = deviceRows.getChildAt(i);
            if (v instanceof LinearLayout && ((LinearLayout) v).getChildCount() == 2 && ((LinearLayout) v).getChildAt(0) instanceof TextView) {
                w.println("  plug=" + ((TextView) ((LinearLayout) v).getChildAt(0)).getText() + " | " + ((TextView) ((LinearLayout) v).getChildAt(1)).getText());
            }
        }
        for (int i = 0; i < 4; i++) w.println("  insight" + i + "=" + insightValue[i].getText() + " | " + insightNote[i].getText());
    }
}
