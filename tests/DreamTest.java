package io.freemytv;

/** Plain-Java test for the screensaver's rules: sh tests/run.sh */
public final class DreamTest {
    public static void main(String[] args) {
        check(DreamPhotos.wide(1920, 1080), "16:9");
        check(DreamPhotos.wide(4000, 3000), "4:3");
        check(!DreamPhotos.wide(13000, 1486), "panorama is too wide");
        check(!DreamPhotos.wide(1080, 1920), "portrait");
        check(!DreamPhotos.wide(0, 0), "no size");
        check(DreamPhotos.nasaLarge("https://images-assets.nasa.gov/image/PIA14417/PIA14417~medium.jpg")
                .equals("https://images-assets.nasa.gov/image/PIA14417/PIA14417~large.jpg"), "NASA large");
        check(DreamPhotos.nasaLarge("https://x/y.png").isEmpty(), "not a NASA preview");
        check(DreamBackground.Fluid.hue(350, 5, 4) == 10, "wraps past 360");
        check(DreamBackground.Fluid.hue(10, -5, 4) == 350, "wraps below 0");
        check(DreamBackground.Fluid.hue(100, 2, 0) == 100, "starts where it was picked");
        check(DreamBackground.Fluid.hue(100, 2, 30) != DreamBackground.Fluid.hue(100, 2, 0), "changes with time");
        java.util.List<AerialVideos.Video> videos = java.util.Arrays.asList(
                new AerialVideos.Video("a", "Dubai · dia", "http://x/a.mov"), new AerialVideos.Video("b", "China · noite", "http://x/b.mov"));
        check(AerialVideos.pick(videos, "b", "", new java.util.Random(1)).id.equals("b"), "the chosen video");
        for (int seed = 0; seed < 20; seed++) {
            check(!AerialVideos.pick(videos, AerialVideos.RANDOM, "a", new java.util.Random(seed)).id.equals("a"),
                    "random: never the last one twice in a row");
        }
        check(AerialVideos.pick(videos, "gone", "a", new java.util.Random(2)).id.equals("b"), "chosen one gone: random");
        check(AerialVideos.pick(new java.util.ArrayList<>(), AerialVideos.RANDOM, "", new java.util.Random()) == null, "no list");
        check(WeatherScene.Rules.kind(0) == WeatherScene.CLEAR && WeatherScene.Rules.kind(-1) == WeatherScene.CLEAR, "clear, or no data yet");
        check(WeatherScene.Rules.kind(2) == WeatherScene.PARTLY && WeatherScene.Rules.kind(3) == WeatherScene.CLOUDY, "clouds");
        check(WeatherScene.Rules.kind(45) == WeatherScene.FOG && WeatherScene.Rules.kind(53) == WeatherScene.DRIZZLE, "fog, drizzle");
        check(WeatherScene.Rules.kind(63) == WeatherScene.RAIN && WeatherScene.Rules.kind(81) == WeatherScene.RAIN, "rain and showers");
        check(WeatherScene.Rules.kind(73) == WeatherScene.SNOW && WeatherScene.Rules.kind(86) == WeatherScene.SNOW, "snow");
        check(WeatherScene.Rules.kind(95) == WeatherScene.STORM && WeatherScene.Rules.kind(99) == WeatherScene.STORM, "storm");
        check(WeatherScene.Rules.drops(61) < WeatherScene.Rules.drops(63) && WeatherScene.Rules.drops(63) < WeatherScene.Rules.drops(65), "heavier rain, more drops");
        check(WeatherScene.Rules.drops(0) == 0 && WeatherScene.Rules.drops(3) == 0, "no rain when dry");
        check(WeatherScene.Rules.clouds(WeatherScene.CLEAR) == 0 && WeatherScene.Rules.clouds(WeatherScene.PARTLY) < WeatherScene.Rules.clouds(WeatherScene.RAIN), "cloud cover");
        long rise = 6 * 3_600_000L, set = 19 * 3_600_000L;
        check(WeatherScene.Rules.daylight(12 * 3_600_000L, rise, set) == 1, "noon: full day");
        check(WeatherScene.Rules.daylight(2 * 3_600_000L, rise, set) == 0, "night");
        float atRise = WeatherScene.Rules.daylight(rise, rise, set);
        check(atRise > 0.4f && atRise < 0.6f, "sunrise: halfway");
        check(WeatherScene.Rules.golden(set, rise, set) == 1, "sunset: golden");
        check(WeatherScene.Rules.golden(12 * 3_600_000L, rise, set) == 0, "noon: not golden");
        check(WeatherScene.Rules.progress(rise, rise, set) == 0 && WeatherScene.Rules.progress(set, rise, set) == 1, "sun's arc");
        check(WeatherScene.Rules.cover(WeatherScene.CLEAR) < WeatherScene.Rules.cover(WeatherScene.CLOUDY)
                && WeatherScene.Rules.cover(WeatherScene.CLOUDY) < WeatherScene.Rules.cover(WeatherScene.STORM), "greyer with clouds");
        check(InfoView.Turns.musicControl(android.view.KeyEvent.KEYCODE_DPAD_LEFT) == 0, "◀ previous");
        check(InfoView.Turns.musicControl(android.view.KeyEvent.KEYCODE_DPAD_CENTER) == 1, "OK play/pause");
        check(InfoView.Turns.musicControl(android.view.KeyEvent.KEYCODE_MEDIA_NEXT) == 2, "⏭ next");
        check(InfoView.Turns.musicControl(android.view.KeyEvent.KEYCODE_BACK) == -1, "Back still leaves");
        check(NowPlaying.Format.time(187_000).equals("3:07") && NowPlaying.Format.time(3_723_000).equals("1:02:03"), "song times");
        check(NowPlaying.Format.time(-5).equals("0:00"), "never negative");
        int[] px = new int[25], tmp = new int[25];
        java.util.Arrays.fill(px, 0xFF000000);
        px[12] = 0xFFFFFFFF;    // one white dot in the middle of 5 × 5 black
        Blur.box(px, tmp, 5, 5, 1, 1);
        check((px[12] & 0xFF) > 0 && (px[12] & 0xFF) < 255, "the dot spreads out");
        check((px[11] & 0xFF) > 0 && (px[7] & 0xFF) > 0, "into its neighbors");
        check((px[0] & 0xFF) == 0 && px[0] >>> 24 == 0xFF, "far corner stays black and opaque");
        int[] flat = new int[16], t2 = new int[16];
        java.util.Arrays.fill(flat, 0xFF336699);
        Blur.box(flat, t2, 4, 4, 2, 2);
        check(flat[5] == 0xFF336699, "a flat color stays the same");
        int[] last = {0xFF000000}, now = {0xFFFFFFFF};
        Blur.mix(last, now, 0.5f);
        check((now[0] & 0xFF) > 120 && (now[0] & 0xFF) < 136 && now[0] >>> 24 == 0xFF, "halfway between frames");
        check(InfoView.Turns.marquee(5000, 100, 200, 80, 40) == 0, "fits: no scrolling");
        check(InfoView.Turns.marquee(1000, 400, 200, 80, 40) == 0, "waits before scrolling");
        check(InfoView.Turns.marquee(3500, 400, 200, 80, 40) == 40, "then moves 40 px a second");
        long cycle = 2500 + (long) ((400 + 80) / 40f * 1000);
        check(InfoView.Turns.marquee(cycle + 100, 400, 200, 80, 40) == 0, "and starts over");
        System.out.println("DreamTest OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
