package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screensaver's photos, without any key: landscapes from Wikimedia Commons' featured pictures and space from NASA's
 * image library. One photo at a time in memory, decoded near the screen's size off the main thread.
 */
final class DreamPhotos {
    static final String LANDSCAPE = "landscape", SPACE = "space";
    private static final String[] SPACE_QUERIES = {"galaxy", "nebula", "milky way", "earth from space", "hubble"};

    interface Listener { void onPhoto(Bitmap photo); }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final List<String> urls = new ArrayList<>();
    private String urlsFor = "";
    private int next;

    /** Fetches the next photo of `kind` at about `width` px wide; the listener hears on the main thread (null: none). */
    void load(final String kind, final int width, final Listener listener) {
        io.execute(() -> {
            Bitmap b = null;
            try {
                if (!kind.equals(urlsFor) || urls.isEmpty()) {
                    urls.clear();
                    urls.addAll(SPACE.equals(kind) ? space() : landscapes(width));
                    Collections.shuffle(urls, random);
                    urlsFor = kind;
                    next = 0;
                }
                for (int tries = 0; b == null && tries < 3 && !urls.isEmpty(); tries++) {
                    String url = urls.get(next++ % urls.size());
                    b = decode(bytes(url), width);
                }
            } catch (Exception e) {
                AppLog.w("dream photo: " + e);
            }
            final Bitmap photo = b;
            main.post(() -> listener.onPhoto(photo));
        });
    }

    private List<String> landscapes(int width) throws Exception {
        String api = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=categorymembers"
                + "&gcmtitle=Category:Featured_pictures_of_landscapes&gcmtype=file&gcmlimit=60&gcmsort=timestamp&gcmdir=desc"
                + "&prop=imageinfo&iiprop=url|size&iiurlwidth=" + width;
        JSONObject pages = new JSONObject(text(api)).getJSONObject("query").getJSONObject("pages");
        List<String> out = new ArrayList<>();
        for (java.util.Iterator<String> it = pages.keys(); it.hasNext(); ) {
            JSONArray info = pages.getJSONObject(it.next()).optJSONArray("imageinfo");
            JSONObject i = info == null ? null : info.optJSONObject(0);
            if (i != null && wide(i.optInt("width"), i.optInt("height")) && !i.optString("thumburl").isEmpty()) {
                out.add(i.optString("thumburl"));
            }
        }
        return out;
    }

    private List<String> space() throws Exception {
        String q = SPACE_QUERIES[random.nextInt(SPACE_QUERIES.length)];
        String api = "https://images-api.nasa.gov/search?media_type=image&page_size=60&q=" + URLEncoder.encode(q, "UTF-8");
        JSONArray items = new JSONObject(text(api)).getJSONObject("collection").getJSONArray("items");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONArray links = items.getJSONObject(i).optJSONArray("links");
            JSONObject l = links == null ? null : links.optJSONObject(0);
            if (l == null) continue;
            // NASA gives no sizes for its previews; a too-tall one would be cropped to a sliver, so skip those.
            if (l.has("width") && l.has("height") && !wide(l.optInt("width"), l.optInt("height"))) continue;
            String large = nasaLarge(l.optString("href"));
            if (!large.isEmpty()) out.add(large);
        }
        return out;
    }

    /** A photo that fills a 16:9 screen without losing most of it: from 4:3 to a bit past 2:1. */
    static boolean wide(int w, int h) {
        if (w <= 0 || h <= 0) return false;
        float r = w / (float) h;
        return r >= 1.3f && r <= 2.2f;
    }

    /** NASA's preview ("…~thumb.jpg", "~small", "~medium") as its large size ("…~large.jpg"); "" if it isn't one. */
    static String nasaLarge(String href) {
        int at = href.lastIndexOf('~');
        if (at < 0 || !href.endsWith(".jpg")) return "";
        return href.substring(0, at) + "~large.jpg";
    }

    private static Bitmap decode(byte[] data, int width) {
        if (data == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= width) o.inSampleSize *= 2;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    private static String text(String url) throws IOException {
        return new String(bytes(url), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "FreeMyTV (screensaver)");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " " + url);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
