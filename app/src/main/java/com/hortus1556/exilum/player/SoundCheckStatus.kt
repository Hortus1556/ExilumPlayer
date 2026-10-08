package com.hortus1556.exilum.player

import com.hortus1556.exilum.data.SoundCheckStore
import com.hortus1556.exilum.player.soundcheck.LoudnessAnalyzer

/**
 * Sound Check as it stands, read from the output stage - see
 * PlayerEngine.soundCheckStatus. For the debug view: whether it is on where
 * the settings say and where the audio is, whether audio is reaching it, and
 * what it measured and does about it.
 */
data class SoundCheckStatus(
    val settingOn: Boolean,
    val nativeOn: Boolean,
    /** Audio handed to it while on, since the app started. */
    val processedSeconds: Float,
    /** This track's integrated loudness so far, in LUFS; -100 for none. */
    val loudness: Float,
    val measuredSeconds: Float,
    /** The gain it is aiming at, in dB. */
    val gainDb: Float,
    /** The most the limiter took off in the last 100 ms, in dB (0 or less). */
    val limiterDb: Float,
    /** Whether the track started at a loudness measured before. */
    val fixed: Boolean,
    val tracksStarted: Int,
    /** What the analysis has kept for this track, or null. */
    val stored: SoundCheckStore.Measurement?,
    /** The average of the tracks measured, which a new one starts from; -100 for none. */
    val prior: Float,
    /** How far the library's analysis has got. */
    val library: LoudnessAnalyzer.Progress,
)
