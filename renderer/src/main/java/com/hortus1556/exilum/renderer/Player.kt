package com.hortus1556.exilum.renderer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.FileNotFoundException
import com.hortus1556.exilum.renderer.model.AudioStats
import com.hortus1556.exilum.renderer.model.FrameInfo
import com.hortus1556.exilum.renderer.model.ModInfo
import com.hortus1556.exilum.renderer.model.ModVars

/** JNI bridge to the libopenmpt / Oboe native player. */
object Player {

    /**
     * The result for a file that could not be opened at all. From loadFromFd
     * that is one no longer ours to read - a folder grant lost or withdrawn -
     * and a file that is simply gone is [LOAD_GONE]; analyzeLoudness, where
     * nobody is told either way, says this for both.
     */
    const val LOAD_UNREADABLE = -3

    /**
     * loadFromFd's result for a file that is no longer there: deleted, moved
     * or renamed since it was listed. Apart from [LOAD_UNREADABLE] because
     * that one is told to the user as storage access lost, and a queue entry
     * whose file was deleted is not that - it only needs skipping.
     */
    const val LOAD_GONE = -6

    const val MIN_BUFFER_MS = 80
    const val MAX_BUFFER_MS = 1000

    /* sample format flags */
    const val FORMAT_32BIT = (1 shl 3)      /* Mix to 32-bit int instead of 16 */

    /* setPlayer/getPlayer parameter ids — see player-libopenmpt.cpp */
    const val PARAM_MASTER_VOLUME = 1       /* render.mastergain.millibel, 0-200 (100 = 0dB) */
    const val PARAM_STEREO_SEPARATION = 2   /* render.stereoseparation, 0-200 (100 = normal) */
    const val PARAM_DITHER = 3              /* dither algorithm, 0-3 */
    const val PARAM_VOLUME_RAMPING = 4      /* render.volumeramping.strength, -1 (default) .. 10 */

    /* Oboe Audio configuration */
    const val OBOE_CHANNELS_STEREO = -1
    const val OBOE_CHANNELS_MONO = 1

    const val OBOE_AUDIO_API_AAUDIO = 1
    const val OBOE_AUDIO_API_OPENSLES = 2
    const val OBOE_AUDIO_API_UNSPECIFIED = -1

    // Default Values

    const val DEFAULT_MASTER_VOLUME = 100
    const val DEFAULT_STEREO_SEPARATION = 100
    const val DEFAULT_DITHER = 1
    const val DEFAULT_VOLUME_RAMPING = -1
    /** On libopenmpt's scale: 100 is half the Amiga's hard panning, 200 all of it, 0 mono. */
    const val DEFAULT_HIVELY_STEREO = 100
    const val DEFAULT_HIVELY_GAIN = 100
    const val DEFAULT_ADPLUG_GAIN = 100

    /** The OPL emulators AdLib tunes can be heard through - see setAdPlugCore. */
    const val ADPLUG_CORE_NUKED = 0
    const val ADPLUG_CORE_DOSBOX = 1
    const val ADPLUG_CORE_MAME = 2
    const val ADPLUG_CORE_KEN = 3
    const val DEFAULT_ADPLUG_CORE = ADPLUG_CORE_NUKED

    /**
     * Seconds to play a chip tune whose file gives no length.
     *
     * GME's own figure. See [setGmeDefaultLength] for why there has to be one.
     */
    const val DEFAULT_GME_LENGTH = 150

    /**
     * Which machine a GME setting belongs to.
     *
     * They are kept apart because they are different machines: an NES mixes to
     * one output and has no stereo of its own, a Game Boy pans its four voices
     * in hardware, and the SNES does both inside its DSP. Same order as
     * GmeConsole in decoder-gme.cpp.
     */
    const val GME_NES = 0
    const val GME_GAME_BOY = 1
    const val GME_SNES = 2

    /** How a SNES voice is read between the samples it actually has. */
    const val GME_INTERP_NONE = 0
    const val GME_INTERP_GAUSSIAN = 1
    const val GME_INTERP_SINC = 2

