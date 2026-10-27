package com.homecontrol.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * From the launcher (ControlReceiver only takes adb): PAIR_PIN, the PIN Fire OS's remote service shows while Smart
 * Home pairs with it, read off the screen (ignored unless pairing that moment); TYPE, phone typing when the screen has
 * no text field, for the remote service to type instead.
 */
public class PinReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if ("com.homecontrol.tv.TYPE".equals(i.getAction())) {
            String text = i.getStringExtra("text");
            if (text == null || text.isEmpty() || text.length() > 500) return;
            PendingResult done = goAsync();
            new Thread(() -> {
                AmazonRemote.type(c.getApplicationContext(), text);
                done.finish();
            }, "remote-type").start();
            return;
        }
        AmazonRemote.pinSeen(i.getStringExtra("pin"));
    }
}
