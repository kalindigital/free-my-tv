package io.freemytv;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The Markets tab: Brazilian and US indexes, the dollar and the euro in reais, and crypto, with the day's change and
 * chart. Indexes and currencies from Yahoo Finance's public chart endpoint, crypto in reais from Binance (Yahoo has no
 * BTC-BRL); neither needs a key. Every 5 minutes while the tab is on screen.
 */
final class Market {
    static final class Asset {
        final String symbol, name;
        final boolean reais;    // a price in R$ (else index points)
        Asset(String symbol, String name, boolean reais) { this.symbol = symbol; this.name = name; this.reais = reais; }
    }

    static final Asset[] ASSETS = {
        new Asset("^BVSP", "Ibovespa", false), new Asset("BRL=X", "Dólar", true),
        new Asset("EURBRL=X", "Euro", true), new Asset("BTCBRL", "Bitcoin", true),
        new Asset("ETHBRL", "Ethereum", true), new Asset("^IXIC", "Nasdaq", false),
        new Asset("^GSPC", "S&P 500", false), new Asset("^DJI", "Dow Jones", false),
    };

    /** Chart periods: label, Yahoo range and interval, Binance interval and number of points. */
    static final String[][] RANGES = {
        {"1D", "1d", "5m", "5m", "288"}, {"5D", "5d", "30m", "30m", "240"}, {"1M", "1mo", "1d", "4h", "180"},
        {"6M", "6mo", "1d", "1d", "183"}, {"1A", "1y", "1d", "1d", "365"}, {"5A", "5y", "1wk", "1w", "260"},
    };

    static final class Quote {
        double price = Double.NaN, change = Double.NaN;   // change in %, against the previous close (1D) or the first point
        long[] times = new long[0];
        double[] closes = new double[0];
    }

    private static final long EVERY = 5 * 60_000L;
    private static Market instance;

    static synchronized Market get() {
        if (instance == null) instance = new Market();
        return instance;
    }

    final Quote[] quotes = new Quote[ASSETS.length];
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> listeners = new ArrayList<>();
    private volatile long fetchedAt;
    private volatile boolean fetching;

    private Market() {
        for (int i = 0; i < quotes.length; i++) quotes[i] = new Quote();
    }

    void addListener(Runnable l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Runnable l) { listeners.remove(l); }
    long fetchedAt() { return fetchedAt; }

