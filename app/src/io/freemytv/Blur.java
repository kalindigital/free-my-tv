package io.freemytv;

/**
 * A box blur on ARGB pixels, run twice horizontally and vertically (close to a gaussian). Android 11 has no blur of
 * its own (RenderEffect is Android 12), so the screensaver blurs a small copy of its background with this.
 */
final class Blur {
    private Blur() {}

    /** Blurs `px` (w × h, row by row) in place, `passes` times with this radius; `tmp` must hold w × h too. */
    static void box(int[] px, int[] tmp, int w, int h, int radius, int passes) {
        for (int p = 0; p < passes; p++) {
            line(px, tmp, w, h, radius, true);
            line(tmp, px, w, h, radius, false);
        }
    }

    /** `now` becomes `last` moved `amount` (0..1) of the way toward `now`, pixel by pixel: a smooth step between frames. */
    static void mix(int[] last, int[] now, float amount) {
        int t = Math.round(amount * 256);
        for (int i = 0; i < now.length; i++) {
            int a = last[i], b = now[i];
            int ch0 = (a >>> 24) + (((b >>> 24) - (a >>> 24)) * t >> 8);
            int ch1 = (a >> 16 & 0xFF) + (((b >> 16 & 0xFF) - (a >> 16 & 0xFF)) * t >> 8);
            int ch2 = (a >> 8 & 0xFF) + (((b >> 8 & 0xFF) - (a >> 8 & 0xFF)) * t >> 8);
            int ch3 = (a & 0xFF) + (((b & 0xFF) - (a & 0xFF)) * t >> 8);
            now[i] = ch0 << 24 | ch1 << 16 | ch2 << 8 | ch3;
        }
    }

    /** One pass along the rows (horizontal) or the columns, from `src` into `dst`, the edges repeated. */
    private static void line(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w, size = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int a = 0, red = 0, g = 0, b = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[at(l, clamp(i, n), w, horizontal)];
                a += c >>> 24; red += c >> 16 & 0xFF; g += c >> 8 & 0xFF; b += c & 0xFF;
            }
            for (int i = 0; i < n; i++) {
                dst[at(l, i, w, horizontal)] = (a / size) << 24 | (red / size) << 16 | (g / size) << 8 | (b / size);
                int out = src[at(l, clamp(i - r, n), w, horizontal)], in = src[at(l, clamp(i + r + 1, n), w, horizontal)];
                a += (in >>> 24) - (out >>> 24);
                red += (in >> 16 & 0xFF) - (out >> 16 & 0xFF);
                g += (in >> 8 & 0xFF) - (out >> 8 & 0xFF);
                b += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    private static int at(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    private static int clamp(int i, int n) {
        return i < 0 ? 0 : i >= n ? n - 1 : i;
    }
}
