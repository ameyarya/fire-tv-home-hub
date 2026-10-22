package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IdleClockTest {

    @Test
    public void opensTheClockWhenIdleAndQuiet() {
        assertEquals(IdleClock.Action.OPEN_CLOCK, IdleClock.decide(5, true, false));
    }

    @Test
    public void waitsWhileSomethingPlays() {
        assertEquals(IdleClock.Action.CHECK_AGAIN, IdleClock.decide(5, true, true));
    }

    @Test
    public void offOrAsleepDoNothing() {
        assertEquals(IdleClock.Action.NOTHING, IdleClock.decide(0, true, false));
        assertEquals(IdleClock.Action.NOTHING, IdleClock.decide(5, false, false));
    }

    @Test
    public void theCameraViewCountsAsPlaying() {
        assertTrue(IdleClock.isLiveView("com.homecontrol.tv.CameraActivity"));
        assertFalse(IdleClock.isLiveView("com.homecontrol.tv.MainActivity"));
    }
}