    void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (fetching || (now - fetchedAt < EVERY && now >= fetchedAt)) return;
        fetching = true;
        io.execute(() -> {
            try {
                boolean any = false;
                for (int i = 0; i < ASSETS.length; i++) {
                    try {
                        quotes[i] = fetch(ASSETS[i].symbol, RANGES[0]);
                        any = true;
                    } catch (Exception e) {
                        AppLog.w("market " + ASSETS[i].symbol + ": " + e.getMessage());
                    }
                }
                if (any) fetchedAt = System.currentTimeMillis();
                main.post(() -> { for (Runnable l : new ArrayList<>(listeners)) l.run(); });
            } finally {
                fetching = false;
            }
        });
    }

    /** One chart, in the background; the answer comes on the main thread (null if it failed). */
    void chart(int asset, int range, Consumer<Quote> done) {
        io.execute(() -> {
            Quote q = null;
            try {
                q = fetch(ASSETS[asset].symbol, RANGES[range]);
            } catch (Exception e) {
                AppLog.w("market chart: " + e.getMessage());
            }
            final Quote out = q;
            main.post(() -> done.accept(out));
        });
    }

    static Quote fetch(String symbol, String[] range) throws Exception {
        if (symbol.endsWith("BRL") && !symbol.contains("=")) return parseKlines(get("https://api.binance.com/api/v3/klines?symbol="
                + symbol + "&interval=" + range[3] + "&limit=" + range[4]));
        String base = "https://query1.finance.yahoo.com/v8/finance/chart/" + URLEncoder.encode(symbol, "UTF-8") + "?range=";
        boolean day = "1d".equals(range[1]);
        Quote q = parse(get(base + range[1] + "&interval=" + range[2]), day);
        if (day && q.closes.length < 2) {
            // Currencies come without the day's points; the last 24 h of the 5-day chart instead (same change).
            Quote week = parse(get(base + "5d&interval=15m"), false);
            int from = 0;
            long since = System.currentTimeMillis() - 24 * 3600_000L;
            while (from < week.times.length && week.times[from] < since) from++;
            q.times = java.util.Arrays.copyOfRange(week.times, from, week.times.length);
            q.closes = java.util.Arrays.copyOfRange(week.closes, from, week.closes.length);
        }
        return q;
    }

    /** Binance klines ([open time, open, high, low, close, ...]): the change is against the first point's open. */
    static Quote parseKlines(String body) throws Exception {
        JSONArray rows = new JSONArray(body);
        Quote q = new Quote();
        q.times = new long[rows.length()];
        q.closes = new double[rows.length()];
        for (int i = 0; i < rows.length(); i++) {
            JSONArray k = rows.getJSONArray(i);
            q.times[i] = k.getLong(0);
            q.closes[i] = Double.parseDouble(k.getString(4));
        }
        if (rows.length() > 0) {
            double open = Double.parseDouble(rows.getJSONArray(0).getString(1));
            q.price = q.closes[q.closes.length - 1];
            q.change = open > 0 ? (q.price - open) / open * 100 : Double.NaN;
        }
        return q;
    }

    static Quote parse(String body, boolean day) throws Exception {
        JSONObject r = new JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0);
        JSONObject meta = r.getJSONObject("meta");
        Quote q = new Quote();
        JSONArray ts = r.optJSONArray("timestamp");
        JSONArray closes = null;
        JSONObject ind = r.optJSONObject("indicators");
        if (ind != null && ind.optJSONArray("quote") != null) closes = ind.getJSONArray("quote").getJSONObject(0).optJSONArray("close");
        List<Long> t = new ArrayList<>();
        List<Double> c = new ArrayList<>();
        for (int i = 0; ts != null && closes != null && i < Math.min(ts.length(), closes.length()); i++) {
            if (closes.isNull(i)) continue;
            t.add(ts.getLong(i) * 1000);
            c.add(closes.getDouble(i));
        }
        q.times = new long[t.size()];
        q.closes = new double[c.size()];
        for (int i = 0; i < t.size(); i++) {
            q.times[i] = t.get(i);
            q.closes[i] = c.get(i);
        }
        q.price = meta.optDouble("regularMarketPrice", q.closes.length > 0 ? q.closes[q.closes.length - 1] : Double.NaN);
        double base = day ? meta.optDouble("chartPreviousClose", Double.NaN) : (q.closes.length > 0 ? q.closes[0] : Double.NaN);
        q.change = base > 0 && !Double.isNaN(q.price) ? (q.price - base) / base * 100 : Double.NaN;
        return q;
    }

    /** "R$ 5,02", "R$ 450.120" or "208.296 pts". */
    static String format(Asset a, double v) {
        if (Double.isNaN(v)) return "—";
        NumberFormat f = NumberFormat.getNumberInstance(Ui.PT);
        int decimals = v >= 10_000 ? 0 : v >= 100 ? 1 : 2;
        if (!a.reais) decimals = v >= 1_000 ? 0 : 2;
        f.setMinimumFractionDigits(decimals);
        f.setMaximumFractionDigits(decimals);
        return a.reais ? "R$ " + f.format(v) : f.format(v) + " pts";
    }

    static String percent(double change) {
        if (Double.isNaN(change)) return "";
        NumberFormat f = NumberFormat.getNumberInstance(Ui.PT);
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return (change >= 0 ? "▲ " : "▼ ") + f.format(Math.abs(change)) + "%";
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }
}