    const val DEFAULT_GME_VOLUME = 100
    const val DEFAULT_GME_STEREO = 100
    const val DEFAULT_GME_PAN = 0
    const val DEFAULT_GME_ECHO = true
    /** The Game Boy Advance's two sides as the console plays them: see [setGsfStereo]. */
    const val DEFAULT_GSF_STEREO = 100
    const val DEFAULT_GSF_LOWPASS = false
    /** A Game Boy Advance channel left where its game puts it: see [setGsfPan]. */
    const val DEFAULT_GSF_PAN = 0
    /** The Game Boy Advance's channels: see [setGsfPan]. */
    const val GSF_CHANNELS = 6

    /**
     * How a Nintendo DS voice's recording is read between the samples it has:
     * see [setTwoSfInterpolation]. vio2sf's resampler numbers them so.
     */
    const val TWOSF_INTERP_NONE = 0
    const val TWOSF_INTERP_BLEP = 1
    const val TWOSF_INTERP_LINEAR = 2
    const val TWOSF_INTERP_BLAM = 3
    const val TWOSF_INTERP_CUBIC = 4
    const val TWOSF_INTERP_SINC = 5
    const val DEFAULT_TWOSF_INTERPOLATION = TWOSF_INTERP_CUBIC
    /** The Nintendo DS's two sides as the console plays them: see [setTwoSfStereo]. */
    const val DEFAULT_TWOSF_STEREO = 100
    /** A Nintendo DS voice left where its game puts it: see [setTwoSfPan]. */
    const val DEFAULT_TWOSF_PAN = 0
    /** The Nintendo DS's voices: see [setTwoSfPan]. */
    const val TWOSF_CHANNELS = 16
    /** The Nintendo 64's two sides as the console plays them: see [setUsfStereo]. */
    const val DEFAULT_USF_STEREO = 100
    /** The Nintendo 64's sound code done over by the emulator: see [setUsfHle]. */
    const val DEFAULT_USF_HLE = true
    /** The PlayStation's two sides as the console plays them: see [setPsfStereo]. */
    const val DEFAULT_PSF_STEREO = 100
    /** The PlayStation's own reverb heard, as the game set it: see [setPsfReverb]. */
    const val DEFAULT_PSF_REVERB = true
    /** The GameCube's two sides as its streams have them: see [setDspStereo]. */
    const val DEFAULT_DSP_STEREO = 100
    /** A record's two sides as it was mixed: see [setAudioStereo]. */
    const val DEFAULT_AUDIO_STEREO = 100

    /** The rooms [setReverb] has, by the numbers the settings keep them under. */
    const val REVERB_SMALL_ROOM = 0
    const val REVERB_MEDIUM_ROOM = 1
    const val REVERB_LARGE_ROOM = 2
    const val REVERB_HALL = 3
    const val REVERB_CONCERT_HALL = 4
    const val REVERB_ARENA = 5
    const val REVERB_PLATE = 6
    const val REVERB_BATHROOM = 7
    const val REVERB_CAVE = 8
    const val DEFAULT_REVERB_TYPE = REVERB_HALL
    const val DEFAULT_REVERB_MIX = 25
    /** The chorus's rate in tenths of a hertz, 0.1 to 5 Hz: see [setChorus]. 1.1 Hz is DirectX's own. */
    const val CHORUS_RATE_MIN = 1
    const val CHORUS_RATE_MAX = 50
    const val DEFAULT_CHORUS_RATE = 11
    const val DEFAULT_CHORUS_SPREAD = 50
    const val DEFAULT_CHORUS_MIX = 50
    const val DEFAULT_SURROUND_MIX = 50
    /** The equalizer's bands, 31 Hz to 16 kHz an octave apart, and how far each goes either way in dB. */
    const val EQ_BANDS = 10
    const val EQ_MAX_DB = 12
    const val DEFAULT_GME_INTERPOLATION = GME_INTERP_SINC

    /** kSoundCheckUnknown natively: no loudness measured. */
    const val SOUND_CHECK_UNKNOWN = -100f

    /** Fields getSoundCheckStats fills. */
    const val SOUND_CHECK_STATS = 8

