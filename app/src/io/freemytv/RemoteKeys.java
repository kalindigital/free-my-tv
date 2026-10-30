package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * The remote's app buttons on TCL TVs. The stock home screen opens the apps for Netflix, YouTube and Prime (and TCL
 * Channel); with it off, the keys still arrive, so we open the app the person picked (Settings > Remote Buttons).
 * Disney and Globoplay are opened by the system itself, so they are not listed. The YouTube button (BUTTON_3) never
 * reaches an app's window; only the accessibility service's key filter (RemoteKeyService) sees it.
 * The dashboard button (the gear) asks the system UI for TOGGLE_NOTIFICATION_HANDLER_PANEL, which it only sends to
 * system apps (Google TV's home screen, off in Free My TV mode): we open our own panel (PanelActivity) instead.
 * The favorites button (the star) "locks" the TV: it opens the screensaver. The power button can't do that: the system
 * handles it before any app sees it.
 */
final class RemoteKeys {
    private RemoteKeys() {}

    /** TCL's dashboard (gear) button. */
    static final int DASHBOARD = 4077;
    /** TCL's favorites (star) button. */
    static final int FAVORITES = 4090;

    /** The screensaver now (Free My TV's, in Free My TV mode); if the TV won't start it, the preview in Settings. */
    static void openScreensaver(Context c) {
        try {
            c.startActivity(new Intent().setClassName("com.android.systemui", "com.android.systemui.Somnambulator")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            AppLog.w("screensaver: " + e.getMessage());
            c.startActivity(new Intent(c, SettingsActivity.class).putExtra("screensaver", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    /** The key codes are TCL's: on other brands they can be other buttons, so nothing here runs there. */
    static boolean supported() {
        return "TCL".equalsIgnoreCase(Build.MANUFACTURER) || "TCL".equalsIgnoreCase(Build.BRAND);
    }

    /** Opens the app for this key on its first press. True if the key is ours and in use (it is then consumed). */
    static boolean onKey(Context c, KeyEvent e) {
        if (!supported()) return false;
        // BUTTON_3 is also a game controller's button: keys from a gamepad or a joystick are left alone.
        int source = e.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;
        if (e.getKeyCode() == DASHBOARD) {
            // Pressed again with the panel open: PanelActivity closes itself (onNewIntent).
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                c.startActivity(new Intent(c, PanelActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return true;
        }
        if (e.getKeyCode() == FAVORITES) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                AppLog.w("remote key favorites -> screensaver");
                openScreensaver(c);
            }
            return true;
        }
        boolean ours = false;
        for (int key : Prefs.KEYS) ours |= key == e.getKeyCode();
        if (!ours) return false;
        String pkg = Prefs.keyApp(e.getKeyCode());
        if (pkg.isEmpty()) return false;   // "Nothing": the key goes on as if we weren't here
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            Intent intent = HomeApps.launchIntent(c.getPackageManager(), pkg);
            AppLog.w("remote key " + e.getKeyCode() + " -> " + pkg + (intent == null ? " (not installed)" : ""));
            if (intent != null) c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        return true;
    }
}
