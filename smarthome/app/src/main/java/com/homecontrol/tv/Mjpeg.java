package com.homecontrol.tv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Cameras that stream MJPEG over HTTP, such as an ESP32 camera, instead of RTSP. Their address is the full
 * stream URL, for example http://192.168.1.194:81/stream.
 */
final class Mjpeg {

    private static final int MAX_FRAME = 4 * 1024 * 1024;

    interface Frames {
        /** Called with each whole JPEG. Return false to stop reading. */
        boolean frame(byte[] jpeg);
    }

    private Mjpeg() {
    }

    static boolean isStream(String url) {
        return url.regionMatches(true, 0, "http://", 0, 7) || url.regionMatches(true, 0, "https://", 0, 8);
    }

    /** Reads JPEGs from a multipart stream until it ends or frames says stop. */
    static void read(InputStream in, Frames frames) throws IOException {
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream(64 * 1024);
        byte[] chunk = new byte[8192];
        boolean inside = false;
        int last = -1;
        for (int n; (n = in.read(chunk)) >= 0; ) {
            for (int i = 0; i < n; i++) {
                int b = chunk[i] & 0xFF;
                if (inside) {
                    jpeg.write(b);
                    if (last == 0xFF && b == 0xD9) {
                        inside = false;
                        if (!frames.frame(jpeg.toByteArray())) return;
                        jpeg.reset();
                    } else if (jpeg.size() > MAX_FRAME) {
                        inside = false;
                        jpeg.reset();
                    }
                } else if (last == 0xFF && b == 0xD8) {
                    inside = true;
                    jpeg.write(0xFF);
                    jpeg.write(0xD8);
                }
                last = b;
            }
        }
    }
}