    /** Voices an NES APU has: square, square, triangle, noise, DMC. */
    const val GME_NES_VOICES = 5

    /**
     * Voices an NSF's expansion chip can add on top of the APU's - eight for
     * a Namco 163, the widest of them - each of which can be panned too.
     */
    const val GME_NES_EXPANSION_VOICES = 8
    /**
     * [ResamplerMode.SINC]'s id, as a constant. It was that enum's value read
     * at run time, and reading it runs this object's initialiser - which loads
     * every decoder there is: the preferences asking for the default loaded
     * the whole native library on the main thread as the app started.
     */
    const val DEFAULT_INTERPOLATION = 3

    private const val TAG: String = "Exilum Player Object"

    init {
        System.loadLibrary("openmpt-jni")
    }

    external fun deinit(): Int
    external fun endPlayer(): Int
    external fun getAudioStats(stats: AudioStats)
    external fun getFormats(): Array<String>

    /**
     * The extensions by decoder, in the order a file is offered to them: each
     * row is the decoder's id (libopenmpt, hively, furnace, libsidplayfp,
     * custommade, mgba, vio2sf, libvgm, gme, adplug) followed by its
     * extensions, sorted.
     */
    external fun getFormatGroups(): Array<Array<String>>
    external fun getFrameInfo(values: FrameInfo)

    /**
     * Reads the playback state the audio device is *currently emitting* into
     * the caller's preallocated arrays, and optionally renders each channel's
     * scope trace.
     *
     * This is the one call the visualisers make, once per display frame. It
     * does not block on the audio thread: the state comes from a lock-free
     * ring of frame-stamped snapshots, and scope rendering (the only part
     * needing the module) uses a try-lock and simply leaves [scopes]
     * untouched if the audio callback happens to hold it.
     *
     * See openmpt_readVisualState in player-libopenmpt.cpp; array layouts are
     * mirrored by [com.hortus1556.exilum.renderer.model.PlaybackSnapshot].
     *
     * @param scopes flat [numChannels][scopeWidth] signed 8-bit waveform, or
     *   null to skip scope rendering entirely.
     * @return false when nothing has been rendered yet.
     */
    external fun readVisualState(
        scalars: IntArray,
        chData: IntArray,
        scopes: ByteArray?,
        scopeWidth: Int
    ): Boolean

    /**
     * The spectrum of what is being heard now: the level of each of [count]
     * bands of pitch, low to high, into [bands]. On the scale of a display -
     * 0 its bottom, 1 its top, a band under or over it below 0 or above 1 -
     * and taken from the output itself, after the equalizer and before the
     * volume, so it is there for every engine. One call a display frame, from
     * the main thread; nothing waits on the audio thread. See spectrum.h.
     *
     * @return false, and every band -1, when nothing has been played yet.
     */
    external fun readSpectrum(bands: FloatArray, count: Int): Boolean

    /** The frequency in the middle of band [index] of [count] of [readSpectrum], in Hz. */
    external fun spectrumBandHz(index: Int, count: Int): Float

    external fun getModVars(vars: ModVars)
    external fun getPatternData(pat: Int, numRows: Int, notes: ByteArray, instruments: ByteArray, fxType: ByteArray, fxParm: ByteArray, fx2Type: ByteArray, fx2Parm: ByteArray)
    external fun getPatternRows(pat: Int): Int
    external fun getPlayer(parm: Int): Int
    external fun getVersion(): String
    external fun hasAudioDisconnected(): Boolean

    /** Clears the sticky disconnect flag once recovery has handled it. */
    external fun clearAudioDisconnected()
    external fun hasModuleEnded(): Boolean
    external fun init(rate: Int, ms: Int, perfMode: Int, channels: Int, audioApi: Int, flags: Int): Boolean
    /**
     * @param filename the file's own name, not a path - the bytes still come
     *   from [fd]. AdPlug needs it: several of its players decide whether a
     *   file is theirs partly by its extension, and with a few dozen formats
     *   sharing no magic number that check is load-bearing.
     */
    external fun loadModuleFd(fd: Int, filename: String, modInfo: ModInfo): Int
    external fun mute(chn: Int, status: Int): Int

