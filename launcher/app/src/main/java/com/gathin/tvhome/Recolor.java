package com.gathin.tvhome;

/**
 * Two-colour recolouring of an app's banner (config.xml tile_recolor): its dark parts become one colour
 * and its light parts another, by brightness, with smooth edges. Spotify's dark banner with a green logo
 * becomes Spotify green with a black logo. Plain math so it is tested on the JVM; AppAdapter turns the
 * matrix into a ColorMatrixColorFilter.
 */
final class Recolor {

    private static final float WR = 0.2126f, WG = 0.7152f, WB = 0.0722f;   // perceived brightness

    private Recolor() {
    }

    static float luminance(int argb) {
        return (WR * ((argb >> 16) & 0xFF) + WG * ((argb >> 8) & 0xFF) + WB * (argb & 0xFF)) / 255f;
    }

    /**
     * A 4x5 ColorMatrix mapping brightness {@code lo} (and darker) to {@code dark} and {@code hi} (and
     * lighter) to {@code light}, linearly between. Alpha is kept.
     */
    static float[] matrix(int dark, int light, float lo, float hi) {
        float span = Math.max(0.05f, hi - lo);
        float[] m = new float[20];
        for (int c = 0; c < 3; c++) {
            int shift = 16 - 8 * c;
            float d = (dark >> shift) & 0xFF, l = (light >> shift) & 0xFF;
            // out = dark + (light - dark) * (brightness - lo) / span, brightness = (WR*r + WG*g + WB*b) / 255
            m[c * 5] = (l - d) * WR / (255f * span);
            m[c * 5 + 1] = (l - d) * WG / (255f * span);
            m[c * 5 + 2] = (l - d) * WB / (255f * span);
            m[c * 5 + 4] = d - (l - d) * lo / span;
        }
        m[18] = 1f;   // alpha unchanged
        return m;
    }

    /** What the matrix does to one colour (for the tests; the TV applies it on the GPU). */
    static int apply(float[] m, int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF, a = (argb >>> 24);
        int[] out = new int[3];
        for (int c = 0; c < 3; c++) {
            float v = m[c * 5] * r + m[c * 5 + 1] * g + m[c * 5 + 2] * b + m[c * 5 + 4];
            out[c] = Math.max(0, Math.min(255, Math.round(v)));
        }
        return (a << 24) | (out[0] << 16) | (out[1] << 8) | out[2];
    }

    /** The 10th and 90th percentile brightness of some pixels: the banner's background and its logo. */
    static float[] range(int[] pixels) {
        float[] l = new float[pixels.length];
        for (int i = 0; i < l.length; i++) l[i] = luminance(pixels[i]);
        java.util.Arrays.sort(l);
        return new float[]{l[(int) (l.length * 0.1f)], l[Math.min(l.length - 1, (int) (l.length * 0.9f))]};
    }

    /** "pkg|#1DB954|#000000" -> {dark, light}; null when unreadable. */
    static int[] parse(String spec, String[] pkgOut) {
        String[] p = spec.split("\\|");
        if (p.length != 3) return null;
        try {
            pkgOut[0] = p[0].trim();
            return new int[]{0xFF000000 | Integer.parseInt(p[1].trim().replace("#", ""), 16),
                    0xFF000000 | Integer.parseInt(p[2].trim().replace("#", ""), 16)};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
