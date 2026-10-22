package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Everything the Weather screen shows for a city, from Open-Meteo (no key): now (with feels-like, humidity, wind,
 * pressure, visibility, UV, dew point), the next 24 hours and the next 10 days. Also the grid of points behind the
 * temperature and wind maps, and RainViewer's radar frames. Blocking calls: run them off the main thread.
 */
final class WeatherFull {
    private WeatherFull() {}

    static final class Now {
        double temp, feels, humidity, dew, wind, windDir, gust, pressure, visibility, uv, precip;
        int code;
        boolean day = true;
    }

    static final class Hour {
        long at;
        double temp, precipProb, precip;
        int code;
        boolean day;
    }

    static final class Day {
        long date, sunrise, sunset;
        double max, min, precipSum, precipProb, uvMax, windMax;
        int code;
    }

    static final class Forecast {
        final Now now = new Now();
        final List<Hour> hours = new ArrayList<>();
        final List<Day> days = new ArrayList<>();
        long fetchedAt;
    }

    private static final long CACHE_MS = 15 * 60_000L;
    private static final Map<String, Forecast> CACHE = new HashMap<>();

    static Forecast forecast(WeatherCities.City city) throws Exception {
        synchronized (CACHE) {
            Forecast f = CACHE.get(city.key());
            if (f != null && System.currentTimeMillis() - f.fetchedAt < CACHE_MS) return f;
        }
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + city.lat + "&longitude=" + city.lon
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,dew_point_2m,precipitation,weather_code,"
                + "wind_speed_10m,wind_direction_10m,wind_gusts_10m,pressure_msl,visibility,uv_index,is_day"
                + "&hourly=temperature_2m,precipitation_probability,precipitation,weather_code,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,"
                + "sunrise,sunset,uv_index_max,wind_speed_10m_max&timezone=auto&forecast_days=10&wind_speed_unit=kmh";
        Forecast f = parse(get(url));
        synchronized (CACHE) { CACHE.put(city.key(), f); }
        return f;
    }

    static Forecast parse(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        TimeZone zone = TimeZone.getTimeZone(o.optString("timezone", TimeZone.getDefault().getID()));
        Forecast f = new Forecast();
        JSONObject c = o.getJSONObject("current");
        Now n = f.now;
        n.temp = c.optDouble("temperature_2m");
        n.feels = c.optDouble("apparent_temperature");
        n.humidity = c.optDouble("relative_humidity_2m");
        n.dew = c.optDouble("dew_point_2m");
        n.precip = c.optDouble("precipitation", 0);
        n.code = c.optInt("weather_code");
        n.wind = c.optDouble("wind_speed_10m");
        n.windDir = c.optDouble("wind_direction_10m");
        n.gust = c.optDouble("wind_gusts_10m");
        n.pressure = c.optDouble("pressure_msl");
        n.visibility = c.optDouble("visibility");
        n.uv = c.optDouble("uv_index", 0);
        n.day = c.optInt("is_day", 1) == 1;

        JSONObject h = o.getJSONObject("hourly");
        JSONArray times = h.getJSONArray("time");
        long now = System.currentTimeMillis() - 3600_000L;   // from the current hour on
        for (int i = 0; i < times.length() && f.hours.size() < 25; i++) {
            long at = time(times.getString(i), zone, "yyyy-MM-dd'T'HH:mm");
            if (at < now) continue;
            Hour x = new Hour();
            x.at = at;
            x.temp = h.getJSONArray("temperature_2m").optDouble(i);
            x.precipProb = h.getJSONArray("precipitation_probability").optDouble(i, 0);
            x.precip = h.getJSONArray("precipitation").optDouble(i, 0);
            x.code = h.getJSONArray("weather_code").optInt(i);
            x.day = h.getJSONArray("is_day").optInt(i, 1) == 1;
            f.hours.add(x);
        }

        JSONObject d = o.getJSONObject("daily");
        JSONArray dates = d.getJSONArray("time");
        for (int i = 0; i < dates.length(); i++) {
            Day x = new Day();
            x.date = time(dates.getString(i) + "T12:00", zone, "yyyy-MM-dd'T'HH:mm");
            x.code = d.getJSONArray("weather_code").optInt(i);
            x.max = d.getJSONArray("temperature_2m_max").optDouble(i);
            x.min = d.getJSONArray("temperature_2m_min").optDouble(i);
            x.precipSum = d.getJSONArray("precipitation_sum").optDouble(i, 0);
            x.precipProb = d.getJSONArray("precipitation_probability_max").optDouble(i, 0);
            x.sunrise = time(d.getJSONArray("sunrise").optString(i), zone, "yyyy-MM-dd'T'HH:mm");
            x.sunset = time(d.getJSONArray("sunset").optString(i), zone, "yyyy-MM-dd'T'HH:mm");
            x.uvMax = d.getJSONArray("uv_index_max").optDouble(i, 0);
            x.windMax = d.getJSONArray("wind_speed_10m_max").optDouble(i, 0);
            f.days.add(x);
        }
        f.fetchedAt = System.currentTimeMillis();
        return f;
    }

