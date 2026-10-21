package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

import java.text.NumberFormat;

/**
 * One Esthetis tab card, full screen: the big number, the change and the chart (bars or a line) with its scale.
 * ◀▶ go to the previous or next card; Back closes.
 */
public class ChartActivity extends Activity {
    static final String EXTRA_INDEX = "index";

    static Intent intent(Context c, int index) {
        return new Intent(c, ChartActivity.class).putExtra(EXTRA_INDEX, index);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        setContentView(new BigChart(this, getIntent().getIntExtra(EXTRA_INDEX, 0)));
    }

    static final class BigChart extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), area = new Paint(Paint.ANTI_ALIAS_FLAG),
                bar = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint();
        private final Path path = new Path(), fill = new Path();
        private final RectF r = new RectF();
        private Paint title, value, change, sub, axis, label, hint;
        private int index;
        private float k = 1;

        BigChart(Context c, int index) {
            super(c);
            this.index = index;
            setFocusable(true);
            setFocusableInTouchMode(true);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setStrokeCap(Paint.Cap.ROUND);
            grid.setColor(0x1AFFFFFF);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            title = Ui.text(Ui.semibold, 40 * k, 0xB3FFFFFF);
            value = Ui.numbers(Ui.thin, 120 * k, 0xF2FFFFFF);
            change = Ui.numbers(Ui.semibold, 38 * k, 0xFF30D158);
            sub = Ui.text(Ui.medium, 30 * k, 0x99FFFFFF);
            axis = Ui.numbers(Ui.medium, 24 * k, 0x80FFFFFF);
            axis.setTextAlign(Paint.Align.RIGHT);
            label = Ui.text(Ui.medium, 26 * k, 0x99FFFFFF);
            hint = Ui.text(Ui.medium, 22 * k, 0x66FFFFFF);
            line.setStrokeWidth(4 * k);
            requestFocus();
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.drawColor(0xFF0B0B0D);
            java.util.List<EsthetisBoard.Card> cards = EsthetisBoard.get().cards;
            if (title == null || cards.isEmpty()) return;
            index = Math.max(0, Math.min(cards.size() - 1, index));
            EsthetisBoard.Card card = cards.get(index);
            float left = 120 * k, right = w - 120 * k;
            c.drawText("ESTHETIS · " + card.title, left, 150 * k, title);
            String keys = Ui.tr("◀▶ outro gráfico  ·  Voltar fecha", "◀▶ another chart  ·  Back closes", "◀▶ otro gráfico  ·  Atrás cierra");
            c.drawText(keys, right - hint.measureText(keys), 150 * k, hint);
            if (card.missing != null) {
                c.drawText(card.missing, left, 280 * k, sub);
                return;
            }
            c.drawText(card.value, left - Ui.leftBearing(value, card.value), 280 * k, value);
            float y = 345 * k;
            if (!Double.isNaN(card.change)) {
                change.setColor(card.change >= 0 ? 0xFF30D158 : 0xFFFF453A);
                String ch = Market.percent(card.change);
                c.drawText(ch, left, y, change);
                c.drawText("   " + card.sub, left + change.measureText(ch), y, sub);
            } else {
                c.drawText(card.sub, left, y, sub);
            }

            double[] s = card.series;
            if (s.length < 2) return;
            float top = 430 * k, bottom = h - 140 * k, chartRight = right - 150 * k;
            double max = 0, min = card.bars ? 0 : Double.MAX_VALUE;
            for (double v : s) { max = Math.max(max, v); if (!card.bars) min = Math.min(min, v); }
            if (max - min < 1e-9) max = min + 1;
            if (!card.bars) { double pad = (max - min) * 0.08; min = Math.max(0, min - pad); max += pad; }
            NumberFormat f = NumberFormat.getIntegerInstance(Ui.PT);
            for (int i = 0; i <= 4; i++) {
                float gy = top + (bottom - top) * i / 4f;
                c.drawLine(left, gy, chartRight, gy, grid);
                double v = max - (max - min) * i / 4;
                c.drawText((card.reais ? "R$ " : "") + f.format(Math.round(v)), right, gy + 8 * k, axis);
            }
            if (card.bars) {
                float slot = (chartRight - left) / s.length, gap = Math.max(4 * k, slot * 0.25f);
                for (int i = 0; i < s.length; i++) {
                    float bh = (float) ((bottom - top) * (s[i] - min) / (max - min));
                    r.set(left + i * slot + gap / 2, bottom - Math.max(bh, 3 * k), left + (i + 1) * slot - gap / 2, bottom);
                    bar.setColor(i == s.length - 1 ? card.color : Ui.alpha(card.color, 0.45f));
                    c.drawRoundRect(r, 6 * k, 6 * k, bar);
                }
            } else {
                path.reset();
                for (int i = 0; i < s.length; i++) {
                    float px = left + (chartRight - left) * i / (s.length - 1f);
                    float py = (float) (bottom - (bottom - top) * (s[i] - min) / (max - min));
                    if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
                }
                fill.set(path);
                fill.lineTo(chartRight, bottom);
                fill.lineTo(left, bottom);
                fill.close();
                area.setShader(new LinearGradient(0, top, 0, bottom, Ui.alpha(card.color, 0.35f), Ui.alpha(card.color, 0f), Shader.TileMode.CLAMP));
                c.drawPath(fill, area);
                line.setColor(card.color);
                c.drawPath(path, line);
            }
            c.drawText(card.first, left, bottom + 50 * k, label);
            c.drawText(card.last, chartRight - label.measureText(card.last), bottom + 50 * k, label);
        }

        @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
            int n = EsthetisBoard.get().cards.size();
            if (n == 0) return super.onKeyDown(keyCode, e);
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_UP: index = (index + n - 1) % n; invalidate(); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_DOWN: index = (index + 1) % n; invalidate(); return true;
                default: return super.onKeyDown(keyCode, e);
            }
        }
    }
}
