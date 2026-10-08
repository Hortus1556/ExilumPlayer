package com.hortus1556.exilum.data

import com.hortus1556.exilum.db.ChorusSettings
import com.hortus1556.exilum.db.ReverbSettings
import com.hortus1556.exilum.db.SurroundSettings
import kotlinx.serialization.Serializable

/**
 * A stretch of one track that plays round and round - from [startMs] to
 * [endMs] and straight back - saved so it can be played again, starred or put
 * in a playlist like a subsong of its own. See [loopPresetCode].
 *
 * @param id stable within its file, so a favourite or a playlist entry that
 *   names this preset still names it after others are added or deleted.
 * @param subsong the subsong the loop is in, or -1 for a file with one.
 */
@Serializable
data class LoopPreset(
    val id: Int,
    val subsong: Int = WHOLE_MODULE,
    val startMs: Int,
    val endMs: Int,
)

/**
 * Everything set for one file alone, over what the settings screen says.
 *
 * Kept in the database as JSON, one row per file, and in tracker.conf beside
 * the favourites and playlists - see TrackerConfigStore.
 */
@Serializable
data class FileOverrides(
    /**
     * Gain for this file, -99 to +99 dB. Retired: the sound check decides how
     * loud a file plays, so nothing sets this any more and it is dropped as
     * the row is read - see FileOverridesRepository - so one set before does
     * not go on applying with nothing left to show it or put it back.
     */
    val boostDb: Int = 0,
    /** Core settings for this file only, by the keys in OverrideKeys; a key not here follows the settings screen. */
    val settings: Map<String, Int> = emptyMap(),
    /** Lengths set by hand, by subsong, for the files that cannot say how long they are. */
    val durations: Map<Int, Int> = emptyMap(),
    val loops: List<LoopPreset> = emptyList(),
    /** The id the next loop preset gets. Never reused, so a deleted preset's id stays retired. */
    val nextLoopId: Int = 0,
) {
    /**
     * Nothing set - which is when the row goes rather than being kept empty.
     *
     * Nor any loop preset ever saved: the row is where [nextLoopId] lives, and
     * dropping it with a file's last preset handed that preset's id to the
     * next one saved - which a queue entry still naming the deleted loop then
     * played in its place.
     */
    val isEmpty: Boolean
        get() = settings.isEmpty() && durations.isEmpty() && loops.isEmpty() && nextLoopId == 0
}

/**
 * Subsong indices from here up are loop presets rather than subsongs: preset
 * [LoopPreset.id] n is saved as this plus n.
 *
 * A preset rides on everything a subsong already has - the favourites key,
 * playlist entries, the queue, names - rather than needing a column of its
 * own in each. No format has anywhere near this many subsongs.
 */
const val LOOP_PRESET_BASE = 10000

fun isLoopPreset(subsong: Int): Boolean = subsong >= LOOP_PRESET_BASE

fun loopPresetCode(id: Int): Int = LOOP_PRESET_BASE + id

fun loopPresetId(subsong: Int): Int = subsong - LOOP_PRESET_BASE

/**
 * The keys of [FileOverrides.settings], one per setting a file can have its
 * own value for. The values are the settings screen's own, in its units.
 */
object OverrideKeys {
    const val OPENMPT_STEREO = "openmpt.stereo"
    const val OPENMPT_INTERPOLATION = "openmpt.interpolation"
    const val HVL_STEREO = "hvl.stereo"
    const val ADPLUG_CORE = "adplug.core"
    const val GME_SNES_ECHO = "gme.snes.echo"
    const val GME_SNES_INTERPOLATION = "gme.snes.interpolation"
    const val GSF_STEREO = "gsf.stereo"
    const val GSF_LOWPASS = "gsf.lowpass"
    const val TWOSF_STEREO = "2sf.stereo"
    const val TWOSF_INTERPOLATION = "2sf.interpolation"
    const val USF_STEREO = "usf.stereo"
    const val USF_HLE = "usf.hle"
    const val PSF_STEREO = "psf.stereo"
    const val PSF_REVERB = "psf.reverb"
    const val DSP_STEREO = "dsp.stereo"
    const val AUDIO_STEREO = "audio.stereo"

