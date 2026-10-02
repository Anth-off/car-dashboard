package fr.cockpit.dashboard.navigation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** French turn announcements temporarily duck music; this app never owns a media playback session. */
internal class NavigationVoice(context: Context, onSpoken: (GuidanceAnnouncement) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var engine: TextToSpeech? = null
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
        }, main).build()
    private val queue: NavigationSpeechQueue = NavigationSpeechQueue(
        now = SystemClock::elapsedRealtime,
        start = { announcement, id ->
            audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED &&
                engine?.speak(announcement.text, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.SUCCESS
        },
        halt = { engine?.stop(); Unit },
        releaseFocus = { audioManager.abandonAudioFocusRequest(focus); Unit },
        onSpoken = onSpoken,
    )

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            // Engine callbacks can arrive on binder threads, including before construction returns.
            main.post {
                val language = if (status == TextToSpeech.SUCCESS) engine?.setLanguage(Locale.FRANCE) else null
                engine?.setAudioAttributes(attributes)
                queue.setReady(language != null && language >= TextToSpeech.LANG_AVAILABLE)
            }
        }
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { main.post { queue.completed(id) } }
            @Deprecated("Legacy TTS callback")
            override fun onError(id: String?) { main.post { queue.completed(id) } }
            override fun onError(id: String?, errorCode: Int) { main.post { queue.completed(id) } }
            override fun onStop(id: String?, interrupted: Boolean) { main.post { queue.completed(id) } }
        })
    }

    fun setContext(context: String) = queue.setContext(context)
    fun speak(announcement: GuidanceAnnouncement) = queue.offer(announcement)
    fun stop() = queue.stop()
}
