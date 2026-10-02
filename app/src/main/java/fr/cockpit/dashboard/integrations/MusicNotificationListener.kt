package fr.cockpit.dashboard.integrations

import android.service.notification.NotificationListenerService

/**
 * Android uses this user-approved component to authorize MediaSessionManager access.
 * It intentionally implements no notification-posted/removed callbacks and stores no notifications.
 */
class MusicNotificationListener : NotificationListenerService() {
    // One request per disconnection; reset only when Android actually reconnects the listener.
    private var reconnectRequested = false

    override fun onListenerConnected() {
        super.onListenerConnected()
        reconnectRequested = false
        MusicRepository.get(this).refresh()
    }

    override fun onListenerDisconnected() {
        val music = MusicRepository.get(this)
        music.onListenerUnavailable()
        super.onListenerDisconnected()
        if (!reconnectRequested) reconnectRequested = music.requestListenerReconnect()
    }
}
