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
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Esthetis tab: six chart cards (recurring revenue, subscriptions, new users, site visitors from GA4, Google
 * clicks and Search Console position) from the Esthetis API's TV dashboard (GET /api/tv/esthetis-dashboard, header
 * x-tv-token, the same token as the revenue card). Only aggregate numbers come back.
 */
final class EsthetisBoard {
    static final int PURPLE = 0xFFB07CFF, BLUE = 0xFF5AC8FA, ORANGE = 0xFFFF9F0A, GREEN = 0xFF30D158;
    private static final long EVERY = 15 * 60_000L;   // the API caches it for 15 min too

    /** One card: what it shows on the tab and, bigger, in its chart (ChartActivity). */
    static final class Card {
        String title = "", value = "—", sub = "", first = "", last = "";
        double change = Double.NaN;
        double[] series = new double[0];
        boolean bars, reais;
        int color = PURPLE;
        String missing;     // why there's no data (GA4 or Search Console not connected)
    }

    private static EsthetisBoard instance;

    static synchronized EsthetisBoard get() {
        if (instance == null) instance = new EsthetisBoard();
        return instance;
    }

    volatile List<Card> cards = Collections.emptyList();
    volatile boolean failed;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> listeners = new ArrayList<>();
    private volatile long fetchedAt;
    private volatile boolean fetching;

    void addListener(Runnable l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Runnable l) { listeners.remove(l); }

    void refreshIfStale() {
        if (!EsthetisMrr.enabled()) return;
        long now = System.currentTimeMillis();
        if (fetching || (now - fetchedAt < EVERY && now >= fetchedAt)) return;
        fetching = true;
        io.execute(() -> {
            try {
                cards = parse(fetch());
                failed = false;
                fetchedAt = System.currentTimeMillis();
            } catch (Exception e) {
                AppLog.w("esthetis board: " + e.getMessage());
                failed = cards.isEmpty();
                fetchedAt = System.currentTimeMillis() - EVERY + 60_000L;   // again in a minute
            } finally {
                fetching = false;
                main.post(() -> { for (Runnable l : new ArrayList<>(listeners)) l.run(); });
            }
        });
    }

