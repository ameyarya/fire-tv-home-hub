package com.homecontrol.tv;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class MjpegTest {

    private static final byte[] ONE = {(byte) 0xFF, (byte) 0xD8, 1, 2, (byte) 0xFF, 0, 3, (byte) 0xFF, (byte) 0xD9};
    private static final byte[] TWO = {(byte) 0xFF, (byte) 0xD8, 9, (byte) 0xFF, (byte) 0xD9};

    private static byte[] stream(byte[]... frames) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] f : frames) {
            out.write("\r\n--frame\r\nContent-Type: image/jpeg\r\n\r\n".getBytes("US-ASCII"));
            out.write(f);
        }
        return out.toByteArray();
    }

    /** Hands out at most `size` bytes per read, as a network does. */
    private static InputStream chunked(byte[] data, int size) {
        return new ByteArrayInputStream(data) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, size));
            }
        };
    }

    @Test
    public void splitsFramesHoweverTheNetworkChunksThem() throws IOException {
        byte[] data = stream(ONE, TWO);
        for (int size : new int[]{1, 2, 3, 7, data.length}) {
            List<byte[]> got = new ArrayList<>();
            Mjpeg.read(chunked(data, size), f -> got.add(f));
            assertEquals(2, got.size());
            assertArrayEquals(ONE, got.get(0));
            assertArrayEquals(TWO, got.get(1));
        }
    }

    @Test
    public void stopsWhenTold() throws IOException {
        List<byte[]> got = new ArrayList<>();
        Mjpeg.read(new ByteArrayInputStream(stream(ONE, TWO)), f -> {
            got.add(f);
            return false;
        });
        assertEquals(1, got.size());
    }

    @Test
    public void ignoresAnUnfinishedFrame() throws IOException {
        byte[] cut = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3};
        List<byte[]> got = new ArrayList<>();
        Mjpeg.read(new ByteArrayInputStream(cut), f -> got.add(f));
        assertTrue(got.isEmpty());
    }

    @Test
    public void onlyTheOfferedDoorbellTimes() {
        for (int ok : new int[]{10, 30, 60, 120}) assertTrue(Mjpeg.isDoorbellSeconds(ok));
        for (int no : new int[]{0, -10, 45, 121}) assertFalse(Mjpeg.isDoorbellSeconds(no));
    }

    @Test
    public void countdownRoundsUpAndStopsAtZero() {
        assertEquals("2:00", Mjpeg.countdown(120_000));
        assertEquals("1:00", Mjpeg.countdown(59_001));
        assertEquals("0:10", Mjpeg.countdown(9_500));
        assertEquals("0:01", Mjpeg.countdown(1));
        assertEquals("0:00", Mjpeg.countdown(0));
        assertEquals("0:00", Mjpeg.countdown(-500));
    }

    @Test
    public void tellsStreamsFromRtspAddresses() {
        assertTrue(Mjpeg.isStream("http://192.168.1.194:81/stream"));
        assertTrue(Mjpeg.isStream("HTTPS://cam/stream"));
        assertFalse(Mjpeg.isStream("rtsp://u:p@192.168.1.249:554/live0"));
        assertFalse(Mjpeg.isStream("192.168.1.60"));
    }
}
