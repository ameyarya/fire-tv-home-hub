package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RecolorTest {

    static final int SPOTIFY_GREEN = 0xFF1DB954, BLACK = 0xFF000000;

    private static int distance(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    @Test
    public void spotifysBlackTileBecomesGreenAndItsLogoBlack() {
        // Spotify's banner: near-black background, green logo and wordmark.
        int bg = 0xFF121212, logo = 0xFF1ED760;
        int[] pixels = new int[100];
        for (int i = 0; i < 100; i++) pixels[i] = i < 75 ? bg : logo;
        float[] range = Recolor.range(pixels);
        float[] m = Recolor.matrix(SPOTIFY_GREEN, BLACK, range[0], range[1]);
        assertTrue(Integer.toHexString(Recolor.apply(m, bg)), distance(Recolor.apply(m, bg), SPOTIFY_GREEN) <= 6);
        assertTrue(Integer.toHexString(Recolor.apply(m, logo)), distance(Recolor.apply(m, logo), BLACK) <= 6);
    }

    @Test
    public void edgesBlendSmoothly() {
        float[] m = Recolor.matrix(SPOTIFY_GREEN, BLACK, 0.1f, 0.6f);
        int mid = Recolor.apply(m, 0xFF5E5E5E);   // brightness ~0.37: about halfway
        int g = (mid >> 8) & 0xFF;
        assertTrue("halfway green " + g, g > 40 && g < 150);
    }

    @Test
    public void alphaIsKept() {
        float[] m = Recolor.matrix(SPOTIFY_GREEN, BLACK, 0.1f, 0.6f);
        assertEquals(0x80, Recolor.apply(m, 0x80121212) >>> 24);
    }

    @Test
    public void parsesTheConfigEntry() {
        String[] pkg = new String[1];
        assertArrayEquals(new int[]{SPOTIFY_GREEN, BLACK}, Recolor.parse("com.spotify.tv.android|#1DB954|#000000", pkg));
        assertEquals("com.spotify.tv.android", pkg[0]);
        assertNull(Recolor.parse("com.spotify.tv.android|green", pkg));
        assertNull(Recolor.parse("x|#zz|#000000", pkg));
    }
}
