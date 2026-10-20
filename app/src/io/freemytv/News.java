package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The day's top headlines (Google News RSS, no key), every 30 min while the screensaver is on, cached. */
final class News {
    private static final String URL = "https://news.google.com/rss?hl=pt-BR&gl=BR&ceid=BR:pt-419";
    private static final long EVERY = 30 * 60_000L;
    static final int MAX = 5;

    private static News instance;

    static synchronized News get(Context c) {
        if (instance == null) instance = new News(c.getApplicationContext());
        return instance;
    }

    volatile List<Rss.Headline> headlines = Collections.emptyList();
    volatile long fetchedAt;

    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<HomeData.Listener> listeners = new ArrayList<>();
    private volatile boolean fetching;

    private News(Context c) {
        cache = c.getSharedPreferences("news", Context.MODE_PRIVATE);
        try {
            String xml = cache.getString("rss", null);
            if (xml != null) {
                headlines = Rss.parse(xml, MAX);
                fetchedAt = cache.getLong("at", 0);
            }
        } catch (Exception ignored) {}
    }

    void addListener(HomeData.Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(HomeData.Listener l) { listeners.remove(l); }

    void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (fetching || (now - fetchedAt < EVERY && now >= fetchedAt)) return;
        fetching = true;
        io.execute(() -> {
            try {
                String xml = get();
                headlines = Rss.parse(xml, MAX);
                fetchedAt = System.currentTimeMillis();
                cache.edit().putString("rss", xml).putLong("at", fetchedAt).apply();
                main.post(() -> { for (HomeData.Listener l : new ArrayList<>(listeners)) l.onData(); });
            } catch (Exception e) {
                AppLog.w("news: " + e.getMessage());
            } finally {
                fetching = false;
            }
        });
    }

    private static String get() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(URL).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
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
}
