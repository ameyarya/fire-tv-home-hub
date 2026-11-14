package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.view.View;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * The home screen's wallpaper: a scene photo with a big glass clock sitting behind its scenery, so the peaks and
 * hills overlap the bottom of the digits. Five scenes follow the sun and the weather (see Sky). The pictures, the clock's
 * frosted fill and the layout come from couch-tv (github.com/Seagey/couch-tv, MIT; see CREDITS.md).
 *
 * Layers, back to front: the photo, the clock (filled with a blurred copy of the photo, with a light rim and a
 * sheen), the scenery cut out of the photo, a soft shade at the bottom for the dock. Positions are for 1920 wide
 * and scale from there.
 */
public final class SceneView extends View {

    private static final long FADE_MS = 1_600;

    /** One scene: its three pictures and where and how its clock is drawn (pixels at 1920 wide). */
    private static final class Look {
        final int photo, glass, cutout;
        final float top, size, date, left;   // clock top and font size; date line top; clock's left edge or -1 to centre
        final float photoBrightness, fillBrightness, fillSaturation, fillContrast, fillAlpha;
        final int rimFill, rimStroke, dateColor, shade;
        final boolean sheen;

        Look(int photo, int glass, int cutout, float top, float size, float date, float left, float photoBrightness,
             float fillBrightness, float fillSaturation, float fillContrast, float fillAlpha, int rimFill, int rimStroke,
             int dateColor, int shade, boolean sheen) {
            this.photo = photo;
            this.glass = glass;
            this.cutout = cutout;
            this.top = top;
            this.size = size;
            this.date = date;
            this.left = left;
            this.photoBrightness = photoBrightness;
            this.fillBrightness = fillBrightness;
            this.fillSaturation = fillSaturation;
            this.fillContrast = fillContrast;
            this.fillAlpha = fillAlpha;
            this.rimFill = rimFill;
            this.rimStroke = rimStroke;
            this.dateColor = dateColor;
            this.shade = shade;
            this.sheen = sheen;
        }
    }

