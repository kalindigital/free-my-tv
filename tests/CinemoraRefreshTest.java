package io.freemytv;

/** Plain-Java test for Cinemora.Refresh: sh tests/run.sh */
public final class CinemoraRefreshTest {
    public static void main(String[] args) {
        long t = 1_000_000_000L;
        check(Cinemora.Refresh.isStale(t, 0, true), "never fetched");
        check(!Cinemora.Refresh.isStale(t + 29_000, t, true), "empty: waits under 30 s");
        check(Cinemora.Refresh.isStale(t + 30_000, t, true), "empty: asks again after 30 s");
        check(!Cinemora.Refresh.isStale(t + 30_000, t, false), "with a catalog: keeps it");
        check(!Cinemora.Refresh.isStale(t + 599_000, t, false), "with a catalog: under ten minutes");
        check(Cinemora.Refresh.isStale(t + 600_000, t, false), "with a catalog: after ten minutes");
        check(Cinemora.Refresh.isStale(t - 1, t, false), "clock went back");
        System.out.println("CinemoraRefreshTest OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
