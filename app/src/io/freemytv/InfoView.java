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
 * The screensaver as an information board on plain black: the clock and date, today's weather with the next hours,
 * the week's forecast and the day's top headlines. Redrawn once a minute, and the whole board moves a few pixels each
 * minute so nothing stays burned into the panel. The arrows pick a headline and OK opens it ({@link Opener}).
 */
final class InfoView extends View implements HomeData.Listener {
    private static final float LEFT = 120, RIGHT = 1800;
    /** What a key does on the board: nothing here (the system's), handled, or ends the screensaver. */
    static final int PASS = 0, HANDLED = 1, EXIT = 2;
    private static final long IDLE_MS = 30_000;

    interface Opener { void open(Rss.Headline headline); }

    private final HomeData data;
    private final News news;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;
    private int w, h;
    private float k = 1;
    private Paint datePaint, clockPaint, tempPaint, rangePaint, cityPaint, smallPaint, smallDim, labelPaint, rule;
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
            news.refreshIfStale();
            invalidate();
            long now = System.currentTimeMillis();
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
        datePaint = Ui.text(Ui.semibold, 40 * k, 0xB3FFFFFF);
        clockPaint = Ui.numbers(Ui.thin, 230 * k, 0xF2FFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        tempPaint = Ui.numbers(Ui.thin, 110 * k, 0xF2FFFFFF);
        tempPaint.setTextAlign(Paint.Align.RIGHT);
        rangePaint = Ui.numbers(Ui.medium, 30 * k, 0xB3FFFFFF);
        rangePaint.setTextAlign(Paint.Align.RIGHT);
        cityPaint = Ui.text(Ui.medium, 26 * k, 0x80FFFFFF);
        cityPaint.setTextAlign(Paint.Align.RIGHT);
        smallPaint = Ui.numbers(Ui.medium, 28 * k, 0xE6FFFFFF);
        smallPaint.setTextAlign(Paint.Align.CENTER);
        smallDim = Ui.numbers(Ui.regular, 26 * k, 0x80FFFFFF);
        smallDim.setTextAlign(Paint.Align.CENTER);
        labelPaint = Ui.text(Ui.medium, 22 * k, 0x80FFFFFF);
        labelPaint.setLetterSpacing(0.12f);
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
        c.drawColor(0xFF000000);
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        // Pixel shift against burn-in: a 5x5 grid of positions, 6 px apart, one step a minute.
        c.save();
        c.translate(((minute % 5) - 2) * 6 * k, (((minute / 5) % 5) - 2) * 6 * k);
        try {
            draw(c, now);
        } finally {
            c.restore();
        }
    }

    private void draw(Canvas c, long now) {
        float left = LEFT * k, right = RIGHT * k;
        c.drawText(Ui.date(now), left, 130 * k, datePaint);
        c.drawText(Ui.clock(now), left - Ui.leftBearing(clockPaint, Ui.clock(now)), 340 * k, clockPaint);

        HomeData.Weather wx = data.weather;
        if (Prefs.weather() && !Double.isNaN(wx.temp)) {
            drawToday(c, wx, right);
            if (!wx.days.isEmpty()) drawWeek(c, wx, left, right);
        }
        drawNews(c, left, right);
    }

    /** Top right: symbol and temperature now, high and low, the city, then the next hours. */
    private void drawToday(Canvas c, HomeData.Weather wx, float right) {
        String temp = HomeData.degrees(wx.temp);
        float base = 190 * k;
        c.drawText(temp, right, base, tempPaint);
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float size = 76 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, right - tempPaint.measureText(temp) - 24 * k - sw / 2,
                base - Ui.cap(tempPaint) / 2, size, 0xE6FFFFFF);
        String range = Ui.tr("Máx. ", "H: ", "Máx. ") + HomeData.degrees(wx.max) + "   "
                + Ui.tr("Mín. ", "L: ", "Mín. ") + HomeData.degrees(wx.min);
        c.drawText(range, right, 240 * k, rangePaint);
        if (!Prefs.city().isEmpty()) c.drawText(Prefs.city(), right, 278 * k, cityPaint);

        int n = Math.min(6, wx.hours.size());
        float col = 112 * k, x0 = right - col * n + col / 2;
        SimpleDateFormat hour = Ui.en ? new SimpleDateFormat("h a", Locale.US) : new SimpleDateFormat("H'h'", Ui.locale());
        for (int i = 0; i < n; i++) {
            float cx = x0 + i * col;
            c.drawText(hour.format(new Date(wx.hours.get(i))), cx, 330 * k, smallDim);
            Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), cx, 370 * k, 32 * k, 0xCCFFFFFF);
            c.drawText(HomeData.degrees(wx.hourTemp.get(i)), cx, 424 * k, smallPaint);
        }
    }

    /** The week across the screen: day, symbol, high and low. */
    private void drawWeek(Canvas c, HomeData.Weather wx, float left, float right) {
        c.drawLine(left, 480 * k, right, 480 * k, rule);
        c.drawText(Ui.tr("PRÓXIMOS DIAS", "THIS WEEK", "PRÓXIMOS DÍAS"), left, 522 * k, labelPaint);
        int n = Math.min(7, wx.days.size());
        float col = (right - left) / n;
        SimpleDateFormat day = new SimpleDateFormat("EEE", Ui.locale());
        for (int i = 0; i < n; i++) {
            float cx = left + col * i + col / 2;
            String name = i == 0 ? Ui.tr("Hoje", "Today", "Hoy") : weekday(day, wx.days.get(i));
            c.drawText(name, cx, 574 * k, smallPaint);
            Symbols.draw(getContext(), c, Symbols.weather(wx.dayCode.get(i), true), cx, 620 * k, 40 * k, 0xCCFFFFFF);
            c.drawText(HomeData.degrees(wx.dayMax.get(i)) + "  " + HomeData.degrees(wx.dayMin.get(i)), cx, 676 * k, smallDim);
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
        List<Rss.Headline> list = news.headlines;
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
