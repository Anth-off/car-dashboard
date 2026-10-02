package fr.cockpit.dashboard.integrations

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MusicState(
    val title: String = "",
    val artist: String = "",
    val playing: Boolean = false,
    val connected: Boolean = false,
    val canPlayPause: Boolean = false,
    val canNext: Boolean = false,
    val canPrevious: Boolean = false,
)

/**
 * Controls the existing Apple Music session. Notification access is needed by Android to
 * discover another application's media session; notification contents are never read.
 */
class MusicRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(MediaSessionManager::class.java)
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    private val listenerComponent = ComponentName(appContext, MusicNotificationListener::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = mutableState.asStateFlow()

    @Volatile private var controller: MediaController? = null
    private var registered = false
    private var controllerCallback: MediaController.Callback? = null

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        if (hasAccess()) selectController(sessions.orEmpty()) else clearAccess()
    }

    init {
        refresh()
    }

    fun hasAccess(): Boolean = runCatching {
        notificationManager.isNotificationListenerAccessGranted(listenerComponent)
    }.getOrDefault(false)

    /** The user must explicitly grant this special access in Android settings. */
    fun requestAccessIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Call on activity resume, including after returning from access settings. */
    fun refresh() {
        onMain {
            if (!hasAccess()) {
                clearAccess()
                return@onMain
            }
            try {
                if (!registered) {
                    manager.addOnActiveSessionsChangedListener(
                        sessionsChanged, listenerComponent, mainHandler,
                    )
                    registered = true
                }
                selectController(manager.getActiveSessions(listenerComponent))
            } catch (_: SecurityException) {
                clearAccess()
            } catch (_: RuntimeException) {
                clearAccess()
            }
        }
    }

    /** Returns false when no supported control can be sent to Apple Music. */
    fun togglePlayback(): Boolean = control { active, playback ->
        val actions = playback.actions
        val playing = isPlaying(playback)
        when {
            playing && actions.has(PlaybackState.ACTION_PAUSE) -> active.transportControls.pause()
            !playing && actions.has(PlaybackState.ACTION_PLAY) -> active.transportControls.play()
            actions.has(PlaybackState.ACTION_PLAY_PAUSE) -> {
                val accepted = active.dispatchMediaButtonEvent(
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
                )
                active.dispatchMediaButtonEvent(
                    KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
                )
                return@control accepted
            }
            else -> return@control false
        }
        true
    }

    fun next(): Boolean = control { active, playback ->
        if (!playback.actions.has(PlaybackState.ACTION_SKIP_TO_NEXT)) return@control false
        active.transportControls.skipToNext()
        true
    }

    fun previous(): Boolean = control { active, playback ->
        if (!playback.actions.has(PlaybackState.ACTION_SKIP_TO_PREVIOUS)) return@control false
        active.transportControls.skipToPrevious()
        true
    }

    /** Launching is kept separate from transport controls, so an unavailable session stays explicit. */
    fun launchAppleMusic(): Boolean = runCatching {
        val intent = appContext.packageManager.getLaunchIntentForPackage(APPLE_MUSIC_PACKAGE)
            ?: return false
        appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    internal fun onListenerUnavailable() {
        onMain { clearAccess() }
    }

    /** Repair a disconnected listener only while Android still grants the user's original access. */
    internal fun requestListenerReconnect(): Boolean {
        if (!hasAccess()) return false
        return runCatching { NotificationListenerService.requestRebind(listenerComponent) }.isSuccess
    }

    private fun control(command: (MediaController, PlaybackState) -> Boolean): Boolean {
        if (!hasAccess()) {
            onMain { clearAccess() }
            return false
        }
        val active = controller ?: return false
        return try {
            val playback = active.playbackState ?: return false
            command(active, playback)
        } catch (_: RuntimeException) {
            onMain {
                // A dead session must not leave stale track information on the dashboard.
                if (controller === active) disconnectController()
            }
            false
        }
    }

    // Every session/callback mutation happens on the main looper.
    private fun selectController(sessions: List<MediaController>) {
        val available = sessions.filter { it.packageName == APPLE_MUSIC_PACKAGE }
        val selected = available.firstOrNull { isPlaying(it.playbackState) }
            ?: available.firstOrNull()
        if (selected == null) {
            disconnectController()
            return
        }
        if (controller?.sessionToken == selected.sessionToken) {
            publish(selected)
            return
        }
        disconnectController()
        controller = selected
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                if (controller === selected) publish(selected)
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (controller === selected) publish(selected)
            }

            override fun onSessionDestroyed() {
                if (controller === selected) {
                    disconnectController()
                    // Let the media service update its list before discovering another session.
                    mainHandler.post { refresh() }
                }
            }
        }
        controllerCallback = callback
        try {
            selected.registerCallback(callback, mainHandler)
            publish(selected)
        } catch (_: RuntimeException) {
            disconnectController()
        }
    }

    private fun publish(active: MediaController) {
        if (!hasAccess()) {
            clearAccess()
            return
        }
        try {
            val metadata = active.metadata
            val playback = active.playbackState
            val actions = playback?.actions ?: 0L
            val playing = isPlaying(playback)
            mutableState.value = MusicState(
                title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: metadata?.description?.title?.toString().orEmpty(),
                artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
                playing = playing,
                connected = true,
                canPlayPause = actions.has(PlaybackState.ACTION_PLAY_PAUSE) ||
                    actions.has(if (playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY),
                canNext = actions.has(PlaybackState.ACTION_SKIP_TO_NEXT),
                canPrevious = actions.has(PlaybackState.ACTION_SKIP_TO_PREVIOUS),
            )
        } catch (_: RuntimeException) {
            if (controller === active) disconnectController()
        }
    }

    private fun clearAccess() {
        if (registered) {
            runCatching { manager.removeOnActiveSessionsChangedListener(sessionsChanged) }
            registered = false
        }
        disconnectController()
    }

    private fun disconnectController() {
        val previous = controller
        val callback = controllerCallback
        controller = null
        controllerCallback = null
        if (previous != null && callback != null) {
            runCatching { previous.unregisterCallback(callback) }
        }
        mutableState.value = MusicState()
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post { action() }
    }

    private fun isPlaying(playback: PlaybackState?): Boolean = when (playback?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> true
        else -> false
    }

    private fun Long.has(action: Long): Boolean = this and action != 0L

    companion object {
        const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"

        @Volatile private var instance: MusicRepository? = null

        fun get(context: Context): MusicRepository = instance ?: synchronized(this) {
            instance ?: MusicRepository(context).also { instance = it }
        }
    }
}