    private static final int WHITE_RIM_FILL = 0x1AFFFFFF, WHITE_RIM = 0x8CFFFFFF, WHITE_DATE = 0xEBFFFFFF;
    private static final Look[] LOOKS = {
            // Night: the clock behind the peak, pink glass
            new Look(R.drawable.scene_night, R.drawable.scene_night_glass, R.drawable.scene_night_fg,
                    150, 400, 96, -1, 0.86f, 1.15f, 1.2f, 1f, 0.9f, 0x0FFFC8D7, 0x80FFD7E1, 0xEBFFE8EE, 0, true),
            // Golden hour: a little smaller, low enough for the volcano's tip to rise into the digits
            new Look(R.drawable.scene_golden, R.drawable.scene_golden_glass, R.drawable.scene_golden_fg,
                    152, 390, 106, -1, 1f, 1f, 1f, 1f, 1f, WHITE_RIM_FILL, WHITE_RIM, WHITE_DATE, 0, true),
            // Fog: a big dark clock on the left, sinking into the misty hills
            new Look(R.drawable.scene_fog, R.drawable.scene_fog_glass, R.drawable.scene_fog_fg,
                    172, 470, 116, 96, 1f, 0.46f, 0.8f, 1.1f, 0.8f, 0, 0x99FFFFFF, 0xFF16201D, 0, false),
            // Rainforest dawn: warm light at the top, the clock low enough for the ridges to cross it
            new Look(R.drawable.scene_dawn, R.drawable.scene_dawn_glass, R.drawable.scene_dawn_fg,
                    230, 400, 176, -1, 1f, 1f, 1f, 1f, 1f, WHITE_RIM_FILL, WHITE_RIM, WHITE_DATE, 0x6B321C0C, true),
            // Daylight, clear: the clock in the sky, its bottom behind the peaks, the lake below
            new Look(R.drawable.scene_water, R.drawable.scene_water_glass, R.drawable.scene_water_fg,
                    130, 420, 76, -1, 1f, 1f, 1f, 1f, 1f, WHITE_RIM_FILL, WHITE_RIM, WHITE_DATE, 0x6B000C18, true),
            // Autumn lake: the clock in the big sky, its foot behind the peaks
            new Look(R.drawable.scene_autumn, R.drawable.scene_autumn_glass, R.drawable.scene_autumn_fg,
                    70, 400, 56, -1, 1f, 1f, 1f, 1f, 1f, WHITE_RIM_FILL, WHITE_RIM, WHITE_DATE, 0x6B000C18, true),
            // Cherry blossoms: the clock between the pagoda and the branches
            new Look(R.drawable.scene_sakura, R.drawable.scene_sakura_glass, R.drawable.scene_sakura_fg,
                    110, 330, 56, -1, 1f, 1f, 1f, 1f, 1f, WHITE_RIM_FILL, WHITE_RIM, WHITE_DATE, 0x6B2A1020, true),
            // Northern lights: the clock in the aurora, the peaks across its foot
            new Look(R.drawable.scene_aurora, R.drawable.scene_aurora_glass, R.drawable.scene_aurora_fg,
                    70, 400, 56, -1, 0.9f, 1.1f, 1.1f, 1f, 0.95f, WHITE_RIM_FILL, WHITE_RIM, WHITE_DATE, 0x6B000010, true),
            // Snowy cabin: the clock in the night sky above the pines
            new Look(R.drawable.scene_cabin, R.drawable.scene_cabin_glass, R.drawable.scene_cabin_fg,
                    60, 360, 46, -1, 0.95f, 1.1f, 1.1f, 1f, 0.95f, WHITE_RIM_FILL, WHITE_RIM, WHITE_DATE, 0x6B000010, true),
            // Summer thunderstorm: the clock in the dark sky, the sunlit ridge crossing its foot
            new Look(R.drawable.scene_storm, R.drawable.scene_storm_glass, R.drawable.scene_storm_fg,
                    110, 380, 60, -1, 1f, 1f, 1f, 1f, 1f, WHITE_RIM_FILL, WHITE_RIM, WHITE_DATE, 0x6B000000, true),
    };

    /** A loaded scene: its bitmaps and ready paints. */
    private static final class Scene {
        final Look look;
        Bitmap photo, glass, cutout;

        Scene(Look look) {
            this.look = look;
        }

        void recycle() {
            for (Bitmap b : new Bitmap[]{photo, glass, cutout}) if (b != null) b.recycle();
            photo = glass = cutout = null;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint pic = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG),
            sheen = new Paint(Paint.ANTI_ALIAS_FLAG), dateText = new Paint(Paint.ANTI_ALIAS_FLAG),
            scrim = new Paint(), shade = new Paint();
    private final Matrix matrix = new Matrix();
    private final Rect bounds = new Rect();

    private Scene current, next;
    private int index;
    private float fade = 1f;   // how far `next` has come in over `current`
    private boolean running, loading;
    private String time = "", date = "";

    /** The scene for this moment: from the sun and the weather (see Sky), so the wallpaper and the screensaver show the same one. */
    private int timeIndex() {
        return SkyStore.scene(getContext());
    }

    /** Milliseconds to the next look at whether the scene should change (every turn). */
    private static long untilNext() {
        return SkyStore.TURN_MS - System.currentTimeMillis() % SkyStore.TURN_MS + 50;   // the next turn of the scenes in a mood
    }

