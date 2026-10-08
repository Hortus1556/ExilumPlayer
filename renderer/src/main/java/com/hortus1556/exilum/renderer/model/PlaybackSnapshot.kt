package com.hortus1556.exilum.renderer.model

import com.hortus1556.exilum.renderer.Player

/** Hard channel ceiling; matches MAX_CHANNELS in player-libopenmpt.cpp. */
const val MAX_CHANNELS = 64

/**
 * The playback state the audio device is currently emitting, plus each
 * channel's scope trace.
 *
 * Deliberately a mutable object refilled in place by [read], never a new
 * immutable value per frame. It is read once per display frame, and at that
 * rate allocating a fresh object graph (and the per-channel arrays inside it)
 * is pure GC pressure for no benefit - nothing keeps a previous frame's
 * snapshot alive.
 *
 * The state is *latency compensated*: it describes the audio leaving the
 * speaker right now, not what the renderer has already produced into the
 * output buffer ahead of it. See openmpt_readVisualState /
 * pickSnapshot in player-libopenmpt.cpp for how, and why every real tracker
 * does the same.
 *
 * Field layout mirrors the ScalarIndex / ChannelField enums in
 * player-libopenmpt.cpp; the two must be changed together.
 */
class PlaybackSnapshot {

    private val scalars = IntArray(SCALAR_COUNT)
    private val chData = IntArray(MAX_CHANNELS * CHANNEL_FIELDS)

    /**
     * Flat [numChannels][scopeWidth] signed 8-bit waveform, one row per
     * channel. Only the first [numChannels] rows are meaningful.
     */
    val scopes = ByteArray(MAX_CHANNELS * SCOPE_WIDTH)

    /** False until the engine has rendered anything at all. */
    var valid: Boolean = false
        private set

    val order: Int get() = scalars[ORDER]
    val pattern: Int get() = scalars[PATTERN]
    val row: Int get() = scalars[ROW]
    val numRows: Int get() = scalars[NUM_ROWS]
    val speed: Int get() = scalars[SPEED]
    val bpm: Int get() = scalars[BPM]
    val timeMs: Int get() = scalars[TIME_MS]
    val totalTimeMs: Int get() = scalars[TOTAL_TIME_MS]
    val playingChannels: Int get() = scalars[PLAYING_CHANNELS]
    val numChannels: Int get() = scalars[NUM_CHANNELS]
    val sequence: Int get() = scalars[SEQUENCE]

    /**
     * How far ahead of the speaker the renderer currently is, in frames.
     * Purely diagnostic - the compensation is already applied to everything
     * else here.
     */
    val latencyFrames: Int get() = scalars[LATENCY_FRAMES]

    /**
     * The song's own master volume, 0 to 1 - a tracker's Vxx / Gxx, and 1 for
     * any format without one. Traces are fitted to their box, so they have to
     * be scaled by this separately to follow a global fade.
     */
    val globalVolume: Float
        get() = if (valid) (scalars[GLOBAL_VOLUME] / GLOBAL_VOLUME_UNITY).coerceIn(0f, 1f) else 1f

    fun sample(ch: Int): Int = chData[ch * CHANNEL_FIELDS + SAMPLE]
    fun instrument(ch: Int): Int = chData[ch * CHANNEL_FIELDS + INSTRUMENT]
    fun note(ch: Int): Int = chData[ch * CHANNEL_FIELDS + NOTE]
    fun key(ch: Int): Int = chData[ch * CHANNEL_FIELDS + KEY]
    fun volume(ch: Int): Int = chData[ch * CHANNEL_FIELDS + VOLUME]
    fun finalVolume(ch: Int): Int = chData[ch * CHANNEL_FIELDS + FINAL_VOLUME]
    fun pan(ch: Int): Int = chData[ch * CHANNEL_FIELDS + PAN]
    fun period(ch: Int): Int = chData[ch * CHANNEL_FIELDS + PERIOD]
    fun pitchBend(ch: Int): Int = chData[ch * CHANNEL_FIELDS + PITCH_BEND]

    /**
     * What this channel is actually putting out, 0f..1f: the loudest sample in
     * the window being heard, at the volume it is being played at.
     *
     * Distinct from [volume] and [finalVolume], which are what the tracker
     * *commands* - a channel holding a note through a silent stretch of its
     * sample, or playing a quiet sample at full volume, reports loud there and
     * correctly here. Measured natively from the same sample walk that draws
     * the scope trace, so it only updates while scopes are being requested.
     */
    fun level(ch: Int): Float = chData[ch * CHANNEL_FIELDS + PEAK] / PEAK_SCALE

    /**
     * True when this channel is producing sound worth drawing a trace for.
     *
     * Measured level first, and only then the tracker fields. Period is a
     * note's Amiga period - a thing a NES or SNES chip does not have, so a
     * decoder for one leaves it at zero, and requiring it meant no voice of
     * theirs was ever considered audible and no trace was ever drawn. The
     * level is taken from the audio actually being rendered, so it answers
     * this for any decoder that can measure its voices.
     */
    fun isAudible(ch: Int): Boolean =
        level(ch) > 0f || (period(ch) > 0 && (volume(ch) > 0 || finalVolume(ch) > 0))

    /**
     * Refills this snapshot from the engine.
     *
     * @param withScopes false to skip waveform rendering entirely - the only
     *   part of this call that touches the module and so the only part that
     *   costs anything beyond a couple of array copies. Views that don't draw
     *   traces should pass false.
     * @return true if a snapshot was available.
     */
    fun read(withScopes: Boolean): Boolean {
        valid = Player.readVisualState(
            scalars = scalars,
            chData = chData,
            scopes = if (withScopes) scopes else null,
            scopeWidth = if (withScopes) SCOPE_WIDTH else 0,
        )
        return valid
    }

    /** Clears every field - used when playback stops so nothing stale shows. */
    fun clear() {
        scalars.fill(0)
        chData.fill(0)
        scopes.fill(0)
        valid = false
    }

    companion object {
        /**
         * Samples per channel in [scopes]. One trace pixel column consumes one
         * of these, so this only needs to cover the widest scope actually
         * drawn on screen; anything beyond that is thrown away by the view.
         */
        const val SCOPE_WIDTH = 256

        // ScalarIndex, player-libopenmpt.cpp
        private const val ORDER = 0
        private const val PATTERN = 1
        private const val ROW = 2
        private const val NUM_ROWS = 3
        private const val SPEED = 4
        private const val BPM = 5
        private const val TIME_MS = 6
        private const val TOTAL_TIME_MS = 7
        private const val PLAYING_CHANNELS = 8
        private const val NUM_CHANNELS = 9
        private const val SEQUENCE = 10
        private const val LATENCY_FRAMES = 11
        private const val GLOBAL_VOLUME = 12
        private const val SCALAR_COUNT = 13

        /** GLOBAL_VOLUME at full volume - kGlobalVolumeUnity natively. */
        private const val GLOBAL_VOLUME_UNITY = 256f

        // ChannelField, player-libopenmpt.cpp
        private const val SAMPLE = 0
        private const val INSTRUMENT = 1
        private const val NOTE = 2
        private const val KEY = 3
        private const val VOLUME = 4
        private const val FINAL_VOLUME = 5
        private const val PAN = 6
        private const val PERIOD = 7
        private const val PITCH_BEND = 8
        private const val PEAK = 9
        private const val CHANNEL_FIELDS = 10

        /** Matches kPeakScale in player-libopenmpt.cpp. */
        private const val PEAK_SCALE = 1024f
    }
}
