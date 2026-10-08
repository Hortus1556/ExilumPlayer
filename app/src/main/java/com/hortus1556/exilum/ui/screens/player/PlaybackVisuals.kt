package com.hortus1556.exilum.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.hortus1556.exilum.renderer.model.MAX_CHANNELS
import com.hortus1556.exilum.renderer.model.PlaybackSnapshot
import kotlin.math.abs
import kotlin.math.exp

/**
 * The single source of live playback state for every visualiser.
 *
 * One read per display frame, feeding four independent invalidation tickets.
 * A view reads the ticket for the thing it draws - inside its draw scope, not
 * its composable body - so it redraws when that thing actually changed and at
 * no other time. [snapshot] itself is a plain mutable object refilled in
 * place; reading a field off it does not subscribe to anything, which is the
 * point: the ticket decides *when* to redraw, the snapshot supplies *what* to
 * draw. That is the copy-then-draw split every tracker uses at the boundary
 * between its audio and video threads.
 *
 * Why once per display frame and not on every audio event: a frame is the
 * only moment anything can actually appear on screen, so reading more often
 * cannot show more, and reading on the audio thread's schedule instead is
 * what the previous design did - ~200 native wakeups a second, each one
 * allocating a state object and invalidating the whole Compose snapshot
 * system, most of them landing between two frames where nothing could be
 * drawn at all. Reading less often, on a timer, is the other failure: a fixed
 * interval beats against both the display and the audio and drops whichever
 * updates fall between its ticks.
 *
 * Nothing here polls, waits, or blocks on the audio thread. The engine state
 * comes from a lock-free ring of frame-stamped snapshots and is already
 * latency-compensated - it describes the audio leaving the speaker at this
 * instant, not what the renderer has run ahead and produced into the buffer.
 */
@Stable
class PlaybackVisuals {

    val snapshot = PlaybackSnapshot()

    /**
     * Whether these are being driven: something is playing and nothing
     * covers the views. For a view that moves on a clock of its own - the
     * spectrum - to stop by the same rule as the ones drawn from here.
     */
    var active by mutableStateOf(false)
        internal set

    /** Bumped when the playing pattern/row/order actually moves. */
    var positionTick by mutableIntStateOf(0)
        private set

    /** Bumped when speed/BPM/time/channel-count change. */
    var infoTick by mutableIntStateOf(0)
        private set

    /**
     * Bumped when a channel changes *what* it is playing - sample,
     * instrument, note or key. Deliberately separate from [levelTick]: the
     * instrument names and channel labels drawn from this are expensive text,
     * and they must not be re-issued just because a volume envelope moved.
     */
    var voiceTick by mutableIntStateOf(0)
        private set

    /**
     * Bumped when a channel's continuous values move - volume, final volume,
     * pan, period, pitch bend, measured level - or while [smoothedLevel]
     * and friends are
     * still travelling toward them. These change on nearly every audio tick,
     * so only cheap shapes should be keyed to this.
     */
    var levelTick by mutableIntStateOf(0)
        private set

    /**
     * Levels and pan eased toward their targets instead of snapping to them,
     * as a fraction of full scale.
     *
     * Exponential decay - the same ballistics a real VU meter has, an
     * RC-circuit response that always converges without overshooting. The
     * underlying values step once per audio tick (tens of times a second, and
     * unevenly), so drawn raw the bars twitch; eased, they read as a level
     * rising and falling. Advanced here rather than in the view because this
     * is the one place that already runs exactly once per display frame,
     * which is the correct clock for an animation.
     *
     * [smoothedLevel] is what the channel is genuinely putting out, measured
     * from the sample data being heard; [smoothedNominal] is the volume the
     * tracker is commanding for it. Drawing both is what makes the pair
     * readable - the backdrop shows the level asked for, the bar over it
     * shows what actually came out.
     */
    val smoothedLevel = FloatArray(MAX_CHANNELS)
    val smoothedNominal = FloatArray(MAX_CHANNELS)
    val smoothedPan = FloatArray(MAX_CHANNELS) { 0.5f }

    private var lastSmoothNanos = 0L

    /**
     * Bumped when there is genuinely new waveform to draw - i.e. while at
     * least one channel is audible, plus exactly one final bump when the last
     * one falls silent so traces settle to a flat line instead of freezing
     * mid-wave. Silence costs nothing.
     */
    var scopeTick by mutableIntStateOf(0)
        private set

