package com.homecontrol.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * The dashboard's bar chart: one bar per hour or day, a faint line for the same hour yesterday (or the week
 * before), the current bar lit green, three guide lines with their values. Same drawing as the phone page's.
 */
public final class BarChartView extends View {
    private double[] values = new double[0], compare;
    private String[] ticks = new String[0];
    private int lit = -1;
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG),
            ref = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float d = getResources().getDisplayMetrics().density;

    public BarChartView(Context c) {
        this(c, null);
    }

    public BarChartView(Context c, AttributeSet a) {
        super(c, a);
        line.setColor(0x26FFFFFF);
        line.setStrokeWidth(1f * d);
        text.setColor(0x99FFFFFF);
        text.setTextSize(11f * d);
        text.setTypeface(getResources().getFont(R.font.outfit));
        ref.setColor(0xB3FFFFFF);
        ref.setStrokeWidth(2f * d);
        ref.setStrokeCap(Paint.Cap.ROUND);
    }

    /** values: watt-hours per bar; compare: the same for the period before (or null); ticks: a label per bar ("" for none); lit: the current bar or -1. */
    void set(double[] values, double[] compare, String[] ticks, int lit) {
        this.values = values;
        this.compare = compare;
        this.ticks = ticks;
        this.lit = lit;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float left = 54f * d, bottom = h - 20f * d, top = 8f * d, right = w - 4f * d;
        double max = 0;
        for (double v : values) max = Math.max(max, v);
        if (compare != null) for (double v : compare) max = Math.max(max, v);
        if (max <= 0) {
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText("Nothing recorded yet", w / 2f, h / 2f, text);
            return;
        }
        double top$ = EnergyFmt.niceMax(max);
        text.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i <= 2; i++) {
            float y = bottom - (bottom - top) * i / 2f;
            c.drawLine(left, y, right, y, line);
            c.drawText(EnergyFmt.energy(top$ * i / 2).replace(".00", ""), left - 8f * d, y + 4f * d, text);
        }
        int n = Math.max(1, values.length);
        float slot = (right - left) / n, bw = Math.max(2f * d, slot * 0.62f);
        for (int i = 0; i < values.length; i++) {
            float x = left + slot * i + (slot - bw) / 2f;
            float hh = (float) (values[i] / top$ * (bottom - top));
            bar.setColor(i == lit ? 0xFF4CD08A : 0x99FFFFFF);
            r.set(x, bottom - Math.max(hh, values[i] > 0 ? 2f * d : 0f), x + bw, bottom);
            c.drawRoundRect(r, Math.min(4f * d, bw / 2f), Math.min(4f * d, bw / 2f), bar);
            if (compare != null && i < compare.length && compare[i] > 0) {
                float y = bottom - (float) (compare[i] / top$ * (bottom - top));
                c.drawLine(x - 1f * d, y, x + bw + 1f * d, y, ref);
            }
        }
        text.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < ticks.length && i < values.length; i++) {
            if (!ticks[i].isEmpty()) c.drawText(ticks[i], left + slot * i + slot / 2f, h - 4f * d, text);
        }
    }
}
