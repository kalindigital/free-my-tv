package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Map tiles for the Weather screen: the dark base map and its labels (Esri's Dark Gray Canvas, no key; Carto now asks
 * for one) and the radar (RainViewer), in Web Mercator, kept in a
 * small LRU (the TV has 2 GB). Radar tiles are decoded at half size: rain is blurry anyway, and an hour of frames
 * would not fit otherwise.
 */
final class MapTiles {
    static final int TILE = 256;

    interface Listener { void onTile(); }

    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(28 << 20) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };
    private final Set<String> loading = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> missing = Collections.synchronizedSet(new HashSet<>());
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;

    MapTiles(Listener listener) { this.listener = listener; }

    static double x(double lon, int z) { return (lon + 180) / 360 * TILE * (1 << z); }

    static double y(double lat, int z) {
        double r = Math.toRadians(lat);
        return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * TILE * (1 << z);
    }

    static double lon(double x, int z) { return x / (TILE * (1 << z)) * 360 - 180; }

    static double lat(double y, int z) {
        double n = Math.PI - 2 * Math.PI * y / (TILE * (1 << z));
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    private static final String ESRI = "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/";

    Bitmap base(int z, int x, int y) {
        int max = 1 << z;
        if (y < 0 || y >= max) return null;
        x = ((x % max) + max) % max;
        return get("b/" + z + "/" + x + "/" + y, ESRI + "World_Dark_Gray_Base/MapServer/tile/" + z + "/" + y + "/" + x, false);
    }

    /** Place names over everything else (transparent tiles). */
    Bitmap labels(int z, int x, int y) {
        int max = 1 << z;
        if (y < 0 || y >= max) return null;
        x = ((x % max) + max) % max;
        return get("l/" + z + "/" + x + "/" + y, ESRI + "World_Dark_Gray_Reference/MapServer/tile/" + z + "/" + y + "/" + x, true, false);
    }

    Bitmap radar(String host, String path, int z, int x, int y) {
        int max = 1 << z;
        if (y < 0 || y >= max) return null;
        x = ((x % max) + max) % max;
        return get("r" + path + "/" + z + "/" + x + "/" + y, host + path + "/256/" + z + "/" + x + "/" + y + "/2/1_1.png", true, true);
    }

    private Bitmap get(String key, String url, boolean alpha) {
        return get(key, url, alpha, false);
    }

    private Bitmap get(String key, String url, boolean alpha, boolean half) {
        Bitmap b = cache.get(key);
        if (b != null || missing.contains(key) || !loading.add(key)) return b;
        io.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(12000);
                c.setRequestProperty("User-Agent", "FreeMyTV (Android TV home screen)");
                try {
                    if (c.getResponseCode() != 200) {
                        missing.add(key);
                        return;
                    }
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    try (InputStream in = c.getInputStream()) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    if (half) o.inSampleSize = 2;
                    if (!alpha) o.inPreferredConfig = Bitmap.Config.RGB_565;
                    byte[] bytes = out.toByteArray();
                    Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                    if (bmp == null) missing.add(key);
                    else {
                        cache.put(key, bmp);
                        main.post(listener::onTile);
                    }
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                missing.add(key);
            } finally {
                loading.remove(key);
            }
        });
        return null;
    }

    void release() {
        io.shutdownNow();
        cache.evictAll();
    }
}
