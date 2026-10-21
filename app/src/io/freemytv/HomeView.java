package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - at the top left, the "For You" title and the tabs (Explore, Movies, Shows); at the top right, icon-only glass
 *    buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below it, and the small crypto quotes
 *    (logo + price) at the right end of the tab row;
 *  - three cards with the titles recommended for right now in the selected tab (Recs, from TMDB); the background is
 *    the first card's photo, heavily blurred and almost white (Backdrop); without one, a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, weather for the next hours
 *    and quotes with the 24 h change); the background lightens (darkens at night). ↑ or Back closes it;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener,
        Cinemora.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: 5:3 icons (210 × 126) in each brand's color, 48 apart, and the glass bar hugging
    // them with 40 of padding (the focused icon grows 20% without touching its edge).
    private static final float SHELF_PAD = 40, TILE_GAP = 48;
    private static final float TILE_RADIUS = 22, TILE_MAX_W = 270;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.2f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // Bottom drawer (measured with it open; everything moves up by SHIFT): the inputs and shortcuts. The weather and
    // the prices are on the Home tab now.
    private static final float SHIFT = 300, ROW_TITLE = 790, ROW_TOP = 812, ROW_H = 126, CARDS_TOP = 708, CARDS_H = 300;
    // "All Apps" sheet.
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule; the cards sit on the 5-column grid (the wide one spans 3).
    private static final float L_TITLE_BASE = 130, L_TABBAR_TOP = 158, L_TABBAR_H = 68, L_CARDS_TOP = 262, L_CARD_H = 442;
    private static final float L_TALL_W = 320, L_CARD_GAP = 40, L_BUTTON = 68, L_BUTTON_GAP = 20, L_BUTTON_CY = 103;
    private static final float L_PLATTER_TOP = 814, L_TILE_H = 126;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    // The Esthetis tab only with esthetis_tv_token in config.properties.
    private static final String[] TABS = esthetisTab(new String[] {"Home", "Filmes", "Séries", "Mercado", "Buscar"});
    private static final String[] TABS_EN = esthetisTab(new String[] {"Home", "Movies", "Shows", "Markets", "Search"});
    private static final String[] TABS_ES = esthetisTab(new String[] {"Inicio", "Películas", "Series", "Mercados", "Buscar"});
    private static final String[] TAB_KIND = {null, "movie", "tv", null, null, null};
    /** The tabs; at the top, the tabs come first (0 to NT - 1) and the three icon-only buttons after them. */
    private static final int NT = TABS.length, T_MARKET = 3, T_SEARCH = 4, T_ESTHETIS = 5;

    private static String[] esthetisTab(String[] tabs) {
        if (!EsthetisMrr.enabled()) return tabs;
        String[] out = java.util.Arrays.copyOf(tabs, tabs.length + 1);
        out[tabs.length] = "Esthetis";
        return out;
    }
    private static final int Z_TOP = 4, Z_CARDS = 5, Z_CINEMA = 6, Z_MARKET = 7, Z_SEARCH = 8, Z_BOARD = 9;
    // Markets: 4 × 2 cards; Search: the field, then the results as Cinemora rows (a little smaller).
    private static final float M_TOP = 262, M_H = 240, M_GAP = 30, S_H = 68, S_W = 900, S_DY = 90, S_SCALE = 0.8f;
    /** Without the apps (every tab but Home), the grids go down to here. */
    private static final float GRID_BOTTOM = 1020;
    // Movies and Shows with Cinemora: one category row of 2:3 posters at a time (▲▼ change the category).
    private static final float C_ROW_TITLE = 300, C_TOP = 330, C_W = 220, C_H = 330, C_GAP = 30;

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Recs recs;
    private final Cinemora cinemora;
    private int cinemaRow, cinemaCol, cinemaFirst, cinemaTop;
    private final Map<Integer, Integer> rowFirst = new HashMap<>();     // each catalog row's own scroll
    // Catalog tabs (Movies and Shows with Cinemora): two rows of posters, the shelf hidden.
    private static final float K_SCALE = 0.95f, K_TITLE = 296, K_TOP = 318, K_STEP = 392;
    private final Market market = Market.get();
    private final EsthetisMrr esthetis = EsthetisMrr.get();
    private final EsthetisBoard board = EsthetisBoard.get();
    private final Runnable onBoard = () -> { if (this.tab == T_ESTHETIS) this.heroDirty = true; };
    private int boardIndex;
    private final Runnable onMrr = () -> { if (this.tab == 0) this.heroDirty = true; };
    private static final int MRR_COLOR = 0xFFB07CFF;
    private final Runnable onMarket = () -> { if (this.tab == T_MARKET || this.tab == 0) this.heroDirty = true; };
    private int marketIndex;
    private String searchQuery = "", greetingShown = "";
    private SearchInput searchInput;
    private final Runnable runSearch = this::searchNow;
    private Paint mValue, mChange;
    private final Path spark = new Path();
    private final Paint sparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    {
        sparkPaint.setStyle(Paint.Style.STROKE);
        sparkPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    /** Opens the TV's keyboard for the search field (HomeActivity holds the hidden text field). */
    interface SearchInput { void start(String current); }
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { activate(); }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer;
    private int zone = Z_SHELF;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to NT - 1) and icon-only buttons (NT to NT + 2); the three cards
    private int tab, topIndex, cardIndex;
    private List<Recs.Pick> cards = new ArrayList<>();
    private final Spring[] topFocus = new Spring[NT + 3];
    private final RectF[] topRects = new RectF[NT + 3];
    private final Spring[] cardFocus = new Spring[3];
    private final RectF[] cardRects = new RectF[3];
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lTitle, lTab, lTabOn, lCardTitle, lCardMeta, lQuote, lTip;
    private Shader lightGradient;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex;

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), quotesCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen;
    private int gridIndex;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving;
    private long lastNanos, resumedAt, lastStep;
    private int w, h;
    private float k = 1;
    private String clockText = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint, quotePaint, staleQuote;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp;
    private Paint pricePaint, changePaint, sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Map<Bitmap, BitmapShader> imageShaders = new WeakHashMap<>();
    private final Matrix imageMatrix = new Matrix();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reload(); }
        @Override public void onInputAdded(String inputId) { reload(); }
        @Override public void onInputRemoved(String inputId) { reload(); }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            recs.refreshIfStale();
            cinemora.refreshIfStale();
            if (tab == T_MARKET || tab == 0) market.refreshIfStale();
            esthetis.refreshIfStale();
            board.refreshIfStale();
            if (!greeting().equals(greetingShown)) heroDirty = true;   // morning, afternoon, evening
            checkNotices();
            updatePalette();
            checkNight(true);
            updatePicks();      // three new ones every hour
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        cinemora = Cinemora.get(a);
        click = new Click(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 9 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms).
        for (View v : new View[] {heroLayer, status, platter, tiles, drawerLayer, sheetLayer}) v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription("Free My TV");
        drawerLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < NT + 3; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < 3; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            cardRects[i] = new RectF();
        }
        makeLightButtons();
        recs.setLight(true);
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setStatusItems(statusList(a));
        setDrawerItems(drawerList(a));
        restore();
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        checkNight(false);
        resumedAt = SystemClock.uptimeMillis();
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        cinemora.addListener(this);
        market.addListener(onMarket);
        if (tab == T_MARKET || tab == 0) market.refreshIfStale();
        esthetis.addListener(onMrr);
        esthetis.refreshIfStale();
        board.addListener(onBoard);
        board.refreshIfStale();
        data.refreshIfStale();
        recs.refreshIfStale();
        cinemora.refreshIfStale();
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        checkNotices();
        reload();
        updatePalette();
        updatePicks();
        if (sheetOpen) closeSheet();
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        data.removeListener(this);
        recs.removeListener(this);
        cinemora.removeListener(this);
        market.removeListener(onMarket);
        esthetis.removeListener(onMrr);
        board.removeListener(onBoard);
        handler.removeCallbacks(runSearch);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        save();
        click.release();
    }

    /**
     * Where the focus was, kept in the process across a re-created home screen. Some TVs (this TCL has "Don't keep
     * activities" on) destroy the home screen as soon as anything opens over it, so Back from a chart, Cinemora or an
     * app built it again from the start. The Home button still starts over (onNewIntent → reset).
     */
    private static final class Saved {
        int tab, zone, shelfIndex, topIndex, cardIndex, cinemaRow, cinemaCol, cinemaFirst, cinemaTop, marketIndex, boardIndex;
        Map<Integer, Integer> rowFirst = new HashMap<>();
        String query = "";
        long at;
    }
    private static Saved saved;
    private static final long SAVED_MS = 30 * 60_000L;

    private void save() {
        Saved s = new Saved();
        s.tab = tab;
        s.zone = zone == Z_DRAWER || zone == Z_STATUS || zone == Z_HERO ? Z_SHELF : zone;
        s.shelfIndex = shelfIndex;
        s.topIndex = topIndex;
        s.cardIndex = cardIndex;
        s.cinemaRow = cinemaRow;
        s.cinemaCol = cinemaCol;
        s.cinemaFirst = cinemaFirst;
        s.cinemaTop = cinemaTop;
        s.rowFirst.putAll(rowFirst);
        s.marketIndex = marketIndex;
        s.boardIndex = boardIndex;
        s.query = searchQuery;
        s.at = SystemClock.uptimeMillis();
        saved = s;
    }

    private void restore() {
        Saved s = saved;
        saved = null;
        if (s == null || SystemClock.uptimeMillis() - s.at > SAVED_MS) return;
        if (s.tab != 0) selectTab(s.tab);
        zone = s.zone;
        shelfIndex = Math.min(s.shelfIndex, Math.max(0, shelf.size() - 1));
        topIndex = s.topIndex;
        cardIndex = s.cardIndex;
        cinemaRow = s.cinemaRow;
        cinemaCol = s.cinemaCol;
        cinemaFirst = s.cinemaFirst;
        cinemaTop = s.cinemaTop;
        rowFirst.putAll(s.rowFirst);
        marketIndex = s.marketIndex;
        boardIndex = s.boardIndex;
        searchQuery = s.query;
        if (!searchQuery.isEmpty()) cinemora.search(searchQuery);
        updateCards();      // a zone whose items aren't there (yet) falls back to the shelf
        retarget(true);
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        zone = Z_SHELF;
        shelfIndex = 0;
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
        }
        if (pickIndex != 0 && !picks.isEmpty()) {
            pickIndex = 0;
            updatePicks();
        }
        if (tab != 0) selectTab(0);
        retarget(!animated);
        motionDirty = true;
    }

    /** The three corner buttons, with their names in the current language. */
    private void makeLightButtons() {
        lightButtons.clear();
        lightButtons.add(HomeApps.action(HomeApps.LIVE_TV, "live", Ui.tr("TV aberta", "Live TV"), "live_tv"));
        lightButtons.add(HomeApps.action(HomeApps.INPUTS, "inputs", Ui.tr("Entradas HDMI", "HDMI Inputs"), "hdmi"));
        lightButtons.add(HomeApps.action(HomeApps.SETTINGS, "settings", Ui.tr("Configurações", "Settings"), "settings"));
    }

    private static String[] tabs() {
        return Ui.es ? TABS_ES : Ui.en ? TABS_EN : TABS;
    }

    @Override public void onData() {
        updatePalette();
        checkNight(true);
        layoutStatus();
        heroDirty = statusDirty = drawerDirty = true;
        platter.invalidate();
    }

    @Override public void onRecs() {
        updatePicks();
    }

    @Override public void onCinemora() {
        if (tab > 0) updateCards();
        heroDirty = true;
    }

    /** The apps (and the drawer under them) are only on Home; the other tabs take the whole screen. */
    private void updateShelf() {
        motionDirty = true;     // updateMotion shows or hides the apps
        if (tab != 0 && zone == Z_SHELF) {
            zone = Z_TOP;
            topIndex = tab;
            retarget(false);
        }
    }

    /** Cinemora's rows for the selected tab (Movies or Shows); empty: the tab shows the TMDB picks as before. */
    private List<Cinemora.Row> cinemaRows() {
        if (tab == T_SEARCH) return searchQuery.trim().equals(cinemora.foundFor()) ? cinemora.found()
                : java.util.Collections.<Cinemora.Row>emptyList();
        return TAB_KIND[tab] != null ? cinemora.rows(TAB_KIND[tab]) : java.util.Collections.<Cinemora.Row>emptyList();
    }

    void setSearchInput(SearchInput input) { searchInput = input; }

    private void searchNow() { cinemora.search(searchQuery); }

    /** What's typed in the search field (from HomeActivity's text field): searched 400 ms after the last key. */
    void setQuery(String q) {
        searchQuery = q == null ? "" : q;
        cinemaRow = cinemaCol = cinemaFirst = 0;
        handler.removeCallbacks(runSearch);
        handler.postDelayed(runSearch, 400);
        heroDirty = true;
    }

    /** "Bom dia, Guilherme" (the name from config.properties; without one, just the greeting). */
    private static String greeting() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String g = hour >= 5 && hour < 12 ? Ui.tr("Bom dia", "Good morning", "Buenos días")
                : hour >= 12 && hour < 18 ? Ui.tr("Boa tarde", "Good afternoon", "Buenas tardes")
                : Ui.tr("Boa noite", "Good evening", "Buenas noches");
        String name = Prefs.name();
        return name.isEmpty() ? g : g + ", " + name;
    }

    private boolean cinema() {
        return !cinemaRows().isEmpty();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(heroLayer, width, height);      // the catalog's second row goes down to the bottom
        measure(status, Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round(320 * kk));
        measure(tiles, width, Math.round(270 * kk));
        measure(drawerLayer, width, Math.round(600 * kk));
        measure(sheetLayer, width, height);
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(status, width - status.getMeasuredWidth(), 0);
        place(platter, 0, Math.round(750 * kk));
        place(tiles, 0, Math.round(790 * kk));
        place(drawerLayer, 0, Math.round(470 * kk));
        sheetLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        makePaints();
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        layoutShelf();
        layoutStatus();
        layoutDrawer();
        layoutLight();
        hero = null;
        heroFrom = null;
        updatePicks();
        motionDirty = true;
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTitle = Ui.text(Ui.displaySemibold, 76 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(Ui.medium, 30 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 30 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 31 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        mValue = Ui.numbers(Ui.semibold, 40 * k, L_INK);
        mChange = Ui.numbers(Ui.semibold, 26 * k, UP_COLOR);
        lQuote = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lightGradient = null;
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        // Quotes: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        quotePaint.setShadowLayer(14 * k, 0, 1 * k, 0x99000000);
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        staleQuote.setTextAlign(Paint.Align.RIGHT);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        pricePaint = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        pricePaint.setTextAlign(Paint.Align.RIGHT);
        changePaint = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        changePaint.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        boolean statusMoving = step(statusFocus, dt);
        boolean heroMoving = heroFocus.step(dt);
        boolean topMoving = step(topFocus, dt), cardsMoving = step(cardFocus, dt);
        if (topMoving) status.invalidate();
        heroMoving |= topMoving | cardsMoving;
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);

        if (drawerMoving || sheetMoving || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusMoving || statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty || heroFading) heroLayer.invalidate();
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (drawerMoving || bgFading) sky.invalidate();
        if (bgFading || (drawerWasMoving && !drawerMoving)) platter.invalidate();
        drawerWasMoving = drawerMoving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = false;
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            if (hero != null && hero.clock) {
                refreshHero(false);
                heroLayer.invalidate();
            }
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || sheetMoving || toastFading || heroFading || bgFading || nightMoving || sheetOpen || sheet.value > 0.001f
                || drawerOpen || drawer.value > 0.001f;
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value);
        float home = 1 - s, top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        float lift = -d * shift;
        show(heroLayer, top, lift);
        show(status, top, lift);
        // The apps only on Home (this runs on every frame of the drawer and after a recreated screen).
        float apps = tab == 0 ? home : 0;
        show(platter, apps, lift);
        show(tiles, apps, lift);
        float da = home * smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(s > 0.001f || sheetOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = p.pkg != null;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = pickIndex;
        for (int i = 0; i < picks.size(); i++) {
            next = (next + dir + picks.size()) % picks.size();
            if (zone != Z_HERO || picks.get(next).pkg != null) break;
        }
        if (next == pickIndex) return false;
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = recs.backdrop(currentPick());
        if (b != null) showBackdrop(b);
        spoken.focusMoved();
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private void neutralGlass() {
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lQuote.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lTip.setColor(tone(L_INK, WHITE));
        staleQuote.setColor(tone(L_FAINT, 0x99FFFFFF));
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            int[] from = frostOf(bgFrom), to = frostOf(bgTo);
            for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
            scene.setFrost(frostMix);
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private static android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        float keep = 1 - amount;
        return new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Quotes and shelf

    private void setShelf(List<HomeApps.Item> list) {
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    private void layoutShelf() {
        int n = shelf.size();
        float pad = SHELF_PAD * k, gap = TILE_GAP * k, th = L_TILE_H * k;
        float full = (CONTENT_R - MARGIN) * k;
        float tw = Math.min(TILE_MAX_W * k, (full - Math.max(0, n - 1) * gap) / Math.max(1, n));
        float width = n * tw + Math.max(0, n - 1) * gap, left = (w - width) / 2f;
        float top = L_PLATTER_TOP * k;
        platterRect.set(left - pad, top, left + width + pad, top + th + 2 * pad);
        float x = left, y = top + pad;
        for (int i = 0; i < n; i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            x += tw + gap;
        }
        crispLogos.clear();
    }

    /** Shelf layer: the glass bar and its shadow (it only changes with the background). */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty()) return;
        float radius = (TILE_RADIUS + SHELF_PAD) * k;
        tmp.set(platterRect);
        tmp.offset(0, 18 * k);
        shadow.setColor(tone(0x1F000000, 0x52000000));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        // White glass by day; smoked glass at night.
        scene.liquid(c, platterRect, radius, tone(0xFFFFFFFF, SMOKE), tone(0.42f, 0.12f), tone(0.03f, 0.08f),
                tone(30, 34) * k, tone(22, 28) * k, tone(0.65f, 0.95f), 1);
    }

    /** The quotes row's text and sizes, for one set of coins, prices and text size. */
    private final class Quotes {
        final int[] coins;
        final double[] prices;
        final float size, cap, halfCap;
        final String[] values;
        final float[] glyphs, widths;

        Quotes(int[] coins, double[] all, Paint text) {
            this.coins = coins;
            size = text.getTextSize();
            cap = Ui.cap(text);
            halfCap = Ui.halfCap(text);
            int n = coins.length;
            prices = new double[n];
            values = new String[n];
            glyphs = new float[n];
            widths = new float[n];
            for (int i = 0; i < n; i++) {
                prices[i] = all[coins[i]];
                values[i] = HomeData.compact(prices[i]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                widths[i] = text.measureText(values[i]);
            }
        }

        boolean matches(int[] now, double[] all, float textSize) {
            if (now != coins || textSize != size) return false;
            for (int i = 0; i < coins.length; i++) if (Double.compare(all[coins[i]], prices[i]) != 0) return false;
            return true;
        }
    }

    private Quotes quotes;

    /** Coin logo + price in the configured currency, in a row at the right end of the tab bar. */
    private void drawQuotes(Canvas c) {
        float right = platterRect.isEmpty() ? CONTENT_R * k : platterRect.right - SHELF_PAD * k;
        applyTone();
        Paint text = lQuote;
        // The corner buttons' names appear in this row: the quotes fade out while one of them has focus.
        float fade = 1;
        for (int i = NT; i < NT + 3; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));
        if (fade <= 0.01f) return;
        int textColor = text.getColor();
        text.setColor(Ui.alpha(textColor, fade));
        int n = HomeData.coinCount();
        if (n == 0) { text.setColor(textColor); return; }
        // Measured once per set of prices: this row is redrawn on every frame of the cards' focus animation, and
        // measuring text there cost ~3 ms a frame on a Mi Box 3.
        int[] coins = HomeData.active();
        Quotes q = quotes;
        if (q == null || !q.matches(coins, data.prices, text.getTextSize())) {
            q = quotes = new Quotes(coins, data.prices, text);
        }
        // On the tab row, at the right.
        float base = (L_TABBAR_TOP + L_TABBAR_H / 2) * k + q.halfCap;
        float cap = q.cap, gap = 6 * k, spacing = 24 * k;
        float total = spacing * (n - 1);
        for (int i = 0; i < n; i++) total += q.glyphs[i] + gap + q.widths[i];
        float x = right - total;
        for (int i = 0; i < n; i++) {
            Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], x + q.glyphs[i] / 2, base - cap / 2, cap,
                    Ui.alpha(tone(0xB38E8E93, 0x8CFFFFFF), fade));
            x += q.glyphs[i] + gap;
            c.drawText(q.values[i], x, base, text);
            x += q.widths[i] + spacing;
        }
        text.setColor(textColor);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (!stale.isEmpty()) c.drawText(stale, right - total - 24 * k, base, staleQuote);
    }

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                drawShelfTile(c, i);
            }
        }
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        int[] style = TILE_STYLE.get(it.key);
        if (it.logo != null && style != null) {
            tileFill.setShader(new LinearGradient(0, cell.top, 0, cell.bottom, style[0], style[1], Shader.TileMode.CLAMP));
            c.drawRoundRect(cell, r, r, tileFill);
            tileFill.setShader(null);
            if (fc > 0.01f) {
                tileFill.setShader(new LinearGradient(0, cell.top, 0, cell.top + cell.height() * 0.5f,
                        Ui.alpha(0xFFFFFFFF, 0.08f * fc), 0x00FFFFFF, Shader.TileMode.CLAMP));
                c.drawRoundRect(cell, r, r, tileFill);
                tileFill.setShader(null);
            }
            drawLogo(c, it, cell, s, style[2]);
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
        } else {
            if (it.tile != null) roundImage(c, it.tile, cell, r);
        }
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`. Android 12+ clips to the rounded shape; on Android 9 to
     * 11 that anti-aliased clip costs the GPU a mask on every frame of a focus animation (measured on a Mi Box 3: most of
     * a 49 ms frame), so there the image is the shader of a rounded rectangle instead, which looks the same.
     */
    private void roundImage(Canvas c, Bitmap image, RectF cell, float r) {
        if (!Ui.LEGACY) {
            clip.reset();
            clip.addRoundRect(cell, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(image, null, cell, bitmapPaint);
            c.restore();
            return;
        }
        BitmapShader shader = imageShaders.get(image);
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, shader);
        }
        imageMatrix.setScale(cell.width() / image.getWidth(), cell.height() / image.getHeight());
        imageMatrix.postTranslate(cell.left, cell.top);
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        c.drawRoundRect(cell, r, r, imageFill);
        imageFill.setShader(null);
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size: when focused it is drawn pixel for
     * pixel, without resampling.
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null);
        if (logo == null) return;
        float scale = s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width());
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        Bitmap out = downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    /** Halves the size while it can (a 2×2 average, no aliasing), then finishes at the exact size. */
    private static Bitmap downscale(Bitmap src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap b = src;
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != src) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != src && b != out) b.recycle();
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        list.addAll(HomeApps.drawerShortcuts());
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void layoutDrawer() {
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = drawerInputs > 0 && drawerInputs < n ? 2 : 1;
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        float x = left, top = ROW_TOP * k;
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k);
            x += tw + gap;
        }
        float cardsTop = CARDS_TOP * k, split = left + (right - left) * 0.585f;
        boolean wx = Prefs.weather(), qt = Prefs.quotes() && HomeData.active().length > 0;
        // A card that is off gives its room to the other one.
        weatherCard.set(left, cardsTop, qt ? split - 16 * k : right, cardsTop + CARDS_H * k);
        quotesCard.set(wx ? split + 16 * k : left, cardsTop, right, cardsTop + CARDS_H * k);
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_DRAWER;
        drawerIndex = shelf.isEmpty() ? 0 : nearest(drawerRects, tileRects[shelfIndex].centerX());
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);   // the drawer goes away: its focus doesn't need to animate
        if (!shelf.isEmpty() && drawerIndex < drawerRects.length) shelfIndex = nearest(tileRects, drawerRects[drawerIndex].centerX());
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        float dk = dark();
        if (dk < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            shadow.setColor(Ui.alpha(shadowColor, 1 - dk));
            shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
            scene.glass(c, r, radius, 0xFFFFFFFF, lightTint, 0, 0, 0.02f, 1, 1 - dk);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        pricePaint.setColor(drawerInk(0.95f));
        float titleBase = ROW_TITLE * k;
        if (drawerInputs > 0) c.drawText(Ui.tr("Entradas", "Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle);
        if (drawerInputs < drawerItems.size()) c.drawText(Ui.tr("Atalhos", "Shortcuts"), drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (f < 0.99f) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink);
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(23 * k * s);
        float room = cell.width() - 16 * k, wide = drawerLabel.measureText(it.label);
        // A long name ("Home Settings", an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.setTextSize(Math.max(18 * k * s, 23 * k * s * room / wide));
        String label = TextUtils.ellipsize(it.label, new TextPaint(drawerLabel), room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(23 * k);
    }

    /** Weather for your location: now (large), the next 6 hours, and sunrise and sunset. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, drawerCardsKey(), cc -> drawWeather(cc, r));
        else drawWeather(c, r);
    }

    private void drawWeather(Canvas c, RectF r) {
        float x = r.left + 34 * k;
        c.drawText(Prefs.city().isEmpty() ? Ui.tr("Clima", "Weather") : Prefs.city(), x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("Sem dados do clima agora.", "No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f));
        String now = HomeData.degrees(wx.temp);
        c.drawText(now, x + sw + 18 * k - Ui.leftBearing(bigTemp, now), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(Ui.en ? "H:" + HomeData.degrees(wx.max) + "   L:" + HomeData.degrees(wx.min)
                : "Máx. " + HomeData.degrees(wx.max) + "   Mín. " + HomeData.degrees(wx.min), x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k, hw = (r.right - 30 * k - hx) / 6;
        for (int i = 0; i < wx.hours.size() && i < 6; i++) {
            float hc = hx + hw * (i + 0.5f);
            c.drawText(HomeData.hour(wx.hours.get(i)), hc, r.top + 96 * k, hourPaint);
            Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
            c.drawText(HomeData.degrees(wx.hourTemp.get(i)), hc, r.top + 196 * k, hourTemp);
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", Ui.tr("Nascer ", "Sunrise ") + Ui.time(wx.sunrise), sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", Ui.tr("Pôr do sol ", "Sunset ") + Ui.time(wx.sunset), sx, base);
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    /** Prices in the configured currency with the 24 h change (green/red, like Apple's Stocks app). */
    private void drawQuotesCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) quotesSnapshot.draw(c, r, drawerCardsKey(), cc -> drawPrices(cc, r));
        else drawPrices(c, r);
    }

    /**
     * What the drawer cards show depends on: the data, the settings, the language, day or night, and the minute (the
     * sunrise and sunset, "12 min ago").
     */
    private String drawerCardsKey() {
        return data.weatherAt + "|" + data.pricesAt + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark()) + "|" + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 9 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot(), quotesSnapshot = new Snapshot();

    private void drawPrices(Canvas c, RectF r) {
        float x = r.left + 34 * k, right = r.right - 34 * k;
        c.drawText(Ui.tr("Cotações", "Crypto"), x, r.top + 54 * k, cardTitle);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        c.drawText(stale.isEmpty() ? Ui.tr("variação em 24 h", "24h change") : Ui.tr("de ", "", "de ") + stale, right, r.top + 54 * k, cardDimRight);
        float cap = Ui.cap(cardText);
        int[] coins = HomeData.active();
        for (int j = 0; j < coins.length; j++) {
            int i = coins[j];
            float base = r.top + (110 + 52 * j) * k, mid = base - cap / 2;
            float gw = Glyphs.width(getContext(), HomeData.COINS[i], cap);
            Glyphs.coin(getContext(), c, HomeData.COINS[i], x + 14 * k, mid, cap, tone(L_DIM, 0xCCFFFFFF));
            c.drawText(HomeData.coinName(i), x + Math.max(gw, 28 * k) / 2 + 30 * k, base, cardText);
            c.drawText(HomeData.brl(data.prices[i]), right - 128 * k, base, pricePaint);
            double change = data.changes[i];
            if (Double.isNaN(change)) continue;
            int color = change >= 0 ? tone(0xFF248A3D, UP_COLOR) : tone(0xFFD70015, DOWN_COLOR);
            tmp.set(right - 108 * k, mid - 17 * k, right, mid + 17 * k);
            fill.setShader(null);
            fill.setColor(Ui.alpha(color, 0.2f));
            c.drawRoundRect(tmp, 17 * k, 17 * k, fill);
            changePaint.setColor(color);
            c.drawText(HomeData.change(change), tmp.centerX(), mid + Ui.halfCap(changePaint), changePaint);
        }
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        boolean heroChanged = heroFocus.target != (!sheetOpen && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        boolean topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        heroChanged |= aim(cardFocus, !sheetOpen && zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        statusChanged |= topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
        spoken.focusMoved();
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    private void openSheet() {
        sheetOpen = true;
        gridIndex = 0;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
    }

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    private void drawSheet(Canvas c) {
        c.drawColor(0xA8000000);
        float shift = scroll.value * GRID_ROW * k;
        c.drawText(Ui.tr("Todos os Apps", "All Apps"), w / 2f, 124 * k - shift, sheetTitle);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            RectF base = new RectF(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = gridFocus[i].value;
            drawCard(c, others.get(i).tile, base, GRID_RADIUS * k, f, 0.08f);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            gridLabel.setColor(blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], base.centerX(), base.bottom + (40 + 6 * f) * k, gridLabel);
        }
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, Bitmap image, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (image != null) roundImage(c, image, cell, r);
        if (f > 0.01f) {
            sheen.setShader(new LinearGradient(0, cell.top, 0, cell.top + cell.height() * 0.5f,
                    Ui.alpha(0xFFFFFFFF, 0.1f * fc), 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(cell, r, r, sheen);
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = HomeApps.shelf(c, true);
            final List<HomeApps.Item> st = statusList(c);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = HomeApps.others(c);
            handler.post(() -> {
                setShelf(s);
                setStatusItems(st);
                setDrawerItems(d);
                if (!sheetOpen) setOthers(o);
            });
        });
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) okDown = true;
                } else {
                    if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void back() {
        if (sheetOpen) {
            closeSheet();
        } else if (zone == Z_DRAWER) {
            closeDrawer();
            retarget(false);
        } else if (tab != 0 && zone != Z_TOP) {
            zone = Z_TOP;
            topIndex = tab;
            heroDirty = true;
            retarget(false);
        } else if (zone != Z_SHELF && tab == 0) {
            if (zone >= Z_CINEMA) heroDirty = true;
            zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[shelfIndex].centerX());
                    } else {
                        return;
                    }
                } else {
                    if (drawerItems.isEmpty()) return;
                    openDrawer();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            default:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    closeDrawer();
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        HomeApps.Item it;
        RectF r;
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p));
            r = heroButton;
        } else if (zone == Z_STATUS) {
            if (statusItems.isEmpty()) return;
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else if (zone == Z_TOP) {
            if (topIndex < NT) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - NT);
            r = topRects[topIndex];
        } else if (zone == Z_MARKET) {
            click.open();
            activity.startActivity(new android.content.Intent(activity, MarketActivity.class)
                    .putExtra(MarketActivity.EXTRA_ASSET, marketIndex));
            return;
        } else if (zone == Z_BOARD) {
            click.open();
            activity.startActivity(ChartActivity.intent(activity, boardIndex));
            return;
        } else if (zone == Z_SEARCH) {
            click.open();
            if (searchInput != null) searchInput.start(searchQuery);
            return;
        } else if (zone == Z_CINEMA) {
            List<Cinemora.Row> rows = cinemaRows();
            if (cinemaRow >= rows.size() || cinemaCol >= rows.get(cinemaRow).items.size()) return;
            Cinemora.Item item = rows.get(cinemaRow).items.get(cinemaCol);
            click.open();
            if (!Cinemora.open(activity, item)) showToast(Ui.tr("Não deu para abrir o Cinemora.", "Couldn't open Cinemora."));
            return;
        } else if (zone == Z_CARDS && tab == 0) {
            // Home: the prices open Markets, Esthetis its tab; the weather only shows.
            if (cardIndex == 1) {
                selectTab(T_MARKET);
                zone = Z_MARKET;
                marketIndex = 0;
            } else if (cardIndex == 2 && mrrCard() && T_ESTHETIS < NT) {
                selectTab(T_ESTHETIS);
                zone = board.cards.isEmpty() ? Z_TOP : Z_BOARD;
                topIndex = tab;
                boardIndex = 0;
            } else {
                return;
            }
            click.open();
            heroDirty = true;
            retarget(false);
            return;
        } else if (zone == Z_CARDS) {
            Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p));
            r = cardRects[cardIndex];
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        click.open();
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            for (int i = 0; i < drawerItems.size(); i++) {
                if (drawerItems.get(i).kind == HomeApps.INPUT) {
                    drawerIndex = i;
                    break;
                }
            }
            retarget(false);
            return;
        }
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.tr("Não deu para abrir ", "Couldn't open ") + it.label + ".");
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    private static final int GRID = 9;

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        for (int i = 0; i < NT + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));
        for (int i = 0; i < Math.min(3, cardCount()); i++) out.add(a11yId(Z_CARDS, i));
        for (int i = 0; i < shelf.size(); i++) out.add(a11yId(Z_SHELF, i));
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + (i + 1) + Ui.tr(" de ", " of ") + others.size();
            case Z_TOP:
                if (i >= 3) return label(lightButtons, i - 3);
                return tabs()[i] + Ui.tr(", aba ", ", tab ") + (i + 1) + Ui.tr(" de ", " of ") + NT;
            case Z_CARDS: return tab == 0 ? homeCardText(i) : i < cards.size() ? pickText(cards.get(i)) : "";
            case Z_SHELF: return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case GRID: {
                if (!sheetOpen || i >= others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
                float y = (GRID_TOP + (i / COLUMNS - scroll.target) * GRID_ROW) * k;
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i >= NT + lightButtons.size()) return false;
                a11yRect.set(topRects[i]);
                dy = (i < NT ? heroLayer : status).getTranslationY();
                break;
            case Z_CARDS:
                if (i >= Math.min(3, cardCount())) return false;
                a11yRect.set(cardRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            case Z_DRAWER:
                if (i >= drawerRects.length) return false;
                a11yRect.set(drawerRects[i]);
                dy = drawerOpen ? 0 : h;
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (sheetOpen) return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_CARDS: return cardIndex < cardCount() ? a11yId(Z_CARDS, cardIndex) : -1;
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() ? -1 : a11yId(Z_DRAWER, drawerIndex);
            default: return -1;
        }
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == GRID) {
            if (!sheetOpen || i >= others.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (sheetOpen) return;
        switch (group) {
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                if (!drawerOpen) openDrawer();
                drawerIndex = i;
                break;
            case Z_TOP:
                if (i >= NT + lightButtons.size()) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                if (i < NT) selectTab(i);
                break;
            case Z_CARDS:
                if (i >= cardCount()) return;
                if (drawerOpen) closeDrawer();
                cardIndex = i;
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private void layoutLight() {
        if (lTab == null) return;
        // Text tabs; the focus pill extends 26 px past the text (the first tab's text starts at the margin).
        float pad = 26 * k, tabTop = L_TABBAR_TOP * k, tabBottom = (L_TABBAR_TOP + L_TABBAR_H) * k;
        float x = MARGIN * k - pad;
        for (int i = 0; i < NT; i++) {
            float tw = Math.max(lTabOn.measureText(tabs()[i]), lTab.measureText(tabs()[i]));
            topRects[i].set(x, tabTop, x + tw + 2 * pad, tabBottom);
            x = topRects[i].right + 4 * k;
        }
        float b = L_BUTTON * k, right = CONTENT_R * k, cy = L_BUTTON_CY * k;
        for (int i = 2; i >= 0; i--) {
            topRects[NT + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + L_BUTTON_GAP * k;
        }
        float top = L_CARDS_TOP * k, ch = L_CARD_H * k, g = L_CARD_GAP * k, left = MARGIN * k, tall = L_TALL_W * k;
        float wide = (CONTENT_R - MARGIN) * k - 2 * tall - 2 * g;
        cardRects[0].set(left, top, left + wide, top + ch);
        cardRects[1].set(cardRects[0].right + g, top, cardRects[0].right + g + tall, top + ch);
        cardRects[2].set(cardRects[1].right + g, top, cardRects[1].right + g + tall, top + ch);
        cinemora.setCoverSize(Math.round(C_W * k * 1.06f), Math.round(C_H * k * 1.06f));
        heroDirty = statusDirty = true;
    }

    /** The three cards of the current tab (Explore, Movies, Shows) and the background taken from the first one. */
    private void updateCards() {
        List<Recs.Pick> list = tab == 0 || tab >= T_MARKET || cinema() ? new ArrayList<>() : recs.now(TAB_KIND[tab]);
        if (tab == 0) market.refreshIfStale();     // the Home prices card
        if (zone == Z_CINEMA && !cinema()) {
            zone = Z_SHELF;
            retarget(false);
        }
        if (!samePicks(list, cards)) {
            cards = list;
            cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
            if (zone == Z_CARDS && cardCount() == 0) {
                zone = Z_SHELF;
                retarget(false);
            }
        }
        for (Recs.Pick p : recs.wanted()) recs.backdrop(p);   // all three tabs preloaded: switching tabs is instant
        Backdrop b = cards.isEmpty() ? null : recs.backdrop(cards.get(0));
        if (cards.isEmpty() || b != null) showBackdrop(b);
        heroDirty = true;
        updateShelf();
        spoken.focusMoved();    // new picks under the focused card
    }

    private void selectTab(int index) {
        if (index == tab) return;
        tab = index;
        cinemaRow = cinemaCol = cinemaFirst = cinemaTop = 0;
        rowFirst.clear();
        if (tab == T_MARKET || tab == 0) market.refreshIfStale();
        updateCards();
        heroDirty = true;
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // Drawer open: the background lightens (by day) or darkens (at night), as a filter on the drawing itself.
        android.graphics.ColorFilter v = veil(tone(0.32f, 0.42f) * Ui.clamp(drawer.value), blend(0xFFFFFFFF, 0xFF000000, night));
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = new LinearGradient(0, 0, 0, h, 0xFFF5F5F7, 0xFFE2E2E7, Shader.TileMode.CLAMP);
            if (nightGradient == null) nightGradient = new LinearGradient(0, 0, 0, h, 0xFF1C1C1E, 0xFF0B0B0D, Shader.TileMode.CLAMP);
            fill.setColorFilter(v);
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    private void drawLightTop(Canvas c) {
        applyTone();
        String hello = greeting();
        greetingShown = hello;
        c.drawText(hello, MARGIN * k - Ui.leftBearing(lTitle, hello), L_TITLE_BASE * k, lTitle);
        // Text tabs (the selected one in bold, in the main text color); with focus on the tabs, the selected one gets
        // the white pill with a shadow and grows a little (the tvOS focus).
        float focus = Ui.clamp(topFocus[tab].value);
        for (int i = 0; i < NT; i++) {
            RectF r = topRects[i];
            if (i == tab && focus > 0.01f) {
                float s = 1 + 0.05f * focus;
                cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                        r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
                float rr = cell.height() / 2;
                if (focus > 0.01f) {
                    tmp.set(cell);
                    tmp.offset(0, 8 * k * focus);
                    shadow.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.4f) * focus));
                    shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
                }
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, focus));
                c.drawRoundRect(cell, rr, rr, fill);
            }
            Paint p = i == tab ? lTabOn : lTab;
            if (i == tab) lTabOn.setColor(blend(tone(L_INK, WHITE), L_INK, focus));   // always dark on the white pill
            c.drawText(tabs()[i], r.centerX() - p.measureText(tabs()[i]) / 2, r.centerY() + Ui.halfCap(p), p);
        }
        if (tab == T_MARKET) {
            drawMarket(c);
        } else if (tab == T_ESTHETIS) {
            drawBoard(c);
        } else if (tab == T_SEARCH) {
            drawSearch(c);
        } else if (catalogTab()) {
            drawCatalog(c);
        } else if (cinema()) {
            drawCinema(c, 0, 1);
        } else {
            int top = -1;
            for (int i = 0; i < 3; i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
            for (int i = 0; i < 3; i++) if (i != top) drawLightCard(c, i);
            if (top >= 0) drawLightCard(c, top);
        }
        if (HomeData.coinCount() > 0) drawQuotes(c);
    }

    /**
     * Recommendation card: the title's photo (already at the focused card size, so it stays sharp), rounded corners and
     * a soft shadow; when focused, it grows 5%, rises and lights up its border. Below it, the title (and on the large
     * card, genre · runtime · service).
     */
    /** Home has its three cards (weather, prices, Esthetis); the other tabs have their picks. */
    private int cardCount() {
        return tab == 0 ? 3 : cards.size();
    }

    private String homeCardText(int i) {
        if (i == 0) return Ui.tr("Previsão do tempo", "Weather");
        if (i == 1) return Ui.tr("Cotações", "Prices");
        return mrrCard() ? Ui.tr("Receita recorrente do Esthetis", "Esthetis recurring revenue") : Ui.tr("Próximos dias", "Next days");
    }

    /** A Home card's base: the same size, focus, shadow and rim as the recommendation cards. Leaves `cell` set. */
    private float homeCardBase(Canvas c, int i, int top, int bottom) {
        RectF base = cardRects[i];
        float v = cardFocus[i].value, f = Ui.clamp(v), s = 1 + 0.05f * v;
        float bw = base.width(), bh = base.height(), r0 = 18 * k;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        tmp.set(cell);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r, DEEP_BLUR * k, shadowDeep);
        fill.setShader(new LinearGradient(0, cell.top, 0, cell.bottom, top, bottom, Shader.TileMode.CLAMP));
        c.drawRoundRect(cell, r, r, fill);
        fill.setShader(null);
        if (f > 0.01f) {
            rim.setShader(new LinearGradient(0, cell.top, 0, cell.bottom,
                    Ui.alpha(0xFFFFFFFF, 0.9f * f), Ui.alpha(0xFFFFFFFF, 0.35f * f), Shader.TileMode.CLAMP));
            c.drawRoundRect(cell, r, r, rim);
        }
        return s;
    }

    /** Home, wide card: the weather now (big), the next hours and the next days. */
    private void drawHomeWeather(Canvas c) {
        float s = homeCardBase(c, 0, tone(0xFFFFFFFF, 0xFF1B2433), tone(0xFFE8F1FB, 0xFF111722));
        float x = cell.left + 34 * k * s, right = cell.right - 34 * k * s, top = cell.top;
        int ink = tone(L_INK, WHITE), dim = tone(L_DIM, 0xFF98989D);
        lCardMeta.setColor(dim);
        c.drawText(Prefs.city().isEmpty() ? Ui.tr("Clima", "Weather") : Prefs.city(), x, top + 52 * k, lCardMeta);
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("Sem dados do clima agora.", "No weather data right now."), x, top + 110 * k, lCardMeta);
        } else {
            String symbol = Symbols.weather(wx.code, wx.isDay);
            float sw = Symbols.width(getContext(), symbol, 70 * k);
            Symbols.draw(getContext(), c, symbol, x + sw / 2, top + 130 * k, 70 * k, ink);
            String now = HomeData.degrees(wx.temp);
            bigTemp.setColor(ink);
            c.drawText(now, x + sw + 22 * k - Ui.leftBearing(bigTemp, now), top + 160 * k, bigTemp);
            lCardTitle.setColor(ink);
            c.drawText(HomeData.condition(wx.code, wx.isDay), x, top + 214 * k, lCardTitle);
            c.drawText((Ui.en ? "H:" : "Máx. ") + HomeData.degrees(wx.max) + (Ui.en ? "   L:" : "   Mín. ") + HomeData.degrees(wx.min),
                    x, top + 250 * k, lCardMeta);
            // Next hours, on the right.
            float hx = cell.left + cell.width() * 0.42f, hw = (right - hx) / 6;
            hourPaint.setColor(dim);
            hourTemp.setColor(ink);
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                float hc = hx + hw * (i + 0.5f);
                c.drawText(HomeData.hour(wx.hours.get(i)), hc, top + 70 * k, hourPaint);
                Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hc, top + 114 * k, 30 * k, ink);
                c.drawText(HomeData.degrees(wx.hourTemp.get(i)), hc, top + 166 * k, hourTemp);
            }
            // The week along the bottom.
            fill.setColor(tone(0x14000000, 0x1FFFFFFF));
            c.drawRect(x, top + 290 * k, right, top + 290 * k + Math.max(1, k), fill);
            int n = Math.min(7, wx.days.size());
            float dw = (right - x) / Math.max(1, n);
            java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("EEE", Ui.locale());
            for (int i = 0; i < n; i++) {
                float dc = x + dw * (i + 0.5f);
                String name = i == 0 ? Ui.tr("Hoje", "Today") : day.format(new java.util.Date(wx.days.get(i))).replace(".", "");
                if (!name.isEmpty()) name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                c.drawText(name, dc, top + 334 * k, hourPaint);
                Symbols.draw(getContext(), c, Symbols.weather(wx.dayCode.get(i), true), dc, top + 370 * k, 28 * k, ink);
                c.drawText(HomeData.degrees(wx.dayMax.get(i)) + " " + HomeData.degrees(wx.dayMin.get(i)), dc, top + 414 * k, hourPaint);
            }
        }
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(dim);
    }

    /** Home, middle card: the dollar, the euro, Ibovespa, Bitcoin and Ethereum with the day's change. */
    private void drawHomePrices(Canvas c) {
        float s = homeCardBase(c, 1, tone(0xFFFFFFFF, 0xFF1C1C1E), tone(0xFFF2F2F7, 0xFF121214));
        float x = cell.left + 26 * k * s, right = cell.right - 26 * k * s, top = cell.top;
        int ink = tone(L_INK, WHITE), dim = tone(L_DIM, 0xFF98989D);
        lCardMeta.setColor(dim);
        c.drawText(Ui.tr("Cotações", "Prices"), x, top + 50 * k, lCardMeta);
        int[] order = {1, 2, 0, 3, 4};    // Dólar, Euro, Ibovespa, Bitcoin, Ethereum
        // The last value sits as far from the bottom edge as the title from the top one; the rows spread in between.
        float pad = 50 * k - Ui.cap(lCardMeta), lastValue = cell.bottom - pad;
        float firstName = top + 96 * k, step = (lastValue - 30 * k - firstName) / (order.length - 1);
        for (int j = 0; j < order.length; j++) {
            Market.Asset a = Market.ASSETS[order[j]];
            Market.Quote q = market.quotes[order[j]];
            float base = firstName + step * j + 6 * k;
            lCardMeta.setColor(dim);
            c.drawText(a.name, x, base - 6 * k, lCardMeta);
            String ch = Market.percent(q.change);
            mChange.setColor(!(q.change < 0) ? UP_COLOR : DOWN_COLOR);
            c.drawText(ch, right - mChange.measureText(ch), base - 6 * k, mChange);
            String v = Market.format(a, q.price).replace(" pts", "");
            mChange.setColor(ink);      // the value a bit smaller than the card titles
            c.drawText(v, x, base + 24 * k, mChange);
        }
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(dim);
    }

    /** Home, third card without Esthetis: the next days, one per row. */
    private void drawHomeDays(Canvas c) {
        float s = homeCardBase(c, 2, tone(0xFFFFFFFF, 0xFF1C1C1E), tone(0xFFF2F2F7, 0xFF121214));
        float x = cell.left + 26 * k * s, right = cell.right - 26 * k * s, top = cell.top;
        int ink = tone(L_INK, WHITE), dim = tone(L_DIM, 0xFF98989D);
        lCardMeta.setColor(dim);
        c.drawText(Ui.tr("Próximos dias", "Next days"), x, top + 50 * k, lCardMeta);
        HomeData.Weather wx = data.weather;
        java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("EEEE", Ui.locale());
        for (int i = 1; i < Math.min(7, wx.days.size()); i++) {
            float base = top + 60 * k + 60 * k * i;
            String name = day.format(new java.util.Date(wx.days.get(i)));
            lCardTitle.setColor(ink);
            c.drawText(name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1), x, base, lCardTitle);
            String t = HomeData.degrees(wx.dayMax.get(i)) + "  " + HomeData.degrees(wx.dayMin.get(i));
            c.drawText(t, right - lCardMeta.measureText(t), base, lCardMeta);
        }
        lCardTitle.setColor(tone(L_INK, WHITE));
    }

    /** On Home, with esthetis_tv_token set, the third card is Esthetis's recurring revenue. */
    private boolean mrrCard() {
        return tab == 0 && EsthetisMrr.enabled();
    }

    /**
     * Esthetis's MRR: the value now, the change against last month and the last 12 months as bars (this month's in
     * color). Same size, focus and shadow as the recommendation card it replaces.
     */
    private void drawMrrCard(Canvas c) {
        RectF base = cardRects[2];
        float v = cardFocus[2].value, f = Ui.clamp(v), s = 1 + 0.05f * v;
        float bw = base.width(), bh = base.height(), r0 = 18 * k;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        tmp.set(cell);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r, DEEP_BLUR * k, shadowDeep);
        fill.setShader(new LinearGradient(0, cell.top, 0, cell.bottom, tone(0xFFFFFFFF, 0xFF241B33), tone(0xFFF1EAFB, 0xFF15111D),
                Shader.TileMode.CLAMP));
        c.drawRoundRect(cell, r, r, fill);
        fill.setShader(null);
        if (f > 0.01f) {
            rim.setShader(new LinearGradient(0, cell.top, 0, cell.bottom,
                    Ui.alpha(0xFFFFFFFF, 0.9f * f), Ui.alpha(0xFFFFFFFF, 0.35f * f), Shader.TileMode.CLAMP));
            c.drawRoundRect(cell, r, r, rim);
        }
        float x = cell.left + 26 * k * s, right = cell.right - 26 * k * s, top = cell.top;
        lCardMeta.setColor(tone(0xFF7A5AA8, 0xFFB9A3DC));
        c.drawText("Esthetis", x, top + 50 * k * s, lCardMeta);
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        c.drawText(Ui.tr("Receita recorrente", "Recurring revenue", "Ingreso recurrente"), x, top + 84 * k * s, lCardMeta);
        mValue.setColor(tone(L_INK, WHITE));
        c.drawText(EsthetisMrr.reais(esthetis.mrr), x, top + 140 * k * s, mValue);
        double ch = esthetis.change;
        if (!Double.isNaN(ch)) {
            mChange.setColor(ch >= 0 ? UP_COLOR : DOWN_COLOR);
            c.drawText(Market.percent(ch) + Ui.tr(" no mês", " this month", " en el mes"), x, top + 178 * k * s, mChange);
        } else if (esthetis.failed) {
            c.drawText(Ui.tr("Sem dados agora", "No data right now", "Sin datos ahora"), x, top + 178 * k * s, lCardMeta);
        }
        long[] hist = esthetis.history;
        if (hist.length > 0) {
            long max = 1;
            for (long h : hist) max = Math.max(max, h);
            float barsTop = top + 214 * k * s, barsBottom = cell.bottom - 60 * k * s;
            float slot = (right - x) / hist.length, gap = Math.max(3 * k, slot * 0.28f);
            for (int i = 0; i < hist.length; i++) {
                float h = (barsBottom - barsTop) * hist[i] / max;
                tmp.set(x + i * slot + gap / 2, barsBottom - Math.max(h, 3 * k), x + (i + 1) * slot - gap / 2, barsBottom);
                fill.setColor(i == hist.length - 1 ? MRR_COLOR : Ui.alpha(MRR_COLOR, 0.38f));
                c.drawRoundRect(tmp, 3 * k, 3 * k, fill);
            }
            String[] labels = esthetis.labels;
            if (labels.length == hist.length) {
                lCardMeta.setColor(tone(L_FAINT, 0xFF8E8E93));
                c.drawText(labels[0], x, cell.bottom - 26 * k * s, lCardMeta);
                String last = labels[labels.length - 1];
                c.drawText(last, right - lCardMeta.measureText(last), cell.bottom - 26 * k * s, lCardMeta);
                lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
            }
        }
    }

    private void drawLightCard(Canvas c, int i) {
        if (tab == 0) {
            if (i == 0) drawHomeWeather(c);
            else if (i == 1) drawHomePrices(c);
            else if (mrrCard()) drawMrrCard(c);
            else drawHomeDays(c);
            return;
        }
        RectF base = cardRects[i];
        Recs.Pick p = i < cards.size() ? cards.get(i) : null;
        float v = cardFocus[i].value, f = Ui.clamp(v), s = 1 + 0.05f * v;
        float bw = base.width(), bh = base.height(), r0 = 18 * k;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        Backdrop b = p == null ? null : recs.backdrop(p);
        Bitmap img = b == null ? null : (i == 0 ? b.wide : b.tall);
        if (img != null) {
            roundImage(c, img, cell, r);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        }
        if (f > 0.01f) {
            sheen.setShader(new LinearGradient(cell.left, cell.top, cell.left + cell.width() * 0.5f, cell.bottom,
                    Ui.alpha(0xFFFFFFFF, 0.22f * f), 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(cell, r, r, sheen);
        }
        if (f > 0.01f) {
            rim.setShader(new LinearGradient(0, cell.top, 0, cell.bottom,
                    Ui.alpha(0xFFFFFFFF, 0.9f * f), Ui.alpha(0xFFFFFFFF, 0.35f * f), Shader.TileMode.CLAMP));
            c.drawRoundRect(cell, r, r, rim);
        }
        if (p == null) return;
        TextPaint tp = new TextPaint(lCardTitle);
        c.drawText(TextUtils.ellipsize(p.title, tp, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 46 * k, lCardTitle);
        if (i == 0 && p.meta != null && !p.meta.isEmpty()) {
            TextPaint mp = new TextPaint(lCardMeta);
            c.drawText(TextUtils.ellipsize(p.meta, mp, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 80 * k, lCardMeta);
        }
    }

    private int cinemaVisible(float scale) {
        return (int) ((CONTENT_R - MARGIN + C_GAP * scale) / ((C_W + C_GAP) * scale));
    }

    /**
     * Search results from Cinemora: the category's name, a row of posters (the focused one grows and lights its
     * border, like the cards) and, under it, the focused title with its year and rating. ▲▼ go through the rows; the
     * next one's name shows on the right (Inter has no ▾).
     */
    private void drawCinema(Canvas c, float dy, float scale) {
        List<Cinemora.Row> rows = cinemaRows();
        cinemaRow = Math.min(cinemaRow, rows.size() - 1);
        Cinemora.Row row = rows.get(cinemaRow);
        cinemaCol = Math.min(cinemaCol, row.items.size() - 1);
        int visible = cinemaVisible(scale);
        cinemaFirst = scrolledFirst(cinemaFirst, cinemaCol, visible, row.items.size());
        boolean focusedRow = zone == Z_CINEMA && !sheetOpen;
        float left = MARGIN * k, top = (C_TOP + dy) * k, ch = C_H * k * scale;

        c.drawText(row.title, left, (C_ROW_TITLE + dy) * k, lCardTitle);
        String where = (cinemaRow + 1) + " / " + rows.size()
                + (cinemaRow + 1 < rows.size() ? Ui.tr("   ·   A seguir: ", "   ·   Next: ", "   ·   Siguiente: ")
                + rows.get(cinemaRow + 1).title : "");
        c.drawText(where, CONTENT_R * k - lCardMeta.measureText(where), (C_ROW_TITLE + dy) * k, lCardMeta);

        int focused = focusedRow ? cinemaCol : -1;
        drawPosters(c, row, cinemaFirst, visible, focused, top, scale);
        if (focused >= 0) {
            Cinemora.Item it = row.items.get(focused);
            float base = top + ch + 62 * k;
            c.drawText(TextUtils.ellipsize(it.title, new TextPaint(lCardTitle), (CONTENT_R - MARGIN) * k,
                    TextUtils.TruncateAt.END).toString(), left, base, lCardTitle);
            c.drawText(itemMeta(it), left, base + 36 * k, lCardMeta);
        }
    }

    /** "2025  ·  ★ 8.0  ·  Cinemora". */
    private static String itemMeta(Cinemora.Item it) {
        StringBuilder meta = new StringBuilder();
        if (it.year != null && !it.year.isEmpty()) meta.append(it.year);
        if (it.rating != null && !it.rating.isEmpty() && !it.rating.equals("0")) {
            meta.append(meta.length() > 0 ? "  ·  " : "").append("★ ").append(it.rating);
        }
        return meta.append(meta.length() > 0 ? "  ·  " : "").append("Cinemora").toString();
    }

    /** The first poster to show so the focused one stays in view. */
    private static int scrolledFirst(int first, int col, int visible, int n) {
        if (col < first) first = col;
        if (col >= first + visible) first = col - visible + 1;
        return Math.max(0, Math.min(first, Math.max(0, n - visible)));
    }

    /** A row of posters from `first`, at `top`; the focused one (or -1) grows, rises and lights its border. */
    private void drawPosters(Canvas c, Cinemora.Row row, int first, int visible, int focused, float top, float scale) {
        float left = MARGIN * k, cw = C_W * k * scale, ch = C_H * k * scale, gap = C_GAP * k * scale, r0 = 16 * k * scale;
        for (int pass = 0; pass < 2; pass++) {      // the focused poster last, over its neighbours
            for (int i = first; i < Math.min(row.items.size(), first + visible); i++) {
                if ((i == focused) != (pass == 1)) continue;
                Cinemora.Item it = row.items.get(i);
                float f = i == focused ? 1 : 0, s = 1 + 0.06f * f;
                float cx = left + (i - first) * (cw + gap) + cw / 2, cy = top + ch / 2 - 6 * k * f;
                cell.set(cx - cw * s / 2, cy - ch * s / 2, cx + cw * s / 2, cy + ch * s / 2);
                float r = r0 * s;
                tmp.set(cell);
                tmp.offset(0, (10 + 14 * f) * k);
                shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
                shadows.draw(c, tmp, r, DEEP_BLUR * k, shadowDeep);
                Bitmap img = cinemora.cover(it);
                if (img != null) {
                    roundImage(c, img, cell, r);
                } else {
                    fill.setShader(null);
                    fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
                    c.drawRoundRect(cell, r, r, fill);
                    TextPaint tp = new TextPaint(lCardMeta);
                    StaticLayout name = new StaticLayout(it.title, tp, Math.round(cell.width() - 24 * k),
                            Layout.Alignment.ALIGN_CENTER, 1.05f, 0, false);
                    c.save();
                    c.translate(cell.left + 12 * k, cell.centerY() - name.getHeight() / 2f);
                    name.draw(c);
                    c.restore();
                }
                if (f > 0) {
                    rim.setShader(new LinearGradient(0, cell.top, 0, cell.bottom,
                            Ui.alpha(0xFFFFFFFF, 0.9f), Ui.alpha(0xFFFFFFFF, 0.35f), Shader.TileMode.CLAMP));
                    c.drawRoundRect(cell, r, r, rim);
                }
            }
        }
    }

    /** Movies and Shows from Cinemora: only the catalog, two category rows at a time (no apps, inputs or shortcuts). */
    private boolean catalogTab() {
        return (tab == 1 || tab == 2) && cinema();
    }

    /**
     * The catalog: two rows at a time in Cinemora's order, each with its name, its posters and, on the focused one,
     * the focused title with year and rating at the right. Each row keeps its own scroll; ▲▼ go to the row above or
     * below, and the pair scrolls with the focus.
     */
    private void drawCatalog(Canvas c) {
        List<Cinemora.Row> rows = cinemaRows();
        cinemaRow = Math.min(cinemaRow, rows.size() - 1);
        if (cinemaRow < cinemaTop) cinemaTop = cinemaRow;
        if (cinemaRow > cinemaTop + 1) cinemaTop = cinemaRow - 1;
        cinemaTop = Math.max(0, Math.min(cinemaTop, rows.size() - 2));
        int visible = cinemaVisible(K_SCALE);
        boolean focusedZone = zone == Z_CINEMA && !sheetOpen;
        float left = MARGIN * k, right = CONTENT_R * k;
        for (int slot = 0; slot < 2 && cinemaTop + slot < rows.size(); slot++) {
            int index = cinemaTop + slot;
            Cinemora.Row row = rows.get(index);
            float titleY = (K_TITLE + slot * K_STEP) * k, top = (K_TOP + slot * K_STEP) * k;
            int first = firstOf(index);
            int focused = -1;
            if (focusedZone && index == cinemaRow) {
                cinemaCol = Math.min(cinemaCol, row.items.size() - 1);
                first = scrolledFirst(first, cinemaCol, visible, row.items.size());
                rowFirst.put(index, first);
                focused = cinemaCol;
            }
            c.drawText(row.title, left, titleY, lCardTitle);
            String info = focused >= 0 ? row.items.get(focused).title + "   ·   " + itemMeta(row.items.get(focused))
                    : (index + 1) + " / " + rows.size();
            info = TextUtils.ellipsize(info, new TextPaint(lCardMeta), (right - left) * 0.6f, TextUtils.TruncateAt.START).toString();
            c.drawText(info, right - lCardMeta.measureText(info), titleY, lCardMeta);
            drawPosters(c, row, first, visible, focused, top, K_SCALE);
        }
    }

    private int firstOf(int row) {
        Integer f = rowFirst.get(row);
        return f == null ? 0 : f;
    }

    /** The Markets card i (4 columns, 2 rows). */
    /** The height of a Markets or Esthetis card: two rows filling the screen down to GRID_BOTTOM. */
    private float gridCardH() {
        return (GRID_BOTTOM - M_TOP - M_GAP) / 2 * k;
    }

    private void marketRect(int i, RectF out) {
        float gap = M_GAP * k, cw = ((CONTENT_R - MARGIN) * k - 3 * gap) / 4, ch = gridCardH();
        float x = MARGIN * k + (i % 4) * (cw + gap), y = M_TOP * k + (i / 4) * (ch + gap);
        out.set(x, y, x + cw, y + ch);
    }

    /**
     * Markets: a card per asset with its name, price, the day's change (green up, red down) and the day's chart; the
     * focused one grows and lights its border, and OK opens the big chart (MarketActivity).
     */
    private void drawMarket(Canvas c) {
        int focused = zone == Z_MARKET && !sheetOpen ? marketIndex : -1;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < Market.ASSETS.length; i++) {
                if ((i == focused) != (pass == 1)) continue;
                Market.Asset a = Market.ASSETS[i];
                Market.Quote q = market.quotes[i];
                float f = i == focused ? 1 : 0, s = 1 + 0.04f * f;
                marketRect(i, tmp);
                float cx = tmp.centerX(), cy = tmp.centerY() - 4 * k * f, bw = tmp.width() * s, bh = tmp.height() * s;
                cell.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
                float r = 22 * k * s;
                tmp.set(cell);
                tmp.offset(0, (8 + 10 * f) * k);
                shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
                shadows.draw(c, tmp, r, DEEP_BLUR * k, shadowDeep);
                fill.setShader(null);
                fill.setColor(f > 0 ? 0xFFFFFFFF : tone(0xEBFFFFFF, 0xFF1C1C1E));
                c.drawRoundRect(cell, r, r, fill);
                int ink = f > 0 ? L_INK : tone(L_INK, WHITE), dim = f > 0 ? L_DIM : tone(L_DIM, 0x99FFFFFF);
                float x = cell.left + 26 * k;
                lCardMeta.setColor(dim);
                c.drawText(a.name, x, cell.top + 46 * k, lCardMeta);
                mValue.setColor(ink);
                c.drawText(Market.format(a, q.price), x, cell.top + 100 * k, mValue);
                boolean up = !(q.change < 0);
                mChange.setColor(up ? UP_COLOR : DOWN_COLOR);
                c.drawText(Market.percent(q.change), x, cell.top + 140 * k, mChange);
                if (q.closes.length > 1) {
                    double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
                    for (double v : q.closes) { min = Math.min(min, v); max = Math.max(max, v); }
                    if (max - min < 1e-9) max = min + 1;
                    float sl = x, sr = cell.right - 26 * k, st = cell.top + 160 * k, sb = cell.bottom - 22 * k;
                    spark.reset();
                    for (int j = 0; j < q.closes.length; j++) {
                        float px = sl + (sr - sl) * j / (q.closes.length - 1f);
                        float py = (float) (sb - (sb - st) * (q.closes[j] - min) / (max - min));
                        if (j == 0) spark.moveTo(px, py); else spark.lineTo(px, py);
                    }
                    sparkPaint.setStrokeWidth(3 * k);
                    sparkPaint.setColor(up ? UP_COLOR : DOWN_COLOR);
                    c.drawPath(spark, sparkPaint);
                }
            }
        }
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
    }

    /** The Esthetis card i (3 columns, 2 rows). */
    private void boardRect(int i, RectF out) {
        float gap = M_GAP * k, cw = ((CONTENT_R - MARGIN) * k - 2 * gap) / 3, ch = gridCardH();
        float x = MARGIN * k + (i % 3) * (cw + gap), y = M_TOP * k + (i / 3) * (ch + gap);
        out.set(x, y, x + cw, y + ch);
    }

    /**
     * Esthetis: six cards (revenue, subscriptions, new users, site visitors, Google clicks and impressions), each with
     * its number, the change and its chart; OK opens it full screen (ChartActivity).
     */
    private void drawBoard(Canvas c) {
        List<EsthetisBoard.Card> list = board.cards;
        if (list.isEmpty()) {
            String msg = board.failed ? Ui.tr("Não deu para carregar os dados do Esthetis agora.", "Couldn't load Esthetis's data right now.",
                    "No se pudieron cargar los datos de Esthetis ahora.") : Ui.tr("Carregando os dados do Esthetis…", "Loading Esthetis's data…",
                    "Cargando los datos de Esthetis…");
            c.drawText(msg, MARGIN * k, (M_TOP + 60) * k, lCardMeta);
            return;
        }
        int focused = zone == Z_BOARD && !sheetOpen ? boardIndex : -1;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < Math.min(6, list.size()); i++) {
                if ((i == focused) != (pass == 1)) continue;
                EsthetisBoard.Card card = list.get(i);
                float f = i == focused ? 1 : 0, s = 1 + 0.03f * f;
                boardRect(i, tmp);
                float cx = tmp.centerX(), cy = tmp.centerY() - 4 * k * f, bw = tmp.width() * s, bh = tmp.height() * s;
                cell.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
                float r = 22 * k * s;
                tmp.set(cell);
                tmp.offset(0, (8 + 10 * f) * k);
                shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
                shadows.draw(c, tmp, r, DEEP_BLUR * k, shadowDeep);
                fill.setShader(null);
                fill.setColor(f > 0 ? 0xFFFFFFFF : tone(0xEBFFFFFF, 0xFF1C1C1E));
                c.drawRoundRect(cell, r, r, fill);
                int ink = f > 0 ? L_INK : tone(L_INK, WHITE), dim = f > 0 ? L_DIM : tone(L_DIM, 0x99FFFFFF);
                float x = cell.left + 26 * k, right = cell.right - 26 * k;
                lCardMeta.setColor(dim);
                c.drawText(card.title, x, cell.top + 46 * k, lCardMeta);
                if (card.missing != null) {
                    c.drawText(card.missing, x, cell.top + 100 * k, lCardMeta);
                    continue;
                }
                mValue.setColor(ink);
                c.drawText(card.value, x, cell.top + 100 * k, mValue);
                float lx = x;
                if (!Double.isNaN(card.change)) {
                    String ch = Market.percent(card.change);
                    mChange.setColor(card.change >= 0 ? UP_COLOR : DOWN_COLOR);
                    c.drawText(ch, lx, cell.top + 140 * k, mChange);
                    lx += mChange.measureText(ch) + 14 * k;
                }
                c.drawText(TextUtils.ellipsize(card.sub, new TextPaint(lCardMeta), right - lx, TextUtils.TruncateAt.END).toString(),
                        lx, cell.top + 140 * k, lCardMeta);
                double[] series = card.series;
                if (series.length < 2) continue;
                float st = cell.top + 162 * k, sb = cell.bottom - 20 * k;
                double max = 0, min = card.bars ? 0 : Double.MAX_VALUE;
                for (double v : series) { max = Math.max(max, v); if (!card.bars) min = Math.min(min, v); }
                if (max - min < 1e-9) max = min + 1;
                if (card.bars) {
                    float slot = (right - x) / series.length, gap = Math.max(2 * k, slot * 0.25f);
                    for (int j = 0; j < series.length; j++) {
                        float bh2 = (float) ((sb - st) * (series[j] - min) / (max - min));
                        tmp.set(x + j * slot + gap / 2, sb - Math.max(bh2, 2 * k), x + (j + 1) * slot - gap / 2, sb);
                        fill.setColor(j == series.length - 1 ? card.color : Ui.alpha(card.color, 0.4f));
                        c.drawRoundRect(tmp, 2 * k, 2 * k, fill);
                    }
                } else {
                    spark.reset();
                    for (int j = 0; j < series.length; j++) {
                        float px = x + (right - x) * j / (series.length - 1f);
                        float py = (float) (sb - (sb - st) * (series[j] - min) / (max - min));
                        if (j == 0) spark.moveTo(px, py); else spark.lineTo(px, py);
                    }
                    sparkPaint.setStrokeWidth(3 * k);
                    sparkPaint.setColor(card.color);
                    c.drawPath(spark, sparkPaint);
                }
            }
        }
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
    }

    /** Search: the field (OK opens the TV's keyboard; the mic in it works too) and Cinemora's results under it. */
    private void drawSearch(Canvas c) {
        boolean on = zone == Z_SEARCH && !sheetOpen;
        float left = MARGIN * k, top = M_TOP * k, h = S_H * k, rr = h / 2;
        cell.set(left, top, left + S_W * k, top + h);
        if (on) {
            tmp.set(cell);
            tmp.offset(0, 8 * k);
            shadow.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.4f)));
            shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
        }
        fill.setShader(null);
        fill.setColor(on ? 0xFFFFFFFF : tone(0xB3FFFFFF, 0x33FFFFFF));
        c.drawRoundRect(cell, rr, rr, fill);
        boolean empty = searchQuery.isEmpty();
        String text = empty ? Ui.tr("Buscar filmes e séries no Cinemora", "Search Cinemora's movies and shows",
                "Buscar películas y series en Cinemora") : searchQuery;
        lTab.setColor(on ? (empty ? L_DIM : L_INK) : tone(empty ? L_DIM : L_INK, empty ? 0x99FFFFFF : WHITE));
        c.drawText(TextUtils.ellipsize(text, new TextPaint(lTab), cell.width() - 64 * k, TextUtils.TruncateAt.END).toString(),
                cell.left + 32 * k, cell.centerY() + Ui.halfCap(lTab), lTab);
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        String note = null;
        if (!cinemora.available()) {
            note = Ui.tr("Instale o Cinemora e entre na sua conta para buscar.", "Install Cinemora and sign in to search.",
                    "Instala Cinemora e inicia sesión para buscar.");
        } else if (!empty && searchQuery.trim().equals(cinemora.foundFor()) && cinemora.found().isEmpty()) {
            note = Ui.tr("Nada encontrado para “", "Nothing found for “", "Nada encontrado para “") + searchQuery.trim() + "”.";
        } else if (empty) {
            note = Ui.tr("OK para digitar ou falar pelo teclado da TV.", "OK to type or speak with the TV's keyboard.",
                    "OK para escribir o hablar con el teclado del TV.");
        }
        if (note != null) c.drawText(note, left, top + h + 60 * k, lCardMeta);
        if (cinema()) drawCinema(c, S_DY, S_SCALE);
    }

    /**
     * Live TV, HDMI Inputs and Settings: icon-only glass circles (light by day, smoked at night); the focused one turns
     * white with a dark icon (the tvOS focus) and its name appears below it.
     */
    private void drawLightButtons(Canvas c) {
        applyTone();
        for (int i = 0; i < 3; i++) {
            RectF r = topRects[NT + i];
            float f = Ui.clamp(topFocus[NT + i].value), s = 1 + 0.1f * f, rad = r.width() / 2 * s;
            cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad);
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
            scene.liquid(c, cell, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                    tone(0.6f, 0.45f), 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawCircle(cell.centerX(), cell.centerY(), rad, fill);
            }
            Symbols.draw(getContext(), c, lightButtons.get(i).symbol, cell.centerX(), cell.centerY(), 27 * k * s,
                    blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
            if (f > 0.01f) {
                String label = lightButtons.get(i).label;
                float half = lTip.measureText(label) / 2;
                float x = Math.min(r.centerX(), CONTENT_R * k - half);
                lTip.setAlpha(Math.round(255 * f));
                c.drawText(label, x, r.bottom + (40 + 4 * f) * k, lTip);
            }
        }
    }

    private boolean moveLight(int dx, int dy) {
        int from = zone;
        switch (zone) {
            case Z_SHELF:
                if (dy >= 0 || shelf.isEmpty()) return false;
                if (tab == T_MARKET) {
                    zone = Z_MARKET;
                    marketIndex = 4 + marketIndex % 4;
                    heroDirty = true;
                } else if (tab == T_ESTHETIS && !board.cards.isEmpty()) {
                    zone = Z_BOARD;
                    boardIndex = 3 + boardIndex % 3;
                    heroDirty = true;
                } else if (tab == T_SEARCH && !cinema()) {
                    zone = Z_SEARCH;
                    heroDirty = true;
                } else if (cinema()) {
                    zone = Z_CINEMA;
                    heroDirty = true;
                } else if (cardCount() == 0) {
                    zone = Z_TOP;
                    topIndex = tab;
                } else {
                    zone = Z_CARDS;
                    cardIndex = nearest(cardRects, cardCount(), tileRects[shelfIndex].centerX());
                }
                break;
            case Z_CARDS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(cardCount() - 1, cardIndex + dx));
                    if (next == cardIndex) return true;
                    cardIndex = next;
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = tab;     // as on Apple TV: moving up to the tabs focuses the selected tab
                } else {
                    zone = Z_SHELF;
                    if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, cardRects[cardIndex].centerX());
                }
                break;
            case Z_TOP:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(NT + 2, topIndex + dx));
                    if (next == topIndex) return true;
                    topIndex = next;
                    if (topIndex < NT) selectTab(topIndex);   // like Apple TV tabs: focusing a tab selects it
                } else if (dy > 0) {
                    if (tab == T_MARKET) {
                        zone = Z_MARKET;
                        marketIndex = marketIndex % 4;
                        heroDirty = true;
                    } else if (tab == T_ESTHETIS && !board.cards.isEmpty()) {
                        zone = Z_BOARD;
                        boardIndex = boardIndex % 3;
                        heroDirty = true;
                    } else if (tab == T_SEARCH) {
                        zone = Z_SEARCH;
                        heroDirty = true;
                    } else if (cinema()) {
                        zone = Z_CINEMA;
                        heroDirty = true;
                    } else if (cardCount() == 0) {
                        zone = Z_SHELF;
                    } else {
                        zone = Z_CARDS;
                        cardIndex = nearest(cardRects, cardCount(), topRects[topIndex].centerX());
                    }
                } else {
                    return true;
                }
                break;
            case Z_CINEMA: {
                List<Cinemora.Row> rows = cinemaRows();
                if (rows.isEmpty()) return false;
                if (catalogTab() && dy != 0) {
                    int next = cinemaRow + dy;
                    if (next >= rows.size()) return true;       // the last row: nothing below (no shelf here)
                    if (next < 0) {
                        zone = Z_TOP;
                        topIndex = tab;
                    } else {
                        // Keep the focus in the same column on screen.
                        int onScreen = cinemaCol - firstOf(cinemaRow);
                        cinemaRow = next;
                        cinemaCol = Math.min(rows.get(next).items.size() - 1, firstOf(next) + Math.max(0, onScreen));
                    }
                    heroDirty = true;
                    break;
                }
                if (dx != 0) {
                    int n = rows.get(Math.min(cinemaRow, rows.size() - 1)).items.size();
                    int next = Math.max(0, Math.min(n - 1, cinemaCol + dx));
                    if (next == cinemaCol) return true;
                    cinemaCol = next;
                } else if (dy > 0) {
                    if (cinemaRow + 1 < rows.size()) {
                        cinemaRow++;
                        cinemaCol = cinemaFirst = 0;
                    } else {
                        zone = Z_SHELF;
                    }
                } else if (cinemaRow > 0) {
                    cinemaRow--;
                    cinemaCol = cinemaFirst = 0;
                } else if (tab == T_SEARCH) {
                    zone = Z_SEARCH;
                } else {
                    zone = Z_TOP;
                    topIndex = tab;
                }
                heroDirty = true;
                break;
            }
            case Z_MARKET: {
                int row = marketIndex / 4, col = marketIndex % 4;
                if (dx != 0) {
                    int next = Math.max(0, Math.min(3, col + dx));
                    if (next == col) return true;
                    marketIndex = row * 4 + next;
                } else if (dy < 0) {
                    if (row == 0) {
                        zone = Z_TOP;
                        topIndex = tab;
                    } else {
                        marketIndex = col;
                    }
                } else if (row == 1) {
                    zone = Z_SHELF;
                } else {
                    marketIndex = 4 + col;
                }
                heroDirty = true;
                break;
            }
            case Z_BOARD: {
                int row = boardIndex / 3, col = boardIndex % 3;
                if (dx != 0) {
                    int next = Math.max(0, Math.min(2, col + dx));
                    if (next == col) return true;
                    boardIndex = row * 3 + next;
                } else if (dy < 0) {
                    if (row == 0) {
                        zone = Z_TOP;
                        topIndex = tab;
                    } else {
                        boardIndex = col;
                    }
                } else if (row == 1) {
                    zone = Z_SHELF;
                } else {
                    boardIndex = 3 + col;
                }
                heroDirty = true;
                break;
            }
            case Z_SEARCH:
                if (dx != 0) return true;
                if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = tab;
                } else if (cinema()) {
                    zone = Z_CINEMA;
                    cinemaRow = cinemaCol = cinemaFirst = 0;
                } else {
                    zone = Z_SHELF;
                }
                heroDirty = true;
                break;
            default:
                return false;
        }
        if (zone == Z_SHELF && tab != 0) {
            zone = from;    // the apps are only on Home: nothing below the last row here
            return true;
        }
        retarget(false);
        click.move();
        return true;
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