    /**
     * Puts every channel back as the file loaded has it, undoing whatever the
     * listener muted, and returns the channels the file itself mutes: bit n
     * for channel n. A module is saved with its mutes - its author's sketches
     * and scratch channels are switched off in it - and those stay off until
     * they are brought back with [mute]. Nothing else has any, and returns 0.
     */
    external fun resetMutes(): Long
    external fun playAudio(): Int
    external fun releaseModule(): Int
    /**
     * Seeks to [time] ms. [ticket] names this seek: one being carried out
     * when [setSeekWanted] is given another ticket has been overtaken, and
     * a tune that is run to its place stops on the way.
     */
    external fun seek(time: Int, ticket: Int): Int

    /**
     * The ticket of the latest thing asked of the tune: a seek, or what
     * takes one's place - another subsong, the next track, a stop. Takes no
     * lock; say it before waiting for whatever a seek is holding.
     */
    external fun setSeekWanted(ticket: Int)
    external fun setLoopMode(loop: Boolean)

    /**
     * Shifts everything up or down by [semitones], without changing the tempo.
     *
     * Clamped to an octave either way natively. Applies to whatever is loaded
     * and to whatever loads next - it is a property of playback, not of a
     * module, so the value survives a track change. What is playing glides
     * there over a fifth of a second rather than jumping.
     */
    external fun setGlobalPitch(semitones: Int)

    /**
     * The speed everything plays at, 10 to 200 percent, without changing the
     * pitch. Like the pitch, a property of playback that carries over to
     * whatever loads next, and glided to by what is playing.
     */
    external fun setGlobalTempo(percent: Int)

    /**
     * A custom loop, in song milliseconds: playback reaching [endMs] goes
     * back to [startMs] without a gap. -1 for either clears it. Held until
     * cleared, across seeks, so a track change should clear it.
     */
    external fun setLoopRegion(startMs: Int, endMs: Int)

    /** Gain on everything played, -99 to +99 dB - the per-file volume boost. */
    external fun setVolumeBoost(db: Int)

    /**
     * A length for the loaded track, for the formats that cannot say -
     * see ModVars.durationKnown. 0 or less goes back to the track's own.
     */
    external fun setDurationOverride(ms: Int)

    /**
     * Sound Check: every track at the same loudness, whatever plays it - see
     * soundcheck.h. While it is on the per-file volume boost is not applied.
     */
    external fun setSoundCheck(enabled: Boolean)

    /**
     * Starts measuring a new track. [knownLufs] is its loudness as measured
     * before, or [SOUND_CHECK_UNKNOWN]; [priorLufs] the average of the tracks
     * heard so far, which an unknown track starts from.
     */
    external fun soundCheckBegin(knownLufs: Float, priorLufs: Float)

    /**
     * The playing track's loudness, measured after it started: the gain
     * glides to it over about a second and stays.
     */
    external fun soundCheckUpdate(knownLufs: Float)

    /** See [analyzeLoudness]. */
    private external fun analyzeLoudnessFd(
        fd: Int,
        filename: String,
        subsong: Int,
        maxSeconds: Int,
        budgetMs: Int,
        modInfo: ModInfo,
        out: FloatArray,
    ): Int

    /** The loudness measured for this track so far, in LUFS, or [SOUND_CHECK_UNKNOWN]. */
    external fun getSoundCheckLoudness(): Float

    /** How much of this track that measurement counts, in seconds. */
    external fun getSoundCheckSeconds(): Float

    /**
     * Sound Check's state, for the debug view, into [out] - at least
     * [SOUND_CHECK_STATS] long, in the order of SoundCheckStat in soundcheck.h.
     */
    external fun getSoundCheckStats(out: FloatArray)

    /**
     * Stereo spread for AHX and Hively tunes, 0 (mono) to 100 (as written).
     *
     * Scales the tune's own spread rather than replacing it - AHX takes its
     * from a five-step table, Hively carries one in the file - so a tune
     * mixed narrow stays narrow at 100. Applies to whatever is loaded and to
     * whatever loads next.
     */
    external fun setHivelyStereo(percent: Int)

