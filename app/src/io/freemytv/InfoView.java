package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.graphics.RectF;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The screensaver as an information board over its background ({@link DreamBackground}): at the top right the date and
 * the time, at the bottom right the weather now over the next hours, at the top left what's playing ({@link NowPlaying}).
 * Redrawn once a minute. The day's headlines
 * are hidden for now ({@link #SHOW_NEWS}); with them on, the arrows pick one and OK opens it ({@link Opener}).
 */
final class InfoView extends View implements HomeData.Listener, NowPlaying.Listener {
    /**
     * The same margin from all four edges (in 1920 × 1080 units): the date's capitals start this far from the top, the
     * now-playing card too, and the lowest line of the weather ends this far from the bottom.
     */
    private static final float MARGIN = 96, LEFT = MARGIN, RIGHT = 1920 - MARGIN, BOTTOM = 1080 - MARGIN;
    // The bottom right block, from the bottom margin up: the next hours' temperatures sit on it, their symbols and labels
    // above; then today's high and low, and the temperature now. In 1920 × 1080 units above the bottom margin.
    private static final float ROW_SYMBOL = 54, ROW_LABEL = 96, ROW_RANGE = 146, ROW_NOW = 196;
    /** What a key does on the board: nothing here (the system's), handled, or ends the screensaver. */
    static final int PASS = 0, HANDLED = 1, EXIT = 2;
    private static final long IDLE_MS = 30_000;
    /** The headlines at the bottom: off until something else is decided for that space. */
    static final boolean SHOW_NEWS = false;

    interface Opener { void open(Rss.Headline headline); }

    private final HomeData data;
    private final News news;
    private final NowPlaying music;
    // The "now playing" card, top left: its paints, the cover's shader (made when the cover changes) and the
    // control the remote just pressed (lit for a moment).
    private TextPaint musicTitle, musicArtist;
    private Paint musicTime, glass, glassEdge, track, controlPaint;
    private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.Matrix coverMatrix = new android.graphics.Matrix();
    private final android.graphics.Path shape = new android.graphics.Path();
    private android.graphics.Bitmap coverShown;
    private android.graphics.BitmapShader coverShader;
    private int pressed = -1;   // 0 previous, 1 play/pause, 2 next
    // The blurred background behind the card ({@link DreamBoard}), at 1/backdropScale of the screen.
    private android.graphics.Bitmap backdrop;
    private android.graphics.BitmapShader backdropShader;
    private final Paint backdropPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.Matrix backdropMatrix = new android.graphics.Matrix();
    private long pressedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;
    private int w, h;
    private float k = 1;
    private Paint datePaint, clockPaint, tempPaint, smallPaint, smallDim, labelPaint, rule;
    private TextPaint headlinePaint, sourcePaint;
    private Paint pill, hintPaint;
    private final RectF row = new RectF();
    private Opener opener;
    private int selected = -1;     // the headline the arrows are on; -1: none (the board is just a clock)
    private final Runnable idle = () -> {
        selected = -1;
        invalidate();
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            if (SHOW_NEWS) news.refreshIfStale();
            invalidate();
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60_000 - now % 60_000 + 50);
        }
    };

    InfoView(Context c) {
        super(c);
        data = HomeData.get(c);
        news = News.get(c);
        music = new NowPlaying(c, this);
    }

    @Override public void onNowPlaying() {
        invalidate();
    }

    boolean musicShowing() { return music.showing(); }

    /** The blurred background for the card's frosted glass (the same bitmap each time, its pixels refreshed). */
    void setBackdrop(android.graphics.Bitmap b, int scale) {
        if (b != backdrop) {
            backdrop = b;
            backdropShader = new android.graphics.BitmapShader(b, android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP);
            backdropMatrix.setScale(scale, scale);
            backdropShader.setLocalMatrix(backdropMatrix);
            backdropPaint.setShader(backdropShader);
        }
        invalidate();
    }

    void start() {
        if (running) return;
        running = true;
        data.addListener(this);
        news.addListener(this);
        music.start();
        tick.run();
    }

    void setOpener(Opener o) { opener = o; }

    void stop() {
        if (!running) return;
        running = false;
        selected = -1;
        handler.removeCallbacks(idle);
        handler.removeCallbacks(tick);
        data.removeListener(this);
        news.removeListener(this);
        music.stop();
    }

    @Override public void onData() {
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        datePaint = Ui.text(Ui.medium, 30 * k, 0xB3FFFFFF);
        datePaint.setTextAlign(Paint.Align.RIGHT);
        datePaint.setShadowLayer(18 * k, 0, 2 * k, 0x66000000);
        clockPaint = Ui.numbers(Ui.semibold, 150 * k, 0xF2FFFFFF);
        clockPaint.setTextAlign(Paint.Align.RIGHT);
        clockPaint.setShadowLayer(18 * k, 0, 2 * k, 0x66000000);
        tempPaint = Ui.numbers(Ui.thin, 84 * k, 0xF2FFFFFF);
        tempPaint.setTextAlign(Paint.Align.RIGHT);
        smallPaint = Ui.numbers(Ui.medium, 28 * k, 0xE6FFFFFF);
        smallPaint.setTextAlign(Paint.Align.CENTER);
        smallDim = Ui.numbers(Ui.regular, 26 * k, 0x80FFFFFF);
        smallDim.setTextAlign(Paint.Align.CENTER);
        labelPaint = Ui.text(Ui.medium, 22 * k, 0x99FFFFFF);
        labelPaint.setLetterSpacing(0.12f);
        // Over a photo the weather needs the same soft shadow as the date and time.
        for (Paint p : new Paint[] {tempPaint, smallPaint, smallDim, labelPaint}) {
            p.setShadowLayer(14 * k, 0, 2 * k, 0x66000000);
        }
        headlinePaint = new TextPaint(Ui.text(Ui.medium, 34 * k, 0xE6FFFFFF));
        sourcePaint = new TextPaint(Ui.text(Ui.regular, 26 * k, 0x73FFFFFF));
        pill = new Paint(Paint.ANTI_ALIAS_FLAG);
        pill.setColor(0x29FFFFFF);
        hintPaint = Ui.text(Ui.medium, 22 * k, 0x66FFFFFF);
        hintPaint.setTextAlign(Paint.Align.RIGHT);
        musicTitle = new TextPaint(Ui.text(Ui.semibold, 36 * k, 0xF2FFFFFF));
        musicArtist = new TextPaint(Ui.text(Ui.regular, 28 * k, 0xB3FFFFFF));
        musicTime = Ui.numbers(Ui.medium, 20 * k, 0x99FFFFFF);
        glass = new Paint(Paint.ANTI_ALIAS_FLAG);
        glass.setColor(0x38000000);
        glassEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
        glassEdge.setStyle(Paint.Style.STROKE);
        glassEdge.setStrokeWidth(Math.max(1, 1.5f * k));
        glassEdge.setColor(0x26FFFFFF);
        track = new Paint(Paint.ANTI_ALIAS_FLAG);
        controlPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        rule = new Paint();
        rule.setColor(0x1FFFFFFF);
        rule.setStrokeWidth(Math.max(1, 1.5f * k));
    }

    @Override protected void onDraw(Canvas c) {
        if (w == 0) return;
        draw(c, System.currentTimeMillis());
    }

    private void draw(Canvas c, long now) {
        float left = LEFT * k, right = RIGHT * k;
        HomeData.Weather wx = data.weather;
        boolean weather = Prefs.weather() && !Double.isNaN(wx.temp);
        drawClock(c, right, now);
        if (weather) {
            drawWeatherNow(c, wx, right);
            drawHours(c, wx, right);
        }
        if (SHOW_NEWS) drawNews(c, left, right);
        if (music.showing()) {
            drawMusic(c, left);
            postInvalidateDelayed(1000);    // the progress bar and the time move
        }
    }

    /**
     * Top left, like the iPhone's lock screen: a dark glass card with the cover, the song and the artist, the
     * progress with the times, and the controls (◀ previous, OK play/pause, ▶ next on the remote).
     */
    private void drawMusic(Canvas c, float left) {
        float top = MARGIN * k, h = 270 * k, w = 860 * k, r = 34 * k, pad = 24 * k;
        row.set(left, top, left + w, top + h);
        // Frosted glass: the background behind, blurred, then a light dark tint so the text reads.
        if (backdropShader != null) c.drawRoundRect(row, r, r, backdropPaint);
        c.drawRoundRect(row, r, r, glass);
        c.drawRoundRect(row, r, r, glassEdge);
        float art = h - 2 * pad, ax = left + pad, ay = top + pad;
        android.graphics.Bitmap b = music.cover;
        row.set(ax, ay, ax + art, ay + art);
        if (b != null) {
            if (b != coverShown) {
                coverShown = b;
                coverShader = new android.graphics.BitmapShader(b, android.graphics.Shader.TileMode.CLAMP,
                        android.graphics.Shader.TileMode.CLAMP);
            }
            float scale = Math.max(art / b.getWidth(), art / b.getHeight());
            coverMatrix.setScale(scale, scale);
            coverMatrix.postTranslate(ax + (art - b.getWidth() * scale) / 2, ay + (art - b.getHeight() * scale) / 2);
            coverShader.setLocalMatrix(coverMatrix);
            coverPaint.setShader(coverShader);
            c.drawRoundRect(row, 18 * k, 18 * k, coverPaint);
        } else {
            track.setColor(0x33FFFFFF);
            c.drawRoundRect(row, 18 * k, 18 * k, track);
            Symbols.draw(getContext(), c, "speaker", row.centerX(), row.centerY(), 60 * k, 0x99FFFFFF);
        }

        float tx = ax + art + 28 * k, tw = left + w - pad - tx;
        boolean moving = drawScrolling(c, music.title, musicTitle, tx, tw, top + 74 * k, top + 30 * k, top + 86 * k);
        moving |= drawScrolling(c, music.artist, musicArtist, tx, tw, top + 114 * k, top + 86 * k, top + 124 * k);
        if (moving) postInvalidateDelayed(40);

        // Progress: a thin bar with the time played and the time left.
        long d = music.duration, p = music.positionNow();
        float by = top + 150 * k, bh = 6 * k;
        if (d > 0) {
            track.setColor(0x40FFFFFF);
            row.set(tx, by, tx + tw, by + bh);
            c.drawRoundRect(row, bh / 2, bh / 2, track);
            track.setColor(0xE6FFFFFF);
            row.set(tx, by, tx + tw * Math.min(1, p / (float) d), by + bh);
            c.drawRoundRect(row, bh / 2, bh / 2, track);
            c.drawText(NowPlaying.Format.time(p), tx, by + 34 * k, musicTime);
            String rest = "-" + NowPlaying.Format.time(d - p);
            c.drawText(rest, tx + tw - musicTime.measureText(rest), by + 34 * k, musicTime);
        }

        // Controls, centered under the text.
        float cy = top + h - 52 * k, cx = tx + tw / 2, gap = 120 * k;
        boolean lit = SystemClock.uptimeMillis() - pressedAt < 350;
        if (lit) postInvalidateDelayed(360);
        for (int i = 0; i < 3; i++) {
            float x = cx + (i - 1) * gap;
            if (lit && pressed == i) {
                controlPaint.setColor(0x33FFFFFF);
                c.drawCircle(x, cy, 40 * k, controlPaint);
            }
            controlPaint.setColor(0xF2FFFFFF);
            if (i == 1) drawPlayPause(c, x, cy, 22 * k, music.playing);
            else drawSkip(c, x, cy, 16 * k, i == 2);
        }
    }

    /** One line of the card: as it is if it fits, else scrolling slowly to the left (true while it scrolls). */
    private boolean drawScrolling(Canvas c, String text, Paint p, float x, float width, float base, float clipTop, float clipBottom) {
        float textW = p.measureText(text), gap = 80 * k;
        float off = Turns.marquee(SystemClock.uptimeMillis(), textW, width, gap, 40 * k);
        if (textW <= width) {
            c.drawText(text, x, base, p);
            return false;
        }
        c.save();
        c.clipRect(x, clipTop, x + width, clipBottom);
        c.drawText(text, x - off, base, p);
        c.drawText(text, x - off + textW + gap, base, p);
        c.restore();
        return true;
    }

    private void drawPlayPause(Canvas c, float x, float y, float s, boolean playing) {
        if (playing) {
            row.set(x - s * 0.75f, y - s, x - s * 0.2f, y + s);
            c.drawRoundRect(row, s * 0.12f, s * 0.12f, controlPaint);
            row.set(x + s * 0.2f, y - s, x + s * 0.75f, y + s);
            c.drawRoundRect(row, s * 0.12f, s * 0.12f, controlPaint);
        } else {
            shape.reset();
            shape.moveTo(x - s * 0.6f, y - s);
            shape.lineTo(x + s, y);
            shape.lineTo(x - s * 0.6f, y + s);
            shape.close();
            c.drawPath(shape, controlPaint);
        }
    }

    /** ◀◀ or ▶▶: two triangles. */
    private void drawSkip(Canvas c, float x, float y, float s, boolean forward) {
        float dir = forward ? 1 : -1;
        shape.reset();
        for (int i = 0; i < 2; i++) {
            float bx = x + dir * (i == 0 ? -s : 0);
            shape.moveTo(bx, y - s);
            shape.lineTo(bx + dir * s, y);
            shape.lineTo(bx, y + s);
            shape.close();
        }
        c.drawPath(shape, controlPaint);
    }

    /** The weather block's turns (apart from the view, so the plain-Java tests can load it without Android). */
    static final class Turns {
        /**
         * How far a line that doesn't fit has scrolled at `ms`: still for 2.5 s, then moving left at `speed` px a second
         * until its copy (`gap` behind it) is where it started, and again. 0 when it fits.
         */
        static float marquee(long ms, float textW, float boxW, float gap, float speed) {
            if (textW <= boxW || speed <= 0) return 0;
            long pause = 2500, travel = (long) ((textW + gap) / speed * 1000), t = ms % (pause + travel);
            return t < pause ? 0 : (t - pause) * speed / 1000f;
        }

        /** With a song showing: ◀ and ⏮ previous (0), OK and ⏯ play/pause (1), ▶ and ⏭ next (2); -1 for the rest. */
        static int musicControl(int code) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_MEDIA_PREVIOUS: return 0;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    return 1;
                case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_MEDIA_NEXT: return 2;
                default: return -1;
            }
        }

    }

    /** Top right, level with the now-playing card: the day of the week and the date, and under it the time, big. */
    private void drawClock(Canvas c, float right, long now) {
        String date = Ui.date(now);
        if (!date.isEmpty()) date = Character.toUpperCase(date.charAt(0)) + date.substring(1);
        float dateBase = MARGIN * k + Ui.cap(datePaint);
        c.drawText(date, right, dateBase, datePaint);
        c.drawText(Ui.clock(now), right, dateBase + 30 * k + Ui.cap(clockPaint), clockPaint);
    }

    /** Bottom right, over the next hours: the weather symbol and the temperature now, and under them today's high and low. */
    private void drawWeatherNow(Canvas c, HomeData.Weather wx, float right) {
        float base = (BOTTOM - ROW_NOW) * k, cap = Ui.cap(tempPaint);
        String temp = HomeData.degrees(wx.temp);
        c.drawText(temp, right, base, tempPaint);
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, cap);
        Symbols.draw(getContext(), c, symbol, right - tempPaint.measureText(temp) - 24 * k - sw / 2, base - cap / 2, cap, 0xE6FFFFFF);
        String range = Ui.tr("Máx. ", "H: ", "Máx. ") + HomeData.degrees(wx.max) + "   "
                + Ui.tr("Mín. ", "L: ", "Mín. ") + HomeData.degrees(wx.min);
        c.drawText(range, right, (BOTTOM - ROW_RANGE) * k, datePaint);
    }

    /** At the bottom right, under the time: the next hours (the temperatures end on the bottom margin). */
    private void drawHours(Canvas c, HomeData.Weather wx, float right) {
        int n = Math.min(6, wx.hours.size());
        SimpleDateFormat hour = Ui.en ? new SimpleDateFormat("h a", Locale.US) : new SimpleDateFormat("H'h'", Ui.locale());
        if (n == 0) return;
        // The columns are centered, so the last one's widest text ends exactly at the right edge, like the lines above.
        float last = Math.max(smallDim.measureText(hour.format(new Date(wx.hours.get(n - 1)))),
                smallPaint.measureText(HomeData.degrees(wx.hourTemp.get(n - 1))));
        float col = 112 * k, x0 = right - last / 2 - col * (n - 1);
        for (int i = 0; i < n; i++) {
            float cx = x0 + i * col;
            c.drawText(hour.format(new Date(wx.hours.get(i))), cx, (BOTTOM - ROW_LABEL) * k, smallDim);
            Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), cx,
                    (BOTTOM - ROW_SYMBOL) * k, 32 * k, 0xCCFFFFFF);
            c.drawText(HomeData.degrees(wx.hourTemp.get(i)), cx, BOTTOM * k, smallPaint);
        }
    }

    /** Bottom: the day's headlines, one line each, with the source. */
    private void drawNews(Canvas c, float left, float right) {
        List<Rss.Headline> list = news.headlines;
        if (list.isEmpty()) return;
        c.drawLine(left, 720 * k, right, 720 * k, rule);
        c.drawText(Ui.tr("NOTÍCIAS DO DIA", "TODAY'S NEWS", "NOTICIAS DEL DÍA"), left, 764 * k, labelPaint);
        c.drawText(selected < 0 ? Ui.tr("▲▼ para escolher", "▲▼ to pick", "▲▼ para elegir")
                : Ui.tr("OK para ler  ·  Voltar para sair", "OK to read  ·  Back to leave", "OK para leer  ·  Atrás para salir"),
                right, 764 * k, hintPaint);
        float base = 816 * k, line = 50 * k, width = right - left;
        for (int i = 0; i < list.size(); i++, base += line) {
            Rss.Headline item = list.get(i);
            String source = item.source.isEmpty() ? "" : "  ·  " + item.source;
            float sw = sourcePaint.measureText(source);
            if (i == selected) {
                row.set(left - 20 * k, base - 40 * k, right + 20 * k, base + 14 * k);
                c.drawRoundRect(row, 14 * k, 14 * k, pill);
            }
            CharSequence title = TextUtils.ellipsize(item.title, headlinePaint, width - sw, TextUtils.TruncateAt.END);
            c.drawText(title, 0, title.length(), left, base, headlinePaint);
            c.drawText(source, left + headlinePaint.measureText(title, 0, title.length()), base, sourcePaint);
        }
    }

    /** The remote on the board: ▲▼ pick a headline, OK opens it, Back leaves; volume and the like go on to the system. */
    int key(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER: case KeyEvent.KEYCODE_SLEEP:
                return PASS;
            default:
                break;
        }
        int control = Turns.musicControl(code);
        if (control >= 0 && music.showing()) {
            if (down && e.getRepeatCount() == 0) {
                if (control == 0) music.previous(); else if (control == 2) music.next(); else music.playPause();
                pressed = control;
                pressedAt = SystemClock.uptimeMillis();
                invalidate();
            }
            return HANDLED;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                break;
            default:
                return EXIT;    // Back and every other key: like any screensaver, it ends
        }
        List<Rss.Headline> list = SHOW_NEWS ? news.headlines : java.util.Collections.<Rss.Headline>emptyList();
        if (list.isEmpty()) return EXIT;    // nothing to pick: like before, any key ends it
        if (!down) return HANDLED;
        int n = list.size();
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) selected = selected < 0 ? 0 : Math.min(n - 1, selected + 1);
        else if (code == KeyEvent.KEYCODE_DPAD_UP) selected = selected < 0 ? n - 1 : Math.max(0, selected - 1);
        else if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (selected < 0) selected = 0;
            else if (opener != null && selected < n) opener.open(list.get(selected));
        }
        handler.removeCallbacks(idle);
        handler.postDelayed(idle, IDLE_MS);
        invalidate();
        return HANDLED;
    }
}