    private var lastOrder = Int.MIN_VALUE
    private var lastPattern = Int.MIN_VALUE
    private var lastRow = Int.MIN_VALUE
    private var lastNumRows = Int.MIN_VALUE
    private var lastSpeed = Int.MIN_VALUE
    private var lastBpm = Int.MIN_VALUE
    private var lastTimeMs = Int.MIN_VALUE
    private var lastPlayingChannels = Int.MIN_VALUE
    private var lastNumChannels = Int.MIN_VALUE
    private val lastChannel = IntArray(MAX_CHANNELS * TRACKED_FIELDS) { Int.MIN_VALUE }
    private var wasAudible = false

    /** Reads the current state and bumps whichever tickets it moved. */
    fun refresh(withScopes: Boolean) {
        if (!snapshot.read(withScopes)) return

        if (snapshot.order != lastOrder ||
            snapshot.pattern != lastPattern ||
            snapshot.row != lastRow ||
            snapshot.numRows != lastNumRows
        ) {
            lastOrder = snapshot.order
            lastPattern = snapshot.pattern
            lastRow = snapshot.row
            lastNumRows = snapshot.numRows
            positionTick++
        }

        if (snapshot.speed != lastSpeed ||
            snapshot.bpm != lastBpm ||
            snapshot.timeMs != lastTimeMs ||
            snapshot.playingChannels != lastPlayingChannels ||
            snapshot.numChannels != lastNumChannels
        ) {
            lastSpeed = snapshot.speed
            lastBpm = snapshot.bpm
            lastTimeMs = snapshot.timeMs
            lastPlayingChannels = snapshot.playingChannels
            lastNumChannels = snapshot.numChannels
            infoTick++
        }

        var voiceChanged = false
        var levelChanged = false
        var audible = false
        val channels = snapshot.numChannels.coerceIn(0, MAX_CHANNELS)
        for (ch in 0 until channels) {
            val base = ch * TRACKED_FIELDS
            voiceChanged = store(base + F_SAMPLE, snapshot.sample(ch)) || voiceChanged
            voiceChanged = store(base + F_INSTRUMENT, snapshot.instrument(ch)) || voiceChanged
            voiceChanged = store(base + F_NOTE, snapshot.note(ch)) || voiceChanged
            voiceChanged = store(base + F_KEY, snapshot.key(ch)) || voiceChanged
            levelChanged = store(base + F_VOLUME, snapshot.volume(ch)) || levelChanged
            levelChanged = store(base + F_FINAL_VOLUME, snapshot.finalVolume(ch)) || levelChanged
            levelChanged = store(base + F_PAN, snapshot.pan(ch)) || levelChanged
            levelChanged = store(base + F_PERIOD, snapshot.period(ch)) || levelChanged
            levelChanged = store(base + F_PITCH_BEND, snapshot.pitchBend(ch)) || levelChanged
            levelChanged = store(base + F_PEAK, (snapshot.level(ch) * 1024f).toInt()) || levelChanged
            if (snapshot.isAudible(ch)) audible = true
        }
        if (voiceChanged) voiceTick++
        if (advanceSmoothing(channels) || levelChanged) levelTick++

        if (withScopes && (audible || wasAudible)) scopeTick++
        wasAudible = audible
    }

    /**
     * Eases the smoothed levels one display frame toward their targets.
     *
     * @return true while anything is still visibly moving. Once every channel
     *   has settled this returns false and the bars stop redrawing entirely -
     *   an animation that has finished is not a reason to keep painting.
     */
    private fun advanceSmoothing(channels: Int): Boolean {
        val now = System.nanoTime()
        // A first frame, or a return from a long pause, must not apply a huge
        // step - clamp it and let the next frames do the easing.
        val dtMs = if (lastSmoothNanos == 0L) {
            0f
        } else {
            ((now - lastSmoothNanos) / 1_000_000f).coerceIn(0f, 100f)
        }
        lastSmoothNanos = now
        if (dtMs <= 0f) return false

        val alpha = 1f - exp(-dtMs / SMOOTHING_TIME_CONSTANT_MS)
        // The volume bars move the way a level meter does: up at once, down
        // gently. Easing both ways alike, as the pan still does, meant a
        // drum hit of 40 ms had come only 40% of the way up before it was
        // over, so short loud notes read as quiet ones.
        val rise = 1f - exp(-dtMs / LEVEL_RISE_TIME_CONSTANT_MS)
        val fall = 1f - exp(-dtMs / LEVEL_FALL_TIME_CONSTANT_MS)
        var moving = false
        for (ch in 0 until channels) {
            val level = snapshot.level(ch)
            moving = ease(smoothedLevel, ch, level, if (level > smoothedLevel[ch]) rise else fall) || moving
            val nominal = snapshot.finalVolume(ch) / 64f
            moving = ease(smoothedNominal, ch, nominal, if (nominal > smoothedNominal[ch]) rise else fall) || moving
            moving = ease(smoothedPan, ch, snapshot.pan(ch) / 255f, alpha) || moving
        }
        return moving
    }

