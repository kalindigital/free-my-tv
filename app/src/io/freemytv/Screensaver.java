package io.freemytv;

import android.service.dreams.DreamService;
import android.view.KeyEvent;

/**
 * The Free My TV screensaver (enabled in Free My TV mode via `settings put secure screensaver_components`). The system
 * still decides when it starts and when the TV turns off (e.g. after 10 min idle, then 2 h later), as before: the app
 * doesn't change those timings. It is interactive: the arrows and OK pick and open a headline ({@link ArticleView});
 * Back and any other key exit, volume keys go on to the system.
 */
public class Screensaver extends DreamService {
    private DreamBoard view;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Watchdog.install(this);
        Ui.init(this);
        setInteractive(true);
        setFullscreen(true);
        setScreenBright(true);
        view = new DreamBoard(this);
        setContentView(view);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (view == null) return super.dispatchKeyEvent(event);
        int r = view.key(event);
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
        if (view != null) view.stop();
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        if (view != null) view.stop();
        super.onDetachedFromWindow();
    }
}
