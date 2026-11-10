package com.homecontrol.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SceneClockTest {
    private static final long P = 30_000;

    @Test
    public void the_scene_changes_every_period_and_cycles() {
        assertEquals(0, SceneClock.index(0, P, 4));
        assertEquals(0, SceneClock.index(P - 1, P, 4));
        assertEquals(1, SceneClock.index(P, P, 4));
        assertEquals(3, SceneClock.index(3 * P + 5, P, 4));
        assertEquals(0, SceneClock.index(4 * P, P, 4));
        assertEquals(1, SceneClock.index(5 * P + 1, P, 4));
    }

    @Test
    public void two_screens_asking_at_nearly_the_same_moment_agree_except_across_a_change() {
        for (long t = 0; t < 10 * P; t += 1_000) {
            long soon = t + 20;
            if (SceneClock.untilNext(t, P) > 20) assertEquals(SceneClock.index(t, P, 4), SceneClock.index(soon, P, 4));
        }
    }

    @Test
    public void the_wait_is_to_the_next_change_and_never_zero() {
        assertEquals(P, SceneClock.untilNext(0, P));
        assertEquals(P, SceneClock.untilNext(3 * P, P));
        assertEquals(1, SceneClock.untilNext(P - 1, P));
        assertEquals(P - 5, SceneClock.untilNext(5, P));
        for (long t = 0; t < 3 * P; t += 777) {
            long w = SceneClock.untilNext(t, P);
            assertTrue(w >= 1 && w <= P);
            // waiting that long lands exactly on the next scene
            assertEquals((SceneClock.index(t, P, 4) + 1) % 4, SceneClock.index(t + w, P, 4));
        }
    }

    @Test
    public void a_clock_before_1970_still_gives_a_valid_scene() {
        for (long t = -5 * P; t < 0; t += 1234) {
            int i = SceneClock.index(t, P, 4);
            assertTrue(i >= 0 && i < 4);
            assertTrue(SceneClock.untilNext(t, P) >= 1);
        }
    }

    @Test
    public void one_scene_is_always_scene_zero() {
        assertEquals(0, SceneClock.index(123456789L, P, 1));
    }
}
