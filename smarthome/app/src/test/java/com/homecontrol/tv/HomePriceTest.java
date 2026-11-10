package com.homecontrol.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HomePriceTest {

    @Test
    public void the_price_starts_at_the_default() {
        assertEquals(Home.DEFAULT_PRICE, Home.fromJson("{\"rooms\":[]}").price, 0);
    }

    @Test
    public void only_sensible_prices_are_accepted() {
        assertTrue(Home.validPrice(0.30));
        assertTrue(Home.validPrice(0.0001));
        assertTrue(Home.validPrice(Home.MAX_PRICE));
        assertFalse(Home.validPrice(0));
        assertFalse(Home.validPrice(-0.1));
        assertFalse(Home.validPrice(Home.MAX_PRICE + 0.01));
        assertFalse(Home.validPrice(Double.NaN));
        assertFalse(Home.validPrice(Double.POSITIVE_INFINITY));
        assertFalse(Home.validPrice(Double.NEGATIVE_INFINITY));
    }

    @Test
    public void a_price_survives_saving_and_loading() {
        Home h = Home.fromJson("{\"rooms\":[]}");
        h.price = 0.4275;
        assertEquals(0.4275, Home.fromJson(h.toJson()).price, 1e-12);
    }

    @Test
    public void a_damaged_price_in_a_save_falls_back_to_the_default() {
        for (String bad : new String[]{"\"price\":\"cheap\"", "\"price\":-3", "\"price\":0", "\"price\":1e99", "\"price\":null"}) {
            assertEquals(bad, Home.DEFAULT_PRICE, Home.fromJson("{\"rooms\":[]," + bad + "}").price, 0);
        }
    }

    @Test
    public void saved_before_prices_existed_loads_with_the_default() {
        assertEquals(Home.DEFAULT_PRICE, Home.fromJson("{\"version\":2,\"rooms\":[],\"devices\":[]}").price, 0);
    }
}
