package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The movies and shows of Cinemora (the Xtream catalog app, br.com.cinemora.tv), when it's installed and signed in:
 * the same category rows it shows, read from its LauncherProvider (titles and posters only; the account and the
 * streams stay in Cinemora). Picking one opens it in Cinemora's own details screen.
 */
final class Cinemora {
    static final String PKG = "br.com.cinemora.tv";
    static final String MOVIE = "movie", SERIES = "series";
    private static final Uri BASE = Uri.parse("content://" + PKG + ".launcher");
    private static final long EVERY = 10 * 60_000L;

    static final class Item {
        final String kind, id, title, year, rating;
        Item(String kind, String id, String title, String year, String rating) {
            this.kind = kind; this.id = id; this.title = title; this.year = year; this.rating = rating;
        }
        String key() { return kind + "/" + id; }
    }

    static final class Row {
        final String title;
        final List<Item> items = new ArrayList<>();
        Row(String title) { this.title = title; }
    }

    interface Listener { void onCinemora(); }

    private static Cinemora instance;

    static synchronized Cinemora get(Context c) {
        if (instance == null) instance = new Cinemora(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService rowsIo = Executors.newSingleThreadExecutor(), coversIo = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> covers = new LruCache<String, Bitmap>(24 << 20) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };
    private final Set<String> loading = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> missing = Collections.synchronizedSet(new HashSet<>());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile List<Row> movies = Collections.emptyList(), series = Collections.emptyList();
    private volatile List<Row> found = Collections.emptyList();
    private volatile String foundFor = "";
    private int searches;
    private volatile long fetchedAt;
    private volatile boolean fetching;
    private int coverW = 220, coverH = 330;

    private Cinemora(Context c) { app = c; }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** The size the posters are kept at (the focused poster's size on screen, so it stays sharp). */
    void setCoverSize(int w, int h) {
        if (w == coverW && h == coverH) return;
        coverW = w;
        coverH = h;
        covers.evictAll();
    }

    /** The rows for a tab ("movie" or "tv"); empty when Cinemora isn't installed, signed out or has no catalog yet. */
    List<Row> rows(String tabKind) {
        return "movie".equals(tabKind) ? movies : "tv".equals(tabKind) ? series : Collections.<Row>emptyList();
    }

    /** Whether Cinemora gave us a catalog (installed, signed in, our permission granted). */
    boolean available() {
        return !movies.isEmpty() || !series.isEmpty();
    }

    /** The results of the last search ("Filmes" and "Séries" rows, those that have any) and what they are for. */
    List<Row> found() { return found; }
    String foundFor() { return foundFor; }

    /** Searches Cinemora's catalog in the background; the listeners hear when the results are in. */
    void search(final String query) {
        final String q = query.trim();
        final int id = ++searches;
        if (q.isEmpty()) {
            found = Collections.emptyList();
            foundFor = "";
            notifyListeners();
            return;
        }
        rowsIo.execute(() -> {
            List<Row> out = new ArrayList<>();
            String[][] kinds = {{MOVIE, Ui.tr("Filmes", "Movies", "Películas")}, {SERIES, Ui.tr("Séries", "Shows", "Series")}};
            for (String[] kind : kinds) {
                Row row = new Row(kind[1]);
                try (Cursor c = app.getContentResolver().query(BASE.buildUpon().appendPath("search").appendPath(kind[0])
                        .appendQueryParameter("q", q).build(), null, null, null, null)) {
                    if (c != null) {
                        int idc = c.getColumnIndex("id"), title = c.getColumnIndex("title"), year = c.getColumnIndex("year"),
                                rating = c.getColumnIndex("rating");
                        while (c.moveToNext()) {
                            row.items.add(new Item(kind[0], c.getString(idc), c.getString(title), c.getString(year), c.getString(rating)));
                        }
                    }
                } catch (RuntimeException e) {
                    AppLog.w("cinemora search: " + e.getMessage());
                }
                if (!row.items.isEmpty()) out.add(row);
            }
            if (id != searches) return;     // a newer search is on its way
            found = out;
            foundFor = q;
            notifyListeners();
        });
    }

    void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (fetching || (now - fetchedAt < EVERY && now >= fetchedAt)) return;
        fetching = true;
        rowsIo.execute(() -> {
            try {
                List<Row> m = query(MOVIE), s = query(SERIES);
                movies = m;
                series = s;
                fetchedAt = System.currentTimeMillis();
                notifyListeners();
            } finally {
                fetching = false;
            }
        });
    }

    private List<Row> query(String kind) {
        List<Row> out = new ArrayList<>();
        try (Cursor c = app.getContentResolver().query(BASE.buildUpon().appendPath("rows").appendPath(kind).build(),
                null, null, null, null)) {
            if (c == null) return out;
            int row = c.getColumnIndex("row"), rowTitle = c.getColumnIndex("row_title"), id = c.getColumnIndex("id"),
                    title = c.getColumnIndex("title"), year = c.getColumnIndex("year"), rating = c.getColumnIndex("rating");
            int last = -1;
            while (c.moveToNext()) {
                if (c.getInt(row) != last) {
                    last = c.getInt(row);
                    out.add(new Row(c.getString(rowTitle)));
                }
                out.get(out.size() - 1).items.add(new Item(kind, c.getString(id), c.getString(title),
                        c.getString(year), c.getString(rating)));
            }
        } catch (RuntimeException e) {
            // Not installed, an older version without the provider, or our permission not granted.
            AppLog.w("cinemora: " + e.getMessage());
        }
        return out;
    }

    /** The poster, or null while it loads (the listeners hear when it's in). */
    Bitmap cover(Item it) {
        String key = it.key();
        Bitmap b = covers.get(key);
        if (b != null || missing.contains(key) || !loading.add(key)) return b;
        final int w = coverW, h = coverH;
        coversIo.execute(() -> {
            try (InputStream in = app.getContentResolver().openInputStream(
                    BASE.buildUpon().appendPath("cover").appendPath(it.kind).appendPath(it.id).build())) {
                Bitmap raw = in == null ? null : BitmapFactory.decodeStream(in);
                if (raw == null) {
                    missing.add(key);
                    return;
                }
                covers.put(key, crop(raw, w, h));
                notifyListeners();
            } catch (Exception e) {
                missing.add(key);
            } finally {
                loading.remove(key);
            }
        });
        return null;
    }

    /** Scaled to fill w×h (2:3), cropped in the middle. */
    private static Bitmap crop(Bitmap raw, int w, int h) {
        float s = Math.max(w / (float) raw.getWidth(), h / (float) raw.getHeight());
        int sw = Math.max(w, Math.round(raw.getWidth() * s)), sh = Math.max(h, Math.round(raw.getHeight() * s));
        Bitmap scaled = Bitmap.createScaledBitmap(raw, sw, sh, true);
        Bitmap out = Bitmap.createBitmap(scaled, (sw - w) / 2, (sh - h) / 2, w, h);
        if (scaled != raw) raw.recycle();
        if (out != scaled) scaled.recycle();
        return out;
    }

    /** Opens the title in Cinemora's details screen. */
    static boolean open(Context c, Item it) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("cinemora://" + PKG + "/" + it.kind + "/" + Uri.encode(it.id)))
                .setPackage(PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
            return true;
        } catch (RuntimeException e) {
            AppLog.w("cinemora open: " + e.getMessage());
            return false;
        }
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onCinemora(); });
    }
}
