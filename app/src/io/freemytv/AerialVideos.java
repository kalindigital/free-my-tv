package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Apple TV aerial videos (the same as the macOS screensaver), from Apple's public list: 1080p H.264 at 6 Mbps,
 * streamed, never stored. The list is kept in the app's files so Settings can name them offline. Apple serves these
 * only over plain HTTP (res/xml/network_security_config.xml allows that host alone).
 */
final class AerialVideos {
    static final String RANDOM = "random";
    private static final String LIST = "http://a1.phobos.apple.com/us/r1000/000/Features/atv/AutumnResources/videos/entries.json";
    private static final String FILE = "aerial-videos.json";

    static final class Video {
        final String id, name, url;
        Video(String id, String name, String url) { this.id = id; this.name = name; this.url = url; }
    }

    private AerialVideos() {}

    /** The videos from the saved list (empty until {@link #refresh} has run once). */
    static List<Video> saved(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return new ArrayList<>();
        try {
            return parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            AppLog.w("aerial list: " + e);
            return new ArrayList<>();
        }
    }

    /** Fetches Apple's list and saves it (call off the main thread); returns the videos, or the saved ones on failure. */
    static List<Video> refresh(Context c) {
        try {
            String json = text(LIST);
            List<Video> list = parse(json);
            if (!list.isEmpty()) {
                try (FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), FILE))) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
                return list;
            }
        } catch (Exception e) {
            AppLog.w("aerial refresh: " + e);
        }
        return saved(c);
    }

    private static List<Video> parse(String json) throws Exception {
        List<Video> out = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        JSONArray groups = new JSONArray(json);
        for (int g = 0; g < groups.length(); g++) {
            JSONArray assets = groups.getJSONObject(g).getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String url = a.optString("url"), id = a.optString("id");
                if (url.isEmpty() || id.isEmpty()) continue;
                String base = place(a.optString("accessibilityLabel")) + " · " + time(a.optString("timeOfDay"));
                Integer n = seen.get(base);
                seen.put(base, n == null ? 1 : n + 1);
                out.add(new Video(id, n == null ? base : base + " " + (n + 1), url));
            }
        }
        return out;
    }

    /** Apple's place names in the TV's language. */
    static String place(String english) {
        switch (english) {
            case "Hawaii": return Ui.tr("Havaí", "Hawaii", "Hawái");
            case "New York City": return Ui.tr("Nova York", "New York City", "Nueva York");
            case "London": return Ui.tr("Londres", "London", "Londres");
            case "Greenland": return Ui.tr("Groenlândia", "Greenland", "Groenlandia");
            default: return english;    // San Francisco, Dubai, China, Los Angeles, Hong Kong, Liwa
        }
    }

    static String time(String timeOfDay) {
        return "night".equals(timeOfDay) ? Ui.tr("noite", "night", "noche") : Ui.tr("dia", "day", "día");
    }

    /** The video to play: the chosen one, or (random, or the chosen one gone) any other than the last one shown. */
    static Video pick(List<Video> list, String chosen, String last, java.util.Random random) {
        if (list.isEmpty()) return null;
        if (!RANDOM.equals(chosen)) {
            for (Video v : list) if (v.id.equals(chosen)) return v;
        }
        if (list.size() == 1) return list.get(0);
        Video v;
        do {
            v = list.get(random.nextInt(list.size()));
        } while (v.id.equals(last));
        return v;
    }

    private static String text(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
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
