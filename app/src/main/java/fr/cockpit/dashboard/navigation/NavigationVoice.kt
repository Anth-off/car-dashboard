package fr.cockpit.dashboard.navigation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** French turn announcements temporarily duck music; this app never owns a media playback session. */
internal class NavigationVoice(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var ready = false
    private var pending: String? = null
    private var engine: TextToSpeech? = null
    private var utteranceId = 0L
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
        }.build()

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            val current = engine
            val language = if (status == TextToSpeech.SUCCESS) current?.setLanguage(Locale.FRANCE) else null
            ready = language != null && language >= TextToSpeech.LANG_AVAILABLE
            if (ready) {
                current?.setAudioAttributes(attributes)
                pending?.let { pending = null; speak(it) }
            }
        }
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { releaseIfCurrent(id) }
            @Deprecated("Legacy TTS callback")
            override fun onError(id: String?) { releaseIfCurrent(id) }
            override fun onError(id: String?, errorCode: Int) { releaseIfCurrent(id) }
        })
    }

    fun speak(text: String) {
        if (!ready) { pending = text; return }
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        utteranceId++
        if (engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId.toString()) == TextToSpeech.ERROR) {
            audioManager.abandonAudioFocusRequest(focus)
        }
    }

    fun stop() {
        pending = null
        utteranceId++
        engine?.stop()
        audioManager.abandonAudioFocusRequest(focus)
    }

    private fun releaseIfCurrent(id: String?) {
        if (id == utteranceId.toString()) audioManager.abandonAudioFocusRequest(focus)
    }
}