    private static long time(String iso, TimeZone zone, String pattern) throws ParseException {
        if (iso == null || iso.isEmpty()) return 0;
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setTimeZone(zone);
        return f.parse(iso).getTime();
    }

    // MARK: - Map grid (temperature and wind)

    static final class Grid {
        final int cols, rows;
        final double north, south, west, east;
        final double[] temp, wind, dir;
        Grid(int cols, int rows, double north, double south, double west, double east) {
            this.cols = cols; this.rows = rows; this.north = north; this.south = south; this.west = west; this.east = east;
            temp = new double[cols * rows];
            wind = new double[cols * rows];
            dir = new double[cols * rows];
        }
    }

    /** Current temperature and wind at cols × rows points over the box (one request for all of them). */
    static Grid grid(int cols, int rows, double north, double south, double west, double east) throws Exception {
        Grid g = new Grid(cols, rows, north, south, west, east);
        StringBuilder lats = new StringBuilder(), lons = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                double lat = north - (north - south) * r / (rows - 1.0), lon = west + (east - west) * c / (cols - 1.0);
                if (lats.length() > 0) { lats.append(','); lons.append(','); }
                lats.append(String.format(Locale.US, "%.3f", lat));
                lons.append(String.format(Locale.US, "%.3f", lon));
            }
        }
        String body = get("https://api.open-meteo.com/v1/forecast?latitude=" + lats + "&longitude=" + lons
                + "&current=temperature_2m,wind_speed_10m,wind_direction_10m&wind_speed_unit=kmh");
        JSONArray a = body.trim().startsWith("[") ? new JSONArray(body) : new JSONArray().put(new JSONObject(body));
        for (int i = 0; i < Math.min(a.length(), cols * rows); i++) {
            JSONObject c = a.getJSONObject(i).getJSONObject("current");
            g.temp[i] = c.optDouble("temperature_2m");
            g.wind[i] = c.optDouble("wind_speed_10m");
            g.dir[i] = c.optDouble("wind_direction_10m");
        }
        return g;
    }

    // MARK: - Radar (RainViewer, no key)

    static final class Radar {
        String host = "";
        final List<String> paths = new ArrayList<>();
        final List<Long> times = new ArrayList<>();
    }

    /** The last `max` past radar frames (10 min apart), oldest first. */
    static Radar radar(int max) throws Exception {
        JSONObject o = new JSONObject(get("https://api.rainviewer.com/public/weather-maps.json"));
        Radar r = new Radar();
        r.host = o.getString("host");
        JSONArray past = o.getJSONObject("radar").getJSONArray("past");
        for (int i = Math.max(0, past.length() - max); i < past.length(); i++) {
            r.paths.add(past.getJSONObject(i).getString("path"));
            r.times.add(past.getJSONObject(i).getLong("time") * 1000);
        }
        return r;
    }

    static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
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
