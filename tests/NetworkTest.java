package io.freemytv;

/** Plain-Java test for Network.ssid: sh tests/run.sh */
public final class NetworkTest {
    public static void main(String[] args) {
        check(Network.ssid("\"AP 504\"").equals("AP 504"), "quotes come off");
        check(Network.ssid("<unknown ssid>").equals("Wi-Fi"), "hidden name");
        check(Network.ssid(null).equals("Wi-Fi"), "no info");
        check(Network.ssid("semaspas").equals("semaspas"), "no quotes");
        System.out.println("NetworkTest OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
