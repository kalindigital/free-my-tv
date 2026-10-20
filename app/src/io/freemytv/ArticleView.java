package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A news article read right on the screensaver: title, photos and text in one column on black, scrolled with ▲▼
 * (a page at a time with ◀▶ or the channel keys), Back to return to the board. The article is fetched and taken apart
 * by {@link Reader} in the background; the photos come in one by one as they download.
 */
final class ArticleView extends View {
    private static final float COLUMN = 1120, TOP = 150;
    private static final long IDLE_MS = 10 * 60_000L;     // left open: back to the board (no static page burning in)
    private static final int MAX_IMAGE_BYTES = 8 << 20;

    interface Closer { void close(); }

    /** One piece of the column: text laid out, or a photo (null until it arrives) with its height. */
    private static final class Item {
        StaticLayout text;
        String url;
        Bitmap image;
        float height, y;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Item> items = new ArrayList<>();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final Paint photoPaint = new Paint(Paint.FILTER_BITMAP_FLAG), placeholder = new Paint(), bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Closer closer;
    private Rss.Headline headline;
    private Reader.Article article;
    private String error;
    private int generation;
    private float k = 1, scroll, target, total;
    private TextPaint titlePaint, bodyPaint, headingPaint, sourcePaint, topPaint, hintPaint;

    private final Runnable idle = () -> { if (closer != null) closer.close(); };

    ArticleView(Context c) {
        super(c);
        placeholder.setColor(0xFF161618);
    }

    void setCloser(Closer c) { closer = c; }

    boolean isOpen() { return headline != null; }

    void open(Rss.Headline h) {
        headline = h;
        article = null;
        error = null;
        clear();
        scroll = target = 0;
        setVisibility(VISIBLE);
        poke();
        final int gen = ++generation;
        if (io.isShutdown()) io = Executors.newSingleThreadExecutor();
        io.execute(() -> {
            try {
                final Reader.Article a = Reader.load(h.link);
                if (a.textLength() < 200) throw new Exception("too little text");
                handler.post(() -> { if (gen == generation) show(a); });
                loadImages(a, gen);
            } catch (Exception e) {
                AppLog.w("article: " + e.getMessage());
                handler.post(() -> {
                    if (gen != generation) return;
                    error = Ui.tr("Não deu para abrir esta matéria aqui.", "Couldn't open this article here.",
                            "No se pudo abrir esta noticia aquí.");
                    invalidate();
                });
            }
        });
        invalidate();
    }

    void close() {
        generation++;
        headline = null;
        article = null;
        handler.removeCallbacks(idle);
        clear();
        setVisibility(GONE);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        io.shutdownNow();
    }

