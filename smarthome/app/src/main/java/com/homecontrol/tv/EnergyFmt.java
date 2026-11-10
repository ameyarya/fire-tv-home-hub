package com.homecontrol.tv;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;

/** How the dashboard writes its numbers, the same on the TV and (in remote.html) on the phone page. Plain Java, tested on the JVM. */
final class EnergyFmt {
    private EnergyFmt() {
    }

    /** Watt-hours as "850 Wh" below a kilowatt-hour, else "1.25 kWh" ("12.5 kWh" from ten up, "125 kWh" from a hundred). */
    static String energy(double wh) {
        if (Double.isNaN(wh) || Double.isInfinite(wh)) return "–";
        double a = Math.abs(wh);
        if (Math.round(a) < 1000) return String.format(Locale.US, a < 10 && a != Math.rint(a) ? "%.1f Wh" : "%.0f Wh", wh);
        double kwh = wh / 1000.0;
        if (a < 9995) return String.format(Locale.US, "%.2f kWh", kwh);      // below 9.995 so "10.00" never shows
        if (a < 99_950) return String.format(Locale.US, "%.1f kWh", kwh);
        return String.format(Locale.US, "%.0f kWh", kwh);
    }

    /** Watts as "42 W", or "1.25 kW" from a kilowatt up. */
    static String power(double w) {
        if (Double.isNaN(w) || Double.isInfinite(w)) return "–";
        if (Math.round(Math.abs(w)) < 1000) return String.format(Locale.US, Math.abs(w) < 10 && w != Math.rint(w) ? "%.1f W" : "%.0f W", w);
        return String.format(Locale.US, "%.2f kW", w / 1000.0);
    }

    /** Money with a symbol: "$0.42", and "$12.30" or "$123" as it grows. */
    static String money(double amount, String symbol) {
        if (Double.isNaN(amount) || Double.isInfinite(amount)) return "–";
        double a = Math.abs(amount);
        String body = a < 100 ? String.format(Locale.US, "%.2f", a) : String.format(Locale.US, "%.0f", a);
        return (amount < 0 ? "-" : "") + symbol + body;
    }

    /** A change as "+12%" or "−8%" (a real minus sign); "–" when unknown; "±0%" when it rounds to nothing. */
    static String change(double fraction) {
        if (Double.isNaN(fraction) || Double.isInfinite(fraction)) return "–";
        long pct = Math.round(fraction * 100);
        if (pct == 0) return "±0%";
        return (pct > 0 ? "+" : "−") + Math.abs(pct) + "%";
    }

    /** An hour of the day as "3 AM" / "12 PM" (0 is 12 AM); the 24-hour clock as "15:00". */
    static String hour(int h, boolean h24) {
        h = Math.floorMod(h, 24);
        if (h24) return String.format(Locale.US, "%02d:00", h);
        return (h % 12 == 0 ? 12 : h % 12) + (h < 12 ? " AM" : " PM");
    }

    /** The short label under a bar of the hourly chart: a few hours only, so the labels do not crowd. */
    static String hourTick(int h, boolean h24) {
        if (h % 3 != 0) return "";
        return h24 ? String.valueOf(h) : (h % 12 == 0 ? 12 : h % 12) + (h < 12 ? "a" : "p");
    }

    /** "Mon 9 Nov". */
    static String day(LocalDate d) {
        return d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.US) + " " + d.getDayOfMonth() + " "
                + d.getMonth().getDisplayName(TextStyle.SHORT, Locale.US);
    }

    /** The axis top for a chart: the next "round" number above the biggest bar (1, 2, 2.5, 5, 10 times a power of ten). */
    static double niceMax(double max) {
        if (Double.isNaN(max) || max <= 0) return 1;
        double pow = Math.pow(10, Math.floor(Math.log10(max)));
        for (double m : new double[]{1, 2, 2.5, 5, 10}) if (max <= m * pow + 1e-9) return m * pow;
        return 10 * pow;
    }
}
