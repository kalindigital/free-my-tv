package io.freemytv;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;

import java.util.List;
import java.util.Random;

/**
 * The screensaver's content: the information board ({@link InfoView}) over its background ({@link DreamBackground}),
 * with the article reader over both ({@link ArticleView}). With the "video" background, an Apple aerial video
 * ({@link AerialVideos}) loops under everything, muted. The real screensaver and the preview in the settings both show
 * this.
 */
final class DreamBoard extends FrameLayout {
    private final DreamBackground background;
    private final InfoView board;
    private final ArticleView article;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextureView video;
    private MediaPlayer player;
    private boolean videoShowing;
    // The background behind the now-playing card, at 1/8 size and blurred, for its frosted glass (refreshed twice a
    // second while the card is up: the background moves).
    private static final int SNAP_SCALE = 8;
    // Two bitmaps in turn: the card draws one while the next is made in the other (changing the one on screen made it
    // flicker). Each new capture is mixed with the last, so a black video frame or a sudden change never shows.
    private final android.graphics.Bitmap[] snaps = new android.graphics.Bitmap[2];
    private int snapTurn;
    private android.graphics.Bitmap grab;
    private android.graphics.Canvas grabCanvas;
    private int[] snapPixels, snapTmp, snapLast;
    private boolean snapFirst = true;
    private final Runnable frost = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (board.musicShowing()) {
                board.setBackdrop(snapshot(), SNAP_SCALE);
            }
            main.postDelayed(this, 500);
        }
    };
    private boolean running;

    DreamBoard(Context c) {
        super(c);
        background = new DreamBackground(c);
        board = new InfoView(c);
        article = new ArticleView(c);
        article.setVisibility(GONE);
        board.setOpener(article::open);
        article.setCloser(article::close);
        addView(background);
        addView(board);
        addView(article);
    }

    void start() {
        running = true;
        background.start();
        board.start();
        if (DreamBackground.VIDEO.equals(Prefs.dreamBackground())) startVideo();
        main.post(frost);
    }

    /** What's behind the board right now, small and blurred: the video's frame (if one plays) with the background over it. */
    private android.graphics.Bitmap snapshot() {
        int w = Math.max(1, getWidth() / SNAP_SCALE), h = Math.max(1, getHeight() / SNAP_SCALE);
        if (grab == null || grab.getWidth() != w || grab.getHeight() != h) {
            grab = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            grabCanvas = new android.graphics.Canvas(grab);
            for (int i = 0; i < 2; i++) snaps[i] = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            snapPixels = new int[w * h];
            snapTmp = new int[w * h];
            snapLast = new int[w * h];
            snapFirst = true;
        }
        grabCanvas.drawColor(0xFF000000);
        if (video != null && videoShowing) video.getBitmap(grab);
        grabCanvas.save();
        grabCanvas.scale(1f / SNAP_SCALE, 1f / SNAP_SCALE);
        background.draw(grabCanvas);
        grabCanvas.restore();
        grab.getPixels(snapPixels, 0, w, 0, 0, w, h);
        Blur.box(snapPixels, snapTmp, w, h, 3, 2);
        if (!snapFirst) Blur.mix(snapLast, snapPixels, 0.35f);
        snapFirst = false;
        System.arraycopy(snapPixels, 0, snapLast, 0, snapPixels.length);
        snapTurn = 1 - snapTurn;
        snaps[snapTurn].setPixels(snapPixels, 0, w, 0, 0, w, h);
        return snaps[snapTurn];
    }

    void stop() {
        running = false;
        main.removeCallbacks(frost);
        videoShowing = false;
        article.close();
        board.stop();
        background.stop();
        release();
        if (video != null) {
            removeView(video);
            video = null;
        }
    }

    /** Picks the video (the chosen one, or a new one at random each time) and plays it; the list may need fetching. */
    private void startVideo() {
        final Context c = getContext();
        List<AerialVideos.Video> saved = AerialVideos.saved(c);
        if (!saved.isEmpty()) {
            play(saved);
            return;
        }
        new Thread(() -> {
            List<AerialVideos.Video> list = AerialVideos.refresh(c);
            main.post(() -> { if (running) play(list); });
        }, "aerial").start();
    }

    private void play(List<AerialVideos.Video> list) {
        AerialVideos.Video v = AerialVideos.pick(list, Prefs.dreamVideo(), Prefs.lastVideo(), new Random());
        if (v == null) return;     // no list (offline): the fluid colors stay
        Prefs.setLastVideo(v.id);
        AppLog.w("aerial: " + v.name);
        // A TextureView, not a VideoView: a SurfaceView sits behind the window, whose opaque background hides it.
        video = new TextureView(getContext());
        addView(video, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h) {
                if (!running) return;
                player = new MediaPlayer();
                try {
                    player.setSurface(new Surface(texture));
                    player.setDataSource(v.url);
                    player.setLooping(true);
                    player.setVolume(0, 0);
                    player.setOnPreparedListener(MediaPlayer::start);
                    player.setOnInfoListener((mp, what, extra) -> {
                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                            videoShowing = true;
                            background.videoShowing();
                        }
                        return false;
                    });
                    player.setOnErrorListener((mp, what, extra) -> {
                        AppLog.w("aerial error " + what + "/" + extra);
                        return true;    // the fluid colors stay
                    });
                    player.prepareAsync();
                } catch (Exception e) {
                    AppLog.w("aerial: " + e);
                }
            }

            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) {}

            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                release();
                return true;
            }

            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
        });
    }

    private void release() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    /** {@link InfoView#PASS}, {@link InfoView#HANDLED} or {@link InfoView#EXIT} (end the screensaver or the preview). */
    int key(KeyEvent e) {
        return article.isOpen() ? article.key(e) : board.key(e);
    }
}