    private void clear() {
        for (Item it : items) if (it.image != null) it.image.recycle();
        items.clear();
        total = 0;
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        k = w / 1920f;
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 58 * k, 0xFFFFFFFF));
        bodyPaint = new TextPaint(Ui.text(Ui.regular, 34 * k, 0xE0FFFFFF));
        headingPaint = new TextPaint(Ui.text(Ui.semibold, 40 * k, 0xF2FFFFFF));
        sourcePaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0x8CFFFFFF));
        sourcePaint.setLetterSpacing(0.08f);
        topPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xB3FFFFFF));
        hintPaint = new TextPaint(Ui.text(Ui.medium, 22 * k, 0x66FFFFFF));
        hintPaint.setTextAlign(Paint.Align.RIGHT);
        bar.setColor(0x40FFFFFF);
        if (article != null) show(article);
    }

    // MARK: - Layout

    private void show(Reader.Article a) {
        article = a;
        if (getWidth() == 0) return;
        List<Bitmap> keep = new ArrayList<>();
        for (Item it : items) if (it.image != null) keep.add(it.image);
        items.clear();
        int width = Math.round(COLUMN * k);
        String source = !a.source.isEmpty() ? a.source : headline != null ? headline.source : "";
        if (!source.isEmpty()) items.add(text(source.toUpperCase(Ui.locale()), sourcePaint, width, 1.0f, 18));
        String title = !a.title.isEmpty() ? a.title : headline != null ? headline.title : "";
        items.add(text(title, titlePaint, width, 1.1f, 44));
        for (Reader.Block b : a.blocks) {
            if (b.image != null) {
                Item it = new Item();
                it.url = b.image;
                it.height = width * 9f / 16 + 40 * k;
                items.add(it);
            } else if (b.heading) {
                items.add(text(b.text, headingPaint, width, 1.25f, 26));
            } else {
                items.add(text(b.text, bodyPaint, width, 1.5f, 30));
            }
        }
        for (Bitmap b : keep) b.recycle();
        relayout();
    }

    private Item text(String s, TextPaint p, int width, float spacing, float after) {
        Item it = new Item();
        it.text = new StaticLayout(s, p, width, Layout.Alignment.ALIGN_NORMAL, spacing, 0, false);
        it.height = it.text.getHeight() + after * k;
        return it;
    }

    private void relayout() {
        float y = TOP * k;
        for (Item it : items) {
            it.y = y;
            y += it.height;
        }
        total = y + 160 * k;
        target = Math.min(target, maxScroll());
        invalidate();
    }

    private float maxScroll() {
        return Math.max(0, total - getHeight());
    }

    // MARK: - Photos

    private void loadImages(Reader.Article a, int gen) {
        int width = Math.round(COLUMN * k);
        for (Reader.Block block : a.blocks) {
            if (gen != generation) return;
            final String url = block.image;
            if (url == null) continue;
            try {
                byte[] bytes = download(url);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                if (o.outWidth < 200 || o.outHeight < 120) continue;
                int sample = 1;
                while (o.outWidth / (sample * 2) >= width) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                final Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                if (b == null) continue;
                handler.post(() -> {
                    Item it = null;
                    if (gen == generation) for (Item i : items) if (url.equals(i.url) && i.image == null) { it = i; break; }
                    if (it == null) {
                        b.recycle();
                        return;
                    }
                    it.image = b;
                    float w = COLUMN * k;
                    it.height = Math.min(w * b.getHeight() / b.getWidth(), getHeight() * 0.8f) + 40 * k;
                    relayout();
                });
            } catch (Exception e) {
                AppLog.w("article photo: " + e.getMessage());
            }
        }
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", Reader.UA);
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > MAX_IMAGE_BYTES) throw new Exception("photo too big");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(0xFF000000);
        if (titlePaint == null) return;
        // Eased scroll toward the target.
        float d = target - scroll;
        if (Math.abs(d) > 0.5f) {
            scroll += d * 0.22f;
            postInvalidateOnAnimation();
        } else {
            scroll = target;
        }
        float left = (w - COLUMN * k) / 2;

        if (article == null) {
            String msg = error != null ? error : Ui.tr("Abrindo a matéria…", "Opening the article…", "Abriendo la noticia…");
            float cy = h / 2f;
            if (headline != null) {
                StaticLayout t = new StaticLayout(headline.title, titlePaint, Math.round(COLUMN * k), Layout.Alignment.ALIGN_CENTER, 1.1f, 0, false);
                c.save();
                c.translate(left, cy - t.getHeight() - 40 * k);
                t.draw(c);
                c.restore();
            }
            topPaint.setTextAlign(Paint.Align.CENTER);
            c.drawText(msg, w / 2f, cy + 40 * k, topPaint);
            topPaint.setTextAlign(Paint.Align.LEFT);
            if (error == null) Ui.spinner(c, w / 2f, cy + 110 * k, 18 * k, SystemClock.uptimeMillis() / 1000f, bar);
            if (error == null) postInvalidateDelayed(50);
            drawTop(c, w);
            return;
        }

        c.save();
        c.translate(left, -scroll);
        for (Item it : items) {
            if (it.y + it.height < scroll || it.y > scroll + h) continue;
            if (it.text != null) {
                c.save();
                c.translate(0, it.y);
                it.text.draw(c);
                c.restore();
            } else {
                float ih = it.height - 40 * k;
                if (it.image != null) {
                    float iw = Math.min(COLUMN * k, ih * it.image.getWidth() / it.image.getHeight());
                    float x = (COLUMN * k - iw) / 2;
                    src.set(0, 0, it.image.getWidth(), it.image.getHeight());
                    dst.set(x, it.y, x + iw, it.y + ih);
                    c.drawBitmap(it.image, src, dst, photoPaint);
                } else {
                    dst.set(0, it.y, COLUMN * k, it.y + ih);
                    c.drawRoundRect(dst, 16 * k, 16 * k, placeholder);
                }
            }
        }
        c.restore();

        // Where you are in the article.
        float max = maxScroll();
        if (max > 0) {
            float track = h - 240 * k, thumb = Math.max(60 * k, track * h / total);
            float y = 120 * k + (track - thumb) * (scroll / max);
            dst.set(w - 60 * k, y, w - 52 * k, y + thumb);
            c.drawRoundRect(dst, 4 * k, 4 * k, bar);
        }
        drawTop(c, w);
    }

    /** The bar on top, over the text: how to go back, and the keys. */
    private void drawTop(Canvas c, int w) {
        Paint shade = placeholder;
        int old = shade.getColor();
        shade.setColor(0xF0000000);
        c.drawRect(0, 0, w, 96 * k, shade);
        shade.setColor(old);
        float base = 62 * k;
        Symbols.draw(getContext(), c, "back", 120 * k, base - Ui.halfCap(topPaint), 30 * k, 0xB3FFFFFF);
        c.drawText(Ui.tr("Voltar", "Back", "Volver"), 150 * k, base, topPaint);
        c.drawText(Ui.tr("▲▼ rolar  ·  ◀▶ página", "▲▼ scroll  ·  ◀▶ page", "▲▼ desplazar  ·  ◀▶ página"), w - 120 * k, base, hintPaint);
    }

    // MARK: - Keys

    /** The remote while reading. Returns like {@link InfoView#key}. */
    int key(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        float step = 200 * k, page = getHeight() * 0.8f;
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER: case KeyEvent.KEYCODE_SLEEP:
                return InfoView.PASS;
            case KeyEvent.KEYCODE_BACK:
                if (down) return InfoView.HANDLED;
                if (closer != null) closer.close();
                return InfoView.HANDLED;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (down) scrollBy(step); return InfoView.HANDLED;
            case KeyEvent.KEYCODE_DPAD_UP: if (down) scrollBy(-step); return InfoView.HANDLED;
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_CHANNEL_DOWN: case KeyEvent.KEYCODE_PAGE_DOWN:
                if (down) scrollBy(page);
                return InfoView.HANDLED;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_CHANNEL_UP: case KeyEvent.KEYCODE_PAGE_UP:
                if (down) scrollBy(-page);
                return InfoView.HANDLED;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) scrollBy(page);
                return InfoView.HANDLED;
            default:
                return InfoView.EXIT;
        }
    }

    private void scrollBy(float dy) {
        target = Math.max(0, Math.min(maxScroll(), target + dy));
        poke();
        invalidate();
    }

    private void poke() {
        handler.removeCallbacks(idle);
        handler.postDelayed(idle, IDLE_MS);
    }
}
