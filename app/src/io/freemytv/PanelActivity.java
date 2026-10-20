package io.freemytv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;

/**
 * The side panel of the remote's dashboard button (Wi-Fi, accessories, inputs, picture, settings). The system
 * opens it with TOGGLE_NOTIFICATION_HANDLER_PANEL, which Google TV's home screen answers when it's on; in Free My TV
 * mode that one is off, so this app answers instead. Pressing the button again closes it.
 */
public class PanelActivity extends Activity {
    private static final String TCL_ACCESSORIES = "com.android.tv.settings/com.tcl.android.tv.settings.accessories.AccessoriesActivity";

    private PanelView view;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new PanelView(this);
        setContentView(view);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();   // the button again: close
    }

    @Override protected void onPause() {
        super.onPause();
        finish();   // never stays behind another app
    }

    void open(int item) {
        Intent i;
        switch (item) {
            case 0: i = new Intent(Settings.ACTION_WIFI_SETTINGS); break;
            case 1: i = new Intent().setComponent(ComponentName.unflattenFromString(TCL_ACCESSORIES)); break;
            case 2: i = new Intent("com.android.tv.action.VIEW_INPUTS"); break;
            case 3: i = new Intent(Settings.ACTION_DISPLAY_SETTINGS); break;
            case 4: i = new Intent(this, SettingsActivity.class); break;
            // No sound item: on TCL the system's sound screen crashes, and the remote has the volume.
            default: i = new Intent(Settings.ACTION_SETTINGS);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            // Not on this TV (another brand): the TV's settings.
            AppLog.w("panel: " + e.getMessage());
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        finish();
    }

    static final class PanelView extends View {
        private static final float WIDTH = 600, ROW = 88, TOP = 250;
        private static final int INK = 0xFF1C1C1E;
        private static final String[] SYMBOLS = {"wifi", "bluetooth", "hdmi", "tv_settings", "sparkles", "settings"};

        private final PanelActivity activity;
        private final RectF rect = new RectF();
        private final Paint dim = new Paint(), panel = new Paint(Paint.ANTI_ALIAS_FLAG), white = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Paint clockPaint, datePaint, rowPaint, rowFocused;
        private int focus;
        private float k = 1;

        PanelView(Context c) {
            super(c);
            activity = (PanelActivity) c;
            setFocusable(true);
            setFocusableInTouchMode(true);
            dim.setColor(0x73000000);
            panel.setColor(0xF51C1C1E);
            white.setColor(0xFFFFFFFF);
        }

        private static String[] labels() {
            return new String[] {
                Ui.tr("Wi-Fi e rede", "Wi-Fi & network", "Wi-Fi y red"),
                Ui.tr("Controles e Bluetooth", "Remotes & Bluetooth", "Controles y Bluetooth"),
                Ui.tr("Entradas", "Inputs", "Entradas"),
                Ui.tr("Imagem", "Picture", "Imagen"),
                "Free My TV",
                Ui.tr("Configurações", "Settings", "Ajustes"),
            };
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            clockPaint = Ui.numbers(Ui.thin, 96 * k, 0xF2FFFFFF);
            datePaint = Ui.text(Ui.medium, 28 * k, 0x99FFFFFF);
            rowPaint = Ui.text(Ui.medium, 32 * k, 0xE6FFFFFF);
            rowFocused = Ui.text(Ui.semibold, 32 * k, INK);
            requestFocus();
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float left = w - WIDTH * k, pad = 48 * k;
            c.drawRect(0, 0, left, h, dim);
            rect.set(left, 0, w + 40 * k, h);
            c.drawRoundRect(rect, 36 * k, 36 * k, panel);
            long now = System.currentTimeMillis();
            c.drawText(Ui.clock(now), left + pad, 150 * k, clockPaint);
            c.drawText(Ui.date(now), left + pad, 196 * k, datePaint);
            String[] labels = labels();
            for (int i = 0; i < labels.length; i++) {
                float top = (TOP + i * ROW) * k, mid = top + ROW * k / 2;
                boolean on = i == focus;
                if (on) {
                    rect.set(left + 24 * k, top + 6 * k, w - 24 * k, top + (ROW - 6) * k);
                    c.drawRoundRect(rect, 20 * k, 20 * k, white);
                }
                Symbols.draw(getContext(), c, SYMBOLS[i], left + pad + 18 * k, mid, 34 * k, on ? INK : 0xCCFFFFFF);
                Paint p = on ? rowFocused : rowPaint;
                c.drawText(labels[i], left + pad + 64 * k, mid + Ui.halfCap(p), p);
            }
        }

        @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
            int n = SYMBOLS.length;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP: focus = (focus + n - 1) % n; invalidate(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: focus = (focus + 1) % n; invalidate(); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER: activity.open(focus); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_BACK: activity.finish(); return true;
                default: return super.onKeyDown(keyCode, event);
            }
        }
    }
}
