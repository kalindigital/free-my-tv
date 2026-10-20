package io.freemytv;

import android.service.dreams.DreamService;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * The Free My TV screensaver (enabled in Free My TV mode via `settings put secure screensaver_components`). The system
 * still decides when it starts and when the TV turns off (e.g. after 10 min idle, then 2 h later), as before: the app
 * doesn't change those timings. It is interactive: the arrows and OK pick and open a headline ({@link ArticleView});
 * Back and any other key exit, volume keys go on to the system.
 */
public class Screensaver extends DreamService {
    private InfoView view;
    private ArticleView article;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Watchdog.install(this);
        Ui.init(this);
        setInteractive(true);
        setFullscreen(true);
        setScreenBright(true);
        view = new InfoView(this);
        article = new ArticleView(this);
        article.setVisibility(View.GONE);
        view.setOpener(article::open);
        article.setCloser(article::close);
        FrameLayout root = new FrameLayout(this);
        root.addView(view);
        root.addView(article);
        setContentView(root);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (view == null) return super.dispatchKeyEvent(event);
        int r = article.isOpen() ? article.key(event) : view.key(event);
        if (r == InfoView.EXIT) {
            finish();
            return true;
        }
        return r == InfoView.HANDLED || super.dispatchKeyEvent(event);
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        if (view != null) view.start();
    }

    @Override public void onDreamingStopped() {
        if (article != null) article.close();
        if (view != null) view.stop();
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        if (article != null) article.close();
        if (view != null) view.stop();
        super.onDetachedFromWindow();
    }
}