    static List<Card> parse(String body) throws Exception {
        JSONObject d = new JSONObject(body).getJSONObject("data");
        List<Card> out = new ArrayList<>();

        JSONObject mrr = d.getJSONObject("mrr");
        Card c = new Card();
        c.title = Ui.tr("Receita recorrente", "Recurring revenue", "Ingreso recurrente");
        c.value = EsthetisMrr.reais(mrr.getLong("value"));
        c.reais = true;
        c.change = num(mrr, "change");
        c.sub = mrr.optInt("paying") + Ui.tr(" assinaturas pagantes", " paying subscriptions", " suscripciones pagas");
        JSONArray h = mrr.getJSONArray("history");
        c.series = new double[h.length()];
        for (int i = 0; i < h.length(); i++) c.series[i] = h.getJSONObject(i).getLong("mrr") / 100.0;
        if (h.length() > 0) {
            c.first = h.getJSONObject(0).optString("label");
            c.last = h.getJSONObject(h.length() - 1).optString("label");
        }
        c.bars = true;
        out.add(c);

        JSONObject subs = d.getJSONObject("subscriptions");
        c = new Card();
        c.title = Ui.tr("Assinaturas", "Subscriptions", "Suscripciones");
        c.value = subs.optInt("active") + Ui.tr(" ativas", " active", " activas");
        // New and canceled in the last 30 days.
        c.sub = "+" + subs.optInt("new30") + Ui.tr(" novas · −", " new · −", " nuevas · −") + subs.optInt("canceled30")
                + Ui.tr(" canceladas · ", " canceled · ", " canceladas · ") + subs.optInt("trials") + Ui.tr(" em trial", " in trial", " en prueba");
        daily(c, subs.getJSONArray("daily"));
        c.bars = true;
        c.color = GREEN;
        out.add(c);

        JSONObject users = d.getJSONObject("users");
        c = new Card();
        c.title = Ui.tr("Novos usuários", "New users", "Nuevos usuarios");
        c.value = format(users.optInt("new30"));
        c.change = num(users, "change");
        c.sub = Ui.tr("em 30 dias · ", "in 30 days · ", "en 30 días · ") + format(users.optInt("active")) + Ui.tr(" ativos", " active", " activos");
        daily(c, users.getJSONArray("daily"));
        c.bars = true;
        c.color = BLUE;
        out.add(c);

        JSONObject ga4 = d.optJSONObject("ga4");
        c = new Card();
        c.title = Ui.tr("Visitantes do site", "Site visitors", "Visitantes del sitio");
        c.color = ORANGE;
        if (ga4 == null) {
            c.missing = Ui.tr("Conecte o GA4 no admin", "Connect GA4 in the admin", "Conecta GA4 en el admin");
        } else {
            c.value = format(ga4.optDouble("activeUsers", 0));
            c.change = num(ga4, "change");
            c.sub = format(ga4.optDouble("sessions", 0)) + Ui.tr(" sessões · GA4, 30 dias", " sessions · GA4, 30 days", " sesiones · GA4, 30 días");
            daily(c, ga4.getJSONArray("daily"));
        }
        out.add(c);

        JSONObject search = d.optJSONObject("search");
        c = new Card();
        c.title = Ui.tr("Cliques no Google", "Google clicks", "Clics en Google");
        c.color = BLUE;
        Card pos = new Card();
        pos.title = Ui.tr("Impressões no Google", "Google impressions", "Impresiones en Google");
        pos.color = PURPLE;
        if (search == null) {
            c.missing = pos.missing = Ui.tr("Conecte o Search Console", "Connect Search Console", "Conecta Search Console");
        } else {
            c.value = format(search.optDouble("clicks", 0));
            c.change = num(search, "change");
            c.sub = Ui.tr("Search Console, 30 dias", "Search Console, 30 days", "Search Console, 30 días");
            daily(c, search.getJSONArray("daily"));
            pos.value = format(search.optDouble("impressions", 0));
            NumberFormat f = NumberFormat.getNumberInstance(Ui.PT);
            f.setMaximumFractionDigits(1);
            f.setMinimumFractionDigits(1);
            pos.sub = Ui.tr("CTR ", "CTR ", "CTR ") + f.format(search.optDouble("ctr", 0) * 100) + "% · "
                    + Ui.tr("posição média ", "average position ", "posición media ") + f.format(search.optDouble("position", 0));
            daily(pos, search.getJSONArray("impressionsDaily"));
        }
        out.add(c);
        out.add(pos);
        return out;
    }

    private static void daily(Card c, JSONArray days) throws Exception {
        c.series = new double[days.length()];
        for (int i = 0; i < days.length(); i++) c.series[i] = days.getJSONObject(i).optDouble("value", 0);
        if (days.length() > 0) {
            c.first = shortDate(days.getJSONObject(0).optString("date"));
            c.last = shortDate(days.getJSONObject(days.length() - 1).optString("date"));
        }
    }

    /** "2026-10-20" → "20/10". */
    private static String shortDate(String iso) {
        return iso.length() == 10 ? iso.substring(8, 10) + "/" + iso.substring(5, 7) : iso;
    }

    private static double num(JSONObject o, String key) {
        return o.isNull(key) ? Double.NaN : o.optDouble(key, Double.NaN);
    }

    static String format(double v) {
        return NumberFormat.getIntegerInstance(Ui.PT).format(Math.round(v));
    }

    private static String fetch() throws IOException {
        String base = Config.ESTHETIS_API.trim().isEmpty() ? "https://api.esthetis.app" : Config.ESTHETIS_API.trim();
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/api/tv/esthetis-dashboard").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(60000);    // the first call after the cache expires asks Google too
        c.setRequestProperty("x-tv-token", Config.ESTHETIS_TV_TOKEN.trim());
        c.setRequestProperty("User-Agent", "FreeMyTV");
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
