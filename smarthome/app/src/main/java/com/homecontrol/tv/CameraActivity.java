package com.homecontrol.tv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.PlayerView;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * Full-screen live view of one camera, straight from its RTSP or MJPEG stream on the local network.
 * Left and Right switch between cameras; Back returns to the grid.
 */
public class CameraActivity extends Activity {

    static final String EXTRA_ID = "camera";
    /** Close by itself after this long (a doorbell ring), unless someone presses a key on the remote. */
    static final String EXTRA_CLOSE_AFTER_MS = "closeAfterMs";

    private final android.os.Handler closer = new android.os.Handler(android.os.Looper.getMainLooper());

    private ExoPlayer player;
    private PlayerView view;
    private ImageView picture;
    private volatile Thread mjpegThread;
    private TextView name, message, timer;
    private long closeAt;
    private List<Home.Camera> cameras;
    private int index;
    private String state = "connecting";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setTurnScreenOn(true);
        setShowWhenLocked(true);
        view = findViewById(R.id.player);
        picture = findViewById(R.id.mjpeg);
        name = findViewById(R.id.camName);
        message = findViewById(R.id.camMessage);
        timer = findViewById(R.id.camTimer);
        cameras = Storage.get(this).cameras;
        String id = getIntent().getStringExtra(EXTRA_ID);
        for (int i = 0; i < cameras.size(); i++) if (cameras.get(i).id.equals(id)) index = i;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (cameras.isEmpty()) {
            finish();
            return;
        }
        play();
        long closeAfter = getIntent().getLongExtra(EXTRA_CLOSE_AFTER_MS, 0);
        if (closeAfter > 0) {
            closeAt = android.os.SystemClock.uptimeMillis() + closeAfter;
            timer.setVisibility(View.VISIBLE);
            tick();
        }
    }

    private void tick() {
        long left = closeAt - android.os.SystemClock.uptimeMillis();
        if (left <= 0) {
            finish();
            return;
        }
        timer.setText(Mjpeg.countdown(left));
        closer.postDelayed(this::tick, 250);
    }

    @Override
    protected void onStop() {
        closer.removeCallbacksAndMessages(null);
        release();
        super.onStop();
    }

    private void play() {
        release();
        Home.Camera cam = cameras.get(index);
        name.setText(cam.name);
        showMessage(getString(R.string.camera_connecting));
        state = "connecting";
        if (Mjpeg.isStream(cam.url)) {
            playMjpeg(cam.url);
            return;
        }
        player = new ExoPlayer.Builder(this).build();
        view.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onRenderedFirstFrame() {
                state = "playing";
                message.setVisibility(View.GONE);
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                state = "error";
                Throwable cause = error.getCause();
                String why = cause != null && cause.getMessage() != null ? cause.getMessage() : error.getErrorCodeName();
                showMessage(getString(R.string.camera_error, why));
            }
        });
        // TCP is steadier than UDP for RTSP over home Wi-Fi.
        RtspMediaSource source = new RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(8000)
                .createMediaSource(MediaItem.fromUri(Uri.parse(cam.url)));
        player.setMediaSource(source);
        player.setPlayWhenReady(true);
        player.prepare();
    }

    /** Reads the stream on its own thread, reconnecting if it drops, and shows the newest picture. */
    private void playMjpeg(String url) {
        picture.setImageDrawable(null);
        picture.setVisibility(View.VISIBLE);
        Thread thread = new Thread(() -> {
            Thread self = Thread.currentThread();
            while (mjpegThread == self) {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    Mjpeg.read(c.getInputStream(), jpeg -> {
                        Bitmap frame = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                        if (frame != null) runOnUiThread(() -> showFrame(self, frame));
                        return mjpegThread == self;
                    });
                } catch (IOException e) {
                    if (mjpegThread == self) {
                        runOnUiThread(() -> {
                            if (mjpegThread != self) return;
                            state = "error";
                            showMessage(getString(R.string.camera_error, String.valueOf(e.getMessage())));
                        });
                    }
                } finally {
                    if (c != null) c.disconnect();
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "camera-mjpeg");
        mjpegThread = thread;
        thread.start();
    }

    private void showFrame(Thread from, Bitmap frame) {
        if (mjpegThread != from) return;
        state = "playing";
        message.setVisibility(View.GONE);
        picture.setImageBitmap(frame);
    }

    private void showMessage(String text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
    }

    private void release() {
        Thread thread = mjpegThread;
        if (thread != null) {
            mjpegThread = null;
            thread.interrupt();
            picture.setVisibility(View.GONE);
        }
        if (player != null) {
            view.setPlayer(null);
            player.release();
            player = null;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        closer.removeCallbacksAndMessages(null);   // someone's watching
        timer.setVisibility(View.GONE);
        if (cameras.size() > 1 && (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT)) {
            index = (index + (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : cameras.size() - 1)) % cameras.size();
            play();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void dump(String prefix, FileDescriptor fd, PrintWriter w, String[] args) {
        super.dump(prefix, fd, w, args);
        w.println(prefix + "Camera state:");
        w.println(prefix + "  camera=" + name.getText());
        w.println(prefix + "  state=" + state);
        w.println(prefix + "  message=" + message.getText().toString().replace('\n', ' '));
    }
}
