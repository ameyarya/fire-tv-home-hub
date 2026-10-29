package com.gathin.tvhome;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * The TV's screen for Smart Home's phone page (its Mirror view): call("shot") returns the screen now as a JPEG,
 * taken by FireHomeWatcher (an accessibility service may take screenshots on Android 11, without asking). Only apps
 * signed like this one may call it (MIRROR permission), since it shows whatever is on the TV.
 */
public class MirrorProvider extends ContentProvider {

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!"shot".equals(method)) return null;
        byte[] jpeg = FireHomeWatcher.shot();
        Bundle b = new Bundle();
        if (jpeg != null) b.putByteArray("jpeg", jpeg);
        else b.putString("error", FireHomeWatcher.lastShotError);
        return b;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
        return null;
    }

    @Override
    public String getType(Uri u) {
        return null;
    }

    @Override
    public Uri insert(Uri u, ContentValues v) {
        return null;
    }

    @Override
    public int delete(Uri u, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri u, ContentValues v, String s, String[] a) {
        return 0;
    }
}
