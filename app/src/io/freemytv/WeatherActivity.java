package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Weather screen (OK on Home's weather card), after the iPhone's Weather app. Three pages, ◀▶ between them:
 *  - Forecast: now, the next 24 hours, the next 10 days and the details (feels like, humidity, wind, UV, rain,
 *    visibility, pressure, sunrise and sunset); ▲▼ go to the previous or next city;
 *  - Maps: rain radar (RainViewer, the last hour animated), temperature and wind (an Open-Meteo grid) over Esri's
 *    dark map; ▲▼ change the layer, OK zooms in or out;
 *  - Cities: the home city and the added ones with their weather now; OK opens one, "+ Add" searches with the TV's
 *    keyboard, holding OK removes one.
 */
public class WeatherActivity extends Activity {
    private WeatherView view;
    private EditText field;
    private boolean typing;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new WeatherView(this);
        field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setHint(Ui.tr("Buscar cidade", "Search city", "Buscar ciudad"));
        field.setAlpha(0f);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { if (typing) view.setQuery(s.toString()); }
        });
        field.setOnEditorActionListener((v, action, event) -> {
            stopTyping();
            return true;
        });
        FrameLayout root = new FrameLayout(this);
        root.addView(view);
        root.addView(field, new FrameLayout.LayoutParams(1, 1));
        setContentView(root);
        view.requestFocus();
    }

    @Override protected void onDestroy() {
        view.release();
        super.onDestroy();
    }

    void startTyping(String current) {
        typing = true;
        field.setText(current);
        field.setSelection(field.length());
        field.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_FORCED);
    }

    private void stopTyping() {
        if (!typing) return;
        typing = false;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
        view.requestFocus();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (RemoteKeys.onKey(this, e)) return true;
        if (typing) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_BACK:
                    if (e.getAction() == KeyEvent.ACTION_UP) stopTyping();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_CENTER:
                    stopTyping();     // the keyboard closed: the arrows are for the screen again
                    break;
                default:
                    return super.dispatchKeyEvent(e);
            }
        }
        if (view.key(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    static final class WeatherView extends View implements MapTiles.Listener {
        private static final int FORECAST = 0, MAPS = 1, CITIES = 2;
        private static final int RAIN = 0, TEMP = 1, WIND = 2;
        private static final int GRID_COLS = 9, GRID_ROWS = 5;

        private final WeatherActivity activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final ExecutorService io = Executors.newFixedThreadPool(2);
        private final MapTiles tiles = new MapTiles(this);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), bar = new Paint(Paint.ANTI_ALIAS_FLAG),
                bmp = new Paint(Paint.FILTER_BITMAP_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Rect src = new Rect();
        private final Path path = new Path();
        private float k = 1;
        private Paint tab, tabOn, hint, city, region, big, cond, range, label, value, small, rowName, rowTemp, mapLabel;

        private List<WeatherCities.City> cities;
        private int cityIndex, page = FORECAST, cityRow;
        private WeatherFull.Forecast forecast;
        private boolean loading, failed;
        private final Map<String, WeatherFull.Forecast> cityWeather = new HashMap<>();

        private int layer = RAIN, zoom = 7;
        private WeatherFull.Radar radar;
        private int frame;
        private WeatherFull.Grid grid;
        private String gridFor = "";
        private Bitmap tempField;

        private boolean searching;
        private String query = "";
        private List<WeatherCities.City> results = new ArrayList<>();
        private int resultRow;
        private boolean removeArmed;

        private final Runnable animate = new Runnable() {
            @Override public void run() {
                if (page != MAPS || layer != RAIN || radar == null || radar.paths.isEmpty()) return;
                frame = (frame + 1) % radar.paths.size();
                invalidate();
                handler.postDelayed(this, frame == radar.paths.size() - 1 ? 1800 : 550);
            }
        };
        private final Runnable runSearch = this::searchNow;

        WeatherView(WeatherActivity a) {
            super(a);
            activity = a;
            setFocusable(true);
            setFocusableInTouchMode(true);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            cities = WeatherCities.all(a);
            load();
        }

        void release() {
            handler.removeCallbacksAndMessages(null);
            io.shutdownNow();
            tiles.release();
        }

        @Override public void onTile() { invalidate(); }

        // MARK: - Data

        private void load() {
            final WeatherCities.City c = cities.get(cityIndex);
            loading = true;
            failed = false;
            io.execute(() -> {
                WeatherFull.Forecast f = null;
                try { f = WeatherFull.forecast(c); } catch (Exception e) { AppLog.w("weather full: " + e.getMessage()); }
                final WeatherFull.Forecast got = f;
                handler.post(() -> {
                    if (cities.get(cityIndex) != c) return;
                    loading = false;
                    failed = got == null;
                    forecast = got;
                    if (got != null) cityWeather.put(c.key(), got);
                    invalidate();
                });
            });
            invalidate();
        }

        /** The weather now of every city, for the Cities page (cached by WeatherFull). */
        private void loadCities() {
            for (final WeatherCities.City c : cities) {
                if (cityWeather.containsKey(c.key())) continue;
                io.execute(() -> {
                    try {
                        WeatherFull.Forecast f = WeatherFull.forecast(c);
                        handler.post(() -> { cityWeather.put(c.key(), f); invalidate(); });
                    } catch (Exception e) {
                        AppLog.w("weather city: " + e.getMessage());
                    }
                });
            }
        }

        private void loadRadar() {
            if (radar != null) return;
            io.execute(() -> {
                try {
                    WeatherFull.Radar got = WeatherFull.radar(6);
                    handler.post(() -> {
                        radar = got;
                        frame = Math.max(0, got.paths.size() - 1);
                        handler.removeCallbacks(animate);
                        handler.postDelayed(animate, 1500);
                        invalidate();
                    });
                } catch (Exception e) {
                    AppLog.w("radar: " + e.getMessage());
                }
            });
        }

        /** The temperature and wind grid over what the map shows now. */
        private void loadGrid() {
            if (getWidth() == 0) return;
            WeatherCities.City c = cities.get(cityIndex);
            final String key = c.key() + "@" + zoom;
            if (key.equals(gridFor)) return;
            gridFor = key;
            double cx = MapTiles.x(c.lon, zoom), cy = MapTiles.y(c.lat, zoom);
            double halfW = getWidth() / 2.0 / k, halfH = getHeight() / 2.0 / k;
            final double north = MapTiles.lat(cy - halfH, zoom), south = MapTiles.lat(cy + halfH, zoom);
            final double west = MapTiles.lon(cx - halfW, zoom), east = MapTiles.lon(cx + halfW, zoom);
            grid = null;
            tempField = null;
            io.execute(() -> {
                try {
                    WeatherFull.Grid g = WeatherFull.grid(GRID_COLS, GRID_ROWS, north, south, west, east);
                    Bitmap field = temperatureField(g);
                    handler.post(() -> {
                        if (!key.equals(gridFor)) return;
                        grid = g;
                        tempField = field;
                        invalidate();
                    });
                } catch (Exception e) {
                    AppLog.w("weather grid: " + e.getMessage());
                }
            });
        }

        void setQuery(String q) {
            query = q == null ? "" : q;
            handler.removeCallbacks(runSearch);
            handler.postDelayed(runSearch, 500);
            invalidate();
        }

        private void searchNow() {
            final String q = query.trim();
            if (q.length() < 2) {
                results = new ArrayList<>();
                invalidate();
                return;
            }
            io.execute(() -> {
                try {
                    List<WeatherCities.City> got = WeatherCities.search(q);
                    handler.post(() -> {
                        if (!q.equals(query.trim())) return;
                        results = got;
                        resultRow = 0;
                        invalidate();
                    });
                } catch (Exception e) {
                    AppLog.w("city search: " + e.getMessage());
                }
            });
        }

        // MARK: - Keys

        boolean key(KeyEvent e) {
            int code = e.getKeyCode();
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (code) {
                case KeyEvent.KEYCODE_BACK:
                    if (!down) {
                        if (searching) {
                            searching = false;
                            invalidate();
                        } else if (page != FORECAST) {
                            setPage(FORECAST);
                        } else {
                            activity.finish();
                        }
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && !searching) setPage((page + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 2)) % 3);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) move(code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down && e.getRepeatCount() == 8 && page == CITIES && !searching && cityRow > 0 && cityRow < cities.size()) {
                        removeArmed = true;       // held OK on an added city: remove it on release
                        invalidate();
                    }
                    if (!down) ok();
                    return true;
                default:
                    return false;
            }
        }

        private void setPage(int p) {
            page = p;
            searching = false;
            handler.removeCallbacks(animate);
            if (page == MAPS) {
                loadRadar();
                loadGrid();
                if (radar != null) handler.postDelayed(animate, 800);
            } else if (page == CITIES) {
                cityRow = cityIndex;
                loadCities();
            }
            invalidate();
        }

        private void move(int d) {
            if (searching) {
                resultRow = Math.max(0, Math.min(results.size() - 1, resultRow + d));
            } else if (page == FORECAST) {
                int next = Math.max(0, Math.min(cities.size() - 1, cityIndex + d));
                if (next != cityIndex) selectCity(next);
            } else if (page == MAPS) {
                layer = (layer + (d > 0 ? 1 : 2)) % 3;
                handler.removeCallbacks(animate);
                if (layer == RAIN && radar != null) handler.postDelayed(animate, 600);
                else loadGrid();
            } else {
                cityRow = Math.max(0, Math.min(cities.size(), cityRow + d));     // the last row is "+ Add"
                removeArmed = false;
            }
            invalidate();
        }

        private void ok() {
            if (searching) {
                if (resultRow < results.size()) {
                    WeatherCities.add(getContext(), results.get(resultRow));
                    cities = WeatherCities.all(getContext());
                    searching = false;
                    cityRow = cities.size() - 1;
                    loadCities();
                }
            } else if (page == MAPS) {
                zoom = zoom == 7 ? 9 : 7;
                loadGrid();
            } else if (page == CITIES) {
                if (removeArmed) {
                    removeArmed = false;
                    WeatherCities.remove(getContext(), cityRow);
                    cities = WeatherCities.all(getContext());
                    if (cityIndex >= cities.size()) selectCity(0);
                    cityRow = Math.min(cityRow, cities.size());
                } else if (cityRow == cities.size()) {
                    searching = true;
                    query = "";
                    results = new ArrayList<>();
                    activity.startTyping("");
                } else {
                    selectCity(cityRow);
                    setPage(FORECAST);
                }
            }
            invalidate();
        }

        private void selectCity(int i) {
            cityIndex = i;
            forecast = cityWeather.get(cities.get(i).key());
            gridFor = "";
            load();
        }

        // MARK: - Drawing

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            tab = Ui.text(Ui.medium, 30 * k, 0xB3FFFFFF);
            tabOn = Ui.text(Ui.semibold, 30 * k, 0xFF1C1C1E);
            hint = Ui.text(Ui.medium, 22 * k, 0x80FFFFFF);
            city = Ui.text(Ui.semibold, 56 * k, 0xFFFFFFFF);
            region = Ui.text(Ui.medium, 26 * k, 0xB3FFFFFF);
            big = Ui.numbers(Ui.thin, 170 * k, 0xFFFFFFFF);
            cond = Ui.text(Ui.medium, 36 * k, 0xF2FFFFFF);
            range = Ui.numbers(Ui.medium, 30 * k, 0xD9FFFFFF);
            label = Ui.text(Ui.semibold, 21 * k, 0x99FFFFFF);
            label.setLetterSpacing(0.08f);
            value = Ui.numbers(Ui.medium, 44 * k, 0xFFFFFFFF);
            small = Ui.text(Ui.medium, 24 * k, 0xCCFFFFFF);
            rowName = Ui.text(Ui.semibold, 40 * k, 0xFFFFFFFF);
            rowTemp = Ui.numbers(Ui.thin, 64 * k, 0xFFFFFFFF);
            mapLabel = Ui.numbers(Ui.semibold, 22 * k, 0xFFFFFFFF);
            mapLabel.setShadowLayer(6 * k, 0, 1 * k, 0xCC000000);
            requestFocus();
            if (page == MAPS) loadGrid();
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (tab == null) return;
            if (page == MAPS) drawMap(c, w, h);
            else {
                drawSky(c, w, h);
                if (page == FORECAST) drawForecast(c, w);
                else drawCities(c, w);
            }
            drawTabs(c, w);
        }

        private void drawSky(Canvas c, int w, int h) {
            WeatherFull.Now n = forecast == null ? null : forecast.now;
            boolean day = n == null || n.day;
            boolean wet = n != null && n.code >= 51;
            int top = !day ? 0xFF0E1A33 : wet ? 0xFF5D6E80 : 0xFF2F80D1;
            int bottom = !day ? 0xFF04070F : wet ? 0xFF27323F : 0xFF16467F;
            fill.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
        }

        private void drawTabs(Canvas c, int w) {
            String[] names = {Ui.tr("Previsão", "Forecast", "Pronóstico"), Ui.tr("Mapas", "Maps", "Mapas"), Ui.tr("Cidades", "Cities", "Ciudades")};
            float x = 100 * k, cy = 70 * k, ph = 56 * k;
            for (int i = 0; i < names.length; i++) {
                Paint p = i == page ? tabOn : tab;
                float tw = p.measureText(names[i]);
                if (i == page) {
                    fill.setColor(0xF2FFFFFF);
                    r.set(x - 24 * k, cy - ph / 2, x + tw + 24 * k, cy + ph / 2);
                    c.drawRoundRect(r, ph / 2, ph / 2, fill);
                }
                c.drawText(names[i], x, cy + Ui.halfCap(p), p);
                x += tw + 64 * k;
            }
            String keys = searching ? Ui.tr("▲▼ escolher  ·  OK adicionar  ·  Voltar cancela", "▲▼ pick  ·  OK add  ·  Back cancels", "▲▼ elegir  ·  OK agregar  ·  Atrás cancela")
                    : page == FORECAST ? Ui.tr("◀▶ página  ·  ▲▼ outra cidade  ·  Voltar fecha", "◀▶ page  ·  ▲▼ another city  ·  Back closes", "◀▶ página  ·  ▲▼ otra ciudad  ·  Atrás cierra")
                    : page == MAPS ? Ui.tr("◀▶ página  ·  ▲▼ camada  ·  OK aproxima", "◀▶ page  ·  ▲▼ layer  ·  OK zooms", "◀▶ página  ·  ▲▼ capa  ·  OK acerca")
                    : Ui.tr("◀▶ página  ·  OK abre  ·  segure OK para remover", "◀▶ page  ·  OK opens  ·  hold OK to remove", "◀▶ página  ·  OK abre  ·  mantén OK para quitar");
            c.drawText(keys, w - 100 * k - hint.measureText(keys), cy + Ui.halfCap(hint), hint);
        }

        private void card(Canvas c, float left, float top, float right, float bottom) {
            fill.setColor(0x24FFFFFF);
            r.set(left, top, right, bottom);
            c.drawRoundRect(r, 28 * k, 28 * k, fill);
        }

        // Forecast

        private void drawForecast(Canvas c, int w) {
            WeatherCities.City ct = cities.get(cityIndex);
            float left = 100 * k;
            c.drawText(ct.name, left, 200 * k, city);
            if (!ct.region.isEmpty()) c.drawText(ct.region, left, 240 * k, region);
            // Which city of how many (like the iPhone's dots).
            if (cities.size() > 1) {
                float dx = left;
                for (int i = 0; i < cities.size(); i++) {
                    fill.setColor(i == cityIndex ? 0xFFFFFFFF : 0x59FFFFFF);
                    c.drawCircle(dx + 6 * k, 270 * k, 6 * k, fill);
                    dx += 22 * k;
                }
            }
            if (forecast == null) {
                c.drawText(failed ? Ui.tr("Não deu para carregar a previsão.", "Couldn't load the forecast.", "No se pudo cargar el pronóstico.")
                        : Ui.tr("Carregando…", "Loading…", "Cargando…"), left, 400 * k, cond);
                return;
            }
            WeatherFull.Now n = forecast.now;
            String t = HomeData.degrees(n.temp);
            c.drawText(t, left - Ui.leftBearing(big, t), 440 * k, big);
            c.drawText(HomeData.condition(n.code, n.day), left, 500 * k, cond);
            if (!forecast.days.isEmpty()) {
                WeatherFull.Day d = forecast.days.get(0);
                c.drawText(Ui.tr("Máx. ", "H: ", "Máx. ") + HomeData.degrees(d.max) + "   " + Ui.tr("Mín. ", "L: ", "Mín. ") + HomeData.degrees(d.min),
                        left, 548 * k, range);
            }
            drawDays(c, left, 600 * k, 820 * k, 1040 * k);
            drawHours(c, 920 * k, 130 * k, 1820 * k, 380 * k);
            drawDetails(c, 920 * k, 410 * k, 1820 * k, 1040 * k);
        }

        private void drawHours(Canvas c, float left, float top, float right, float bottom) {
            card(c, left, top, right, bottom);
            c.drawText(Ui.tr("PRÓXIMAS 24 HORAS", "NEXT 24 HOURS", "PRÓXIMAS 24 HORAS"), left + 30 * k, top + 44 * k, label);
            List<WeatherFull.Hour> hs = forecast.hours;
            int cols = 12;
            float cw = (right - left - 40 * k) / cols;
            SimpleDateFormat hf = new SimpleDateFormat(Ui.en ? "h a" : "H'h'", Ui.locale());
            small.setTextAlign(Paint.Align.CENTER);
            value.setTextSize(30 * k);
            value.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < cols && i * 2 < hs.size(); i++) {
                WeatherFull.Hour x = hs.get(i * 2);
                float cx = left + 20 * k + cw * (i + 0.5f);
                c.drawText(i == 0 ? Ui.tr("Agora", "Now", "Ahora") : hf.format(new Date(x.at)), cx, top + 96 * k, small);
                Symbols.draw(getContext(), c, Symbols.weather(x.code, x.day), cx, top + 140 * k, 38 * k, 0xFFFFFFFF);
                if (x.precipProb >= 20) {
                    label.setColor(0xFF7FD3FF);
                    label.setTextAlign(Paint.Align.CENTER);
                    c.drawText(Math.round(x.precipProb) + "%", cx, top + 178 * k, label);
                    label.setTextAlign(Paint.Align.LEFT);
                    label.setColor(0x99FFFFFF);
                }
                c.drawText(HomeData.degrees(x.temp), cx, top + 220 * k, value);
            }
            small.setTextAlign(Paint.Align.LEFT);
            value.setTextAlign(Paint.Align.LEFT);
            value.setTextSize(44 * k);
        }

        private void drawDays(Canvas c, float left, float top, float right, float bottom) {
            card(c, left - 30 * k, top, right, bottom);
            c.drawText(Ui.tr("PRÓXIMOS 10 DIAS", "NEXT 10 DAYS", "PRÓXIMOS 10 DÍAS"), left, top + 44 * k, label);
            List<WeatherFull.Day> ds = forecast.days;
            if (ds.isEmpty()) return;
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (WeatherFull.Day d : ds) { lo = Math.min(lo, d.min); hi = Math.max(hi, d.max); }
            if (hi - lo < 1) hi = lo + 1;
            int n = Math.min(10, ds.size());
            float rowH = (bottom - top - 70 * k) / n, y0 = top + 70 * k;
            SimpleDateFormat df = new SimpleDateFormat("EEE", Ui.locale());
            float barL = left + 330 * k, barR = right - 110 * k;
            for (int i = 0; i < n; i++) {
                WeatherFull.Day d = ds.get(i);
                float cy = y0 + rowH * (i + 0.5f), base = cy + Ui.halfCap(small);
                String name = i == 0 ? Ui.tr("Hoje", "Today", "Hoy") : df.format(new Date(d.date)).replace(".", "");
                if (!name.isEmpty()) name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                c.drawText(name, left, base, small);
                Symbols.draw(getContext(), c, Symbols.weather(d.code, true), left + 150 * k, cy, 32 * k, 0xFFFFFFFF);
                if (d.precipProb >= 20) {
                    label.setColor(0xFF7FD3FF);
                    c.drawText(Math.round(d.precipProb) + "%", left + 190 * k, base, label);
                    label.setColor(0x99FFFFFF);
                }
                String mn = HomeData.degrees(d.min), mx = HomeData.degrees(d.max);
                small.setColor(0x99FFFFFF);
                c.drawText(mn, barL - 16 * k - small.measureText(mn), base, small);
                small.setColor(0xCCFFFFFF);
                c.drawText(mx, barR + 16 * k, base, small);
                // The day's range on the week's scale, colored from cool to warm.
                bar.setColor(0x33FFFFFF);
                r.set(barL, cy - 4 * k, barR, cy + 4 * k);
                c.drawRoundRect(r, 4 * k, 4 * k, bar);
                float a = (float) (barL + (barR - barL) * (d.min - lo) / (hi - lo)), b = (float) (barL + (barR - barL) * (d.max - lo) / (hi - lo));
                bar.setShader(new LinearGradient(barL, 0, barR, 0, new int[] {0xFF64D2FF, 0xFF8BE36C, 0xFFFFD60A, 0xFFFF9F0A},
                        null, Shader.TileMode.CLAMP));
                r.set(a, cy - 4 * k, Math.max(b, a + 8 * k), cy + 4 * k);
                c.drawRoundRect(r, 4 * k, 4 * k, bar);
                bar.setShader(null);
            }
        }

        private void drawDetails(Canvas c, float left, float top, float right, float bottom) {
            WeatherFull.Now n = forecast.now;
            WeatherFull.Day today = forecast.days.isEmpty() ? null : forecast.days.get(0);
            WeatherFull.Day tomorrow = forecast.days.size() > 1 ? forecast.days.get(1) : null;
            float gap = 24 * k, cw = (right - left - 2 * gap) / 3, ch = (bottom - top - 2 * gap) / 3;
            String[][] tiles = new String[9][];
            tiles[0] = new String[] {"thermostat", Ui.tr("SENSAÇÃO", "FEELS LIKE", "SENSACIÓN"), HomeData.degrees(n.feels),
                    n.feels < n.temp - 1 ? Ui.tr("O vento deixa mais frio.", "Wind makes it feel cooler.", "El viento lo hace más frío.")
                    : n.feels > n.temp + 1 ? Ui.tr("A umidade deixa mais quente.", "Humidity makes it feel warmer.", "La humedad lo hace más cálido.")
                    : Ui.tr("Parecido com a real.", "Similar to the actual.", "Parecida a la real.")};
            tiles[1] = new String[] {"water_drop", Ui.tr("UMIDADE", "HUMIDITY", "HUMEDAD"), Math.round(n.humidity) + "%",
                    Ui.tr("Ponto de orvalho ", "Dew point ", "Punto de rocío ") + HomeData.degrees(n.dew) + "."};
            tiles[2] = new String[] {"air", Ui.tr("VENTO", "WIND", "VIENTO"), Math.round(n.wind) + " km/h",
                    Ui.tr("Rajadas de ", "Gusts ", "Ráfagas de ") + Math.round(n.gust) + " km/h · " + compass(n.windDir)};
            tiles[3] = new String[] {"uv", Ui.tr("ÍNDICE UV", "UV INDEX", "ÍNDICE UV"), Math.round(n.uv) + " · " + uvText(n.uv),
                    today == null ? "" : Ui.tr("Máximo hoje: ", "Today's high: ", "Máximo hoy: ") + Math.round(today.uvMax) + " (" + uvText(today.uvMax).toLowerCase(Ui.locale()) + ")."};
            tiles[4] = new String[] {"umbrella", Ui.tr("CHUVA HOJE", "RAIN TODAY", "LLUVIA HOY"), today == null ? "—" : mm(today.precipSum),
                    Ui.tr("Amanhã: ", "Tomorrow: ", "Mañana: ") + (tomorrow == null ? "—" : mm(tomorrow.precipSum)) + "."};
            tiles[5] = new String[] {"schedule", Ui.tr("PRÓXIMA CHUVA", "NEXT RAIN", "PRÓXIMA LLUVIA"), nextRain(), nextRainText()};
            tiles[6] = new String[] {"visibility", Ui.tr("VISIBILIDADE", "VISIBILITY", "VISIBILIDAD"),
                    Double.isNaN(n.visibility) ? "—" : Math.round(n.visibility / 1000) + " km",
                    n.visibility >= 10000 ? Ui.tr("Céu limpo para enxergar longe.", "Clear view.", "Vista despejada.") : Ui.tr("Visibilidade reduzida.", "Reduced visibility.", "Visibilidad reducida.")};
            tiles[7] = new String[] {"gauge", Ui.tr("PRESSÃO", "PRESSURE", "PRESIÓN"), Math.round(n.pressure) + " hPa",
                    n.pressure < 1009 ? Ui.tr("Baixa: tempo instável.", "Low: unsettled.", "Baja: inestable.") : n.pressure > 1020 ? Ui.tr("Alta: tempo firme.", "High: settled.", "Alta: estable.") : Ui.tr("Normal.", "Normal.", "Normal.")};
            tiles[8] = new String[] {"sunrise", Ui.tr("NASCER DO SOL", "SUNRISE", "AMANECER"), today == null ? "—" : Ui.time(today.sunrise),
                    today == null ? "" : Ui.tr("Pôr do sol às ", "Sunset at ", "Atardecer a las ") + Ui.time(today.sunset)};
            TextPaint tp = new TextPaint(small);
            tp.setTextSize(22 * k);
            for (int i = 0; i < 9; i++) {
                float x = left + (cw + gap) * (i % 3), y = top + (ch + gap) * (i / 3);
                card(c, x, y, x + cw, y + ch);
                Symbols.draw(getContext(), c, tiles[i][0], x + 40 * k, y + 38 * k, 22 * k, 0x99FFFFFF);
                c.drawText(tiles[i][1], x + 62 * k, y + 46 * k, label);
                c.drawText(tiles[i][2], x + 28 * k, y + 110 * k, value);
                // The note under the number, in up to two lines.
                android.text.StaticLayout note = android.text.StaticLayout.Builder.obtain(tiles[i][3], 0, tiles[i][3].length(), tp,
                        Math.round(cw - 56 * k)).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build();
                c.save();
                c.translate(x + 28 * k, y + ch - 22 * k - note.getHeight());
                note.draw(c);
                c.restore();
                if (i == 2) {   // the wind's direction: an arrow pointing where it blows to
                    drawArrow(c, x + cw - 44 * k, y + 40 * k, 26 * k, (float) n.windDir + 180, 0xCCFFFFFF);
                }
            }
        }

        private String nextRain() {
            for (WeatherFull.Hour x : forecast.hours) {
                if (x.precipProb >= 50 || x.precip >= 0.5) return Ui.en ? new SimpleDateFormat("h a", Ui.locale()).format(new Date(x.at))
                        : new SimpleDateFormat("H'h'", Ui.locale()).format(new Date(x.at));
            }
            return Ui.tr("Sem chuva", "No rain", "Sin lluvia");
        }

        private String nextRainText() {
            for (WeatherFull.Hour x : forecast.hours) {
                if (x.precipProb >= 50 || x.precip >= 0.5) return Math.round(x.precipProb) + Ui.tr("% de chance de chuva.", "% chance of rain.", "% de probabilidad.");
            }
            return Ui.tr("nas próximas 24 horas.", "in the next 24 hours.", "en las próximas 24 horas.");
        }

        private static String mm(double v) {
            return (v < 10 ? String.format(Ui.locale(), "%.1f", v) : String.valueOf(Math.round(v))) + " mm";
        }

        private static String uvText(double uv) {
            if (uv < 3) return Ui.tr("Baixo", "Low", "Bajo");
            if (uv < 6) return Ui.tr("Moderado", "Moderate", "Moderado");
            if (uv < 8) return Ui.tr("Alto", "High", "Alto");
            if (uv < 11) return Ui.tr("Muito alto", "Very high", "Muy alto");
            return Ui.tr("Extremo", "Extreme", "Extremo");
        }

        private static String compass(double deg) {
            String[] pt = {"N", "NE", "L", "SE", "S", "SO", "O", "NO"}, en = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
            int i = (int) Math.round(((deg % 360) + 360) % 360 / 45) % 8;
            return (Ui.en ? en : pt)[i];
        }

        private void drawArrow(Canvas c, float cx, float cy, float len, float toward, int color) {
            c.save();
            c.rotate(toward, cx, cy);
            stroke.setColor(color);
            stroke.setStrokeWidth(Math.max(2, len / 9));
            c.drawLine(cx, cy + len / 2, cx, cy - len / 2, stroke);
            path.reset();
            path.moveTo(cx, cy - len / 2 - len * 0.12f);
            path.lineTo(cx - len * 0.28f, cy - len * 0.12f);
            path.lineTo(cx + len * 0.28f, cy - len * 0.12f);
            path.close();
            fill.setColor(color);
            c.drawPath(path, fill);
            c.restore();
        }

        // Maps

        private void drawMap(Canvas c, int w, int h) {
            c.drawColor(0xFF0B0B0D);
            WeatherCities.City ct = cities.get(cityIndex);
            double cx = MapTiles.x(ct.lon, zoom), cy = MapTiles.y(ct.lat, zoom);
            double ox = cx - w / 2.0 / k, oy = cy - h / 2.0 / k;      // world pixels at the screen's top left
            int t0x = (int) Math.floor(ox / MapTiles.TILE), t0y = (int) Math.floor(oy / MapTiles.TILE);
            int t1x = (int) Math.floor((ox + w / k) / MapTiles.TILE), t1y = (int) Math.floor((oy + h / k) / MapTiles.TILE);
            float ts = MapTiles.TILE * k;
            for (int ty = t0y; ty <= t1y; ty++) {
                for (int tx = t0x; tx <= t1x; tx++) {
                    Bitmap b = tiles.base(zoom, tx, ty);
                    if (b == null) continue;
                    float sx = (float) ((tx * MapTiles.TILE - ox) * k), sy = (float) ((ty * MapTiles.TILE - oy) * k);
                    r.set(sx, sy, sx + ts, sy + ts);
                    c.drawBitmap(b, null, r, bmp);
                }
            }
            String legend;
            if (layer == RAIN) {
                legend = drawRadar(c, ox, oy, t0x, t0y, t1x, t1y, ts);
            } else {
                loadGrid();
                legend = layer == TEMP ? drawTemperature(c, w, h) : drawWind(c, w, h);
            }
            // Place names over the layers.
            for (int ty = t0y; ty <= t1y; ty++) {
                for (int tx = t0x; tx <= t1x; tx++) {
                    Bitmap b = tiles.labels(zoom, tx, ty);
                    if (b == null) continue;
                    float sx = (float) ((tx * MapTiles.TILE - ox) * k), sy = (float) ((ty * MapTiles.TILE - oy) * k);
                    r.set(sx, sy, sx + ts, sy + ts);
                    c.drawBitmap(b, null, r, bmp);
                }
            }
            // The city.
            fill.setColor(0xFFFFFFFF);
            c.drawCircle(w / 2f, h / 2f, 11 * k, fill);
            fill.setColor(0xFF0A84FF);
            c.drawCircle(w / 2f, h / 2f, 7 * k, fill);
            c.drawText(ct.name, w / 2f + 18 * k, h / 2f - 14 * k, mapLabel);
            // Layers, top right; the legend, bottom left.
            String[] names = {Ui.tr("Chuva", "Rain", "Lluvia"), Ui.tr("Temperatura", "Temperature", "Temperatura"), Ui.tr("Vento", "Wind", "Viento")};
            float y = 150 * k, ph = 52 * k;
            for (int i = names.length - 1, x = Math.round(w - 100 * k); i >= 0; i--) {
                Paint p = i == layer ? tabOn : tab;
                float tw = p.measureText(names[i]);
                fill.setColor(i == layer ? 0xF2FFFFFF : 0x99000000);
                r.set(x - tw - 48 * k, y - ph / 2, x, y + ph / 2);
                c.drawRoundRect(r, ph / 2, ph / 2, fill);
                c.drawText(names[i], x - tw - 24 * k, y + Ui.halfCap(p), p);
                x -= Math.round(tw + 64 * k);
            }
            fill.setColor(0xB3000000);
            float lw = Math.max(small.measureText(legend), 420 * k) + 60 * k;
            r.set(100 * k, h - 140 * k, 100 * k + lw, h - 60 * k);
            c.drawRoundRect(r, 20 * k, 20 * k, fill);
            c.drawText(legend, 130 * k, h - 92 * k, small);
            hint.setTextSize(18 * k);
            c.drawText(layer == RAIN ? "RainViewer · Esri, HERE, Garmin, OpenStreetMap" : "Open-Meteo · Esri, HERE, Garmin, OpenStreetMap", 130 * k, h - 70 * k, hint);
            hint.setTextSize(22 * k);
        }

        private String drawRadar(Canvas c, double ox, double oy, int t0x, int t0y, int t1x, int t1y, float ts) {
            if (radar == null || radar.paths.isEmpty()) return Ui.tr("Carregando o radar…", "Loading the radar…", "Cargando el radar…");
            int z = Math.min(zoom, 7);     // RainViewer's free tiles stop at zoom 7: scale them up beyond
            double scale = Math.pow(2, zoom - z);
            String p = radar.paths.get(frame);
            bmp.setAlpha(210);
            int tz0x = (int) Math.floor(t0x / scale), tz0y = (int) Math.floor(t0y / scale);
            int tz1x = (int) Math.floor(t1x / scale), tz1y = (int) Math.floor(t1y / scale);
            for (int ty = tz0y; ty <= tz1y; ty++) {
                for (int tx = tz0x; tx <= tz1x; tx++) {
                    Bitmap b = tiles.radar(radar.host, p, z, tx, ty);
                    // Load the other frames too, so the animation has them.
                    for (String other : radar.paths) if (!other.equals(p)) tiles.radar(radar.host, other, z, tx, ty);
                    if (b == null) continue;
                    float size = (float) (ts * scale);
                    float sx = (float) ((tx * MapTiles.TILE * scale - ox) * k), sy = (float) ((ty * MapTiles.TILE * scale - oy) * k);
                    r.set(sx, sy, sx + size, sy + size);
                    c.drawBitmap(b, null, r, bmp);
                }
            }
            bmp.setAlpha(255);
            // Time of the frame and where it is in the hour.
            long at = radar.times.get(frame);
            String when = Ui.time(at);
            float bx = 100 * k, by = 150 * k;
            fill.setColor(0xB3000000);
            r.set(bx, by - 30 * k, bx + 420 * k, by + 30 * k);
            c.drawRoundRect(r, 30 * k, 30 * k, fill);
            c.drawText(when, bx + 28 * k, by + Ui.halfCap(small), small);
            float pl = bx + 140 * k, pr = bx + 390 * k;
            bar.setColor(0x4DFFFFFF);
            c.drawRect(pl, by - 3 * k, pr, by + 3 * k, bar);
            bar.setColor(0xFFFFFFFF);
            c.drawRect(pl, by - 3 * k, pl + (pr - pl) * (frame + 1f) / radar.paths.size(), by + 3 * k, bar);
            return Ui.tr("Radar de chuva na última hora  ·  azul fraco → vermelho forte", "Rain radar, last hour  ·  light blue → heavy red",
                    "Radar de lluvia, última hora  ·  azul débil → rojo fuerte");
        }

        private String drawTemperature(Canvas c, int w, int h) {
            if (grid == null || tempField == null) return Ui.tr("Carregando as temperaturas…", "Loading temperatures…", "Cargando temperaturas…");
            bmp.setAlpha(150);
            src.set(0, 0, tempField.getWidth(), tempField.getHeight());
            r.set(0, 0, w, h);
            c.drawBitmap(tempField, src, r, bmp);
            bmp.setAlpha(255);
            mapLabel.setTextAlign(Paint.Align.CENTER);
            for (int row = 0; row < grid.rows; row++) {
                for (int col = 0; col < grid.cols; col++) {
                    double t = grid.temp[row * grid.cols + col];
                    if (Double.isNaN(t)) continue;
                    float x = w * (col + 0.5f) / grid.cols, y = h * (row + 0.5f) / grid.rows;
                    c.drawText(HomeData.degrees(t), x, y, mapLabel);
                }
            }
            mapLabel.setTextAlign(Paint.Align.LEFT);
            return Ui.tr("Temperatura agora  ·  azul frio → vermelho quente", "Temperature now  ·  blue cold → red hot", "Temperatura ahora  ·  azul frío → rojo caliente");
        }

        private String drawWind(Canvas c, int w, int h) {
            if (grid == null) return Ui.tr("Carregando o vento…", "Loading wind…", "Cargando el viento…");
            mapLabel.setTextAlign(Paint.Align.CENTER);
            for (int row = 0; row < grid.rows; row++) {
                for (int col = 0; col < grid.cols; col++) {
                    int i = row * grid.cols + col;
                    double s = grid.wind[i];
                    if (Double.isNaN(s)) continue;
                    float x = w * (col + 0.5f) / grid.cols, y = h * (row + 0.5f) / grid.rows;
                    float len = (float) (30 + Math.min(70, s * 1.6)) * k;
                    drawArrow(c, x, y, len, (float) grid.dir[i] + 180, windColor(s));
                    c.drawText(Math.round(s) + " km/h", x, y + len / 2 + 30 * k, mapLabel);
                }
            }
            mapLabel.setTextAlign(Paint.Align.LEFT);
            return Ui.tr("Vento agora  ·  seta para onde sopra, cor pela velocidade", "Wind now  ·  arrow where it blows, color by speed",
                    "Viento ahora  ·  flecha hacia donde sopla, color por velocidad");
        }

        private static int windColor(double kmh) {
            if (kmh < 10) return 0xFF8BE36C;
            if (kmh < 20) return 0xFFFFD60A;
            if (kmh < 35) return 0xFFFF9F0A;
            return 0xFFFF453A;
        }

        /** A small bitmap of the temperature field (bilinear between the grid points), drawn stretched and blurred. */
        private static Bitmap temperatureField(WeatherFull.Grid g) {
            int fw = 96, fh = 54;
            int[] px = new int[fw * fh];
            for (int y = 0; y < fh; y++) {
                double gy = Math.max(0, Math.min(g.rows - 1.0001, (y + 0.5) / fh * g.rows - 0.5));
                int r0 = (int) gy;
                double fy = gy - r0;
                for (int x = 0; x < fw; x++) {
                    double gx = Math.max(0, Math.min(g.cols - 1.0001, (x + 0.5) / fw * g.cols - 0.5));
                    int c0 = (int) gx;
                    double fx = gx - c0;
                    double a = g.temp[r0 * g.cols + c0], b = g.temp[r0 * g.cols + c0 + 1];
                    double cc = g.temp[(r0 + 1) * g.cols + c0], d = g.temp[(r0 + 1) * g.cols + c0 + 1];
                    double t = (a * (1 - fx) + b * fx) * (1 - fy) + (cc * (1 - fx) + d * fx) * fy;
                    px[y * fw + x] = tempColor(t);
                }
            }
            return Bitmap.createBitmap(px, fw, fh, Bitmap.Config.ARGB_8888);
        }

        private static final double[] STOPS = {-5, 5, 12, 18, 24, 30, 38};
        private static final int[] COLORS = {0xFF8E44FF, 0xFF2D7DFF, 0xFF27C4F5, 0xFF52D273, 0xFFFFE14D, 0xFFFF9330, 0xFFFF3B30};

        private static int tempColor(double t) {
            if (Double.isNaN(t)) return 0;
            if (t <= STOPS[0]) return COLORS[0];
            for (int i = 1; i < STOPS.length; i++) {
                if (t <= STOPS[i]) {
                    float f = (float) ((t - STOPS[i - 1]) / (STOPS[i] - STOPS[i - 1]));
                    int a = COLORS[i - 1], b = COLORS[i];
                    int rr = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * f);
                    int gg = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * f);
                    int bb = Math.round((a & 255) + ((b & 255) - (a & 255)) * f);
                    return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
                }
            }
            return COLORS[COLORS.length - 1];
        }

        // Cities

        private void drawCities(Canvas c, int w) {
            float left = 100 * k, right = w - 100 * k, top = 150 * k, rowH = 120 * k;
            if (searching) {
                drawSearch(c, left, right, top, rowH);
                return;
            }
            int n = cities.size() + 1;
            int first = Math.max(0, Math.min(cityRow - 3, n - 7));
            for (int i = first; i < Math.min(n, first + 7); i++) {
                float y = top + rowH * (i - first);
                boolean on = i == cityRow;
                fill.setColor(on ? (removeArmed ? 0xFFFF453A : 0xF2FFFFFF) : 0x24FFFFFF);
                r.set(left, y, right, y + rowH - 16 * k);
                c.drawRoundRect(r, 26 * k, 26 * k, fill);
                int ink = on && !removeArmed ? 0xFF1C1C1E : 0xFFFFFFFF;
                float mid = y + (rowH - 16 * k) / 2;
                if (i == cities.size()) {
                    Symbols.draw(getContext(), c, "add", left + 50 * k, mid, 32 * k, ink);
                    rowName.setColor(ink);
                    c.drawText(Ui.tr("Adicionar cidade", "Add a city", "Agregar ciudad"), left + 90 * k, mid + Ui.halfCap(rowName), rowName);
                    continue;
                }
                WeatherCities.City ct = cities.get(i);
                rowName.setColor(ink);
                region.setColor(on && !removeArmed ? 0x991C1C1E : 0xB3FFFFFF);
                if (i == 0) Symbols.draw(getContext(), c, "location", left + 46 * k, mid - 18 * k, 24 * k, ink);
                c.drawText(ct.name, left + (i == 0 ? 76 : 40) * k, mid - 6 * k, rowName);
                String sub = removeArmed && on ? Ui.tr("Solte OK para remover", "Release OK to remove", "Suelta OK para quitar")
                        : i == 0 ? Ui.tr("Minha cidade", "My city", "Mi ciudad") : ct.region;
                c.drawText(sub, left + 40 * k, mid + 34 * k, region);
                WeatherFull.Forecast f = cityWeather.get(ct.key());
                if (f != null) {
                    String t = HomeData.degrees(f.now.temp);
                    rowTemp.setColor(ink);
                    c.drawText(t, right - 40 * k - rowTemp.measureText(t), mid + Ui.halfCap(rowTemp), rowTemp);
                    Symbols.draw(getContext(), c, Symbols.weather(f.now.code, f.now.day), right - 220 * k, mid, 44 * k, ink);
                    String cond2 = HomeData.condition(f.now.code, f.now.day);
                    if (!f.days.isEmpty()) cond2 += "  ·  " + HomeData.degrees(f.days.get(0).max) + " / " + HomeData.degrees(f.days.get(0).min);
                    region.setColor(on && !removeArmed ? 0x991C1C1E : 0xB3FFFFFF);
                    c.drawText(cond2, right - 280 * k - region.measureText(cond2), mid + Ui.halfCap(region), region);
                }
            }
            region.setColor(0xB3FFFFFF);
            rowName.setColor(0xFFFFFFFF);
            rowTemp.setColor(0xFFFFFFFF);
        }

        private void drawSearch(Canvas c, float left, float right, float top, float rowH) {
            fill.setColor(0xF2FFFFFF);
            r.set(left, top, right, top + 90 * k);
            c.drawRoundRect(r, 45 * k, 45 * k, fill);
            Symbols.draw(getContext(), c, "search", left + 50 * k, top + 45 * k, 30 * k, 0xFF6E6E73);
            rowName.setColor(query.isEmpty() ? 0xFF8E8E93 : 0xFF1C1C1E);
            c.drawText(query.isEmpty() ? Ui.tr("Digite ou fale o nome da cidade", "Type or say the city's name", "Escribe o di el nombre de la ciudad") : query,
                    left + 90 * k, top + 45 * k + Ui.halfCap(rowName), rowName);
            rowName.setColor(0xFFFFFFFF);
            for (int i = 0; i < Math.min(6, results.size()); i++) {
                float y = top + 120 * k + rowH * i;
                boolean on = i == resultRow;
                fill.setColor(on ? 0xF2FFFFFF : 0x24FFFFFF);
                r.set(left, y, right, y + rowH - 16 * k);
                c.drawRoundRect(r, 26 * k, 26 * k, fill);
                WeatherCities.City ct = results.get(i);
                float mid = y + (rowH - 16 * k) / 2;
                rowName.setColor(on ? 0xFF1C1C1E : 0xFFFFFFFF);
                region.setColor(on ? 0x991C1C1E : 0xB3FFFFFF);
                c.drawText(ct.name, left + 40 * k, mid - 6 * k, rowName);
                c.drawText(ct.region, left + 40 * k, mid + 34 * k, region);
            }
            if (results.isEmpty() && query.trim().length() >= 2) {
                c.drawText(Ui.tr("Buscando…", "Searching…", "Buscando…"), left + 40 * k, top + 170 * k, small);
            }
            rowName.setColor(0xFFFFFFFF);
            region.setColor(0xB3FFFFFF);
        }
    }
}