    private fun ease(values: FloatArray, index: Int, target: Float, alpha: Float): Boolean {
        val current = values[index]
        val delta = target - current
        // Below this the change cannot move the drawing by a pixel, so treat
        // it as arrived rather than easing forever on an asymptote.
        if (abs(delta) < SETTLED_EPSILON) {
            if (current != target) {
                values[index] = target
                return true
            }
            return false
        }
        values[index] = current + delta * alpha
        return true
    }

    /**
     * Marks every layer as needing to redraw without discarding what they are
     * showing.
     *
     * Each visualiser only redraws when the ticket it watches moves, which is
     * exactly right while playing and a liability while stopped: nothing moves
     * for minutes at a time, so nothing repaints, and if the window's drawing
     * buffers are dropped underneath it - which is what happens on a trim
     * after a long spell in the background or with the screen off - there is
     * no state change left to bring the picture back. Pressing play brought it
     * back only because that resumed the ticks.
     */
    fun invalidate() {
        positionTick++
        infoTick++
        voiceTick++
        levelTick++
        scopeTick++
    }

    /** Forgets everything, so the next [refresh] reports every field as new. */
    fun reset() {
        snapshot.clear()
        lastOrder = Int.MIN_VALUE
        lastPattern = Int.MIN_VALUE
        lastRow = Int.MIN_VALUE
        lastNumRows = Int.MIN_VALUE
        lastSpeed = Int.MIN_VALUE
        lastBpm = Int.MIN_VALUE
        lastTimeMs = Int.MIN_VALUE
        lastPlayingChannels = Int.MIN_VALUE
        lastNumChannels = Int.MIN_VALUE
        lastChannel.fill(Int.MIN_VALUE)
        smoothedLevel.fill(0f)
        smoothedNominal.fill(0f)
        smoothedPan.fill(0.5f)
        lastSmoothNanos = 0L
        wasAudible = false
        positionTick++
        infoTick++
        voiceTick++
        levelTick++
        scopeTick++
    }

    private fun store(index: Int, value: Int): Boolean {
        if (lastChannel[index] == value) return false
        lastChannel[index] = value
        return true
    }

    private companion object {
        const val F_SAMPLE = 0
        const val F_INSTRUMENT = 1
        const val F_NOTE = 2
        const val F_KEY = 3
        const val F_VOLUME = 4
        const val F_FINAL_VOLUME = 5
        const val F_PAN = 6
        const val F_PERIOD = 7
        const val F_PITCH_BEND = 8
        const val F_PEAK = 9
        const val TRACKED_FIELDS = 10

        /** Roughly the time to close ~63% of the distance to a new pan. */
        const val SMOOTHING_TIME_CONSTANT_MS = 80f

        /** The same for a volume bar going up: about one display frame. */
        const val LEVEL_RISE_TIME_CONSTANT_MS = 12f

        /** And for one going down, slow enough to read. */
        const val LEVEL_FALL_TIME_CONSTANT_MS = 150f

        /** Fraction of full scale below which a level counts as arrived. */
        const val SETTLED_EPSILON = 0.0005f
    }
}

/**
 * Drives a [PlaybackVisuals] from the display's own frame callback while
 * [active].
 *
 * [withScopes] gates the only expensive part of the read (walking each
 * channel's sample data to build its trace), so a view that shows no scopes
 * pays nothing for them.
 */
@Composable
fun rememberPlaybackVisuals(active: Boolean, withScopes: Boolean): PlaybackVisuals {
    val visuals = remember { PlaybackVisuals() }

    // Coming back to the foreground has to repaint even if no playback state
    // changed while away - see invalidate().
    LifecycleResumeEffect(visuals) {
        visuals.invalidate()
        onPauseOrDispose { }
    }

    LaunchedEffect(visuals, active, withScopes) {
        visuals.active = active
        if (!active) {
            // One last read so a paused or stopped view shows exactly where
            // playback came to rest, rather than whatever frame it happened
            // to be on when the loop stopped.
            visuals.refresh(withScopes)
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { }
            visuals.refresh(withScopes)
        }
    }
    return visuals
}
