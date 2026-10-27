package io.freemytv;

/** Plain-Java test for the screensaver's rules: sh tests/run.sh */
public final class DreamTest {
    public static void main(String[] args) {
        check(!InfoView.Turns.showDays(28_000_000L), "even minute: now and the next hours");
        check(InfoView.Turns.showDays(28_000_001L), "odd minute: the next days");
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
        for (long m = 0; m < 100; m++) {
            int a = InfoView.Turns.shift(m), b = InfoView.Turns.shift(m + 1);
            check(Math.abs(a) <= 2 && Math.abs(a - b) <= 1, "drift moves one step at a time, within 2: " + m);
        }
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
        System.out.println("DreamTest OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
