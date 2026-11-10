package com.homecontrol.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;

public class EnergyFmtTest {

    @Test
    public void energy_is_watt_hours_then_kilowatt_hours() {
        assertEquals("0 Wh", EnergyFmt.energy(0));
        assertEquals("0.5 Wh", EnergyFmt.energy(0.5));
        assertEquals("5 Wh", EnergyFmt.energy(5));
        assertEquals("9.5 Wh", EnergyFmt.energy(9.5));
        assertEquals("10 Wh", EnergyFmt.energy(10));
        assertEquals("850 Wh", EnergyFmt.energy(850));
        assertEquals("999 Wh", EnergyFmt.energy(999.4));
        assertEquals("1.00 kWh", EnergyFmt.energy(999.6));   // never "1000 Wh"
        assertEquals("10.0 kWh", EnergyFmt.energy(9996));    // never "10.00 kWh"
        assertEquals("100 kWh", EnergyFmt.energy(99_960));   // never "100.0 kWh"
        assertEquals("1.00 kWh", EnergyFmt.energy(1000));
        assertEquals("1.25 kWh", EnergyFmt.energy(1250));
        assertEquals("9.99 kWh", EnergyFmt.energy(9994));
        assertEquals("12.5 kWh", EnergyFmt.energy(12_500));
        assertEquals("99.9 kWh", EnergyFmt.energy(99_900));
        assertEquals("125 kWh", EnergyFmt.energy(125_000));
        assertEquals("–", EnergyFmt.energy(Double.NaN));
        assertEquals("–", EnergyFmt.energy(Double.POSITIVE_INFINITY));
    }

    @Test
    public void power_is_watts_then_kilowatts() {
        assertEquals("0 W", EnergyFmt.power(0));
        assertEquals("0.4 W", EnergyFmt.power(0.4));
        assertEquals("12 W", EnergyFmt.power(12));
        assertEquals("999 W", EnergyFmt.power(999));
        assertEquals("1.00 kW", EnergyFmt.power(999.6));
        assertEquals("1.00 kW", EnergyFmt.power(1000));
        assertEquals("1.85 kW", EnergyFmt.power(1850));
        assertEquals("–", EnergyFmt.power(Double.NaN));
    }

    @Test
    public void money_has_a_symbol_and_cents_until_it_is_large() {
        assertEquals("$0.00", EnergyFmt.money(0, "$"));
        assertEquals("$0.42", EnergyFmt.money(0.421, "$"));
        assertEquals("$12.30", EnergyFmt.money(12.3, "$"));
        assertEquals("$99.99", EnergyFmt.money(99.99, "$"));
        assertEquals("$123", EnergyFmt.money(123.4, "$"));
        assertEquals("€5.00", EnergyFmt.money(5, "€"));
        assertEquals("-$1.50", EnergyFmt.money(-1.5, "$"));
        assertEquals("–", EnergyFmt.money(Double.NaN, "$"));
    }

    @Test
    public void a_change_is_a_signed_percent() {
        assertEquals("+12%", EnergyFmt.change(0.12));
        assertEquals("−8%", EnergyFmt.change(-0.08));
        assertEquals("+100%", EnergyFmt.change(1.0));
        assertEquals("±0%", EnergyFmt.change(0.001));
        assertEquals("±0%", EnergyFmt.change(-0.004));
        assertEquals("+1%", EnergyFmt.change(0.005));
        assertEquals("–", EnergyFmt.change(Double.NaN));
        assertEquals("–", EnergyFmt.change(Double.NEGATIVE_INFINITY));
    }

    @Test
    public void hours_read_naturally() {
        assertEquals("12 AM", EnergyFmt.hour(0, false));
        assertEquals("9 AM", EnergyFmt.hour(9, false));
        assertEquals("12 PM", EnergyFmt.hour(12, false));
        assertEquals("3 PM", EnergyFmt.hour(15, false));
        assertEquals("11 PM", EnergyFmt.hour(23, false));
        assertEquals("15:00", EnergyFmt.hour(15, true));
        assertEquals("00:00", EnergyFmt.hour(24, true));   // wraps
        assertEquals("11 PM", EnergyFmt.hour(-1, false));
    }

    @Test
    public void chart_ticks_are_every_third_hour() {
        StringBuilder b = new StringBuilder();
        for (int h = 0; h < 24; h++) b.append(EnergyFmt.hourTick(h, false)).append('|');
        assertEquals("12a|||3a|||6a|||9a|||12p|||3p|||6p|||9p|||", b.toString());
        assertEquals("15", EnergyFmt.hourTick(15, true));
        assertEquals("", EnergyFmt.hourTick(16, true));
    }

    @Test
    public void a_day_is_short_and_clear() {
        assertEquals("Mon 9 Nov", EnergyFmt.day(LocalDate.of(2026, 11, 9)));
        assertEquals("Thu 31 Dec", EnergyFmt.day(LocalDate.of(2026, 12, 31)));
    }

    @Test
    public void the_axis_top_is_a_round_number_above_the_data() {
        assertEquals(1.0, EnergyFmt.niceMax(0), 0);
        assertEquals(1.0, EnergyFmt.niceMax(Double.NaN), 0);
        assertEquals(1.0, EnergyFmt.niceMax(-5), 0);
        assertEquals(1.0, EnergyFmt.niceMax(1), 1e-9);
        assertEquals(2.0, EnergyFmt.niceMax(1.01), 1e-9);
        assertEquals(2.5, EnergyFmt.niceMax(2.2), 1e-9);
        assertEquals(5.0, EnergyFmt.niceMax(3), 1e-9);
        assertEquals(10.0, EnergyFmt.niceMax(7), 1e-9);
        assertEquals(100.0, EnergyFmt.niceMax(100), 1e-9);
        assertEquals(250.0, EnergyFmt.niceMax(201), 1e-9);
        assertEquals(0.05, EnergyFmt.niceMax(0.04), 1e-9);
        for (double v = 0.001; v < 1e6; v *= 1.37) {
            double top = EnergyFmt.niceMax(v);
            assertTrue(v + " " + top, top >= v - 1e-9 && top <= v * 2.5 + 1e-9);
        }
    }
}