    /**
     * Volume for AHX and Hively tunes, as a percentage of the mix gain the
     * tune loaded with. These are quiet formats; 100 leaves them alone.
     */
    external fun setHivelyGain(percent: Int)

    /** Volume for AdLib tunes, as a percentage of the mix. */
    external fun setAdPlugGain(percent: Int)

    /**
     * Which OPL emulator AdLib tunes are heard through: one of the
     * ADPLUG_CORE values. Nuked keeps running for the views whichever is
     * chosen. Changing it during a tune replays that tune on the new chip up
     * to where it was.
     */
    external fun setAdPlugCore(core: Int)

    /**
     * Seconds to play a chip tune whose file gives no length.
     *
     * Some of the formats GME plays have no end and say nothing about one. A
     * plain NSF or a GBS is a ROM and an entry point: the console goes round
     * for ever, and how long to listen is a decision the file does not make.
     * An SPC's ID666 tag and an NSFE's time chunk do make it, and those are
     * used instead of this.
     *
     * Takes effect on what is playing as well as on what loads next.
     */
    external fun setGmeDefaultLength(seconds: Int)

    /** Master volume for one console, as a percentage of the mix. */
    external fun setGmeVolume(console: Int, percent: Int)

    /**
     * Stereo width for one console: 0 collapses it to mono, 100 leaves it as
     * the chip made it, 200 doubles it.
     *
     * Worth nothing on an NES, which mixes to one output and has no stereo to
     * widen - that machine gets per-voice panning instead.
     */
    external fun setGmeStereo(console: Int, percent: Int)

    /**
     * Where one voice sits, -100 hard left through 0 centred to +100 hard
     * right. A balance and not a pan law: centred is exactly what the chip
     * produced, and moving it takes the other side down rather than lifting
     * anything up.
     */
    external fun setGmePan(console: Int, voice: Int, pan: Int)

    /**
     * The SNES DSP's echo.
     *
     * A delay line in the console's own RAM that the tune writes into and
     * reads back, so it can only be turned off inside the chip - by the time
     * there is a stereo pair it is already mixed in. The tune's master volume
     * is left alone, so a fade written into the music still fades.
     */
    external fun setGmeEcho(enabled: Boolean)

    /**
     * How far apart the Game Boy Advance's two sides are, 0 to 200: 100 as
     * the console plays them, 0 mono, beyond 100 wider still. Applies to
     * whatever is loaded and to whatever loads next.
     */
    external fun setGsfStereo(percent: Int)

    /**
     * A gentle low-pass filter on the Game Boy Advance, for the grit its
     * games' low-rate mixing leaves at the top - an option the GSF players
     * before this one had too. Off, the sound is the console's as it is.
     */
    external fun setGsfLowpass(enabled: Boolean)

    /**
     * Moves one Game Boy Advance channel - 0 to 5: the two squares, the wave,
     * the noise, then the two Direct Sound channels - to the left or right of
     * where its game puts it, -100 to 100. 0 leaves it there, and a channel
     * can be taken no further than all the way to a side. Applies to
     * whatever is loaded and to whatever loads next.
     */
    external fun setGsfPan(channel: Int, value: Int)

    /**
     * How far apart the Nintendo DS's two sides are, 0 to 200: 100 as the
     * console plays them, 0 mono, beyond 100 wider still. Applies to whatever
     * is loaded and to whatever loads next.
     */
    external fun setTwoSfStereo(percent: Int)

    /**
     * How a Nintendo DS voice's recording is read between the samples it
     * has, one of the TWOSF_INTERP values. The console does nothing: each
     * sample is held until the next, which is the bright, gritty sound the
     * machine has - none plays that as it is, BLEP the same without the
     * aliasing a higher output rate adds to it. Linear and its band-limited
     * BLAM soften the most, cubic less; sinc, over thirty-two samples, keeps
     * the recording's whole band and takes off only what lies above it. A voice
     * takes the setting when it next starts a note; the square waves and
     * the noise are made by the console and are not touched by it.
     */
    external fun setTwoSfInterpolation(mode: Int)

