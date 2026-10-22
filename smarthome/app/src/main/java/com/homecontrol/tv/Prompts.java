package com.homecontrol.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.Toast;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Remote-friendly dialogs (a text box, a list, a list of check boxes), one at a time, and what they show
 * for dumpsys so the UI tests can read and drive them.
 */
final class Prompts {

    private final Activity activity;
    private AlertDialog dialog;
    private String title;
    private List<String> items = new ArrayList<>();
    String toast = "";

    Prompts(Activity activity) {
        this.activity = activity;
    }

    boolean showing() {
        return dialog != null && dialog.isShowing();
    }

    void say(String text) {
        toast = text;
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }

    /** A text box. Empty answers are refused with a message, not passed on. */
    void ask(String title, String value, String hint, int inputType, Consumer<String> done) {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setText(value);
        input.setHint(hint);
        input.setSelection(input.getText().length());
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(activity);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(R.string.ok, (di, w) -> {
                    String text = input.getText().toString().trim();
                    if (text.isEmpty() || text.equals("rtsp://")) say(activity.getString(R.string.empty_name));
                    else done.accept(text);
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        track(title, Arrays.asList("input"));
        dialog.show();
        input.requestFocus();
    }

    void askText(String title, String value, String hint, Consumer<String> done) {
        ask(title, value, hint, InputType.TYPE_CLASS_TEXT, done);
    }

    /** Pick one. */
    void list(String title, List<String> options, Consumer<Integer> done) {
        dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setItems(options.toArray(new String[0]), (di, which) -> done.accept(which))
                .create();
        track(title, options);
        dialog.show();
    }

    /** Tick any; Next passes the ticks on. */
    void checks(String title, List<String> options, boolean[] checked, Consumer<boolean[]> done) {
        dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMultiChoiceItems(options.toArray(new String[0]), checked, (di, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.next, (di, w) -> done.accept(checked))
                .setNegativeButton(R.string.cancel, null)
                .create();
        track(title, options);
        dialog.show();
    }

    /** A QR code with its link and a message, then a list of choices below (Back closes it). */
    void qr(String title, String link, String message, List<String> options, Consumer<Integer> done) {
        int size = (int) (220 * activity.getResources().getDisplayMetrics().density);
        android.widget.LinearLayout box = new android.widget.LinearLayout(activity);
        box.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        int pad = (int) (24 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        android.widget.ImageView img = new android.widget.ImageView(activity);
        img.setImageBitmap(qrBitmap(link, size));
        box.addView(img, new android.widget.LinearLayout.LayoutParams(size, size));
        android.widget.TextView text = new android.widget.TextView(activity);
        text.setText(message);
        text.setTextSize(16);
        text.setTextIsSelectable(false);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0, -2, 1);
        lp.setMarginStart(pad);
        box.addView(text, lp);
        dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(R.string.ok, null)
                .setNeutralButton(options.get(0), (di, w) -> done.accept(0))
                .setNegativeButton(options.get(1), (di, w) -> done.accept(1))
                .create();
        track(title, options);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
    }

    static android.graphics.Bitmap qrBitmap(String text, int size) {
        try {
            com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size);
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) b.setPixel(x, y, m.get(x, y) ? 0xff000000 : 0xffffffff);
            return b;
        } catch (com.google.zxing.WriterException e) {
            return android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888);
        }
    }

    /** A message with one button. */
    void tell(String title, String message) {
        dialog = new AlertDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton(R.string.ok, null).create();
        track(title, new ArrayList<>());
        dialog.show();
    }

    private void track(String t, List<String> options) {
        title = t;
        items = new ArrayList<>(options);
        AlertDialog mine = dialog;
        // One dialog often opens the next from its button; the first one's dismissal comes after that and
        // must not wipe what the new one set.
        dialog.setOnDismissListener(di -> {
            if (dialog != mine) return;
            title = null;
            items = new ArrayList<>();
        });
    }

    void dump(String prefix, PrintWriter w) {
        w.println(prefix + "  toast=" + toast);
        if (!showing() || title == null) return;
        w.println(prefix + "  dialog title=" + title);
        ListView lv = dialog.getListView();
        android.util.SparseBooleanArray checked = lv != null ? lv.getCheckedItemPositions() : null;
        for (int i = 0; i < items.size(); i++) {
            boolean focused = lv != null && lv.hasFocus() && lv.getSelectedItemPosition() == i;
            boolean ticked = checked != null && checked.get(i);
            w.println(prefix + "  dialog item=" + items.get(i) + (focused ? " [focused]" : "") + (ticked ? " [checked]" : ""));
        }
        View b = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        w.println(prefix + "  dialog okFocused=" + (b != null && b.isFocused()));
    }
}
