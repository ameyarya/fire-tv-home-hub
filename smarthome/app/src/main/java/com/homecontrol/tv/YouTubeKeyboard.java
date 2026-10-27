package com.homecontrol.tv;

import android.content.Context;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.Locale;

/**
 * Typing into YouTube's search, whose on-screen keyboard nothing can read or type into: the arrows walk to each key
 * and OK presses it, through Fire OS's remote service (AmazonRemote). The keyboard, as YouTube draws it:
 *
 *   A B C D E F G ⌫
 *   H I J K L M N &123
 *   O P Q R S T U 🌐
 *   V W X Y Z - '
 *   SPACE  CLEAR  SEARCH      (down from any letter lands on SPACE)
 *
 * Up stops at the top row, so Up ×4 then Right ×7 always lands on ⌫, whatever was focused: that's where each
 * typing starts. YouTube drops presses closer than ~0.3 s apart. Letters, space, - and ' only (no digits).
 */
final class YouTubeKeyboard {

    private static final String[] ROWS = {"abcdefg", "hijklmn", "opqrstu", "vwxyz-'"};
    private static final long GAP_MS = 300, RECENT_MS = 120_000;
    private static long typedAt;   // Enter and Delete use the keyboard only just after typing on it

    private YouTubeKeyboard() {
    }

    static synchronized void type(Context c, String text) {
        int[] at = anchor(c);
        for (char ch : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (at[0] == 4) {   // on SPACE: up comes back to V
                press(c, KeyEvent.KEYCODE_DPAD_UP);
                at[0] = 3;
                at[1] = 0;
            }
            if (ch == ' ') {
                walk(c, at, 3, 0);
                press(c, KeyEvent.KEYCODE_DPAD_DOWN);
                at[0] = 4;
            } else {
                int row = -1, col = -1;
                for (int r = 0; r < ROWS.length && row < 0; r++) {
                    int i = ROWS[r].indexOf(ch);
                    if (i >= 0) {
                        row = r;
                        col = i;
                    }
                }
                if (row < 0) continue;   // not on this keyboard's letters page
                walk(c, at, row, col);
            }
            press(c, KeyEvent.KEYCODE_DPAD_CENTER);
        }
        typedAt = SystemClock.uptimeMillis();
    }

    /** Enter: the keyboard's SEARCH (down from the letters always lands on SPACE; SEARCH is two to its right). */
    static synchronized boolean search(Context c) {
        if (SystemClock.uptimeMillis() - typedAt > RECENT_MS) return false;
        int[] at = anchor(c);
        walk(c, at, 3, 0);
        press(c, KeyEvent.KEYCODE_DPAD_DOWN);
        press(c, KeyEvent.KEYCODE_DPAD_RIGHT);
        press(c, KeyEvent.KEYCODE_DPAD_RIGHT);
        press(c, KeyEvent.KEYCODE_DPAD_CENTER);
        typedAt = 0;
        return true;
    }

    /** Delete: the keyboard's ⌫; false unless typed on it just now. */
    static synchronized boolean backspace(Context c) {
        if (SystemClock.uptimeMillis() - typedAt > RECENT_MS) return false;
        anchor(c);
        press(c, KeyEvent.KEYCODE_DPAD_CENTER);
        typedAt = SystemClock.uptimeMillis();
        return true;
    }

    /** To ⌫ from anywhere on the keyboard; returns the position {row, column}. */
    private static int[] anchor(Context c) {
        for (int i = 0; i < 4; i++) press(c, KeyEvent.KEYCODE_DPAD_UP);
        for (int i = 0; i < 7; i++) press(c, KeyEvent.KEYCODE_DPAD_RIGHT);
        return new int[]{0, 7};
    }

    /** Across, then up or down (every letter column runs through rows 0–3). */
    private static void walk(Context c, int[] at, int row, int col) {
        for (; at[1] < col; at[1]++) press(c, KeyEvent.KEYCODE_DPAD_RIGHT);
        for (; at[1] > col; at[1]--) press(c, KeyEvent.KEYCODE_DPAD_LEFT);
        for (; at[0] < row; at[0]++) press(c, KeyEvent.KEYCODE_DPAD_DOWN);
        for (; at[0] > row; at[0]--) press(c, KeyEvent.KEYCODE_DPAD_UP);
    }

    private static void press(Context c, int key) {
        long start = SystemClock.uptimeMillis();
        AmazonRemote.key(c, key);
        long left = GAP_MS - (SystemClock.uptimeMillis() - start);
        if (left > 0) SystemClock.sleep(left);
    }
}
