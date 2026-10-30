package io.freemytv;

import android.service.notification.NotificationListenerService;

/**
 * Does nothing with notifications: with notification access granted to it, Android lets {@link NowPlaying} read the
 * other apps' media sessions (Spotify's song, cover and controls) for the screensaver.
 */
public class NowPlayingListener extends NotificationListenerService {
}
