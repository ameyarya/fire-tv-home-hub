package com.homecontrol.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The PIN Fire OS's remote service shows while Smart Home pairs with it, read off the screen by the launcher
 * (ControlReceiver only takes adb). Ignored unless Smart Home is pairing that moment (AmazonRemote.pinSeen).
 */
public class PinReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        AmazonRemote.pinSeen(i.getStringExtra("pin"));
    }
}
