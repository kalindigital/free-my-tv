package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Random;

/**
 * The "live weather" screensaver background, in the spirit of the iPhone Weather app: the sky as it is outside right
 * now, from the weather code (WMO, as Open-Meteo gives it), the time and today's sunrise and sunset.
 *  - The sky follows the sun: night, a warm dawn and dusk, blue by day, greyer the more clouds there are.
 *  - The sun moves along its arc (low and warm near the horizon); at night the moon, with stars that twinkle and now
 *    and then a shooting star.
 *  - Clouds are made once as soft, lit-from-above sprites and drift in three layers at different speeds (parallax).
 *  - Rain, drizzle and snow fall in three depths (far: small, faint and slow; near: big and fast), with a slow wind.
 *  - A storm has lightning bolts that light up the clouds; fog drifts in banks.
 * Nothing is allocated per frame: sprites and shaders are made when the weather or the light changes.
 */
final class WeatherScene {
    static final int CLEAR = 0, PARTLY = 1, CLOUDY = 2, FOG = 3, DRIZZLE = 4, RAIN = 5, SNOW = 6, STORM = 7;
    private static final int MAX_DROPS = 360, STARS = 180, CLOUDS = 12, SPRITES = 4;
    private static final int SPRITE_W = 320, SPRITE_H = 150;

    private final Random random = new Random();
    private final Paint fill = new Paint(), soft = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sprite = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final Path bolt = new Path();
    private final float[] dropX = new float[MAX_DROPS], dropY = new float[MAX_DROPS], dropDepth = new float[MAX_DROPS];
    private final float[] starX = new float[STARS], starY = new float[STARS], starSize = new float[STARS], starPhase = new float[STARS];
    private final float[] cloudX = new float[CLOUDS], cloudY = new float[CLOUDS], cloudScale = new float[CLOUDS];
    private final int[] cloudLayer = new int[CLOUDS], cloudSprite = new int[CLOUDS];
    private final Bitmap[] sprites = new Bitmap[SPRITES];
    private int kind = -1, w, h, lightStep = -1;
    private boolean day;
    private Shader sky, sunGlow, sunGlare, moonGlow;
    private int sunColor;
    private float sunX, sunY;
    private float nextFlash = 3, flashAt = -10, nextMeteor = 20, meteorAt = -10, meteorX, meteorY;

