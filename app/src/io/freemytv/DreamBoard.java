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
    }

    void stop() {
        running = false;
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
                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) background.videoShowing();
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
