package com.hortus1556.exilum.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import timber.log.Timber

/**
 * Wraps Android's [AudioFocusRequest] lifecycle with the distinctions a
 * professional media app needs: a short, duckable interruption (e.g. a
 * notification chime) just lowers volume and keeps playing, a longer
 * interruption (e.g. a phone call) pauses but stays resumable, and losing
 * focus permanently (another app took over playback) pauses and gives up
 * focus outright so a stray callback can't resurrect playback later.
 */
class AudioFocusHandler(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    /** True while the app holds [AudioManager.AUDIOFOCUS_GAIN]. */
    var hasFocus = false
        private set

    /**
     * Requests [AudioManager.AUDIOFOCUS_GAIN] if not already held.
     * @param onGain Focus (re)granted after a transient loss — resume if appropriate.
     * @param onDuck A short interruption started — lower volume but keep playing.
     * @param onEndDuck The duckable interruption ended — restore volume.
     * @param onPause A non-duckable transient interruption started — pause, resumable.
     * @param onPermanentLoss Focus lost for good — pause and don't expect a later [onGain].
     */
    fun request(
        onGain: () -> Unit,
        onDuck: () -> Unit,
        onEndDuck: () -> Unit,
        onPause: () -> Unit,
        onPermanentLoss: () -> Unit,
    ) {
        if (hasFocus) return
        // Abandon any previous request so its listener doesn't fire alongside the new one.
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { change ->
                Timber.i("Audio Focus Changed: $change")
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        hasFocus = true
                        onEndDuck()
                        onGain()
                    }

                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onDuck()

                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        hasFocus = false
                        onPause()
                    }

                    AudioManager.AUDIOFOCUS_LOSS -> {
                        hasFocus = false
                        onPermanentLoss()
                        // Give up focus outright: no further callbacks should reach us
                        // until the next explicit request() call.
                        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                        focusRequest = null
                    }
                }
            }
            .build()

        focusRequest = request

        val result = audioManager.requestAudioFocus(request)
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Timber.d("Audio focus result=$result")
    }

    /** Releases audio focus. Call from the service's onDestroy. */
    fun abandon() {
        focusRequest?.let(audioManager::abandonAudioFocusRequest)
        focusRequest = null
        hasFocus = false
    }
}