    WeatherScene() {
        for (int i = 0; i < MAX_DROPS; i++) {
            dropX[i] = random.nextFloat();
            dropY[i] = random.nextFloat();
            dropDepth[i] = i % 3 == 0 ? 1f : i % 3 == 1 ? 0.6f : 0.3f;    // near, middle, far
        }
        for (int i = 0; i < STARS; i++) {
            starX[i] = random.nextFloat();
            starY[i] = (float) Math.pow(random.nextFloat(), 1.4) * 0.75f;   // more of them high up
            starSize[i] = 0.5f + (float) Math.pow(random.nextFloat(), 3) * 1.8f;
            starPhase[i] = random.nextFloat() * 6.28f;
        }
        for (int i = 0; i < CLOUDS; i++) {
            cloudLayer[i] = i % 3;                                  // 0 far, 1 middle, 2 near
            cloudX[i] = random.nextFloat();
            cloudY[i] = 0.02f + random.nextFloat() * (0.18f + cloudLayer[i] * 0.12f);
            cloudScale[i] = (0.55f + cloudLayer[i] * 0.35f) * (0.8f + random.nextFloat() * 0.5f);
            cloudSprite[i] = random.nextInt(SPRITES);
        }
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    /** The rules from the weather and the clock (apart from the drawing, so the plain-Java tests can load them). */
    static final class Rules {
        /** What the sky looks like for a WMO weather code. */
        static int kind(int code) {
            if (code < 0 || code <= 1) return CLEAR;
            if (code == 2) return PARTLY;
            if (code == 3) return CLOUDY;
            if (code == 45 || code == 48) return FOG;
            if (code >= 51 && code <= 57) return DRIZZLE;
            if (code >= 71 && code <= 77 || code == 85 || code == 86) return SNOW;
            if (code >= 95) return STORM;
            if (code >= 61 && code <= 67 || code >= 80 && code <= 82) return RAIN;
            return CLOUDY;
        }

        /** How many drops (or flakes) fall: light, moderate or heavy, by the code. */
        static int drops(int code) {
            switch (kind(code)) {
                case DRIZZLE: return 120;
                case SNOW: return code == 75 || code == 86 ? 260 : code == 73 ? 180 : 110;
                case STORM: return MAX_DROPS;
                case RAIN: return code == 65 || code == 67 || code == 82 ? MAX_DROPS : code == 63 || code == 81 ? 240 : 150;
                default: return 0;
            }
        }

        /** How many clouds drift by. */
        static int clouds(int kind) {
            switch (kind) {
                case CLEAR: return 0;
                case PARTLY: return 5;
                case FOG: return 3;
                default: return CLOUDS;
            }
        }

        /** How grey the sky is under the clouds: 0 clear, 1 storm. */
        static float cover(int kind) {
            switch (kind) {
                case CLEAR: return 0;
                case PARTLY: return 0.2f;
                case CLOUDY: return 0.65f;
                case FOG: return 0.75f;
                case STORM: return 0.95f;
                default: return 0.8f;   // rain, drizzle, snow
            }
        }

        /** Daylight, 0 (night) to 1 (day), easing over the 40 minutes around sunrise and sunset. */
        static float daylight(long now, long sunrise, long sunset) {
            float rise = clamp((now - sunrise) / (40 * 60_000f) + 0.5f), set = clamp((sunset - now) / (40 * 60_000f) + 0.5f);
            return smooth(Math.min(rise, set));
        }

        /** The golden hour, 0 to 1: strongest right at sunrise and sunset, gone an hour away from them. */
        static float golden(long now, long sunrise, long sunset) {
            long near = Math.min(Math.abs(now - sunrise), Math.abs(now - sunset));
            return smooth(clamp(1 - near / (60 * 60_000f)));
        }

        /** How far through the day the sun is, 0 at sunrise to 1 at sunset (outside of that, clamped). */
        static float progress(long now, long sunrise, long sunset) {
            return sunset <= sunrise ? 0.5f : clamp((now - sunrise) / (float) (sunset - sunrise));
        }

        static float clamp(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
        static float smooth(float t) { return t * t * (3 - 2 * t); }
    }

    void draw(Canvas c, int width, int height, float s, HomeData.Weather wx, long now) {
        int k = Rules.kind(wx.code);
        boolean known = wx.sunrise > 0 && wx.sunset > wx.sunrise;
        float daylight = known ? Rules.daylight(now, wx.sunrise, wx.sunset) : wx.isDay ? 1 : 0;
        float golden = known ? Rules.golden(now, wx.sunrise, wx.sunset) : 0;
        boolean isDay = daylight > 0.5f;
        // The light changes slowly: the sky and the sun are made again every 30 s, or when the weather changes.
        int step = (int) (now / 30_000);
        if (k != kind || isDay != day || width != w || height != h) setUpWeather(k, isDay, width, height);
        if (step != lightStep) {
            lightStep = step;
            setUpLight(daylight, golden, known ? Rules.progress(now, wx.sunrise, wx.sunset) : 0.5f);
        }
        fill.setShader(sky);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
        float cover = Rules.cover(kind);
        if (daylight < 0.6f && cover < 0.7f) drawStars(c, s, (1 - daylight / 0.6f) * (1 - cover));
        if (cover < 0.85f) {
            if (daylight > 0.05f) drawSun(c, daylight * (1 - cover * 0.9f));
            if (daylight < 0.5f) drawMoon(c, (1 - daylight * 2) * (1 - cover * 0.8f));
        }
        if (daylight < 0.4f && cover < 0.3f) drawMeteor(c, s);
        float flash = kind == STORM ? lightning(s) : 0;
        drawClouds(c, s, 0, flash);
        int n = Rules.drops(wx.code);
        if (kind == SNOW) drawSnow(c, s, n, 0.3f);
        else if (n > 0) drawRain(c, s, n, 0.3f);
        drawClouds(c, s, 1, flash);
        if (kind == SNOW) drawSnow(c, s, n, 0.6f);
        else if (n > 0) drawRain(c, s, n, 0.6f);
        if (kind == STORM && flash > 0) drawBolt(c, flash);
        drawClouds(c, s, 2, flash);
        if (kind == SNOW) drawSnow(c, s, n, 1f);
        else if (n > 0) drawRain(c, s, n, 1f);
        if (kind == FOG) drawFog(c, s);
        if (flash > 0) {
            fill.setColor(Ui.alpha(0xFFDDE6FF, 0.28f * flash));
            c.drawRect(0, 0, w, h, fill);
        }
    }

    // MARK: - Setting up

    private void setUpWeather(int k, boolean isDay, int width, int height) {
        kind = k;
        day = isDay;
        w = width;
        h = height;
        lightStep = -1;
        int[] tone = cloudTone(k, isDay);
        for (int i = 0; i < SPRITES; i++) {
            if (sprites[i] != null) sprites[i].recycle();
            sprites[i] = cloudSprite(tone[0], tone[1], new Random(i * 7919L + 13));
        }
    }

    /** The sky gradient and the sun's place and color, for this light. */
    private void setUpLight(float daylight, float golden, float progress) {
        float cover = Rules.cover(kind);
        int top = mix(0xFF050A1C, 0xFF2F78D2, daylight), bottom = mix(0xFF1A2748, 0xFF9CCBF2, daylight);
        top = mix(top, 0xFF3C3F78, golden * 0.6f);
        bottom = mix(bottom, 0xFFF59A6B, golden * 0.85f);
        int grey = mix(0xFF141920, 0xFF8E99A5, daylight);
        top = mix(top, mix(grey, 0xFF2A3038, kind == STORM ? 0.7f : 0), cover);
        bottom = mix(bottom, mix(grey, 0xFFB7C0C9, daylight * 0.6f), cover);
        sky = new LinearGradient(0, 0, 0, h, new int[] {top, mix(top, bottom, 0.55f), bottom}, new float[] {0, 0.6f, 1},
                Shader.TileMode.CLAMP);
        // The sun crosses from the left to the right, high at noon, low and warm near sunrise and sunset.
        float elevation = (float) Math.sin(Math.PI * progress);
        sunX = w * (0.3f + 0.5f * progress);
        sunY = h * (0.62f - 0.46f * elevation);
        sunColor = mix(0xFFFFF7E0, 0xFFFFB067, golden);
        int halo = sunColor & 0x00FFFFFF;
        sunGlow = new RadialGradient(sunX, sunY, h * 0.55f, new int[] {sunColor, 0x66000000 | halo, 0x1A000000 | halo, halo},
                new float[] {0, 0.12f, 0.4f, 1}, Shader.TileMode.CLAMP);
        sunGlare = new RadialGradient(0, 0, 1, new int[] {0x40000000 | halo, halo}, null, Shader.TileMode.CLAMP);
        moonGlow = new RadialGradient(w * 0.8f, h * 0.2f, h * 0.3f, new int[] {0x55DCE6F5, 0x14DCE6F5, 0x00DCE6F5},
                new float[] {0, 0.3f, 1}, Shader.TileMode.CLAMP);
    }

    /** Light and shadow of the clouds: white on top, a soft grey underneath; darker for rain and storms, dim at night. */
    private static int[] cloudTone(int k, boolean d) {
        if (!d) {
            return k == STORM ? new int[] {0xFF2E343E, 0xFF12161C} : k == PARTLY || k == CLEAR
                    ? new int[] {0xFF5A6476, 0xFF262D3A} : new int[] {0xFF3E4652, 0xFF1A1F27};
        }
        switch (k) {
            case CLEAR: case PARTLY: return new int[] {0xFFFFFFFF, 0xFFC6D1DD};
            case CLOUDY: return new int[] {0xFFF0F2F5, 0xFF9DA7B2};
            case SNOW: case FOG: return new int[] {0xFFF4F6F8, 0xFFB3BCC6};
            case STORM: return new int[] {0xFF79818C, 0xFF2C3139};
            default: return new int[] {0xFFC2C9D1, 0xFF5D6670};     // rain, drizzle
        }
    }

    /**
     * A cloud: thirty-odd soft puffs, bigger in the middle, each drawn first in its shadow color a little lower and then
     * in its light color, so the cloud looks lit from above. Drawn small and stretched later, which softens it further.
     */
    private static Bitmap cloudSprite(int light, int shadow, Random r) {
        Bitmap b = Bitmap.createBitmap(SPRITE_W, SPRITE_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int n = 34;
        float[] x = new float[n], y = new float[n], rad = new float[n];
        for (int i = 0; i < n; i++) {
            float t = r.nextFloat() * 2 - 1;                        // -1..1 across the cloud
            float bulk = 1 - t * t;                                 // taller in the middle
            x[i] = SPRITE_W / 2f + t * SPRITE_W * 0.36f;
            y[i] = SPRITE_H * (0.66f - bulk * 0.28f * r.nextFloat());
            rad[i] = SPRITE_H * (0.14f + 0.22f * bulk * (0.6f + 0.4f * r.nextFloat()));
        }
        for (int pass = 0; pass < 2; pass++) {
            int color = pass == 0 ? shadow : light;
            for (int i = 0; i < n; i++) {
                float cy = y[i] + (pass == 0 ? rad[i] * 0.22f : -rad[i] * 0.05f), cr = rad[i] * (pass == 0 ? 1.05f : 0.92f);
                p.setShader(new RadialGradient(x[i], cy, cr, new int[] {color, 0xCC000000 | color & 0xFFFFFF, color & 0xFFFFFF},
                        new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
                c.drawCircle(x[i], cy, cr, p);
            }
        }
        return b;
    }

    // MARK: - Drawing

    private void drawStars(Canvas c, float s, float strength) {
        float unit = Math.max(1, h / 720f);
        for (int i = 0; i < STARS; i++) {
            float tw = 0.55f + 0.45f * (float) Math.sin(s * (0.6f + (i % 5) * 0.25f) + starPhase[i]);
            soft.setColor(Ui.alpha(i % 9 == 0 ? 0xFFFFE9C4 : i % 7 == 0 ? 0xFFCFE0FF : 0xFFFFFFFF, strength * tw));
            c.drawCircle(starX[i] * w, starY[i] * h, unit * starSize[i], soft);
        }
    }

    private void drawSun(Canvas c, float strength) {
        if (strength <= 0.02f) return;
        fill.setShader(sunGlow);
        fill.setAlpha(Math.round(255 * strength));
        c.drawRect(0, 0, w, h, fill);
        // A faint horizontal glare through the sun, like a camera lens.
        float gw = w * 0.45f, gh = h * 0.025f;
        matrix.setScale(gw, gh);
        matrix.postTranslate(sunX, sunY);
        sunGlare.setLocalMatrix(matrix);
        fill.setShader(sunGlare);
        c.drawRect(sunX - gw, sunY - gh, sunX + gw, sunY + gh, fill);
        fill.setShader(null);
        fill.setAlpha(255);
        soft.setColor(Ui.alpha(sunColor | 0xFF000000, Math.min(1, strength * 1.2f)));
        c.drawCircle(sunX, sunY, h * 0.045f, soft);
    }

    /** A pale moon with a soft halo and a few darker seas on its face. */
    private void drawMoon(Canvas c, float strength) {
        if (strength <= 0.02f) return;
        float mx = w * 0.8f, my = h * 0.2f, r = h * 0.05f;
        fill.setShader(moonGlow);
        fill.setAlpha(Math.round(255 * strength));
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
        fill.setAlpha(255);
        soft.setColor(Ui.alpha(0xFFF2EFE6, strength));
        c.drawCircle(mx, my, r, soft);
        soft.setColor(Ui.alpha(0xFFC9C6BC, strength * 0.55f));
        c.drawCircle(mx - r * 0.3f, my - r * 0.2f, r * 0.28f, soft);
        c.drawCircle(mx + r * 0.25f, my + r * 0.25f, r * 0.2f, soft);
        c.drawCircle(mx + r * 0.1f, my - r * 0.45f, r * 0.12f, soft);
    }

    /** Now and then, on a clear night, a shooting star crosses the top of the sky. */
    private void drawMeteor(Canvas c, float s) {
        if (s >= nextMeteor) {
            meteorAt = s;
            meteorX = w * (0.2f + random.nextFloat() * 0.5f);
            meteorY = h * (0.05f + random.nextFloat() * 0.2f);
            nextMeteor = s + 25 + random.nextFloat() * 50;
        }
        float t = (s - meteorAt) / 0.9f;
        if (t < 0 || t > 1) return;
        float x = meteorX + t * w * 0.25f, y = meteorY + t * h * 0.12f, tail = w * 0.08f;
        line.setStrokeWidth(Math.max(1.5f, h / 540f));
        line.setShader(null);
        line.setColor(Ui.alpha(0xFFFFFFFF, (float) Math.sin(Math.PI * t)));
        c.drawLine(x - tail, y - tail * 0.48f, x, y, line);
    }

    /** One layer of clouds (0 far, 1 middle, 2 near), drifting right at its own speed and coming back from the left. */
    private void drawClouds(Canvas c, float s, int layer, float flash) {
        int n = Rules.clouds(kind);
        float speed = (0.004f + layer * 0.005f) * w;     // px a second
        int alpha = layer == 0 ? 170 : layer == 1 ? 220 : 255;
        for (int i = 0; i < n; i++) {
            if (cloudLayer[i] != layer) continue;
            float cw = w * 0.42f * cloudScale[i], ch = cw * SPRITE_H / SPRITE_W, span = w + cw;
            float x = (cloudX[i] * span + speed * s) % span - cw;
            float y = cloudY[i] * h;
            dst.set(x, y, x + cw, y + ch);
            src.set(0, 0, SPRITE_W, SPRITE_H);
            sprite.setAlpha(alpha);
            c.drawBitmap(sprites[cloudSprite[i]], src, dst, sprite);
            if (flash > 0) {
                // Lightning lights the clouds from inside.
                sprite.setAlpha(Math.round(120 * flash));
                c.drawBitmap(sprites[cloudSprite[i]], src, dst, sprite);
            }
        }
    }

    /** Drops at one depth: far ones short, thin, faint and slow; near ones long and fast. The wind sways slowly. */
    private void drawRain(Canvas c, float s, int n, float depth) {
        boolean drizzle = kind == DRIZZLE;
        float len = h * (drizzle ? 0.018f : 0.055f) * (0.4f + depth), speed = h * (drizzle ? 0.55f : 1.5f) * (0.45f + depth * 0.7f);
        float wind = 0.12f + 0.06f * (float) Math.sin(s * 0.07f);
        line.setShader(null);
        line.setStrokeWidth(Math.max(1, h / 1080f * (drizzle ? 1.2f : 1.4f + 2.2f * depth)));
        line.setColor(Ui.alpha(day ? 0xFFE8EEF5 : 0xFFB9C6D6, (drizzle ? 0.25f : 0.18f) + 0.42f * depth));
        for (int i = 0; i < n; i++) {
            if (dropDepth[i] != depth) continue;
            float y = (dropY[i] * (h + len) + speed * s) % (h + len) - len;
            float x = ((dropX[i] * w + y * wind) % w + w) % w;
            c.drawLine(x, y, x + len * wind, y + len, line);
        }
    }

    /** Flakes at one depth: far ones tiny and slow, near ones big, soft and swaying more. */
    private void drawSnow(Canvas c, float s, int n, float depth) {
        float speed = h * 0.06f * (0.4f + depth), r = h / 1080f * (1.5f + 4.5f * depth);
        soft.setColor(Ui.alpha(0xFFFFFFFF, 0.45f + 0.5f * depth));
        for (int i = 0; i < n; i++) {
            if (dropDepth[i] != depth) continue;
            float y = (dropY[i] * (h + 2 * r) + speed * s) % (h + 2 * r) - r;
            float x = dropX[i] * w + (float) Math.sin(s * (0.4f + depth * 0.3f) + i) * h * 0.025f * depth;
            c.drawCircle(x, y, r, soft);
        }
    }

    /** Fog: low banks of cloud drifting slowly across the bottom two thirds, over a pale veil. */
    private void drawFog(Canvas c, float s) {
        fill.setColor(day ? 0x40E5E9ED : 0x30808890);
        c.drawRect(0, h * 0.3f, w, h, fill);
        src.set(0, 0, SPRITE_W, SPRITE_H);
        for (int i = 0; i < 6; i++) {
            float cw = w * 0.9f, ch = h * 0.32f, span = w + cw;
            float x = ((i * 0.31f * span + (i % 2 == 0 ? 1 : -1) * w * 0.006f * s) % span + span) % span - cw;
            float y = h * (0.28f + 0.11f * i);
            dst.set(x, y, x + cw, y + ch);
            sprite.setAlpha(110);
            c.drawBitmap(sprites[i % SPRITES], src, dst, sprite);
        }
    }

    /** The lightning's brightness now (0 most of the time), with two flickers per strike. */
    private float lightning(float s) {
        if (s >= nextFlash) {
            flashAt = s;
            nextFlash = s + 5 + random.nextFloat() * 10;
            makeBolt();
        }
        float t = s - flashAt;
        if (t < 0 || t > 0.7f) return 0;
        return (float) Math.exp(-t * 6) * (t < 0.07f || t > 0.15f ? 1 : 0.25f);
    }

    /** A new jagged bolt from the clouds down most of the way, with a branch. */
    private void makeBolt() {
        bolt.reset();
        float x = w * (0.25f + random.nextFloat() * 0.5f), y = h * 0.12f;
        bolt.moveTo(x, y);
        float bx = 0, by = 0;
        for (int i = 0; i < 14; i++) {
            x += (random.nextFloat() - 0.5f) * w * 0.05f;
            y += h * (0.035f + random.nextFloat() * 0.03f);
            bolt.lineTo(x, y);
            if (i == 5) { bx = x; by = y; }
        }
        bolt.moveTo(bx, by);
        for (int i = 0; i < 5; i++) {
            bx += w * (0.012f + random.nextFloat() * 0.02f);
            by += h * (0.03f + random.nextFloat() * 0.02f);
            bolt.lineTo(bx, by);
        }
    }

    private void drawBolt(Canvas c, float flash) {
        line.setShader(null);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeWidth(h / 1080f * 14);
        line.setColor(Ui.alpha(0xFFB9C8FF, 0.25f * flash));     // the glow
        c.drawPath(bolt, line);
        line.setStrokeWidth(h / 1080f * 3);
        line.setColor(Ui.alpha(0xFFFFFFFF, flash));
        c.drawPath(bolt, line);
        line.setStyle(Paint.Style.FILL);
    }

    private static int mix(int a, int b, float t) {
        t = Rules.clamp(t);
        int ar = a >> 16 & 0xFF, ag = a >> 8 & 0xFF, ab = a & 0xFF, br = b >> 16 & 0xFF, bg = b >> 8 & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | Math.round(ar + (br - ar) * t) << 16 | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
    }
}
