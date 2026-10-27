package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The screensaver video, picked from a grid: "Random" first, then each Apple aerial video with a picture from it
 * (app/assets/aerial/<id>.jpg, one frame of each, made once with ffmpeg) and its place. The arrows move, OK picks it
 * (and makes the aerial video the screensaver's background), Back closes.
 */
public class VideoPickerActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        setContentView(new Grid(this));
    }

    static final class Grid extends View {
        private static final int COLS = 4;
        private static final float LEFT = 120, TOP = 250, GAP = 32, NAME_H = 64;
        private final Activity activity;
        private final List<AerialVideos.Video> videos = new ArrayList<>();
        private final List<Bitmap> thumbs = new ArrayList<>();
        private final List<BitmapShader> shaders = new ArrayList<>();     // one per picture, made once
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Matrix matrix = new Matrix();
        private final RectF cell = new RectF();
        private Paint title, hint;
        private TextPaint name;
        private float k = 1, scroll, scrollFrom, scrollTo;
        private long scrollAt;
        private int focus;

        Grid(Activity a) {
            super(a);
            activity = a;
            setFocusable(true);
            setFocusableInTouchMode(true);
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(0xFFFFFFFF);
            load(a);
        }

        /** The saved list right away (fetched if there's none yet), then the pictures, off the main thread. */
        private void load(final Context c) {
            videos.addAll(AerialVideos.saved(c));
            String chosen = Prefs.dreamVideo();
            for (int i = 0; i < videos.size(); i++) if (videos.get(i).id.equals(chosen)) focus = i + 1;
            new Thread(() -> {
                List<AerialVideos.Video> list = videos.isEmpty() ? AerialVideos.refresh(c) : new ArrayList<>(videos);
                List<Bitmap> pictures = new ArrayList<>();
                for (AerialVideos.Video v : list) pictures.add(picture(c, v.id));
                post(() -> {
                    videos.clear();
                    videos.addAll(list);
                    thumbs.clear();
                    thumbs.addAll(pictures);
                    shaders.clear();
                    for (Bitmap b : pictures) {
                        shaders.add(b == null ? null : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                    }
                    invalidate();
                });
            }, "aerial-thumbs").start();
        }

        private static Bitmap picture(Context c, String id) {
            try (InputStream in = c.getAssets().open("aerial/" + id + ".jpg")) {
                return BitmapFactory.decodeStream(in);
            } catch (Exception e) {
                return null;
            }
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            title = Ui.text(Ui.semibold, 48 * k, 0xF2FFFFFF);
            hint = Ui.text(Ui.medium, 24 * k, 0x80FFFFFF);
            name = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xCCFFFFFF));
            ring.setStrokeWidth(5 * k);
            requestFocus();
            scroll = scrollTo = targetScroll();
        }

        private float tileW() { return ((1920 - 2 * LEFT) - (COLS - 1) * GAP) / COLS * k; }
        private float tileH() { return tileW() * 9 / 16; }
        private float rowH() { return tileH() + NAME_H * k + GAP * k; }

        /** Keeps the focused row in view: the grid scrolls so it's never below the second row on screen. */
        private float targetScroll() {
            int row = focus / COLS;
            return Math.max(0, (row - 1) * rowH());
        }

        @Override protected void onDraw(Canvas c) {
            if (title == null) return;
            c.drawColor(0xFF0B0B0D);
            float t = Math.min(1, (SystemClock.uptimeMillis() - scrollAt) / 260f);
            float e = 1 - (1 - t) * (1 - t);
            scroll = scrollFrom + (scrollTo - scrollFrom) * e;
            if (t < 1) postInvalidateOnAnimation();

            float tw = tileW(), th = tileH(), left = LEFT * k;
            String chosen = Prefs.dreamVideo();
            c.save();
            c.clipRect(0, TOP * k - 30 * k, getWidth(), getHeight());
            for (int i = 0; i <= videos.size(); i++) {
                float x = left + (i % COLS) * (tw + GAP * k), y = TOP * k + (i / COLS) * rowH() - scroll;
                if (y > getHeight() || y + rowH() < 0) continue;
                boolean focused = i == focus;
                float s = focused ? 1.06f : 1;
                cell.set(x + tw / 2 - tw * s / 2, y + th / 2 - th * s / 2, x + tw / 2 + tw * s / 2, y + th / 2 + th * s / 2);
                float r = 18 * k;
                Bitmap b = i == 0 ? null : i - 1 < thumbs.size() ? thumbs.get(i - 1) : null;
                BitmapShader shader = b == null ? null : shaders.get(i - 1);
                if (shader != null) {
                    float scale = Math.max(cell.width() / b.getWidth(), cell.height() / b.getHeight());
                    matrix.setScale(scale, scale);
                    matrix.postTranslate(cell.centerX() - b.getWidth() * scale / 2, cell.centerY() - b.getHeight() * scale / 2);
                    shader.setLocalMatrix(matrix);
                    image.setShader(shader);
                    c.drawRoundRect(cell, r, r, image);
                } else {
                    fill.setColor(i == 0 ? 0xFF2C2C34 : 0xFF1C1C21);
                    c.drawRoundRect(cell, r, r, fill);
                    if (i == 0) Symbols.draw(getContext(), c, "sparkles", cell.centerX(), cell.centerY(), 60 * k, 0xE6FFFFFF);
                }
                if (focused) c.drawRoundRect(cell, r, r, ring);
                String label = i == 0 ? Ui.tr("Aleatório (um novo a cada vez)", "Random (a new one each time)")
                        : videos.get(i - 1).name;
                boolean picked = i == 0 ? AerialVideos.RANDOM.equals(chosen) : videos.get(i - 1).id.equals(chosen);
                if (picked) label = "✓  " + label;
                name.setColor(focused ? 0xFFFFFFFF : 0xB3FFFFFF);
                CharSequence shown = TextUtils.ellipsize(label, name, tw, TextUtils.TruncateAt.END);
                c.drawText(shown, 0, shown.length(), x, y + th + 46 * k, name);
            }
            c.restore();
            // On top, over the scrolled grid.
            fill.setColor(0xFF0B0B0D);
            c.drawRect(0, 0, getWidth(), TOP * k - 30 * k, fill);
            c.drawText(Ui.tr("Vídeo da tela de descanso", "Screensaver video"), left, 150 * k, title);
            String keys = Ui.tr("OK escolhe  ·  Voltar fecha", "OK picks  ·  Back closes");
            c.drawText(keys, getWidth() - left - hint.measureText(keys), 150 * k, hint);
        }

        @Override public boolean onKeyDown(int code, KeyEvent e) {
            int n = videos.size() + 1, next = focus;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (focus % COLS < COLS - 1 && focus + 1 < n) next = focus + 1; break;
                case KeyEvent.KEYCODE_DPAD_LEFT: if (focus % COLS > 0) next = focus - 1; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: next = Math.min(n - 1, focus + COLS); break;
                case KeyEvent.KEYCODE_DPAD_UP: if (focus >= COLS) next = focus - COLS; break;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (e.getRepeatCount() == 0) pick();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    activity.finish();
                    return true;
                default:
                    return super.onKeyDown(code, e);
            }
            if (next != focus) {
                focus = next;
                scrollFrom = scroll;
                scrollTo = targetScroll();
                scrollAt = SystemClock.uptimeMillis();
                invalidate();
            }
            return true;
        }

        private void pick() {
            Prefs.setDreamVideo(focus == 0 ? AerialVideos.RANDOM : videos.get(focus - 1).id);
            Prefs.setDreamBackground(DreamBackground.VIDEO);
            activity.finish();
        }
    }
}
