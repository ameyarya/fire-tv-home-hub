package com.homecontrol.tv;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import java.util.ArrayList;
import java.util.List;

/**
 * The TV this app runs on, as dashboard tiles: its HDMI and AV inputs, and its volume. Inputs switch the
 * way the clean launcher's Inputs menu does (the input's passthrough channel, which Live TV handles).
 * Volume goes through the TV's own audio stream; with a soundbar on HDMI-ARC the TV may report a fixed
 * volume, and the tile says so.
 */
final class TvControls {

    static final class Input {
        final String id;
        final String label;

        Input(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private TvControls() {
    }

    static List<Input> inputs(Context ctx) {
        List<Input> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        try {
            for (TvInputInfo i : tim.getTvInputList()) {
                if (!i.isPassthroughInput() || i.isHidden(ctx)) continue;
                CharSequence custom = i.loadCustomLabel(ctx);
                CharSequence label = custom != null && custom.length() > 0 ? custom : i.loadLabel(ctx);
                out.add(new Input(i.getId(), String.valueOf(label)));
            }
        } catch (RuntimeException ignored) {   // some firmware refuses the list to ordinary apps
        }
        return out;
    }

    /**
     * Fire OS: the link Amazon's own input picker opens, which starts its passthrough player. Tried
     * first, but on Fire OS 8 it needs com.amazon.tv.permission.TUNE_INPUT (signature|amazon), so the
     * dashboard doesn't offer switching there (isFireOs). Other TVs: the standard passthrough URI.
     */
    /** Fire OS: only Amazon's apps may switch inputs (see switchTo). */
    static boolean isFireOs(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo("com.amazon.tv.inputpreference.service", 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static Intent fireOsIntent(String inputId) {
        return new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("tvinput://tunenow?inputId=" + android.net.Uri.encode(inputId)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static boolean switchTo(Context ctx, Input in) {
        Intent fire = fireOsIntent(in.id);
        Intent standard = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(in.id))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        for (Intent i : new Intent[]{fire, standard}) {
            if (i.resolveActivity(ctx.getPackageManager()) == null) continue;
            try {
                ctx.startActivity(i);
                return true;
            } catch (RuntimeException ignored) {
            }
        }
        return false;
    }

    private static AudioManager audio(Context ctx) {
        return (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    /** "35%", "Muted" or "Fixed" (volume handled by a soundbar or receiver). */
    static String volumeText(Context ctx) {
        AudioManager am = audio(ctx);
        if (am.isVolumeFixed()) return "Fixed";
        if (am.isStreamMute(AudioManager.STREAM_MUSIC)) return "Muted";
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        return Math.round(100f * am.getStreamVolume(AudioManager.STREAM_MUSIC) / max) + "%";
    }

    /** direction: AudioManager.ADJUST_RAISE, ADJUST_LOWER or ADJUST_TOGGLE_MUTE. Shows the TV's own volume bar. */
    static void adjust(Context ctx, int direction) {
        audio(ctx).adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
    }
}
