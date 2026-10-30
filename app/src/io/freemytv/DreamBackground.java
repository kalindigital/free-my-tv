package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

/**
 * What's behind the screensaver's board ({@link Prefs#dreamBackground()}): slow fluid colors, heavily blurred and
 * opaque, new at random each time and slowly shifting around the color wheel; a landscape or space photo ({@link DreamPhotos}) that changes every 5 minutes with a crossfade. Photos get
 * a dark shade only where the board has text (top left and bottom right); or, for the aerial video playing under it,
 * just those shades; or the sky as it is outside right now ({@link WeatherScene}). Until the first photo or video frame arrives, and if none does, the fluid colors stay.
 */
final class DreamBackground extends View {
    static final String FLUID = "fluid", VIDEO = "video", WEATHER = "weather";
    private static final long PHOTO_MS = 5 * 60_000L, FADE_MS = 2_000L, FRAME_MS = 40;
    // Small on purpose: drawn at 64 × 36 and stretched to the screen, the blobs come out as a soft blur for free.
    private static final int SMALL_W = 64, SMALL_H = 36;
    /** How often the blobs' colors take a step around the color wheel (they move slowly, so steps don't show). */
    private static final long COLOR_STEP_MS = 500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DreamPhotos photos = new DreamPhotos();
    private final Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG), blob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fade = new Paint(Paint.FILTER_BITMAP_FLAG), shade = new Paint(), dim = new Paint();
    private final Matrix matrix = new Matrix();
    private final Rect src = new Rect(), small = new Rect(0, 0, SMALL_W, SMALL_H);
    private final RectF dst = new RectF();
    private final RadialGradient[] blobs = new RadialGradient[4];
    // Each start of the screensaver picks new colors: a random hue per blob, each drifting at its own speed.
    private final float[] hues = new float[4], speeds = new float[4], hsv = new float[3];
    private final java.util.Random random = new java.util.Random();
    private long colorsAt = -1;
    private int base;
    private Bitmap fluid, photo, previous;
    private Canvas fluidCanvas;
    private WeatherScene scene;     // the "live weather" background, made the first time it's needed
    private Shader topShade, bottomShade;
    private long photoAt, started, videoAt;   // videoAt: when the aerial video ({@link DreamBoard}) showed its first frame
    private boolean running;
    private String mode = FLUID;

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!running) return;
            invalidate();
            // The fluid colors move; a photo only needs frames while it fades in.
            long now = SystemClock.uptimeMillis();
            boolean moving = WEATHER.equals(mode) || (videoAt > 0 ? now - videoAt < FADE_MS : photo == null || now - photoAt < FADE_MS);
            handler.postDelayed(this, moving ? FRAME_MS : 1_000);
        }
    };

    private final Runnable nextPhoto = new Runnable() {
        @Override public void run() {
            if (!running || !DreamPhotos.LANDSCAPE.equals(mode) && !DreamPhotos.SPACE.equals(mode)) return;
            photos.load(mode, Math.max(1280, getWidth()), b -> {
                if (!running) return;
                if (b != null) {
                    if (previous != null) previous.recycle();
                    previous = photo;
                    photo = b;
                    photoAt = SystemClock.uptimeMillis();
                }
                handler.postDelayed(this, b != null ? PHOTO_MS : 60_000);
            });
        }
    };

    DreamBackground(Context c) {
        super(c);
        dim.setColor(0x47000000);
    }

    void start() {
        if (running) return;
        running = true;
        mode = Prefs.dreamBackground();
        started = SystemClock.uptimeMillis();
        float first = random.nextFloat() * 360;
        for (int i = 0; i < hues.length; i++) {
            // Spread around the wheel from a random start, so the mix is never the same twice but never muddy.
            hues[i] = (first + i * (60 + random.nextFloat() * 50)) % 360;
            speeds[i] = (1.5f + random.nextFloat() * 2.5f) * (random.nextBoolean() ? 1 : -1);    // degrees a second
        }
        colorsAt = -1;
        videoAt = 0;
        handler.post(frame);
        handler.post(nextPhoto);
    }

    /** The video under this view is playing: the fluid colors fade away and only the shades behind the text stay. */
    void videoShowing() {
        videoAt = SystemClock.uptimeMillis();
        handler.removeCallbacks(frame);
        if (running) handler.post(frame);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(frame);
        handler.removeCallbacks(nextPhoto);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float k = h / 1080f;
        // Round, from the two corners with text, so the rest of the picture (the sun, a skyline) keeps its light.
        topShade = new RadialGradient(0, 0, 1150 * k, new int[] {0x99000000, 0x66000000, 0x00000000},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP);
        bottomShade = new RadialGradient(w, h, 1250 * k, new int[] {0xB3000000, 0x73000000, 0x00000000},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        long now = SystemClock.uptimeMillis();
        if (WEATHER.equals(mode)) {
            if (scene == null) scene = new WeatherScene();
            scene.draw(c, w, h, (now - started) / 1000f, HomeData.get(getContext()).weather, System.currentTimeMillis());
            drawShades(c, w, h);
            return;
        }
        if (videoAt > 0) {
            float t = Math.min(1, (now - videoAt) / (float) FADE_MS);
            if (t < 1) {
                c.saveLayerAlpha(0, 0, w, h, Math.round(255 * (1 - t)));
                drawFluid(c, (now - started) / 1000f);
                c.restore();
            }
            drawShades(c, w, h);
            return;
        }
        if (photo == null) {
            drawFluid(c, (now - started) / 1000f);
            return;
        }
        float t = Math.min(1, (now - photoAt) / (float) FADE_MS);
        if (t < 1) {
            if (previous != null) drawPhoto(c, previous, 255);
            else drawFluid(c, (now - started) / 1000f);
        }
        drawPhoto(c, photo, Math.round(255 * t));
        drawShades(c, w, h);
    }

    /** Dark only where the board has text (top left, bottom right), so it reads over any photo or video. */
    private void drawShades(Canvas c, int w, int h) {
        shade.setShader(topShade);
        c.drawRect(0, 0, w, h, shade);
        shade.setShader(bottomShade);
        c.drawRect(0, 0, w, h, shade);
    }

    /** Four color blobs drifting on slow, out-of-step circles, over a dark base: opaque, never black gaps. */
    private void drawFluid(Canvas c, float s) {
        if (fluid == null) {
            fluid = Bitmap.createBitmap(SMALL_W, SMALL_H, Bitmap.Config.ARGB_8888);
            fluidCanvas = new Canvas(fluid);
        }
        long step = (long) (s * 1000) / COLOR_STEP_MS;
        if (step != colorsAt) {
            colorsAt = step;
            for (int i = 0; i < blobs.length; i++) {
                int color = Fluid.color(hues[i], speeds[i], s, hsv);
                blobs[i] = new RadialGradient(0, 0, 1, color, color & 0x00FFFFFF, Shader.TileMode.CLAMP);
            }
            hsv[0] = Fluid.hue(hues[0], speeds[0], s);
            hsv[1] = 0.6f;
            hsv[2] = 0.16f;
            base = android.graphics.Color.HSVToColor(hsv);   // a deep shade of the first blob, so nothing is ever black
        }
        fluidCanvas.drawColor(base);
        for (int i = 0; i < blobs.length; i++) {
            float speed = 0.035f + i * 0.012f, phase = i * 1.7f;
            float x = SMALL_W * (0.5f + 0.42f * (float) Math.cos(s * speed + phase));
            float y = SMALL_H * (0.5f + 0.42f * (float) Math.sin(s * speed * 1.3f + phase * 0.7f));
            float r = SMALL_H * (0.75f + 0.2f * (float) Math.sin(s * 0.05f + i));
            matrix.setScale(r, r);
            matrix.postTranslate(x, y);
            blobs[i].setLocalMatrix(matrix);
            blob.setShader(blobs[i]);
            fluidCanvas.drawCircle(x, y, r, blob);
        }
        dst.set(0, 0, getWidth(), getHeight());
        c.drawBitmap(fluid, small, dst, filter);
        c.drawRect(dst, dim);
    }

    /** The fluid colors' drift (apart from the view, so the plain-Java tests can load it without Android). */
    static final class Fluid {
        /** A blob's hue `seconds` in: where it started plus its speed, around the wheel. */
        static float hue(float start, float speed, float seconds) {
            float h = (start + speed * seconds) % 360;
            return h < 0 ? h + 360 : h;
        }

        /** The blob's color then: rich but not neon. */
        static int color(float start, float speed, float seconds, float[] hsv) {
            hsv[0] = hue(start, speed, seconds);
            hsv[1] = 0.62f;
            hsv[2] = 0.88f;
            return android.graphics.Color.HSVToColor(hsv);
        }
    }

    /** The photo filling the screen (center crop). */
    private void drawPhoto(Canvas c, Bitmap b, int alpha) {
        int w = getWidth(), h = getHeight();
        float scale = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
        int sw = Math.round(w / scale), sh = Math.round(h / scale);
        int sx = (b.getWidth() - sw) / 2, sy = (b.getHeight() - sh) / 2;
        src.set(sx, sy, sx + sw, sy + sh);
        dst.set(0, 0, w, h);
        fade.setAlpha(alpha);
        c.drawBitmap(b, src, dst, fade);
    }
}
