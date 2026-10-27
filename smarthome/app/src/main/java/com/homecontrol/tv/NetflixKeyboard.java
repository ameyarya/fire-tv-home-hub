package com.homecontrol.tv;

import android.content.Context;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.Locale;

/**
 * Typing into Netflix's search, which nothing can read (it hides its screen even from screenshots): the arrows walk
 * to each key and OK presses it, through Fire OS's remote service (AmazonRemote), like YouTubeKeyboard. The keyboard:
 *
 *   [ space ][  ⌫  ]          (Up from a b c → space, from d e f → ⌫; Down from space → b, from ⌫ → e)
 *   a b c d e f
 *   g h i j k l              Left from the first column wraps to the last; Right from the last leaves to the
 *   m n o p q r              results, which stop at their 4th card, and Left from their first card comes back
 *   s t u v w x              to "a". So Right ×10, Left ×4 always lands on "a", wherever the highlight was on the
 *   y z 1 2 3 4              keyboard or results: that's where each typing starts. (Netflix otherwise remembers
 *   5 6 7 8 9 0              the last key, so there's no fixed point inside the keyboard.)
 *
 * Netflix searches as it's typed, so Enter goes to the first result instead. It drops presses that come too close,
 * above all while it loads results, so 0.5 s between presses and a pause after each letter.
 */
final class NetflixKeyboard {

    private static final String[] ROWS = {"abcdef", "ghijkl", "mnopqr", "stuvwx", "yz1234", "567890"};
    private static final long GAP_MS = 500, SETTLE_MS = 700, RECENT_MS = 120_000;
    private static long typedAt;   // Enter and Delete use the keyboard only just after typing on it

    private NetflixKeyboard() {
    }

    static synchronized void type(Context c, String text) {
        int[] at = anchor(c);   // {row, column}; row 0 is the letters' first row, -1 the space/⌫ row
        for (char ch : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (ch == ' ') {
                topKey(c, at, 1);
                continue;
            }
            int row = -1, col = -1;
            for (int r = 0; r < ROWS.length && row < 0; r++) {
                int i = ROWS[r].indexOf(ch);
                if (i >= 0) {
                    row = r;
                    col = i;
                }
            }
            if (row < 0) continue;   // not on this keyboard
            walk(c, at, row, col);
            press(c, KeyEvent.KEYCODE_DPAD_CENTER);
            SystemClock.sleep(SETTLE_MS);   // new results load
        }
        typedAt = SystemClock.uptimeMillis();
    }

    /** Delete: the ⌫ key; false unless typed on the keyboard just now. */
    static synchronized boolean backspace(Context c) {
        if (SystemClock.uptimeMillis() - typedAt > RECENT_MS) return false;
        topKey(c, anchor(c), 4);
        typedAt = SystemClock.uptimeMillis();
        return true;
    }

    /** Enter: to the first result (Netflix has searched already); false unless typed on the keyboard just now. */
    static synchronized boolean results(Context c) {
        if (SystemClock.uptimeMillis() - typedAt > RECENT_MS) return false;
        for (int i = 0; i < 10; i++) press(c, KeyEvent.KEYCODE_DPAD_RIGHT);
        for (int i = 0; i < 3; i++) press(c, KeyEvent.KEYCODE_DPAD_LEFT);
        typedAt = 0;
        return true;
    }

    /** Space (over "b", column 1) or ⌫ (over "e", column 4): up to it, OK, and back down to that letter. */
    private static void topKey(Context c, int[] at, int col) {
        walk(c, at, 0, col);
        press(c, KeyEvent.KEYCODE_DPAD_UP);
        press(c, KeyEvent.KEYCODE_DPAD_CENTER);
        SystemClock.sleep(SETTLE_MS);
        press(c, KeyEvent.KEYCODE_DPAD_DOWN);
    }

    private static int[] anchor(Context c) {
        for (int i = 0; i < 10; i++) press(c, KeyEvent.KEYCODE_DPAD_RIGHT);
        SystemClock.sleep(SETTLE_MS);
        for (int i = 0; i < 4; i++) press(c, KeyEvent.KEYCODE_DPAD_LEFT);
        SystemClock.sleep(SETTLE_MS);
        return new int[]{0, 0};
    }

    /** Up or down, then across, never Right past the last column (that leaves the keyboard). */
    private static void walk(Context c, int[] at, int row, int col) {
        for (; at[0] < row; at[0]++) press(c, KeyEvent.KEYCODE_DPAD_DOWN);
        for (; at[0] > row; at[0]--) press(c, KeyEvent.KEYCODE_DPAD_UP);
        for (; at[1] < col; at[1]++) press(c, KeyEvent.KEYCODE_DPAD_RIGHT);
        for (; at[1] > col; at[1]--) press(c, KeyEvent.KEYCODE_DPAD_LEFT);
    }

    private static void press(Context c, int key) {
        long start = SystemClock.uptimeMillis();
        AmazonRemote.key(c, key);
        long left = GAP_MS - (SystemClock.uptimeMillis() - start);
        if (left > 0) SystemClock.sleep(left);
    }
}