    // The output effects, which are whatever plays: on or off as 1 or 0, and
    // the rest in the settings screen's own units. Not the equalizer, which
    // is set for the ears and the speakers rather than for a tune.
    const val FX_REVERB = "fx.reverb"
    const val FX_REVERB_TYPE = "fx.reverb.type"
    const val FX_REVERB_MIX = "fx.reverb.mix"
    const val FX_CHORUS = "fx.chorus"
    const val FX_CHORUS_RATE = "fx.chorus.rate"
    const val FX_CHORUS_SPREAD = "fx.chorus.spread"
    const val FX_CHORUS_MIX = "fx.chorus.mix"
    const val FX_SURROUND = "fx.surround"
    const val FX_SURROUND_MIX = "fx.surround.mix"

    fun gmeStereo(console: Int) = "gme.$console.stereo"
    fun gmePan(console: Int, voice: Int) = "gme.$console.pan.$voice"
    fun gsfPan(channel: Int) = "gsf.pan.$channel"
    fun twoSfPan(channel: Int) = "2sf.pan.$channel"

    /**
     * Keys a file could once be given and no longer can: the libraries' own
     * volumes ("hvl.gain", "adplug.gain", "gme.<console>.volume"), which the
     * volume boost does the job of, and AdPlug's and the SNES's stereo
     * spread, gone altogether. Dropped as overrides are read, so a value set
     * before does not go on applying with nothing left to show it or put it
     * back.
     */
    fun isRetired(key: String): Boolean =
        key == "hvl.gain" || key == "adplug.gain" || key == "adplug.stereo" ||
            key == "gme.2.stereo" ||
            (key.startsWith("gme.") && key.endsWith(".volume"))
}

/** The reverb as a file with these settings of its [own] has it: its own where it has them, this otherwise. */
fun ReverbSettings.forFile(own: Map<String, Int>): ReverbSettings = ReverbSettings(
    enabled = own[OverrideKeys.FX_REVERB]?.let { it != 0 } ?: enabled,
    type = own[OverrideKeys.FX_REVERB_TYPE] ?: type,
    mix = own[OverrideKeys.FX_REVERB_MIX] ?: mix,
)

/** The chorus, the same way. */
fun ChorusSettings.forFile(own: Map<String, Int>): ChorusSettings = ChorusSettings(
    enabled = own[OverrideKeys.FX_CHORUS]?.let { it != 0 } ?: enabled,
    rate = own[OverrideKeys.FX_CHORUS_RATE] ?: rate,
    spread = own[OverrideKeys.FX_CHORUS_SPREAD] ?: spread,
    mix = own[OverrideKeys.FX_CHORUS_MIX] ?: mix,
)

/** And the virtual surround. */
fun SurroundSettings.forFile(own: Map<String, Int>): SurroundSettings = SurroundSettings(
    enabled = own[OverrideKeys.FX_SURROUND]?.let { it != 0 } ?: enabled,
    mix = own[OverrideKeys.FX_SURROUND_MIX] ?: mix,
)

/** How elapsed time is shown - see AppPreferences.getTimeDisplayFlow. */
object TimeDisplay {
    const val CLOCK = 0
    const val MILLISECONDS = 1
    const val BYTES = 2

    /** Bytes of audio per millisecond at the render format: 48 kHz, 16-bit, stereo. */
    const val BYTES_PER_MS = 48 * 2 * 2

    /** [ms] as the mode shows it; clock time to the millisecond when [precise]. */
    fun format(ms: Long, mode: Int, precise: Boolean = false): String {
        val clamped = ms.coerceAtLeast(0L)
        return when (mode) {
            MILLISECONDS -> "%,d ms".format(clamped)
            BYTES -> "%,d B".format(clamped * BYTES_PER_MS)
            else -> {
                val minutes = clamped / 60_000
                val seconds = (clamped / 1000) % 60
                if (precise) {
                    "%d:%02d.%03d".format(minutes, seconds, clamped % 1000)
                } else {
                    "%d:%02d".format(minutes, seconds)
                }
            }
        }
    }
}