    /**
     * Moves one of the Nintendo DS's sixteen voices to the left or right of
     * where its game puts it, -100 to 100. 0 leaves it there, and a voice
     * can be taken no further than all the way to a side. Applies to
     * whatever is loaded and to whatever loads next.
     */
    external fun setTwoSfPan(channel: Int, value: Int)

    /**
     * How far apart the Nintendo 64's two sides are, 0 to 200: 100 as the
     * console plays them, 0 mono, beyond 100 wider still. Applies to whatever
     * is loaded and to whatever loads next.
     */
    external fun setUsfStereo(percent: Int)

    /**
     * How the Nintendo 64's sound code is run. Each game mixes its music on
     * the console's signal processor with code of its own; on, the emulator
     * does that code over with its own version of it (HLE), several times
     * faster and very nearly the same; off, it runs the processor on the
     * game's code as it is (LLE), exact, for several times the work. Takes at
     * once, in whatever is playing.
     */
    external fun setUsfHle(enabled: Boolean)

    /**
     * How far apart the PlayStation's two sides are, 0 to 200: 100 as the
     * console plays them, 0 mono, beyond 100 wider still. Applies to whatever
     * is loaded and to whatever loads next.
     */
    external fun setPsfStereo(percent: Int)

    /**
     * Whether the PlayStation's own reverb is heard. The console's sound unit
     * has one built in, and each game sets its room and which voices go
     * through it; on, it is heard as the game set it; off, the voices are
     * heard without it. Applies to whatever is loaded and to whatever loads
     * next.
     */
    external fun setPsfReverb(enabled: Boolean)

    /**
     * How far apart the two sides of a GameCube stream are, 0 to 200: 100 as
     * recorded, 0 mono, beyond 100 wider still. Applies to whatever is
     * loaded and to whatever loads next.
     */
    external fun setDspStereo(percent: Int)

    /**
     * The same for recorded music - MP3, FLAC, AAC and the rest FFmpeg
     * decodes: 100 as mixed, 0 mono, beyond 100 wider still.
     */
    external fun setAudioStereo(percent: Int)

    /**
     * The words of the file playing, as it carries them: lines with their
     * times in front ("[01:23.45]") where they are timed, plain lines where
     * they are not. Null for a file with none, and for every kind of file
     * that is not recorded music.
     */
    external fun getLyrics(): String?

    /**
     * A reverb on everything played, whatever engine plays it: OpenMPT's
     * emulation of the DirectX I3DL2 reverb, in one of the REVERB_ rooms.
     * [mix] is how much of the room is heard, 0 to 100 percent, the loudness
     * the same all the way across. Held natively for the life of the process
     * and glided to; off fades the room out, and costs nothing once it has.
     */
    external fun setReverb(enabled: Boolean, type: Int, mix: Int)

    /**
     * A chorus on everything played: OpenMPT's emulation of the DirectX
     * chorus. [rateTenthsHz] is how fast its delay sways, [CHORUS_RATE_MIN]
     * to [CHORUS_RATE_MAX]; [spread] how differently the two sides sway, 0 to
     * 100 percent of half a turn; [mix] how much of it is heard, 0 to 100.
     */
    external fun setChorus(enabled: Boolean, rateTenthsHz: Int, spread: Int, mix: Int)

    /** ModPlug Player's surround on everything played; [mix] 0 to 100 percent of its full depth. */
    external fun setSurround(enabled: Boolean, mix: Int)

    /**
     * The ten-band equalizer on everything played: [bandsDb] holds up to
     * [EQ_BANDS] gains, -[EQ_MAX_DB] to [EQ_MAX_DB] dB, from 31 Hz up. The
     * whole is turned down by its largest boost, so it never clips.
     */
    external fun setEqualizer(enabled: Boolean, bandsDb: IntArray)

