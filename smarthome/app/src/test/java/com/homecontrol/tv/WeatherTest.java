package com.homecontrol.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.util.Locale;

public class WeatherTest {

    /** A real Open-Meteo reply for San Francisco, 2026-10-20 20:00 local time. */
    static final String REAL = "{\"latitude\":37.763283,\"longitude\":-122.41286,\"timezone\":\"America/Los_Angeles\","
            + "\"current\":{\"time\":\"2026-10-20T20:00\",\"interval\":900,\"temperature_2m\":60.1,\"weather_code\":3,\"is_day\":0},"
            + "\"hourly\":{\"time\":[\"2026-10-20T20:00\",\"2026-10-20T21:00\",\"2026-10-20T22:00\",\"2026-10-20T23:00\","
            + "\"2026-10-21T00:00\",\"2026-10-21T01:00\"],\"temperature_2m\":[60.1,60.2,59.4,59.6,58.9,59.2],"
            + "\"weather_code\":[3,3,3,3,3,3]},\"daily\":{\"time\":[\"2026-10-20\"],\"temperature_2m_max\":[68.7],"
            + "\"temperature_2m_min\":[50.3]}}";

    @Test
    public void parsesARealReply() throws IOException {
        Weather.Forecast f = Weather.parse(REAL);
        assertEquals(60.1, f.temp, 1e-9);
        assertEquals(3, f.code);
        assertFalse(f.day);
        assertEquals(68.7, f.high, 1e-9);
        assertEquals(50.3, f.low, 1e-9);
        // The current hour (20:00) is "now"; the list starts after it, across midnight.
        assertEquals(5, f.hours.size());
        assertEquals("21:00", f.hours.get(0).time);
        assertEquals("01:00", f.hours.get(4).time);
        assertEquals(59.2, f.hours.get(4).temp, 1e-9);
    }

    @Test
    public void rejectsWhatIsNotAForecast() {
        for (String bad : new String[]{"<html>", "{}", "{\"error\":true,\"reason\":\"Latitude must be in range\"}"}) {
            try {
                Weather.parse(bad);
                fail("accepted " + bad);
            } catch (IOException expected) {
            }
        }
    }

    @Test
    public void wordsAndIcons() {
        assertEquals("Cloudy", Weather.describe(3));
        assertEquals("Thunderstorm, hail", Weather.describe(99));
        assertEquals("", Weather.describe(1234));
        assertEquals(Weather.MOON, Weather.icon(0, false));
        assertEquals(Weather.SUN, Weather.icon(1, true));
        assertEquals(Weather.RAIN, Weather.icon(81, true));
        assertEquals(Weather.SNOW, Weather.icon(86, true));
        assertEquals(Weather.STORM, Weather.icon(95, false));
        assertEquals(Weather.FOG, Weather.icon(45, true));
    }

    @Test
    public void unitsFollowTheCountry() {
        assertTrue(Weather.fahrenheit(Locale.US));
        assertFalse(Weather.fahrenheit(Locale.UK));
        assertFalse(Weather.fahrenheit(new Locale("en", "IN")));
        assertTrue(Weather.url(37.775, -122.419, true).contains("temperature_unit=fahrenheit"));
        assertFalse(Weather.url(12.97, 77.59, false).contains("fahrenheit"));
        assertTrue(Weather.url(37.775, -122.419, true).contains("latitude=37.775&longitude=-122.419"));
    }

    @Test
    public void temperatureText() {
        assertEquals("60°", Weather.temp(60.1));
        assertEquals("–", Weather.temp(Double.NaN));
        assertEquals("-3°", Weather.temp(-2.6));
    }
}
