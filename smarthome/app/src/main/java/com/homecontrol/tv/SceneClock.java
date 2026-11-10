package com.homecontrol.tv;

/**
 * Which scene is showing and when the next one comes, from the clock alone: the wallpaper on the home screen and the
 * screensaver (Smart Home) both use this, so they always show the same scene. Plain Java, tested on the JVM.
 */
final class SceneClock {
    private SceneClock() {
    }

    /** The scene number at this moment: changes every periodMs, cycling through count scenes. */
    static int index(long nowMs, long periodMs, int count) {
        return (int) Math.floorMod(Math.floorDiv(nowMs, periodMs), (long) count);
    }

    /** Milliseconds until the scene changes: the whole period just as it changes, never 0. */
    static long untilNext(long nowMs, long periodMs) {
        return periodMs - Math.floorMod(nowMs, periodMs);
    }
}
