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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Esthetis's monthly recurring revenue for the Explore tab card: now, the change against last month and the last 12
 * months, from the Esthetis API's TV summary (GET /api/tv/esthetis-mrr, header x-tv-token). Only aggregate numbers
 * come back. Off unless config.properties has esthetis_tv_token.
 */
final class EsthetisMrr {
    private static final long EVERY = 10 * 60_000L;     // the API caches it for 10 min too
    private static EsthetisMrr instance;

    static synchronized EsthetisMrr get() {
        if (instance == null) instance = new EsthetisMrr();
        return instance;
    }

    static boolean enabled() {
        return !Config.ESTHETIS_TV_TOKEN.trim().isEmpty();
    }

    volatile long mrr = -1, previous = -1;   // centavos; -1 = not loaded
    volatile double change = Double.NaN;
    volatile int paying;
    volatile long[] history = new long[0];
    volatile String[] labels = new String[0];
    volatile boolean failed;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> listeners = new ArrayList<>();
    private volatile long fetchedAt;
    private volatile boolean fetching;

    void addListener(Runnable l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Runnable l) { listeners.remove(l); }

    void refreshIfStale() {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        if (fetching || (now - fetchedAt < EVERY && now >= fetchedAt)) return;
        fetching = true;
        io.execute(() -> {
            try {
                parse(fetch());
                failed = false;
                fetchedAt = System.currentTimeMillis();
            } catch (Exception e) {
                AppLog.w("esthetis mrr: " + e.getMessage());
                failed = mrr < 0;
                fetchedAt = System.currentTimeMillis() - EVERY + 60_000L;   // try again in a minute
            } finally {
                fetching = false;
                main.post(() -> { for (Runnable l : new ArrayList<>(listeners)) l.run(); });
            }
        });
    }

    private void parse(String body) throws Exception {
        JSONObject d = new JSONObject(body).getJSONObject("data");
        JSONArray h = d.getJSONArray("history");
        long[] values = new long[h.length()];
        String[] names = new String[h.length()];
        for (int i = 0; i < h.length(); i++) {
            values[i] = h.getJSONObject(i).getLong("mrr");
            names[i] = h.getJSONObject(i).optString("label");
        }
        history = values;
        labels = names;
        previous = d.optLong("previousMonthMrr", -1);
        change = d.isNull("change") ? Double.NaN : d.optDouble("change", Double.NaN);
        paying = d.optInt("paying");
        mrr = d.getLong("mrr");
    }

    private static String fetch() throws IOException {
        String base = Config.ESTHETIS_API.trim().isEmpty() ? "https://api.esthetis.app" : Config.ESTHETIS_API.trim();
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/api/tv/esthetis-mrr").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("x-tv-token", Config.ESTHETIS_TV_TOKEN.trim());
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    /** "R$ 776" (whole reais). */
    static String reais(long cents) {
        if (cents < 0) return "—";
        java.text.NumberFormat f = java.text.NumberFormat.getNumberInstance(Ui.PT);
        f.setMaximumFractionDigits(0);
        return "R$ " + f.format(Math.round(cents / 100.0));
    }
}