    /**
     * How a SNES voice is read between its samples. One of the GME_INTERP
     * values.
     *
     * The gaussian is the SNES's own four-tap table and the only accurate
     * choice - its softness is a real part of how the machine sounds. None
     * takes the nearest sample and gives the raw BRR back, edges and all;
     * sinc is brighter than either.
     */
    external fun setGmeInterpolation(mode: Int)
    external fun setPlayer(parm: Int, value: Int): Int
    external fun setPlaying(value: Boolean)
    external fun setResampler(mode: Int): Int
    external fun restartModule(): Int
    external fun stopModule(): Int
    external fun setPosition(pos: Int): Int
    external fun prevPosition(): Int
    external fun nextPosition(): Int
    external fun restartAudio(): Boolean
    external fun stopAudio(): Boolean
    external fun setSequence(seq: Int): Boolean
    external fun startPlayer(rate: Int, format: Int = 0): Int
    external fun testModuleFd(fd: Int, filename: String, modInfo: ModInfo): Boolean

    /**
     * The files the next load may ask for by name - the libraries a .minigsf
     * or a .mini2sf names - which the native side cannot reach from the one descriptor it
     * is handed. See [withCompanions].
     */
    private external fun setCompanionFiles(names: Array<String>, contents: Array<ByteArray>)

    /**
     * Runs [load] with the files the file at [uri] needs read from beside it
     * and handed over - the libraries a PSF names in its tags, the other half
     * of a GameCube stream split in two, the bank an AdLib tune keeps its
     * instruments in - and takes them back after. Most files need none, and
     * cost a look at their first bytes and their name.
     */
    private inline fun <T> withCompanions(context: Context, uri: Uri, load: () -> T): T {
        val name = uri.displayName(context)
        return handingOver(
            PsfCompanions.collect(context, uri) + StreamPairs.collect(context, uri, name) +
                AdLibBanks.collect(context, uri, name),
            load,
        )
    }

    /** Runs [load] with [files] handed over to it, on this thread, and takes them back after. */
    private inline fun <T> handingOver(files: List<Pair<String, ByteArray>>, load: () -> T): T {
        if (files.isEmpty()) return load()
        setCompanionFiles(files.map { it.first }.toTypedArray(), files.map { it.second }.toTypedArray())
        try {
            return load()
        } finally {
            setCompanionFiles(emptyArray(), emptyArray())
        }
    }
    external fun time(): Int

    /**
     * The name of the file at a URI, extension and all.
     *
     * Only the name: a SAF document id can be anything, and what the decoders
     * want is the "song.rad" a player checks the extension of. Mostly the id
     * ends in it; one that does not - the Downloads provider's "msf:33" - says
     * nothing, and the provider is asked what the file is called.
     */
    private fun Uri.displayName(context: Context): String {
        val fromPath = (lastPathSegment ?: "").substringAfterLast('/').substringAfterLast(':')
        if ('.' in fromPath || scheme != "content") return fromPath
        val asked = try {
            context.contentResolver.query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: RuntimeException) {
            // A provider that will not say: a lost grant, a URI it does not answer queries for.
            null
        }
        return asked?.takeIf { it.isNotEmpty() } ?: fromPath
    }

    fun testFromFd(context: Context, uri: Uri, modInfo: ModInfo = ModInfo()): Boolean =
        probeFromFd(context, uri, modInfo) == true

