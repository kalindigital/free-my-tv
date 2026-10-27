package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
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
 * The screensaver as an information board over its background ({@link DreamBackground}): the date at the top left with
 * the time under it, and at the bottom right the weather, which takes turns each minute between now with the next hours
 * and the next days. Redrawn once a minute, and the whole board moves a few pixels each minute so nothing stays burned
 * into the panel. The day's headlines are hidden for now ({@link #SHOW_NEWS}); with them on, the arrows pick one and OK
 * opens it ({@link Opener}).
 */
final class InfoView extends View implements HomeData.Listener {
    private static final float LEFT = 120, RIGHT = 1800;
    /** What a key does on the board: nothing here (the system's), handled, or ends the screensaver. */
    static final int PASS = 0, HANDLED = 1, EXIT = 2;
    private static final long IDLE_MS = 30_000;
    /** The headlines at the bottom: off until something else is decided for that space. */
    static final boolean SHOW_NEWS = false;
    private static final long ROW_FADE_MS = 900;
    private boolean rowDays, rowDrawn;
    private long switchAt;

    interface Opener { void open(Rss.Headline headline); }

    private final HomeData data;
    private final News news;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;
    private int w, h;
    private float k = 1;
    private Paint datePaint, clockPaint, tempPaint, rangePaint, smallPaint, smallDim, labelPaint, rule;
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
            drift(now / 60_000);
            handler.postDelayed(this, 60_000 - now % 60_000 + 50);
        }
    };

    InfoView(Context c) {
        super(c);
        data = HomeData.get(c);
        news = News.get(c);
    }

    void start() {
        if (running) return;
        running = true;
        data.addListener(this);
        news.addListener(this);
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
    }

    @Override public void onData() {
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        datePaint = Ui.text(Ui.thin, 46 * k, 0xE6FFFFFF);
        datePaint.setShadowLayer(18 * k, 0, 2 * k, 0x66000000);
        clockPaint = Ui.numbers(Ui.semibold, 150 * k, 0xF2FFFFFF);
        clockPaint.setShadowLayer(18 * k, 0, 2 * k, 0x66000000);
        tempPaint = Ui.numbers(Ui.thin, 110 * k, 0xF2FFFFFF);
        tempPaint.setTextAlign(Paint.Align.RIGHT);
        rangePaint = Ui.numbers(Ui.medium, 30 * k, 0xB3FFFFFF);
        rangePaint.setTextAlign(Paint.Align.RIGHT);
        smallPaint = Ui.numbers(Ui.medium, 28 * k, 0xE6FFFFFF);
        smallPaint.setTextAlign(Paint.Align.CENTER);
        smallDim = Ui.numbers(Ui.regular, 26 * k, 0x80FFFFFF);
        smallDim.setTextAlign(Paint.Align.CENTER);
        labelPaint = Ui.text(Ui.medium, 22 * k, 0x99FFFFFF);
        labelPaint.setLetterSpacing(0.12f);
        // Over a photo the weather needs the same soft shadow as the date and time.
        for (Paint p : new Paint[] {tempPaint, rangePaint, smallPaint, smallDim, labelPaint}) {
            p.setShadowLayer(14 * k, 0, 2 * k, 0x66000000);
        }
        headlinePaint = new TextPaint(Ui.text(Ui.medium, 34 * k, 0xE6FFFFFF));
        sourcePaint = new TextPaint(Ui.text(Ui.regular, 26 * k, 0x73FFFFFF));
        pill = new Paint(Paint.ANTI_ALIAS_FLAG);
        pill.setColor(0x29FFFFFF);
        hintPaint = Ui.text(Ui.medium, 22 * k, 0x66FFFFFF);
        hintPaint.setTextAlign(Paint.Align.RIGHT);
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
        String date = Ui.date(now);
        if (!date.isEmpty()) date = Character.toUpperCase(date.charAt(0)) + date.substring(1);
        c.drawText(date, left - Ui.leftBearing(datePaint, date), 140 * k, datePaint);
        c.drawText(Ui.clock(now), left - Ui.leftBearing(clockPaint, Ui.clock(now)), 300 * k, clockPaint);

        HomeData.Weather wx = data.weather;
        if (Prefs.weather() && !Double.isNaN(wx.temp)) {
            drawToday(c, wx, right);
            boolean days = Turns.showDays(now / 60_000) && !wx.days.isEmpty();
            if (days != rowDays) {
                // The first frame shows the row as it is; after that, each change fades.
                switchAt = rowDrawn ? android.os.SystemClock.uptimeMillis() : 0;
                rowDays = days;
            }
            rowDrawn = true;
            float t = Math.min(1, (android.os.SystemClock.uptimeMillis() - switchAt) / (float) ROW_FADE_MS);
            if (t < 1) {
                drawRow(c, wx, right, !days, 1 - t);
                postInvalidateOnAnimation();
            }
            drawRow(c, wx, right, days, t);
        }
        if (SHOW_NEWS) drawNews(c, left, right);
    }

    /**
     * Against burn-in the board keeps moving over a 5 × 5 grid of positions 6 px apart, one a minute. It glides to the
     * next one over the whole minute (on the render thread, without redrawing), so it never visibly jumps.
     */
    private void drift(long minute) {
        animate().translationX(Turns.shift(minute) * 6 * k).translationY(Turns.shift(minute / 5) * 6 * k)
                .setDuration(58_000).setInterpolator(new android.view.animation.LinearInterpolator()).start();
    }

    /** The weather block's turns (apart from the view, so the plain-Java tests can load it without Android). */
    static final class Turns {
        /** Now and the next hours one minute, the next days the next. */
        static boolean showDays(long minute) { return minute % 2 == 1; }

        /** The board's step on one axis: -2 to 2, back and forth (0 1 2 1 0 -1 -2 -1 …), so each move is one step. */
        static int shift(long step) {
            int[] path = {0, 1, 2, 1, 0, -1, -2, -1};
            return path[(int) (step % path.length)];
        }
    }

    /** Bottom right: symbol and temperature now, the city, high and low (always there; the row under it takes turns). */
    private void drawToday(Canvas c, HomeData.Weather wx, float right) {
        String temp = HomeData.degrees(wx.temp);
        float base = 742 * k;
        c.drawText(temp, right, base, tempPaint);
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float size = 76 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, right - tempPaint.measureText(temp) - 24 * k - sw / 2,
                base - Ui.cap(tempPaint) / 2, size, 0xE6FFFFFF);
        String range = Ui.tr("Máx. ", "H: ", "Máx. ") + HomeData.degrees(wx.max) + "   "
                + Ui.tr("Mín. ", "L: ", "Mín. ") + HomeData.degrees(wx.min);
        String place = Prefs.city().isEmpty() ? range : Prefs.city() + "  ·  " + range;
        c.drawText(place, right, 792 * k, rangePaint);
    }

    /** The row under the temperature (the next days or the next hours) at `alpha`, for the fade between them. */
    private void drawRow(Canvas c, HomeData.Weather wx, float right, boolean days, float alpha) {
        if (alpha <= 0.01f) return;
        boolean faded = alpha < 0.99f;
        if (faded) c.saveLayerAlpha(right - 1000 * k, 820 * k, right + 60 * k, 1050 * k, Math.round(255 * alpha));
        if (days) drawWeek(c, wx, right); else drawHours(c, wx, right);
        if (faded) c.restore();
    }

    /** Under the temperature: the next hours. */
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
            c.drawText(hour.format(new Date(wx.hours.get(i))), cx, 880 * k, smallDim);
            Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), cx, 922 * k, 32 * k, 0xCCFFFFFF);
            c.drawText(HomeData.degrees(wx.hourTemp.get(i)), cx, 976 * k, smallPaint);
        }
    }

    /**
     * Under the temperature, in place of the hours: the next days. Each day is one narrow column (the day, the symbol,
     * the high and, under it and dimmer, the low), so the days read apart instead of as one run of numbers.
     */
    private void drawWeek(Canvas c, HomeData.Weather wx, float right) {
        int n = Math.min(7, wx.days.size());
        SimpleDateFormat day = new SimpleDateFormat("EEE", Ui.locale());
        float last = Math.max(smallDim.measureText(weekday(day, wx.days.get(n - 1))),
                smallPaint.measureText(HomeData.degrees(wx.dayMax.get(n - 1))));
        float col = 112 * k, x0 = right - last / 2 - col * (n - 1);
        for (int i = 0; i < n; i++) {
            float cx = x0 + col * i;
            String name = i == 0 ? Ui.tr("Hoje", "Today", "Hoy") : weekday(day, wx.days.get(i));
            c.drawText(name, cx, 880 * k, smallDim);
            Symbols.draw(getContext(), c, Symbols.weather(wx.dayCode.get(i), true), cx, 922 * k, 32 * k, 0xCCFFFFFF);
            c.drawText(HomeData.degrees(wx.dayMax.get(i)), cx, 976 * k, smallPaint);
            c.drawText(HomeData.degrees(wx.dayMin.get(i)), cx, 1014 * k, smallDim);
        }
    }

    private static String weekday(SimpleDateFormat f, long at) {
        String s = f.format(new Date(at)).replace(".", "");
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
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
