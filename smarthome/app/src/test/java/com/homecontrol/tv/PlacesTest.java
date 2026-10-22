package com.homecontrol.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;

public class PlacesTest {

    @Test
    public void everyZoneIsRealAndHasCitiesInRange() {
        assertTrue(Places.ZONES.size() > 30);
        Set<String> names = new HashSet<>();
        for (Places.Zone z : Places.ZONES.values()) {
            ZoneId.of(z.id);   // throws for a zone Java doesn't know
            assertFalse(z.label, z.cities.isEmpty());
            for (Places.City c : z.cities) {
                assertTrue(c.name, Math.abs(c.lat) <= 90 && Math.abs(c.lon) <= 180);
                assertTrue("listed twice: " + c.name, names.add(c.name));
            }
        }
    }

    @Test
    public void citiesSitInTheirZone() {
        // A city's sunset in its own zone must be in the evening there: catches a city filed under the wrong zone.
        for (Places.Zone z : Places.ZONES.values()) {
            for (Places.City c : z.cities) {
                ZonedDateTime set = Schedules.sun(LocalDate.of(2026, 3, 20), c.lat, c.lon, ZoneId.of(z.id), false);
                assertNotNull(c.name, set);
                assertTrue(c.name + " sunset " + set.toLocalTime(), set.getHour() >= 16 && set.getHour() <= 20);
            }
        }
    }

    @Test
    public void tvZoneOffersItsOwnCities() {
        assertEquals("Pacific Time (US & Canada)", Places.forTv(ZoneId.of("America/Los_Angeles")).label);
        // Not listed, same offset as a listed zone: offered that one.
        Places.Zone z = Places.forTv(ZoneId.of("America/Tijuana"));
        assertNotNull(z);
        assertEquals(ZoneId.of(z.id).getRules().getOffset(java.time.Instant.now()),
                ZoneId.of("America/Tijuana").getRules().getOffset(java.time.Instant.now()));
        assertNull(Places.forTv(ZoneId.of("Pacific/Chatham")));   // +12:45/+13:45: nothing listed shares it
    }

    @Test
    public void savedLocationShownByCityName() {
        assertEquals("San Francisco", Places.nearest(37.7749, -122.4194));
        assertEquals("", Places.nearest(0, -30));   // the middle of the Atlantic
    }
}
