package com.homecontrol.tv;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * A camera on the phone page. Phone browsers can't play RTSP, so while someone watches, the TV plays the camera
 * itself (the same RTSP player as CameraActivity, hardware-decoded) into an off-screen ImageReader instead of the
 * screen, and turns frames into JPEGs, up to ~8 a second, for an MJPEG stream any browser shows in an <img>.
 *
 * It uses the camera's smaller second stream (/live1 for /live0) to keep it light, has no sound, and stops a
 * few seconds after the last viewer leaves.
 */
final class CameraRelay {

    private static final long FRAME_MS = 125, IDLE_STOP_MS = 8000;
    private static final Map<String, CameraRelay> RELAYS = new HashMap<>();

    private final Context app;
    private String url;
    private final String main0;   // the camera's own stream, the fallback
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread frames;
    private ExoPlayer player;
    private ImageReader reader;
    private int width, height;
    private int viewers;
    private long lastFrameAt;
    // the latest JPEG, and its number
    private byte[] jpeg;
    private long seq;
    volatile String state = "idle";

    private CameraRelay(Context c, String url, String mainStream) {
        app = c.getApplicationContext();
        this.url = url;
        main0 = mainStream;
    }

    /** The relay for a camera, or null for no such camera. */
    static synchronized CameraRelay get(Context c, Home.Camera cam) {
        CameraRelay r = RELAYS.get(cam.id);
        String light = cam.url.endsWith("/live0") ? cam.url.substring(0, cam.url.length() - 1) + "1" : cam.url;
        if (r == null || !r.main0.equals(cam.url)) {
            if (r != null) r.stop();
            r = new CameraRelay(c, light, cam.url);
            RELAYS.put(cam.id, r);
        }
        return r;
    }

    /** Each relay's state, for /api/<secret>/status. */
    static synchronized String states() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, CameraRelay> e : RELAYS.entrySet()) {
            CameraRelay r = e.getValue();
            b.append(e.getKey()).append(": ").append(r.state).append(", viewers ").append(r.viewers)
                    .append(", frames ").append(r.seq).append(r.reader == null ? ", no surface" : "").append("; ");
        }
        return b.toString();
    }

    synchronized void join() {
        viewers++;
        if (player == null && frames == null) main.post(this::start);
    }

    synchronized void leave() {
        viewers--;
        if (viewers <= 0) {
            viewers = 0;
            main.postDelayed(() -> {
                synchronized (CameraRelay.this) {
                    if (viewers == 0) stop();
                }
            }, IDLE_STOP_MS);
        }
    }

    /** The next JPEG after `after` (waits up to timeoutMs); null when none came. */
    synchronized byte[] next(long after, long timeoutMs, long[] number) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (seq <= after || jpeg == null) {
            long left = end - SystemClock.uptimeMillis();
            if (left <= 0) return null;
            wait(left);
        }
        number[0] = seq;
        return jpeg;
    }

    private void start() {
        state = "connecting " + url.replaceAll("//[^@/]*@", "//");   // never the login
        frames = new HandlerThread("camera-frames");
        frames.start();
        player = new ExoPlayer.Builder(app).build();
        // Pictures only, and silent on the TV. (Turning the audio track off instead breaks Media3's RTSP loader.)
        player.setVolume(0f);
        player.addListener(new Player.Listener() {
            @Override
            public void onTracksChanged(@NonNull Tracks tracks) {
                // The size comes from the stream's description; the off-screen surface must match it.
                for (Tracks.Group g : tracks.getGroups()) {
                    if (g.getType() != C.TRACK_TYPE_VIDEO || g.length == 0) continue;
                    Format f = g.getTrackFormat(0);
                    if (f.width > 0 && f.height > 0) surface(f.width, f.height);
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                if (!url.equals(main0)) {   // no second stream on this camera: its main one
                    url = main0;
                    stop();
                    main.postDelayed(CameraRelay.this::start, 500);
                    return;
                }
                Throwable why = error.getCause();
                state = "error: " + error.getErrorCodeName() + (why != null ? " (" + why.getClass().getSimpleName() + ": "
                        + String.valueOf(why.getMessage()).replaceAll("//[^@/]*@", "//") + ")" : "");
                synchronized (CameraRelay.this) {
                    CameraRelay.this.notifyAll();
                }
            }
        });
        player.setMediaSource(new RtspMediaSource.Factory().setForceUseRtpTcp(true).setTimeoutMs(8000)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url))));
        player.setPlayWhenReady(true);
        player.prepare();
    }

    private void surface(int w, int h) {
        if (reader != null && w == width && h == height) return;
        if (reader != null) reader.close();
        width = w;
        height = h;
        reader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 3);
        reader.setOnImageAvailableListener(r -> {
            Image img;
            try {
                img = r.acquireLatestImage();
            } catch (RuntimeException e) {
                return;
            }
            if (img == null) return;
            try {
                long now = SystemClock.uptimeMillis();
                if (now - lastFrameAt < FRAME_MS) return;   // ~8 a second is plenty for a phone
                lastFrameAt = now;
                byte[] j = toJpeg(img);
                synchronized (CameraRelay.this) {
                    jpeg = j;
                    seq++;
                    state = "playing " + width + "x" + height + (url.equals(main0) ? " (main stream)" : " (second stream)");
                    CameraRelay.this.notifyAll();
                }
            } finally {
                img.close();
            }
        }, new Handler(frames.getLooper()));
        player.setVideoSurface(reader.getSurface());
    }

    /**
     * YUV_420_888 to NV21 (Y, then V and U interleaved), then JPEG. Pictures wider than 1280 are halved on the way
     * (every other pixel and row): plenty for a phone, and a quarter of the work for the TV.
     */
    private static byte[] toJpeg(Image img) {
        int sw = img.getWidth(), sh = img.getHeight();
        int step = sw > 1280 ? 2 : 1;
        int w = (sw / step) & ~1, h = (sh / step) & ~1;
        byte[] nv21 = new byte[w * h * 3 / 2];
        Image.Plane[] p = img.getPlanes();
        ByteBuffer y = p[0].getBuffer(), u = p[1].getBuffer(), v = p[2].getBuffer();
        int yRow = p[0].getRowStride(), yPix = p[0].getPixelStride();
        int uvRow = p[1].getRowStride(), uvPix = p[1].getPixelStride();
        int at = 0;
        if (step == 1 && yPix == 1) {
            for (int row = 0; row < h; row++) {
                y.position(row * yRow);
                y.get(nv21, row * w, w);
            }
            at = w * h;
        } else {
            for (int row = 0; row < h; row++) {
                int base = row * step * yRow;
                for (int col = 0; col < w; col++) nv21[at++] = y.get(base + col * step * yPix);
            }
        }
        // chroma is half the source size: for each output chroma sample take the source sample `step` apart
        for (int row = 0; row < h / 2; row++) {
            int base = row * step * uvRow;
            for (int col = 0; col < w / 2; col++) {
                int i = base + col * step * uvPix;
                nv21[at++] = v.get(i);
                nv21[at++] = u.get(i);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(48 * 1024);
        new YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(new Rect(0, 0, w, h), 70, out);
        return out.toByteArray();
    }

    private synchronized void stop() {
        Runnable r = () -> {
            if (player != null) {
                player.release();
                player = null;
            }
            if (reader != null) {
                reader.close();
                reader = null;
            }
            if (frames != null) {
                frames.quitSafely();
                frames = null;
            }
            width = height = 0;
            state = "idle";
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else main.post(r);
        jpeg = null;
    }
}