    private final Runnable rotate = this::startNext;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshText();
            invalidate();
            if (running) main.postDelayed(this, 15_000);
        }
    };

    public SceneView(Context c) {
        this(c, null);
    }

    public SceneView(Context c, AttributeSet a) {
        super(c, a);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        Typeface digits = getResources().getFont(R.font.antonio);
        for (Paint p : new Paint[]{fill, rim, sheen}) {
            p.setTypeface(digits);
            p.setLetterSpacing(-0.02f);
        }
        rim.setStyle(Paint.Style.STROKE);
        dateText.setTypeface(getResources().getFont(R.font.outfit));
        dateText.setFontVariationSettings("'wght' 500");
        dateText.setLetterSpacing(0.01f);
        dateText.setSubpixelText(true);
        pic.setFilterBitmap(true);
    }

    private static SceneView active;

    /** Look at once whether the scene should change (every turn) (Smart Home just sent a new list or weather). */
    static void recheck() {
        SceneView v = active;
        if (v != null) v.main.post(() -> {
            v.main.removeCallbacks(v.rotate);
            v.startNext();
        });
    }

    /** Shows the scene and keeps them turning; call from onResume. */
    void start() {
        if (running) return;
        running = true;
        active = this;
        refreshText();
        index = timeIndex();
        if (current != null && current.look != LOOKS[index]) {
            current.recycle();
            current = null;
        }
        if (current == null) current = load(LOOKS[index]);
        tick.run();
        main.postDelayed(rotate, untilNext());
    }

    /** Stops the timers and drops the scene waiting to come in; call from onPause. */
    void stop() {
        running = false;
        if (active == this) active = null;
        main.removeCallbacks(rotate);
        main.removeCallbacks(tick);
        if (next != null) {
            next.recycle();
            next = null;
        }
        loading = false;
        fade = 1f;
    }

    private Scene load(Look look) {
        Scene s = new Scene(look);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        s.photo = BitmapFactory.decodeResource(getResources(), look.photo, o);
        o.inSampleSize = 2;   // the glass copy is a blur
        s.glass = BitmapFactory.decodeResource(getResources(), look.glass, o);
        o.inSampleSize = 1;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        s.cutout = BitmapFactory.decodeResource(getResources(), look.cutout, o);
        return s;
    }

    private void startNext() {
        if (!running || loading) return;
        loading = true;
        final int upcoming = timeIndex();
        if (upcoming == index) {   // not yet time
            loading = false;
            main.postDelayed(rotate, untilNext());
            return;
        }
        new Thread(() -> {
            Scene s = load(LOOKS[upcoming]);
            main.post(() -> {
                if (!running || !loading) {
                    s.recycle();
                    return;
                }
                next = s;
                ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(FADE_MS);
                a.addUpdateListener(v -> {
                    fade = (float) v.getAnimatedValue();
                    invalidate();
                });
                a.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        if (next != s) return;
                        if (current != null) current.recycle();
                        current = s;
                        next = null;
                        index = upcoming;
                        fade = 1f;
                        loading = false;
                        if (running) main.postDelayed(rotate, untilNext());
                        invalidate();
                    }
                });
                a.start();
            });
        }, "scene-load").start();
    }

    private void refreshText() {
        Date now = new Date();
        boolean h24 = DateFormat.is24HourFormat(getContext());
        time = new java.text.SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now);
        date = new java.text.SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (current == null || w == 0) return;
        c.drawColor(Color.BLACK);
        float night = dimmed() ? 0.72f : 1f;
        draw(c, current, 1f, night, w, h);
        if (next != null && fade > 0f) draw(c, next, fade, night, w, h);
        scrim.setShader(new LinearGradient(0, h * 0.6f, 0, h, 0, 0x80000000, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.6f, w, h, scrim);
    }

    /** After 9 pm and before dawn everything dims a little. */
    private static boolean dimmed() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour >= 21 || hour < 5;
    }

    private void draw(Canvas c, Scene s, float alpha, float night, int w, int h) {
        if (s.photo == null || s.cutout == null || s.glass == null) return;
        Look l = s.look;
        float k = w / 1920f;
        int layer = c.saveLayerAlpha(0, 0, w, h, Math.round(alpha * 255));

        pic.setColorFilter(brightness(l.photoBrightness * night));
        cover(c, s.photo, w, h, pic);
        if (l.shade != 0) {
            shade.setShader(new LinearGradient(0, 0, 0, h * 0.55f, l.shade, 0, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h * 0.55f, shade);
        }

        // the clock
        float size = l.size * k;
        fill.setTextSize(size);
        rim.setTextSize(size);
        sheen.setTextSize(size);
        fill.getTextBounds(time, 0, time.length(), bounds);
        float width = fill.measureText(time);
        float x = l.left >= 0 ? l.left * k : (w - width) / 2f;
        float baseline = (l.top + 0.918f * l.size) * k;   // the line box is 0.82 em tall, Antonio's baseline sits 0.918 em down

        BitmapShader glass = new BitmapShader(s.glass, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        matrix.setScale(w / (float) s.glass.getWidth(), h / (float) s.glass.getHeight());
        glass.setLocalMatrix(matrix);
        fill.setShader(glass);
        fill.setColorFilter(fillFilter(l, night));
        fill.setAlpha(Math.round(l.fillAlpha * 255));
        fill.setShadowLayer(28f * k, 0, 6f * k, 0x29000000);
        c.drawText(time, x, baseline, fill);
        fill.clearShadowLayer();

        rim.setShader(null);
        rim.setStyle(Paint.Style.FILL);
        rim.setColor(l.rimFill);
        c.drawText(time, x, baseline, rim);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, 1.45f * k));
        rim.setColor(l.rimStroke);
        c.drawText(time, x, baseline, rim);

        if (l.sheen) {   // light across the glass, from top left (CSS 160deg)
            float top = baseline - 0.87f * size, bottom = baseline, mid = x + width / 2f;
            float dx = 0.342f, dy = 0.94f, half = (width * dx + (bottom - top) * dy) / 2f;
            float cy = (top + bottom) / 2f;
            sheen.setShader(new LinearGradient(mid - dx * half, cy - dy * half, mid + dx * half, cy + dy * half,
                    new int[]{0x8CFFFFFF, 0, 0, 0x38FFFFFF}, new float[]{0f, 0.38f, 0.7f, 1f}, Shader.TileMode.CLAMP));
            c.drawText(time, x, baseline, sheen);
        }

        // the scenery over the digits
        pic.setColorFilter(brightness(l.photoBrightness * night));
        cover(c, s.cutout, w, h, pic);

        // the date, above the clock
        dateText.setTextSize(34f * k);
        dateText.setColor(l.dateColor);
        dateText.setShadowLayer(l.dateColor == 0xFF16201D ? 0f : 9f * k, 0, 2f * k, 0x40000000);
        float dateWidth = dateText.measureText(date);
        c.drawText(date, l.left >= 0 ? (l.left + 8) * k : (w - dateWidth) / 2f, (l.date + 30f) * k, dateText);

        c.restoreToCount(layer);
    }

    /** The picture scaled to cover the view, centred. */
    private void cover(Canvas c, Bitmap b, int w, int h, Paint p) {
        float s = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
        matrix.setScale(s, s);
        matrix.postTranslate((w - b.getWidth() * s) / 2f, (h - b.getHeight() * s) / 2f);
        c.drawBitmap(b, matrix, p);
    }

    private static ColorMatrixColorFilter brightness(float f) {
        return new ColorMatrixColorFilter(new float[]{f, 0, 0, 0, 0, 0, f, 0, 0, 0, 0, 0, f, 0, 0, 0, 0, 0, 1, 0});
    }

    /** Saturation, then contrast, then brightness, as CSS filter: brightness() saturate() contrast() would. */
    private static ColorMatrixColorFilter fillFilter(Look l, float night) {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(l.fillSaturation);
        if (l.fillContrast != 1f) {
            float t = (1f - l.fillContrast) * 127.5f, k = l.fillContrast;
            m.postConcat(new ColorMatrix(new float[]{k, 0, 0, 0, t, 0, k, 0, 0, t, 0, 0, k, 0, t, 0, 0, 0, 1, 0}));
        }
        float b = l.fillBrightness * night;
        m.postConcat(new ColorMatrix(new float[]{b, 0, 0, 0, 0, 0, b, 0, 0, 0, 0, 0, b, 0, 0, 0, 0, 0, 1, 0}));
        return new ColorMatrixColorFilter(m);
    }
}