    /**
     * Whether a decoder takes the file at [uri] - or null when it could not be
     * read at all, which says nothing about whether it is a module.
     *
     * Null for a lost SAF grant (a SecurityException: the app was reinstalled
     * since the folder was picked, or the system withdrew the grant) and for a
     * file that is no longer there. The two used to come back as false, the
     * same as a file that is simply not a module - so a scan that ran while
     * access was gone filed every file it touched as "not a module", and
     * nothing ever looked at them again.
     */
    fun probeFromFd(context: Context, uri: Uri, modInfo: ModInfo = ModInfo()): Boolean? {
        Log.d(TAG, "Testing: $uri")
        return try {
            val name = uri.displayName(context)
            val taken = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                // Detached: the native side reads it and closes it.
                testModuleFd(pfd.detachFd(), name, modInfo)
            } ?: return null
            // An AdLib tune whose instruments are in another file is no tune
            // without it. Looked for only once the file has been turned down,
            // and only for the few kinds that have one: finding a file beside
            // another is a listing of the folder, and a scan asks this of
            // every file in it.
            val withBank = taken || AdLibBanks.names(name).isNotEmpty() && run {
                val banks = AdLibBanks.collect(context, uri, name)
                banks.isNotEmpty() && context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    handingOver(banks) { testModuleFd(pfd.detachFd(), name, modInfo) }
                } == true
            }
            if (withBank) Log.i(TAG, "Test Success: ${modInfo.name} | ${modInfo.type}")
            withBank
        } catch (e: SecurityException) {
            Log.w(TAG, "Lost SAF permission for $uri", e)
            null
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "Gone: $uri", e)
            null
        } catch (e: RuntimeException) {
            Log.w(TAG, "Gone: $uri", e)
            null
        }
    }

    fun loadFromFd(context: Context, uri: Uri, modInfo: ModInfo = ModInfo()): Int {
        Log.d(TAG, "Loading: $uri")
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                withCompanions(context, uri) { loadModuleFd(pfd.detachFd(), uri.displayName(context), modInfo) }.also { result ->
                    when (result) {
                        0 -> Log.i(TAG, "Loaded: ${modInfo.name} | ${modInfo.type}")
                        -2 -> Log.w(TAG, "Test failed for $uri")
                        else -> Log.e(TAG, "Load failed: $result for $uri")
                    }
                }
            } ?: LOAD_UNREADABLE
        } catch (e: SecurityException) {
            // See probeFromFd - same lost-grant scenario, but on the playback
            // path: without this, picking a track whose folder permission was
            // lost crashes the whole app instead of just failing that load.
            Log.w(TAG, "Lost SAF permission for $uri", e)
            LOAD_UNREADABLE
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "Gone: $uri", e)
            LOAD_GONE
        } catch (e: RuntimeException) {
            // A document of a tree that is no longer there is not always a
            // FileNotFoundException: the storage provider checks first that
            // it is inside the tree, and says IllegalArgumentException when
            // it cannot tell - of a file deleted since it was listed. Thrown
            // on the loader's thread with nothing to catch it, that was the
            // app gone, for a file that only needed skipping.
            Log.w(TAG, "Gone: $uri", e)
            LOAD_GONE
        }
    }

    /**
     * Measures the loudness of [subsong] of the file at [uri] - for Sound
     * Check, and only in a process of its own: it loads the file into the
     * same decoders playback uses, of which a process has one each. See
     * engine_analyzeLoudness.
     *
     * Plays it through once, for at most [maxSeconds] of audio, and for at
     * most [budgetMs] when that is above zero. [out], at least
     * [LOUDNESS_RESULTS] long, receives the integrated loudness in LUFS (-100
     * for silence), the seconds of audio analysed, the seconds that passed the
     * gates, and 1 when the track ended within the limits. The file stays
     * loaded, for [getModVars]; [releaseModule] when done.
     *
     * @return 0, a load error, or [LOAD_UNREADABLE].
     */
    fun analyzeLoudness(
        context: Context,
        uri: Uri,
        subsong: Int,
        maxSeconds: Int,
        budgetMs: Int,
        out: FloatArray,
    ): Int = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            withCompanions(context, uri) {
                analyzeLoudnessFd(pfd.detachFd(), uri.displayName(context), subsong, maxSeconds, budgetMs, ModInfo(), out)
            }
        } ?: LOAD_UNREADABLE
    } catch (e: SecurityException) {
        Log.w(TAG, "Lost SAF permission for $uri", e)
        LOAD_UNREADABLE
    } catch (e: FileNotFoundException) {
        Log.w(TAG, "Gone: $uri", e)
        LOAD_UNREADABLE
    } catch (e: RuntimeException) {
        Log.w(TAG, "Gone: $uri", e)
        LOAD_UNREADABLE
    }

    /** What [analyzeLoudness] writes. */
    const val LOUDNESS_RESULTS = 4
}
