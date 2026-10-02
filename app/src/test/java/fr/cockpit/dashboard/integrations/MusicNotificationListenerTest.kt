package fr.cockpit.dashboard.integrations

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNotificationListenerService
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class MusicNotificationListenerTest {
    private lateinit var context: Context
    private lateinit var notifications: NotificationManager
    private lateinit var sessions: MediaSessionManager
    private lateinit var music: MusicRepository
    private lateinit var service: ServiceController<MusicNotificationListener>

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notifications = context.getSystemService(NotificationManager::class.java)
        sessions = context.getSystemService(MediaSessionManager::class.java)
        shadowOf(sessions).clearControllers()
        grantAccess(false)
        music = MusicRepository.get(context)
        music.refresh()
        shadowOf(Looper.getMainLooper()).idle()
        ShadowNotificationListenerService.reset()
        service = Robolectric.buildService(MusicNotificationListener::class.java).create()
    }

    @After fun tearDown() {
        grantAccess(false)
        music.refresh()
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(sessions).clearControllers()
        service.destroy()
    }

    @Test fun grantedAccessReconnectsOnceAndClearsTheDisconnectedSession() {
        val controller = connectAppleMusic()
        assertEquals("Morceau en cours", music.state.value.title)
        assertTrue(music.state.value.connected)
        assertEquals(1, shadowOf(controller).callbacks.size)

        service.get().onListenerDisconnected()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("A disconnect does not revoke the user's authorization", music.hasAccess())
        assertEquals(MusicState(), music.state.value)
        assertTrue(shadowOf(controller).callbacks.isEmpty())
        assertEquals(1, ShadowNotificationListenerService.getRebindRequestCount())

        service.get().onListenerDisconnected()
        assertEquals("Repeated disconnect callbacks must not loop", 1,
            ShadowNotificationListenerService.getRebindRequestCount())
    }

    @Test fun revokedAccessClearsTheSessionWithoutRequestingReconnection() {
        connectAppleMusic()
        grantAccess(false)

        service.get().onListenerDisconnected()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(music.hasAccess())
        assertEquals(MusicState(), music.state.value)
        assertEquals(0, ShadowNotificationListenerService.getRebindRequestCount())
    }

    @Test fun reconnectionRestoresMusicWithoutTogglingAccessOrDuplicatingCallbacks() {
        val controller = connectAppleMusic()
        music.refresh()
        assertEquals("Normal refresh must not force a listener reconnect", 0,
            ShadowNotificationListenerService.getRebindRequestCount())

        service.get().onListenerDisconnected()
        service.get().onListenerConnected()
        service.get().onListenerConnected()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(music.hasAccess())
        assertTrue(music.state.value.connected)
        assertEquals("Morceau en cours", music.state.value.title)
        assertEquals(1, shadowOf(controller).callbacks.size)
        assertEquals(1, ShadowNotificationListenerService.getRebindRequestCount())

        service.get().onListenerDisconnected()
        assertEquals("A later independent interruption can reconnect again", 2,
            ShadowNotificationListenerService.getRebindRequestCount())
    }

    private fun grantAccess(granted: Boolean) {
        shadowOf(notifications).setNotificationListenerAccessGranted(
            ComponentName(context, MusicNotificationListener::class.java), granted,
        )
    }

    private fun connectAppleMusic(): MediaController {
        grantAccess(true)
        // Robolectric's MediaSession constructor is intentionally a no-op. Supply its controller
        // with a no-op platform binder, as Robolectric's own ShadowMediaController tests do.
        val binderClass = Class.forName("android.media.session.ISessionController")
        val binder = ReflectionHelpers.createNullProxy(binderClass)
        val token = MediaSession.Token::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType, binderClass)
            .apply { isAccessible = true }.newInstance(0, binder)
        val controller = MediaController(context, token)
        shadowOf(controller).apply {
            setPackageName(MusicRepository.APPLE_MUSIC_PACKAGE)
            setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Morceau en cours").build())
            setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT).build())
        }
        shadowOf(sessions).addController(controller)
        service.get().onListenerConnected()
        shadowOf(Looper.getMainLooper()).idle()
        return controller
    }
}
