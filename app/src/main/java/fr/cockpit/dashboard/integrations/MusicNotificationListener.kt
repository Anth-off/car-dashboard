package fr.cockpit.dashboard.integrations

import android.service.notification.NotificationListenerService

/**
 * Android uses this user-approved component to authorize MediaSessionManager access.
 * It intentionally implements no notification-posted/removed callbacks and stores no notifications.
 */
class MusicNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        MusicRepository.get(this).refresh()
    }

    override fun onListenerDisconnected() {
        MusicRepository.get(this).onListenerUnavailable()
        super.onListenerDisconnected()
    }
}
