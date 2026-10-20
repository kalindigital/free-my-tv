package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * A big chart of one Market asset (opened from the Market tab): ◀▶ change the period (1 day to 5 years), ▲▼ go to the
 * previous or next asset, Back closes.
 */
public class MarketActivity extends Activity {
    static final String EXTRA_ASSET = "asset";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        int asset = Math.max(0, Math.min(Market.ASSETS.length - 1, getIntent().getIntExtra(EXTRA_ASSET, 0)));
        setContentView(new ChartView(this, asset));
    }

    static final class ChartView extends View {
        private static final int UP = 0xFF30D158, DOWN = 0xFFFF453A;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), area = new Paint(Paint.ANTI_ALIAS_FLAG),
                grid = new Paint(), pill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path(), fill = new Path();
        private final RectF r = new RectF();
        private Paint name, price, change, label, axis, rangeOn, rangeOff, hint;
        private int asset, range;
        private Market.Quote quote;
        private boolean loading, failed;
        private int request;
        private float k = 1;

        ChartView(Context c, int asset) {
            super(c);
            this.asset = asset;
            setFocusable(true);
            setFocusableInTouchMode(true);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setStrokeCap(Paint.Cap.ROUND);
            grid.setColor(0x1AFFFFFF);
            load();
        }

        private void load() {
            final int id = ++request;
            loading = true;
            failed = false;
            Market.get().chart(asset, range, q -> {
                if (id != request) return;
                loading = false;
                failed = q == null || q.closes.length < 2;
                quote = q;
                invalidate();
            });
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            name = Ui.text(Ui.semibold, 40 * k, 0xB3FFFFFF);
            price = Ui.numbers(Ui.thin, 120 * k, 0xF2FFFFFF);
            change = Ui.numbers(Ui.semibold, 38 * k, UP);
            label = Ui.text(Ui.medium, 26 * k, 0x99FFFFFF);
            axis = Ui.numbers(Ui.medium, 24 * k, 0x80FFFFFF);
            axis.setTextAlign(Paint.Align.RIGHT);
            rangeOn = Ui.text(Ui.semibold, 28 * k, 0xFF1C1C1E);
            rangeOn.setTextAlign(Paint.Align.CENTER);
            rangeOff = Ui.text(Ui.medium, 28 * k, 0xB3FFFFFF);
            rangeOff.setTextAlign(Paint.Align.CENTER);
            hint = Ui.text(Ui.medium, 22 * k, 0x66FFFFFF);
            line.setStrokeWidth(4 * k);
            requestFocus();
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.drawColor(0xFF0B0B0D);
            if (name == null) return;
            Market.Asset a = Market.ASSETS[asset];
            float left = 120 * k, right = w - 120 * k;
            c.drawText(a.name, left, 150 * k, name);
            Market.Quote q = quote;
            double p = q != null ? q.price : Market.get().quotes[asset].price;
            c.drawText(Market.format(a, p), left - Ui.leftBearing(price, "0"), 280 * k, price);
            if (q != null && !Double.isNaN(q.change)) {
                change.setColor(q.change >= 0 ? UP : DOWN);
                c.drawText(Market.percent(q.change) + "  " + Market.RANGES[range][0], left, 345 * k, change);
            }

            // Periods, the selected one in a white pill.
            float pw = 96 * k, ph = 60 * k, x = right - pw * Market.RANGES.length, cy = 130 * k;
            for (int i = 0; i < Market.RANGES.length; i++, x += pw) {
                if (i == range) {
                    pill.setColor(0xFFFFFFFF);
                    r.set(x + 6 * k, cy - ph / 2, x + pw - 6 * k, cy + ph / 2);
                    c.drawRoundRect(r, ph / 2, ph / 2, pill);
                }
                Paint tp = i == range ? rangeOn : rangeOff;
                c.drawText(Market.RANGES[i][0], x + pw / 2, cy + Ui.halfCap(tp), tp);
            }
            String keys = Ui.tr("◀▶ período  ·  ▲▼ outro ativo  ·  Voltar fecha", "◀▶ period  ·  ▲▼ another asset  ·  Back closes",
                    "◀▶ período  ·  ▲▼ otro activo  ·  Atrás cierra");
            c.drawText(keys, right - hint.measureText(keys), 210 * k, hint);

            float top = 420 * k, bottom = h - 140 * k, chartRight = right - 150 * k;
            if (loading || failed || q == null) {
                label.setTextAlign(Paint.Align.CENTER);
                c.drawText(failed ? Ui.tr("Não deu para carregar o gráfico.", "Couldn't load the chart.", "No se pudo cargar el gráfico.")
                        : Ui.tr("Carregando…", "Loading…", "Cargando…"), w / 2f, (top + bottom) / 2, label);
                label.setTextAlign(Paint.Align.LEFT);
                if (loading) postInvalidateDelayed(100);
                return;
            }
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            for (double v : q.closes) { min = Math.min(min, v); max = Math.max(max, v); }
            if (max - min < 1e-9) { max += 1; min -= 1; }
            double pad = (max - min) * 0.08;
            min -= pad;
            max += pad;
            // Grid and values on the right.
            for (int i = 0; i <= 4; i++) {
                float y = top + (bottom - top) * i / 4f;
                c.drawLine(left, y, chartRight, y, grid);
                double v = max - (max - min) * i / 4;
                c.drawText(Market.format(a, v).replace(" pts", ""), right, y + 8 * k, axis);
            }
            int n = q.closes.length;
            path.reset();
            for (int i = 0; i < n; i++) {
                float px = left + (chartRight - left) * i / (n - 1f);
                float py = (float) (bottom - (bottom - top) * (q.closes[i] - min) / (max - min));
                if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            int color = q.closes[n - 1] >= q.closes[0] ? UP : DOWN;
            fill.set(path);
            fill.lineTo(chartRight, bottom);
            fill.lineTo(left, bottom);
            fill.close();
            area.setShader(new LinearGradient(0, top, 0, bottom, Ui.alpha(color, 0.35f), Ui.alpha(color, 0f), Shader.TileMode.CLAMP));
            c.drawPath(fill, area);
            line.setColor(color);
            c.drawPath(path, line);
            // Start and end dates under the chart.
            SimpleDateFormat f = new SimpleDateFormat(range <= 1 ? "dd/MM HH:mm" : "dd/MM/yy", Ui.locale());
            c.drawText(f.format(new Date(q.times[0])), left, bottom + 50 * k, label);
            String end = f.format(new Date(q.times[n - 1]));
            c.drawText(end, chartRight - label.measureText(end), bottom + 50 * k, label);
        }

        @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT: if (range > 0) { range--; load(); } return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (range < Market.RANGES.length - 1) { range++; load(); } return true;
                case KeyEvent.KEYCODE_DPAD_UP: asset = (asset + Market.ASSETS.length - 1) % Market.ASSETS.length; quote = null; load(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: asset = (asset + 1) % Market.ASSETS.length; quote = null; load(); return true;
                default: return super.onKeyDown(keyCode, e);
            }
        }
    }
}
