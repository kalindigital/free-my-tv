package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * The Weather screen's cities: the one in config.properties first (it can't be removed), then the ones added by
 * search (Open-Meteo geocoding, no key), kept in the app's preferences.
 */
final class WeatherCities {
    static final class City {
        final String name, region;
        final double lat, lon;
        City(String name, String region, double lat, double lon) {
            this.name = name; this.region = region == null ? "" : region; this.lat = lat; this.lon = lon;
        }
        String key() { return Math.round(lat * 100) + "," + Math.round(lon * 100); }
    }

    private WeatherCities() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("weather-cities", Context.MODE_PRIVATE);
    }

    /** The home city (config.properties) and the added ones, in the order they were added. */
    static List<City> all(Context c) {
        List<City> out = new ArrayList<>();
        String home = Prefs.city().isEmpty() ? Ui.tr("Minha cidade", "My city", "Mi ciudad") : Prefs.city();
        out.add(new City(home, "", Double.parseDouble(Prefs.latitude()), Double.parseDouble(Prefs.longitude())));
        try {
            JSONArray a = new JSONArray(sp(c).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new City(o.getString("name"), o.optString("region"), o.getDouble("lat"), o.getDouble("lon")));
            }
        } catch (Exception e) {
            AppLog.w("weather cities: " + e.getMessage());
        }
        return out;
    }

    static void add(Context c, City city) {
        List<City> list = all(c);
        for (City x : list) if (x.key().equals(city.key())) return;
        list.add(city);
        save(c, list);
    }

    /** Removes an added city (index 0, the home one, stays). */
    static void remove(Context c, int index) {
        List<City> list = all(c);
        if (index <= 0 || index >= list.size()) return;
        list.remove(index);
        save(c, list);
    }

    private static void save(Context c, List<City> list) {
        JSONArray a = new JSONArray();
        try {
            for (int i = 1; i < list.size(); i++) {
                City x = list.get(i);
                a.put(new JSONObject().put("name", x.name).put("region", x.region).put("lat", x.lat).put("lon", x.lon));
            }
        } catch (Exception ignored) {}
        sp(c).edit().putString("list", a.toString()).apply();
    }

    /** Places matching the name (blocking: call off the main thread). */
    static List<City> search(String name) throws Exception {
        String lang = Ui.en ? "en" : Ui.es ? "es" : "pt";
        String body = WeatherFull.get("https://geocoding-api.open-meteo.com/v1/search?count=8&language=" + lang
                + "&name=" + URLEncoder.encode(name.trim(), "UTF-8"));
        JSONArray r = new JSONObject(body).optJSONArray("results");
        List<City> out = new ArrayList<>();
        for (int i = 0; r != null && i < r.length(); i++) {
            JSONObject o = r.getJSONObject(i);
            String region = o.optString("admin1");
            String country = o.optString("country");
            if (!country.isEmpty() && !"BR".equals(o.optString("country_code"))) region = region.isEmpty() ? country : region + ", " + country;
            out.add(new City(o.getString("name"), region, o.getDouble("latitude"), o.getDouble("longitude")));
        }
        return out;
    }
}
