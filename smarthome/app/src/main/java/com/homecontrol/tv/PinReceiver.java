package com.homecontrol.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * From the launcher (ControlReceiver only takes adb): PAIR_PIN, the PIN Fire OS's remote service shows while Smart
 * Home pairs with it, read off the screen (ignored unless pairing that moment); TYPE, phone typing when the screen has
 * a text field the app should see typed, or none, for the remote service to type into Fire OS's keyboard (it
 * sets the keyboard's whole text), "youtube" (type, search, backspace) for YouTube's keyboard; "open" to press OK on the field first, which opens that keyboard; or "submit" for the keyboard's Next.
 */
public class PinReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if ("com.homecontrol.tv.TYPE".equals(i.getAction())) {   // "text" to type, or "submit": the keyboard's Next
            String text = i.getStringExtra("text"), youtube = i.getStringExtra("youtube");
            Context app = c.getApplicationContext();
            if (youtube != null) {   // YouTube's keyboard, by the arrows (YouTubeKeyboard): "type", "search", "backspace"
                if ("type".equals(youtube) && (text == null || text.isEmpty() || text.length() > 100)) return;
                PendingResult done = goAsync();
                new Thread(() -> {
                    if ("type".equals(youtube)) YouTubeKeyboard.type(app, text);
                    else if ("search".equals(youtube) && !YouTubeKeyboard.search(app)) AmazonRemote.key(app, android.view.KeyEvent.KEYCODE_DPAD_CENTER);
                    else if ("backspace".equals(youtube)) YouTubeKeyboard.backspace(app);
                    done.finish();
                }, "youtube-keys").start();
                return;
            }
            boolean submit = i.getBooleanExtra("submit", false);
            if (!submit && (text == null || text.isEmpty() || text.length() > 500)) return;
            PendingResult done = goAsync();
            new Thread(() -> {
                if (submit) AmazonRemote.submit(c.getApplicationContext());
                else AmazonRemote.type(c.getApplicationContext(), text, i.getBooleanExtra("open", false));
                done.finish();
            }, "remote-type").start();
            return;
        }
        AmazonRemote.pinSeen(i.getStringExtra("pin"));
    }
}
