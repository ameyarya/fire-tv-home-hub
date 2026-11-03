package com.homecontrol.tv;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DoorbellTest {

    private static final String MJPEG = "http://192.168.1.60:81/stream", RTSP = "rtsp://192.168.1.60:554/live0";

    @Test
    public void pictureOnlyOverSomethingPlaying() {
        assertTrue(Mjpeg.doorbellPicture(true, true, MJPEG));
        assertFalse("TV off", Mjpeg.doorbellPicture(false, false, MJPEG));
        assertFalse("off, with audio still reported", Mjpeg.doorbellPicture(false, true, MJPEG));
        assertFalse("screensaver or home screen", Mjpeg.doorbellPicture(true, false, MJPEG));
    }

    @Test
    public void rtspCamerasGoFullScreen() {
        assertFalse(Mjpeg.doorbellPicture(true, true, RTSP));
    }
}
