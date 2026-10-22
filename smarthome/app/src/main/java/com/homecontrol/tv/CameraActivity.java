package com.homecontrol.tv;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.PlayerView;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/**
 * Full-screen live view of one camera, straight from its RTSP stream on the local network.
 * Left and Right switch between cameras; Back returns to the grid.
 */
public class CameraActivity extends Activity {

    static final String EXTRA_ID = "camera";

    private ExoPlayer player;
    private PlayerView view;
    private TextView name, message;
    private List<Home.Camera> cameras;
    private int index;
    private String state = "connecting";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = findViewById(R.id.player);
        name = findViewById(R.id.camName);
        message = findViewById(R.id.camMessage);
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
    }

    @Override
    protected void onStop() {
        release();
        super.onStop();
    }

    private void play() {
        release();
        Home.Camera cam = cameras.get(index);
        name.setText(cam.name);
        showMessage(getString(R.string.camera_connecting));
        state = "connecting";
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

    private void showMessage(String text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
    }

    private void release() {
        if (player != null) {
            view.setPlayer(null);
            player.release();
            player = null;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
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
