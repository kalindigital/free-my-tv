package io.freemytv;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

/** The name of the network the TV is on, for the home screen: the Wi-Fi's name, "Cabo" on Ethernet. */
final class Network {
    private Network() {}

    static String name(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (caps == null) return Ui.tr("Sem rede", "No network", "Sin red");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return Ui.tr("Cabo", "Ethernet", "Cable");
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return Ui.tr("Conectado", "Connected", "Conectado");
        WifiManager wifi = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
        return ssid(info == null ? null : info.getSSID());
    }

    /** "\"Casa\"" → "Casa"; Android hides the name ("<unknown ssid>") without the location permission. */
    static String ssid(String raw) {
        if (raw == null || raw.isEmpty() || raw.equals("<unknown ssid>")) return "Wi-Fi";
        if (raw.length() > 1 && raw.startsWith("\"") && raw.endsWith("\"")) return raw.substring(1, raw.length() - 1);
        return raw;
    }
}
