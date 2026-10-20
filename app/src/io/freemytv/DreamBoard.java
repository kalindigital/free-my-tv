package io.freemytv;

import android.content.Context;
import android.view.KeyEvent;
import android.widget.FrameLayout;

/**
 * The screensaver's content: the information board ({@link InfoView}) with the article reader over it
 * ({@link ArticleView}). The real screensaver and the preview in the settings both show this.
 */
final class DreamBoard extends FrameLayout {
    private final InfoView board;
    private final ArticleView article;

    DreamBoard(Context c) {
        super(c);
        board = new InfoView(c);
        article = new ArticleView(c);
        article.setVisibility(GONE);
        board.setOpener(article::open);
        article.setCloser(article::close);
        addView(board);
        addView(article);
    }

    void start() { board.start(); }

    void stop() {
        article.close();
        board.stop();
    }

    /** {@link InfoView#PASS}, {@link InfoView#HANDLED} or {@link InfoView#EXIT} (end the screensaver or the preview). */
    int key(KeyEvent e) {
        return article.isOpen() ? article.key(e) : board.key(e);
    }
}
