package com.hortus1556.exilum.player

import android.content.Context
import com.hortus1556.exilum.data.FileOverrides
import com.hortus1556.exilum.data.FileOverridesRepository
import com.hortus1556.exilum.data.StorageAccessEvents
import com.hortus1556.exilum.data.LoopPreset
import com.hortus1556.exilum.data.OverrideKeys
import com.hortus1556.exilum.data.forFile
import com.hortus1556.exilum.player.soundcheck.LoudnessAnalyzer
import com.hortus1556.exilum.player.soundcheck.LoudnessProtocol
import com.hortus1556.exilum.db.AppPreferences
import com.hortus1556.exilum.db.entity.ModuleEntity
import com.hortus1556.exilum.player.model.NoteCell
import com.hortus1556.exilum.player.model.PatternData
import com.hortus1556.exilum.renderer.Player
import com.hortus1556.exilum.renderer.ResamplerMode
import com.hortus1556.exilum.renderer.model.AudioStats
import com.hortus1556.exilum.renderer.model.FrameInfo
import com.hortus1556.exilum.renderer.model.ModInfo
import com.hortus1556.exilum.renderer.model.ModVars
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/** A volume shown in the effect column, as "V" and its value - effectChar's 31. */
private const val VOLUME_COLUMN_EFFECT = 31

/**
 * Low-level audio playback engine that wraps the libopenmpt / Oboe JNI layer.
 */
class PlayerEngine(
    private val context: Context,
    private val prefs: AppPreferences,
    private val overridesRepo: FileOverridesRepository,
    private val access: StorageAccessEvents,
    private val analyzer: LoudnessAnalyzer,
) {

    companion object {
        /** Fraction of the current master volume applied while ducked for a transient interruption. */
        private const val DUCK_FACTOR = 0.4f

        /**
         * How often [renderLoop] checks for end-of-module, audio disconnect
         * and position changes. Nothing visible is driven from there any more
         * (the visualisers read their own snapshot per display frame), so this
         * only needs to be fine enough that a track change isn't audibly late.
         */
        private const val SUPERVISE_INTERVAL_MS = 20L

        /** The master volume that leaves the output alone - what Sound Check holds it at. */
        private const val UNITY_VOLUME = 100

        /**
         * How long a track that has never been analysed is held back before it
         * starts, for Sound Check to measure it - as much of it as fits, the
         * rest following while it plays. Only ever the first time a track is
         * played before the background analysis has reached it.
         */
        private const val SOUND_CHECK_WAIT_MS = 2500L

        /** How many of the tracks after the one starting are analysed ahead of the library. */
        const val SOUND_CHECK_AHEAD = 4

        /** A later figure for the playing track that differs by less than this is left alone. */
        private const val SOUND_CHECK_UPDATE_LU = 0.5f
    }

    // Deliberately its own thread, not Dispatchers.Default/IO: both draw from one
    // shared, CPU-core-bounded scheduler pool, and the library scanner validates
    // files with real CPU work (native module parsing) on Dispatchers.IO — enough
    // concurrent load there starves this scope's coroutines for as long as the
    // scan runs, so a settings change silently sits unapplied until it finishes.
    // A dedicated thread guarantees preference changes always land immediately.
    private val prefsExecutor = Executors.newSingleThreadExecutor { Thread(it, "PlayerEngine-Prefs") }
    private val scope = CoroutineScope(prefsExecutor.asCoroutineDispatcher() + SupervisorJob())

    /** The dedicated thread running [renderLoop]. Null when stopped. */
    @Volatile
    private var renderThread: Thread? = null

    /** Per-frame playback snapshot emitted by the render loop; reset when idle. */
    val frameInfoFlow: StateFlow<FrameInfo>
        field = MutableStateFlow(FrameInfo())

    /** True while the Oboe stream is actively rendering audio. */
    val isPlaying: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** Current playback position in milliseconds, updated every render frame. */
    val positionMs: StateFlow<Long>
        field = MutableStateFlow(0L)

    /** Index of the currently playing sequence within the loaded module. */
    val currentSequenceFlow: StateFlow<Int>
        field = MutableStateFlow(0)

    /** Module metadata, emitted once after each successful load. */
    val modVarsFlow: StateFlow<ModVars>
        field = MutableStateFlow(ModVars())

    /**
     * Which channels the listener has muted: bit n set means channel n is off.
     *
     * The one copy of this there is. Pattern view and channel view each used
     * to keep their own, read back from the engine only when the channel count
     * changed - and both stay composed side by side in the pager, so a mute
     * made in one never reached the other, and a track change that unmuted
     * everything natively left both still drawing "Muted" over channels that
     * were playing. Both views now draw from here and write through here.
     *
     * A Long because the views address at most 64 channels.
     */
    val channelMutes: StateFlow<Long>
        field = MutableStateFlow(0L)

    /**
     * The channel a long press soloed, while that solo stands; -1 otherwise.
     * Only for keeping a solo over channels that appear later - see
     * [rereadChannels] - where a mask with one channel playing could as well
     * be two channels and a tap that muted one.
     */
    @Volatile
    private var soloedChannel = -1

    /**
     * The channels the loaded file itself mutes, bit n for channel n: what
     * [channelMutes] starts as, and goes back to when a solo is lifted.
     */
    @Volatile
    private var fileMutes = 0L

    /**
     * Guards every native mute call: [start] unmutes everything on the loader
     * thread while a change may be being applied on [muteExecutor], and
     * without the lock one could land between the other's calls and leave the
     * engine and the flow disagreeing.
     */
    private val muteLock = Any()

    // Off the main thread: every native mute takes the player mutex, which the
    // render callback holds while it renders, and a solo is one call per
    // channel. One thread, so changes land in the order they were made.
    private val muteExecutor = Executors.newSingleThreadExecutor { Thread(it, "PlayerEngine-Mute") }

    /**
     * Where seeks are carried out, off the caller's thread - which is the main
     * one. A tune that is a running program seeks by running it up to the
     * place, a Nintendo DS's at a few dozen times real time, and holds the
     * player mutex for all of it: for a long song that is seconds. One
     * thread, so seeks land in the order they were made.
     */
    private val seekExecutor = Executors.newSingleThreadExecutor { Thread(it, "PlayerEngine-Seek") }

    /**
     * The latest seek asked for, bumped by every module change too: a seek
     * still queued behind a newer one, or behind another file, is dropped.
     */
    private val seekTicket = AtomicInteger()

    /** Seeks asked for and not yet carried out: the readout holds their target until they are. */
    private val seeksPending = AtomicInteger()

    /**
     * The latest subsong asked for through [setSequenceAsync], bumped by every
     * module change too. A count of its own rather than [seekTicket]: a seek
     * made after a subsong was picked is for that subsong and has to wait
     * behind it, not take its place.
     */
    private val sequenceTicket = AtomicInteger()

    /** The subsong [setSequenceAsync] is on its way to, or -1 when it has got there. */
    private val sequencePending = AtomicInteger(-1)

    /**
     * The subsong playing, or the one a switch still on its way is going to -
     * what "the next one" is counted from, so two skips made before the first
     * has landed move on twice.
     */
    val sequenceTarget: Int
        get() = sequencePending.get().takeIf { it >= 0 } ?: currentSequenceFlow.value

    /**
     * Bumped each time Oboe reports the stream died - headphones unplugged, a
     * Bluetooth device going away, or an OEM audio-routing reconfiguration
     * (a phone call, or Samsung's internal-audio screen recorder).
     *
     * A counter rather than a boolean, and the distinction is load-bearing.
     * Two places report a disconnect - the supervisory loop and [resume] -
     * and as a conflated StateFlow a second report of `true` while the flow
     * already held `true` emitted nothing at all, so the recovery that
     * reopens the stream silently never ran and playback stayed dead until
     * the app was restarted. That is exactly what a disconnect during a phone
     * call looks like from the outside. A counter always differs from its
     * previous value, so it always emits.
     */
    val audioDisconnectedFlow: StateFlow<Int>
        field = MutableStateFlow(0)

    /**
     * Emits once each time the render loop stops because the module reached its
     * end naturally. A dedicated event (rather than piggybacking on [isPlaying]'s
     * true->false transition) so a racing [stop] or [pause] can never suppress
     * queue auto-advance by clobbering a boolean flag before it's observed.
     */
    val moduleEndedFlow: SharedFlow<Unit>
        field = MutableSharedFlow(extraBufferCapacity = 1)

    /**
     * Lazily populated by [getPatternData]; cleared on each module load.
     * A concurrent map: [getPatternData] is called off the main thread (see
     * PlayerScreen), while [clearPatternCache] runs on the loadAndStartAt
     * background thread — a plain HashMap under that access pattern is a real
     * data race, not just a theoretical one.
     */
    private val patternCache = java.util.concurrent.ConcurrentHashMap<Int, PatternData>()

    @Volatile
    private var isRepeatOne = false

    /** Set to true to signal the render thread to exit cleanly. */
    @Volatile
    private var stopRequest = false

    /**
     * The channel layout [modVarsFlow] was last read for - see
     * [FrameInfo.channelLayout]. Back to 0 with every load, as the engine's is.
     */
    @Volatile
    private var channelLayoutSeen = 0

    /** When true, the render loop advances through all module sequences automatically. */
    @Volatile
    var playAllSequences = false

    /**
     * True while the loaded entry is one subsong of its file - a favourite or a
     * playlist entry that saved one. Its end is the entry's end: the render
     * loop does not walk on into the file's other subsongs.
     */
    @Volatile
    var pinnedSubsong = false

    /** True while playback is paused (stream is open but not rendering). */
    @Volatile
    var paused = false
        private set

    /**
     * Whether the last word on playing was "pause": set by [pause], cleared by
     * [resume], and read by [start], which brings a track up paused when it is.
     *
     * [paused] alone could not say this across a load. [start] used to clear it
     * outright, so a pause that arrived while a track was loading or being
     * measured by Sound Check - a phone call, a headset button - was thrown
     * away and the track started anyway, over the call. Whoever asks for a
     * load sets this to what that load should come up as.
     */
    @Volatile
    var pauseRequested = false

    /** True after [load] or [loadNext] completes successfully. */
    @Volatile
    var initialized = false
        private set

    /** True after the render loop exits because the module reached its end. */
    @Volatile
    var endedNaturally = false
        private set

    /** The three machines with settings of their own - see Player.GME_NES. */
    private val gmeConsoles = intArrayOf(Player.GME_NES, Player.GME_GAME_BOY, Player.GME_SNES)

    /**
     * The consoles whose stereo spread is a setting. Not the SNES: its panning
     * is the tune's own, and it plays as written.
     */
    private val gmeStereoConsoles = intArrayOf(Player.GME_NES, Player.GME_GAME_BOY)

    /** What Sound Check's analysis has measured, per file and subsong. */
    private val soundCheckStore get() = analyzer.store

    /** Whether Sound Check is on, as last read from the settings. */
    @Volatile
    private var soundCheckOn = false

    /** The file and subsong Sound Check is measuring, for keeping the measurement when it ends. */
    @Volatile
    private var soundCheckUri: String? = null

    @Volatile
    private var soundCheckSubsong = 0

    /** The loudness the playing track's gain was set from, or null while it is not known. */
    @Volatile
    private var soundCheckApplied: Float? = null

    /**
     * Serialises the calls that build the engine up or tear it down.
     *
     * [load], [loadNext], [stop], [release] and [handleAudioDisconnect] all
     * open, close or swap the thing the audio callback is reading from, and all
     * of them are called from whatever background thread happened to want them
     * - the loader thread, the one a stop is dispatched to, the service's
     * onDestroy. Two of those overlapping is a module being freed while another
     * is being loaded onto the same stream, which is heard rather than crashed:
     * garbage, stutter, a tune that will not stop.
     *
     * Deliberately not on [start], [pause] or [resume]. Those are called from
     * the main thread on a media-session command, and this lock is held for as
     * long as it takes to join the render thread - up to two seconds - which is
     * not something the main thread can be made to wait for. They are guarded
     * by [initialized] and [stopRequest] instead, which the holder of this lock
     * sets before it lets go.
     */
    private val lifecycle = Any()

    /** Every file's overrides, kept current from the database for reads that cannot wait. */
    private val allOverrides = MutableStateFlow<Map<String, FileOverrides>>(emptyMap())

    /** The file loaded, whose overrides apply. */
    private val currentUri = MutableStateFlow<String?>(null)

    /**
     * The loaded file's overrides - what it sets for itself over the settings
     * screen. See [FileOverrides].
     */
    val fileOverrides: StateFlow<FileOverrides> =
        combine(allOverrides, currentUri) { all, uri -> uri?.let { all[it] } ?: FileOverrides() }
            .stateIn(scope, SharingStarted.Eagerly, FileOverrides())

    /** The custom loop playing, or null. See [startLoop]. */
    val loopRegion: StateFlow<LoopRegion?>
        field = MutableStateFlow<LoopRegion?>(null)

    /** The loaded file's overrides, read now rather than when the flow next gets round to it. */
    private fun overridesNow(): FileOverrides = currentUri.value?.let { allOverrides.value[it] } ?: FileOverrides()

    /** A setting's value for the file loaded: its own when it has one, the settings screen's otherwise. */
    private fun effective(key: String, global: Int): Int = overridesNow().settings[key] ?: global

    init {
        overridesRepo.all.onEach { allOverrides.value = it }.launchIn(scope)
        prefs.getGlobalPitchFlow()
            .distinctUntilChanged()
            .onEach { Player.setGlobalPitch(it) }
            .launchIn(scope)

        // The same way as the pitch, and for the same reason: held natively and
        // carried to whatever loads next.
        prefs.getGlobalTempoFlow()
            .distinctUntilChanged()
            .onEach { Player.setGlobalTempo(it) }
            .launchIn(scope)

        // The output effects, the same way: held natively for the life of
        // the process and applied whether or not anything is loaded. Three
        // of them as the loaded file has them, where it has any of its own
        // (see FileOverrides); the equalizer is the settings' alone.
        val ownSettings = fileOverrides.map { it.settings }.distinctUntilChanged()
        combine(prefs.getReverbFlow(), ownSettings) { all, own -> all.forFile(own) }
            .distinctUntilChanged()
            .onEach { Player.setReverb(it.enabled, it.type, it.mix) }
            .launchIn(scope)
        combine(prefs.getChorusFlow(), ownSettings) { all, own -> all.forFile(own) }
            .distinctUntilChanged()
            .onEach { Player.setChorus(it.enabled, it.rate, it.spread, it.mix) }
            .launchIn(scope)
        combine(prefs.getSurroundFlow(), ownSettings) { all, own -> all.forFile(own) }
            .distinctUntilChanged()
            .onEach { Player.setSurround(it.enabled, it.mix) }
            .launchIn(scope)
        prefs.getEqualizerFlow().onEach { Player.setEqualizer(it.enabled, it.bands.toIntArray()) }.launchIn(scope)

        // Sound Check first, because the volumes below follow it: while it is
        // on, every one of them is held where it leaves the sound alone.
        // Applied natively whether or not anything is loaded, like the pitch.
        val soundCheck = prefs.getSoundCheckFlow().distinctUntilChanged()
        soundCheck.onEach { on ->
            soundCheckOn = on
            Player.setSoundCheck(on)
            if (on) {
                if (initialized) beginSoundCheck()
                // Like Apple's: the whole library, in the background, so that
                // tracks are measured before they are ever played. Started
                // and left to it: reading the library and sorting out what is
                // left to analyse ran here, on the one thread settings are
                // applied from, and held every other setting up meanwhile.
                analyzer.startLibrary()
            } else {
                analyzer.stopLibrary()
            }
        }.launchIn(scope)

        // A figure arriving for the track playing - its whole analysis after a
        // quick look, or one that was not ready when it started - moves its
        // gain there once, gliding.
        analyzer.onLoudness = { uri, subsong, lufs ->
            val applied = soundCheckApplied
            if (soundCheckOn && uri == soundCheckUri && subsong == soundCheckSubsong &&
                (applied == null || kotlin.math.abs(applied - lufs) >= SOUND_CHECK_UPDATE_LU)
            ) {
                Timber.d("soundCheck: '$uri' #$subsong now known at $lufs LUFS (was $applied)")
                soundCheckApplied = lufs
                Player.soundCheckUpdate(lufs)
            }
        }

        observePref(prefs.getDitherTypeFlow()) { Player.setPlayer(Player.PARAM_DITHER, it) }
        observePref(prefs.getVolumeRampingFlow()) { Player.setPlayer(Player.PARAM_VOLUME_RAMPING, it) }
        observePref(prefs.getGmeLengthFlow()) { Player.setGmeDefaultLength(it) }
        // Everything below can also be set for one file alone, from the
        // overrides sheet - so each follows the settings screen and the
        // loaded file's overrides together. See observeSetting.
        observeSetting(OverrideKeys.OPENMPT_STEREO, prefs.getStereoSeparationFlow()) {
            Player.setPlayer(Player.PARAM_STEREO_SEPARATION, it)
        }
        observeSetting(OverrideKeys.OPENMPT_INTERPOLATION, prefs.getInterpolationTypeFlow()) {
            Player.setResampler(ResamplerMode.fromId(it).id)
        }
        observeSetting(OverrideKeys.HVL_STEREO, prefs.getHivelyStereoFlow()) { Player.setHivelyStereo(it) }
        observeSetting(OverrideKeys.ADPLUG_CORE, prefs.getAdPlugCoreFlow()) { Player.setAdPlugCore(it) }
        // One console at a time, and the NES panning one voice at a time. All
        // of it is held natively per console, so a setting made for one is
        // still there when that machine's turn comes round again.
        for (console in gmeStereoConsoles) {
            observeSetting(OverrideKeys.gmeStereo(console), prefs.getGmeStereoFlow(console)) {
                Player.setGmeStereo(console, it)
            }
        }
        // The expansion chip's voices as well as the APU's five. Their sliders
        // were stored and shown and never sent here, so moving one changed
        // nothing that could be heard.
        for (voice in 0 until Player.GME_NES_VOICES + Player.GME_NES_EXPANSION_VOICES) {
            observeSetting(
                OverrideKeys.gmePan(Player.GME_NES, voice),
                prefs.getGmePanFlow(Player.GME_NES, voice),
            ) { Player.setGmePan(Player.GME_NES, voice, it) }
        }
        observeSetting(OverrideKeys.GME_SNES_ECHO, prefs.getGmeEchoFlow().map { if (it) 1 else 0 }) {
            Player.setGmeEcho(it != 0)
        }
        observeSetting(OverrideKeys.GME_SNES_INTERPOLATION, prefs.getGmeInterpolationFlow()) {
            Player.setGmeInterpolation(it)
        }
        observeSetting(OverrideKeys.GSF_STEREO, prefs.getGsfStereoFlow()) { Player.setGsfStereo(it) }
        observeSetting(OverrideKeys.GSF_LOWPASS, prefs.getGsfLowpassFlow().map { if (it) 1 else 0 }) {
            Player.setGsfLowpass(it != 0)
        }
        for (channel in 0 until Player.GSF_CHANNELS) {
            observeSetting(OverrideKeys.gsfPan(channel), prefs.getGsfPanFlow(channel)) {
                Player.setGsfPan(channel, it)
            }
        }
        observeSetting(OverrideKeys.TWOSF_STEREO, prefs.getTwoSfStereoFlow()) { Player.setTwoSfStereo(it) }
        observeSetting(OverrideKeys.TWOSF_INTERPOLATION, prefs.getTwoSfInterpolationFlow()) {
            Player.setTwoSfInterpolation(it)
        }
        for (channel in 0 until Player.TWOSF_CHANNELS) {
            observeSetting(OverrideKeys.twoSfPan(channel), prefs.getTwoSfPanFlow(channel)) {
                Player.setTwoSfPan(channel, it)
            }
        }
        observeSetting(OverrideKeys.USF_STEREO, prefs.getUsfStereoFlow()) { Player.setUsfStereo(it) }
        observeSetting(OverrideKeys.USF_HLE, prefs.getUsfHleFlow().map { if (it) 1 else 0 }) {
            Player.setUsfHle(it != 0)
        }
        observeSetting(OverrideKeys.PSF_STEREO, prefs.getPsfStereoFlow()) { Player.setPsfStereo(it) }
        observeSetting(OverrideKeys.PSF_REVERB, prefs.getPsfReverbFlow().map { if (it) 1 else 0 }) {
            Player.setPsfReverb(it != 0)
        }
        observeSetting(OverrideKeys.DSP_STEREO, prefs.getDspStereoFlow()) { Player.setDspStereo(it) }
        observeSetting(OverrideKeys.AUDIO_STEREO, prefs.getAudioStereoFlow()) { Player.setAudioStereo(it) }

        combine(fileOverrides, currentSequenceFlow) { ov, seq -> ov.durations[seq] ?: 0 }
            .distinctUntilChanged()
            .onEach { if (initialized) Player.setDurationOverride(it) }
            .launchIn(scope)
    }

    /**
     * Follows a setting the loaded file can override: the file's own value
     * while it has one, the settings screen's otherwise, re-applied when
     * either changes or another file loads.
     */
    private fun observeSetting(key: String, global: Flow<Int>, apply: (Int) -> Unit) {
        combine(global, fileOverrides.map { it.settings[key] }) { value, own -> own ?: value }
            .distinctUntilChanged()
            .onEach { if (initialized) apply(it) }
            .launchIn(scope)
    }

    /** The loop preset [id] of [uri], if it still exists. */
    fun loopPreset(uri: String, id: Int): LoopPreset? =
        allOverrides.value[uri]?.loops?.firstOrNull { it.id == id }

    /**
     * Plays [region] round and round, from its start when [fromStart]. Null
     * stops looping, leaving playback where it is.
     */
    fun startLoop(region: LoopRegion?, fromStart: Boolean = true) {
        if (region == null || region.endMs <= region.startMs) {
            whenDecoderIsFree { Player.setLoopRegion(-1, -1) }
            loopRegion.value = null
            return
        }
        whenDecoderIsFree { Player.setLoopRegion(region.startMs, region.endMs) }
        loopRegion.value = region
        if (fromStart) seek(region.startMs)
    }

    /** The master volume in effect just before [duck] was last called; null when not ducked. */
    @Volatile
    private var preDuckVolume: Int? = null

    /** Lowers master volume for a duckable audio-focus interruption (e.g. a notification chime). */
    fun duck() {
        if (!initialized) return
        val current = UNITY_VOLUME
        if (preDuckVolume == null) preDuckVolume = current
        val ducked = (current * DUCK_FACTOR).toInt().coerceAtLeast(0)
        Timber.d("duck: $current -> $ducked")
        Player.setPlayer(Player.PARAM_MASTER_VOLUME, ducked)
    }

    /** Restores the master volume captured by the most recent [duck] call, if any. */
    fun restoreVolume() {
        val target = preDuckVolume ?: return
        preDuckVolume = null
        if (!initialized) return
        Timber.d("restoreVolume: -> $target")
        Player.setPlayer(Player.PARAM_MASTER_VOLUME, target)
    }

    /**
     * Clears [endedNaturally] before a queue transition.
     *
     * The next load clears it too, but not until its background thread gets
     * that far - and the state published in the meantime would otherwise say
     * playback had finished when it is only between tracks.
     */
    fun clearEndedNaturally() {
        endedNaturally = false
    }

    /**
     * Shifts playback up or down by [semitones].
     *
     * Applied whether or not a module is loaded and whether or not the engine
     * is initialised, unlike the settings that go through observePref: the
     * native side keeps the value and re-applies it to whatever loads next,
     * so setting it early is how it is there in time for the first track.
     */
    fun setGlobalPitch(semitones: Int) {
        Timber.d("setGlobalPitch: $semitones")
        Player.setGlobalPitch(semitones)
    }

    /** Sets the playback speed in percent, pitch unchanged; see [setGlobalPitch]. */
    fun setGlobalTempo(percent: Int) {
        Timber.d("setGlobalTempo: $percent")
        Player.setGlobalTempo(percent)
    }

    /**
     * Every channel back as the loaded file has it, the listener's mutes
     * gone, and the views told.
     */
    private fun resetMutes() {
        synchronized(muteLock) {
            fileMutes = Player.resetMutes()
            channelMutes.value = fileMutes
            soloedChannel = -1
        }
    }

    /** Mutes [channel] if it is playing, and brings it back if it is not. */
    fun toggleMute(channel: Int) {
        if (channel !in 0 until 64) return
        soloedChannel = -1
        channelMutes.update { it xor (1L shl channel) }
        pushMutes()
    }

    /**
     * Solos [channel] among the first [numChannels], or lifts the solo when it
     * is already the only one playing.
     *
     * Decided from [channelMutes] rather than from a remembered "soloed" flag,
     * which goes stale the moment a plain tap mutes or unmutes anything - and
     * then the next long press does the opposite of what is on screen.
     */
    fun soloChannel(channel: Int, numChannels: Int) {
        val count = numChannels.coerceIn(0, 64)
        if (channel !in 0 until count) return
        val all = if (count == 64) -1L else (1L shl count) - 1
        val others = all and (1L shl channel).inv()
        channelMutes.update { mask ->
            val alreadySolo = (mask and all) == others
            soloedChannel = if (alreadySolo) -1 else channel
            // Lifting a solo brings back every channel, not only the ones
            // this track is known to have: a count that arrived short would
            // otherwise leave the rest silent with nothing on screen saying so.
            // Every channel but those the file itself mutes, which were not
            // playing before the solo either.
            if (alreadySolo) fileMutes else (mask and all.inv()) or others
        }
        pushMutes()
    }

    /**
     * Hands whatever [channelMutes] now says to the native side - every
     * channel of it, every time.
     *
     * Only the channels that differed from what the native side was last told
     * used to be sent. That is a bet that nothing else ever changes them, and
     * the decoders lose it: a seek is a tune started again, and a tune started
     * again comes up with its own idea of which channels sound. Once the two
     * had drifted apart, a tap that took a channel from muted to not was a
     * tap that changed nothing on this side - so it changed nothing at all,
     * and the channel stayed silent with the screen saying it was playing.
     *
     * Sixty-four calls rather than one or two, off the main thread, and the
     * two can no longer disagree for longer than one tap.
     */
    private fun pushMutes() {
        muteExecutor.execute {
            synchronized(muteLock) {
                // The latest value, not the one current when this was queued:
                // if a track change reset everything in between, this hands
                // over what is wanted now rather than what was wanted then.
                val wanted = channelMutes.value
                for (ch in 0 until 64) {
                    Player.mute(ch, if ((wanted and (1L shl ch)) != 0L) 1 else 0)
                }
            }
        }
    }

    /** Enables or disables single-track repeat mode. */
    fun isRepeatingOne(value: Boolean) {
        Timber.d("isRepeatingOne: $value")
        isRepeatOne = value
        // As it stands when it is said, which is what is wanted by then.
        if (initialized) whenDecoderIsFree { Player.setLoopMode(isRepeatOne) }
    }

    /**
     * Switches to sequence [index] within the loaded module.
     * Updates [positionMs] and [currentSequenceFlow] on success.
     *
     * @return true if the sequence index is valid and the switch succeeded.
     */
    fun setSequence(index: Int): Boolean {
        val result = Player.setSequence(index)
        Timber.d("setSequence: index=$index result=$result")
        if (result) {
            // A loop belongs to the subsong it was set in.
            startLoop(null)
            Player.setDurationOverride(overridesNow().durations[index] ?: 0)
            modVarsFlow.value = readModVars()
            positionMs.value = 0L
            currentSequenceFlow.value = index
            if (soundCheckOn) beginSoundCheck()
            // A subsong is the same track, so the listener's mutes stand. Not
            // every replayer keeps them through starting another subsong, so
            // they are said again rather than trusted to have survived.
            pushMutes()
        }
        return result
    }

    /**
     * [setSequence] for the main thread: carried out on [seekExecutor], with
     * [then] run there once the subsong has changed, or [refused] if the
     * decoder would not start it.
     *
     * Starting a subsong takes the decoder's mutex, and a seek holds that for
     * as long as the tune takes to run to the place - seconds, for a SID or
     * an NSF dragged far in. Picking a subsong or pressing next in that time
     * parked the main thread behind it: a frozen screen, and past five
     * seconds an ANR. On the seeks' own thread it waits its turn instead.
     *
     * Only the latest one asked for is carried out, and a seek still queued
     * for the subsong being left is dropped. One that is not carried out -
     * a newer one asked for, the module gone - runs neither [then] nor
     * [refused]: nothing was refused, and whatever took its place has the
     * say.
     */
    fun setSequenceAsync(index: Int, then: (() -> Unit)? = null, refused: (() -> Unit)? = null) {
        val ticket = sequenceTicket.incrementAndGet()
        overtakeSeek()
        sequencePending.set(index)
        seekExecutor.execute {
            try {
                // Under the lifecycle lock, like a seek: the module cannot
                // change between the check and the switch.
                synchronized(lifecycle) {
                    if (sequenceTicket.get() != ticket || !initialized) return@execute
                    if (setSequence(index)) then?.invoke() else refused?.invoke()
                }
            } finally {
                sequencePending.compareAndSet(index, -1)
            }
        }
    }

    /**
     * Takes the place of whatever seek is queued or being carried out: the
     * queued one is dropped, and one a decoder is running a tune to stops
     * where it has got to and lets go of [lifecycle].
     *
     * A console's tune is run to its place at a few times real time, minutes
     * in being many seconds, and the seek holds the lock for all of it. A
     * Next pressed in that time, a stop, a track ending, waited at the lock
     * until the seek nobody wanted any more had been made in full. So this
     * goes before the lock is asked for, not after it is had.
     */
    private fun overtakeSeek() {
        Player.setSeekWanted(seekTicket.incrementAndGet())
    }

    /**
     * Says something to the decoder that takes its mutex, without making the
     * caller wait for it - the caller being the main thread.
     *
     * A seek holds that mutex for as long as the tune takes to be run to the
     * place. A tap on repeat, or a loop set or cleared, in that time parked
     * the main thread behind it: a frozen screen, and for a long seek an
     * ANR. Behind the seeks, on their thread, it waits its turn instead, and
     * stays in order with them - a loop is set before the seek to its start.
     * A caller that holds [lifecycle] is not the main thread and has no seek
     * to wait for: it is said at once.
     */
    private fun whenDecoderIsFree(say: () -> Unit) {
        if (Thread.holdsLock(lifecycle)) {
            say()
            return
        }
        seekExecutor.execute {
            synchronized(lifecycle) { if (initialized) say() }
        }
    }

    /** A module change: nothing still queued for the one being left is carried out on the next. */
    private fun dropQueuedSeeks() {
        seekTicket.incrementAndGet()
        sequenceTicket.incrementAndGet()
        sequencePending.set(-1)
    }

    /**
     * Fast-path for queue transitions: keeps the Oboe stream open, stops the
     * render loop, releases the current module, then loads [file].
     * Falls back to [load] if the engine is uninitialised.
     *
     * @return true on success; false if Oboe or libopenmpt init fails.
     */
    fun loadNext(file: ModuleEntity): Boolean {
        overtakeSeek()
        return synchronized(lifecycle) { loadNextLocked(file) }
    }

    private fun loadNextLocked(file: ModuleEntity): Boolean {
        Timber.d("loadNext: '${file.filename}' initialized=$initialized")
        dropQueuedSeeks()
        if (!initialized) return loadLocked(file)

        softStop()
        clearPatternCache()
        resetSequenceState()
        Player.releaseModule()
        return loadModule(file)
    }

    /**
     * Starts the render loop and audio output.
     * Applies all current preference values to libopenmpt before playback begins.
     */
    fun start() {
        Timber.d("start: repeatOne=$isRepeatOne playAllSeq=$playAllSequences")
        if (!initialized) {
            Timber.w("start: not initialized, aborting")
            return
        }
        if (renderThread?.isAlive == true) {
            Timber.w("start: render thread already alive")
            return
        }

        stopRequest = false
        // As it was last asked to be, not "playing" outright - see
        // pauseRequested.
        paused = pauseRequested
        Player.setLoopMode(isRepeatOne)

        // Everything the track plays with, before any of it is played. Between
        // tracks the stream is left running, so the track is being rendered
        // from the moment startPlayer says so - and with this after it, the
        // first tens of milliseconds came out on the last track's mutes and
        // settings, or on the library's own defaults, which is what a module
        // just loaded has: its interpolation changed under its first notes.
        //
        // The listener's mutes do not carry over to another track: every
        // channel is put back as the file has it - and the views are told,
        // which they once were not, and went on drawing "Muted" over channels
        // of the new track that were playing. As the file has it, not all on:
        // a module can be saved with channels switched off, and those are
        // silent in it. Shown as muted, they can be brought back with a tap;
        // shown as playing, which they were, they were boxes with nothing in
        // them and no way to say why.
        resetMutes()
        applyRuntimeSettings()

        // 0: the engine takes the rate from the stream itself, which is the
        // only place that knows what the device actually opened at.
        if (Player.startPlayer(0, 0) != 0) {
            Timber.e("startPlayer() failed")
            return
        }

        // startPlayer sets everything playing, whatever was asked. A pause
        // that was - before the load, or while the settings above were being
        // applied, which takes a moment on the loader's thread - is put back
        // on both sides of starting the stream: before, so a stream opened
        // afresh never plays a note of a track that was to come up paused,
        // which after a route change would be out of the phone's speaker;
        // after, for one that arrived in between. Read again rather than as
        // it was on the way in, so the last word is the one kept: a Play
        // pressed meanwhile plays.
        paused = pauseRequested
        if (paused) Player.setPlaying(false)
        Player.playAudio()
        if (paused) Player.setPlaying(false)
        isPlaying.value = !paused

        renderThread = Thread(::renderLoop, "ModPlayerThread").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    /**
     * Pauses playback: drains the Oboe buffer and signals [isPlaying] false.
     * The stream stays open so [resume] can restart without re-loading.
     */
    fun pause() {
        Timber.d("pause")
        // The request before the state: start() reads the one to set the
        // other, and in this order cannot undo a pause it has just missed.
        pauseRequested = true
        paused = true
        Player.setPlaying(false)
        isPlaying.value = false
    }

    /**
     * Resumes paused playback without reloading or seeking.
     *
     * If the underlying Oboe stream disconnected while paused (e.g. a transient
     * audio-focus loss that coincided with a routing change — observed on Samsung
     * when a screen recorder starts capturing internal audio), this defers to the
     * existing [audioDisconnectedFlow] recovery path instead of pretending to
     * resume a dead stream.
     */
    fun resume() {
        // Before any of the ways out below: where there is nothing to resume
        // yet because the track is still on its way in, this is what has
        // start() play it when it gets here.
        pauseRequested = false
        if (!paused) {
            Timber.w("resume: not paused, ignoring")
            return
        }
        if (Player.hasAudioDisconnected()) {
            Timber.w("resume: stream disconnected while paused — deferring to disconnect recovery")
            paused = false
            audioDisconnectedFlow.value++
            return
        }
        // Paused, then another track asked for: it is loaded but not started
        // yet, and setting it playing from here would have it rendered before
        // start() has given it its settings and its mutes. start() is on its
        // way, and plays.
        if (renderThread?.isAlive != true) {
            Timber.w("resume: nothing started yet, leaving it to start()")
            return
        }
        Timber.d("resume")
        paused = false
        // Some OEM audio HALs silently stop the underlying stream (without ever
        // reporting an error callback) when the audio session is reconfigured out
        // from under us. Explicitly restarting is a cheap no-op when the stream is
        // already running, and a real fix when it silently isn't.
        if (!Player.restartAudio()) {
            // There, and it will not start: the way back is the one a
            // disconnect takes - another stream, and the tune on it.
            Timber.w("resume: the stream would not start - deferring to disconnect recovery")
            audioDisconnectedFlow.value++
            return
        }
        Player.setPlaying(true)
        isPlaying.value = true
    }

    /**
     * Seeks to [posMs] milliseconds within the current module, on
     * [seekExecutor]. Updates [positionMs] at once, and keeps it there until
     * the seek is done, so the UI shows where it is going rather than where
     * it was.
     */
    fun seek(posMs: Int) {
        Timber.d("engine.seek posMs=$posMs")
        val ticket = seekTicket.incrementAndGet()
        // The one before it, if it is still being carried out, stops on the way.
        Player.setSeekWanted(ticket)
        seeksPending.incrementAndGet()
        positionMs.value = posMs.toLong()
        seekExecutor.execute {
            try {
                // Under the lifecycle lock, so the module cannot change
                // between the check and the seek.
                synchronized(lifecycle) {
                    if (seekTicket.get() != ticket) return@execute
                    Player.seek(posMs, ticket)
                }
                positionMs.value = posMs.toLong()
                // A tune is a running program with no way back, so a seek
                // behind where it is starts it again - and a decoder that
                // starts again starts with every channel sounding. What the
                // listener muted or soloed is hers, and is handed to the new
                // run of it.
                pushMutes()
            } finally {
                seeksPending.decrementAndGet()
            }
        }
    }

    /**
     * Stops playback and releases the current module, but keeps the Oboe stream
     * open and outputting silence. The next [load] / [loadNext] call reuses the
     * live stream so the hardware DAC never deactivates — no hardware pop.
     *
     * Call [release] when the engine is truly being torn down (service destroyed).
     */
    fun stop() {
        overtakeSeek()
        stopLocked()
    }

    private fun stopLocked() = synchronized(lifecycle) {
        Timber.d("stop: initialized=$initialized")
        dropQueuedSeeks()
        endedNaturally = false
        joinRenderThread(2_000)
        if (initialized) {
            Player.endPlayer()
            Player.releaseModule()
        }
        isPlaying.value = false
        positionMs.value = 0L
        frameInfoFlow.value = FrameInfo()
    }

    /**
     * Cleans up after an Oboe ErrorDisconnected event so the next [load] / [loadNext]
     * call can open a fresh stream on the new audio device.
     *
     * Must be called off the main thread (joins the render thread and calls [Player.deinit]).
     */
    fun handleAudioDisconnect() {
        overtakeSeek()
        synchronized(lifecycle) { disconnectedLocked() }
    }

    private fun disconnectedLocked() {
        Timber.w("handleAudioDisconnect: joining render thread, initialized=$initialized")
        joinRenderThread(1_000)
        deinit()
        // The native flag is sticky: nothing else clears it except opening a
        // fresh stream, so a recovery path that does not reach that point
        // would leave every later start immediately aborting on a disconnect
        // that has already been dealt with.
        Player.clearAudioDisconnected()
        Timber.w("handleAudioDisconnect: done")
    }

    /**
     * Closes the Oboe stream and native player. Called when the PlayerService is
     * destroyed (which is also how [PlayerService.onTaskRemoved] ends an idle
     * engine) — but this object is a process-lifetime singleton, not a
     * service-lifetime one, and PlayerService can come back while it's still
     * alive. So this must NOT cancel [scope]/[prefsExecutor]: those back the
     * preference observers registered once in [init], and nothing ever
     * re-registers them — cancelling here would leave settings permanently
     * unresponsive (audio itself self-heals on the next [load]/[loadNext], which
     * re-sets [initialized], but a dead observer coroutine stays dead) until the
     * whole process restarts. Do not call during normal stop/play transitions —
     * use [stop] for those.
     */
    fun release() {
        overtakeSeek()
        synchronized(lifecycle) {
            Timber.d("release")
            stopLocked()
            deinit()
        }
    }

    /**
     * Returns (potentially cached) [PatternData] for [patternIndex].
     * Used by the pattern visualiser.
     */
    fun getPatternData(patternIndex: Int): PatternData =
        patternCache[patternIndex] ?: run {
            val numCh = modVarsFlow.value.chn
            val numRows = Player.getPatternRows(patternIndex)
            // Don't cache invalid results — a race with module teardown can produce
            // numRows=0 for a valid pattern. Leaving the entry absent lets the next
            // recomposition retry rather than permanently serving a blank.
            if (numCh == 0 || numRows == 0) return PatternData()

            // One native call for the whole pattern, under a single mutex acquisition,
            // instead of one call per row — the per-row version made up to 64 separate
            // lock/unlock cycles per fetch, contending heavily with the audio render
            // callback for the same native mutex. Row-major, fixed 64-column stride.
            val stride = 64
            val total = numRows * stride
            val notes = ByteArray(total)
            val ins = ByteArray(total)
            val fxt = ByteArray(total)
            val fxp = ByteArray(total)
            val fx2t = ByteArray(total)
            val fx2p = ByteArray(total)
            Player.getPatternData(patternIndex, numRows, notes, ins, fxt, fxp, fx2t, fx2p)

            // Furnace's effects are two hex digits, which a signed byte with -1
            // for "none" cannot carry, and its volume has a column of its own -
            // so its decoder fills these columns its own way: the effect as an
            // unsigned byte, and in place of a second effect a pair of flags,
            // 1 for "has an effect" and 2 for "has a volume", with the volume
            // as its value. See furnace_decoder_pattern_data.
            val furnace = modVarsFlow.value.renderingEngine.startsWith("Furnace")

            val cells = Array(numRows) { row ->
                val base = row * stride
                Array(numCh) { ch ->
                    val i = base + ch
                    if (furnace) {
                        val flags = fx2t[i].toInt()
                        NoteCell(
                            note = notes[i].toInt() and 0xFF,
                            instrument = ins[i].toInt() and 0xFF,
                            fxType = if ((flags and 1) != 0) fxt[i].toInt() and 0xFF else -1,
                            fxParam = fxp[i].toInt() and 0xFF,
                            fx2Type = if ((flags and 2) != 0) VOLUME_COLUMN_EFFECT else -1,
                            fx2Param = fx2p[i].toInt() and 0xFF,
                            wideEffect = true,
                        )
                    } else {
                        NoteCell(
                            note = notes[i].toInt() and 0xFF,
                            instrument = ins[i].toInt() and 0xFF,
                            fxType = fxt[i].toInt(), // signed; -1 = empty slot
                            fxParam = fxp[i].toInt() and 0xFF,
                            fx2Type = fx2t[i].toInt(),
                            fx2Param = fx2p[i].toInt() and 0xFF,
                        )
                    }
                }.toList().toImmutableList()
            }.toList().toImmutableList()

            PatternData(
                cells = cells,
                numChannels = numCh,
                numRows = numRows,
                patternIndex = patternIndex
            ).also { patternCache[patternIndex] = it }
        }

    fun getAudioStats(stats: AudioStats) = Player.getAudioStats(stats)

    /**
     * Full initialisation path: inits the Oboe stream, then loads [file].
     * Use [loadNext] for queue transitions so the stream stays open between tracks.
     */
    private fun load(file: ModuleEntity): Boolean {
        overtakeSeek()
        return synchronized(lifecycle) { loadLocked(file) }
    }

    private fun loadLocked(file: ModuleEntity): Boolean {
        Timber.d("load: '${file.filename}' initialized=$initialized")
        dropQueuedSeeks()
        if (initialized) {
            softStop()
            Player.releaseModule()
        } else if (!initAudio()) {
            return false
        }
        clearPatternCache()
        resetSequenceState()
        return loadModule(file)
    }

    /** Resets sequence-tracking fields before loading a new module. */
    private fun resetSequenceState() {
        endedNaturally = false
        stopRequest = false
        currentSequenceFlow.value = 0
    }

    /** Initializes the Oboe audio stream using the current audio preferences. */
    private fun initAudio(): Boolean = runBlocking {
        val audioApi = prefs.getOboeAudioApiFlow().first()

        Timber.d("initAudio: audioApi=$audioApi")

        initialized = Player.init(
            // Unspecified, both of them. The rate is the native side's fixed
            // render rate, and Oboe negotiates the device's own and converts
            // between them - so how fast a tune plays never depends on what the
            // hardware runs at (see kRenderRate in oboe.cpp). The buffer is
            // sized from the device's own burst; asking for a particular one
            // only fought the one the hardware wanted.
            rate = 0,
            ms = 0,
            perfMode = 0, // ignored natively — Oboe performance mode is hardcoded to None
            // Stereo, always: the stereo separation slider goes to 0, which
            // is the same mix in both ears and what mono output ever was.
            channels = Player.OBOE_CHANNELS_STEREO,
            audioApi = audioApi,
            flags = 0,
        )
        if (!initialized) Timber.e("Player.init() failed")
        initialized
    }

    /** Applies volume / stereo separation / dither / ramping / interpolation settings to the running player. */
    private fun applyRuntimeSettings() = runBlocking {
        // With the loaded file's overrides read directly, not through the
        // flows: this runs as playback starts, and a flow that has not caught
        // up with the new file yet would start it on the last file's settings.
        val soundCheck = prefs.getSoundCheckFlow().first()
        soundCheckOn = soundCheck
        Player.setSoundCheck(soundCheck)
        // Unity, always: how loud a file plays is the sound check's to say,
        // and a gain of its own went with the master volume.
        Player.setPlayer(Player.PARAM_MASTER_VOLUME, UNITY_VOLUME)
        Player.setVolumeBoost(0)
        Player.setPlayer(
            Player.PARAM_STEREO_SEPARATION,
            effective(OverrideKeys.OPENMPT_STEREO, prefs.getStereoSeparationFlow().first()),
        )
        Player.setPlayer(Player.PARAM_DITHER, prefs.getDitherTypeFlow().first())
        Player.setPlayer(Player.PARAM_VOLUME_RAMPING, prefs.getVolumeRampingFlow().first())
        Player.setHivelyStereo(effective(OverrideKeys.HVL_STEREO, prefs.getHivelyStereoFlow().first()))
        // The libraries' own volumes are left where they were made: what a
        // file plays at is the sound check's to decide, and a gain in front
        // of it only fought it.
        Player.setHivelyGain(Player.DEFAULT_HIVELY_GAIN)
        Player.setAdPlugGain(Player.DEFAULT_ADPLUG_GAIN)
        Player.setAdPlugCore(effective(OverrideKeys.ADPLUG_CORE, prefs.getAdPlugCoreFlow().first()))
        Player.setGmeDefaultLength(prefs.getGmeLengthFlow().first())
        for (console in gmeConsoles) {
            Player.setGmeVolume(console, Player.DEFAULT_GME_VOLUME)
        }
        for (console in gmeStereoConsoles) {
            Player.setGmeStereo(
                console, effective(OverrideKeys.gmeStereo(console), prefs.getGmeStereoFlow(console).first()),
            )
        }
        Player.setGmeStereo(Player.GME_SNES, Player.DEFAULT_GME_STEREO)
        for (voice in 0 until Player.GME_NES_VOICES + Player.GME_NES_EXPANSION_VOICES) {
            Player.setGmePan(
                Player.GME_NES,
                voice,
                effective(
                    OverrideKeys.gmePan(Player.GME_NES, voice),
                    prefs.getGmePanFlow(Player.GME_NES, voice).first(),
                ),
            )
        }
        Player.setGmeEcho(
            effective(OverrideKeys.GME_SNES_ECHO, if (prefs.getGmeEchoFlow().first()) 1 else 0) != 0
        )
        Player.setGmeInterpolation(
            effective(OverrideKeys.GME_SNES_INTERPOLATION, prefs.getGmeInterpolationFlow().first())
        )
        Player.setGsfStereo(effective(OverrideKeys.GSF_STEREO, prefs.getGsfStereoFlow().first()))
        Player.setGsfLowpass(
            effective(OverrideKeys.GSF_LOWPASS, if (prefs.getGsfLowpassFlow().first()) 1 else 0) != 0
        )
        for (channel in 0 until Player.GSF_CHANNELS) {
            Player.setGsfPan(
                channel, effective(OverrideKeys.gsfPan(channel), prefs.getGsfPanFlow(channel).first()),
            )
        }
        Player.setTwoSfStereo(effective(OverrideKeys.TWOSF_STEREO, prefs.getTwoSfStereoFlow().first()))
        Player.setTwoSfInterpolation(
            effective(OverrideKeys.TWOSF_INTERPOLATION, prefs.getTwoSfInterpolationFlow().first())
        )
        for (channel in 0 until Player.TWOSF_CHANNELS) {
            Player.setTwoSfPan(
                channel, effective(OverrideKeys.twoSfPan(channel), prefs.getTwoSfPanFlow(channel).first()),
            )
        }
        Player.setUsfStereo(effective(OverrideKeys.USF_STEREO, prefs.getUsfStereoFlow().first()))
        Player.setUsfHle(effective(OverrideKeys.USF_HLE, if (prefs.getUsfHleFlow().first()) 1 else 0) != 0)
        Player.setPsfStereo(effective(OverrideKeys.PSF_STEREO, prefs.getPsfStereoFlow().first()))
        Player.setPsfReverb(
            effective(OverrideKeys.PSF_REVERB, if (prefs.getPsfReverbFlow().first()) 1 else 0) != 0
        )
        Player.setDspStereo(effective(OverrideKeys.DSP_STEREO, prefs.getDspStereoFlow().first()))
        Player.setAudioStereo(effective(OverrideKeys.AUDIO_STEREO, prefs.getAudioStereoFlow().first()))
        Player.setResampler(
            ResamplerMode.fromId(
                effective(OverrideKeys.OPENMPT_INTERPOLATION, prefs.getInterpolationTypeFlow().first())
            ).id
        )
        val own = overridesNow()
        Player.setDurationOverride(own.durations[currentSequenceFlow.value] ?: 0)
        // And the effects as this file has them, said now with the rest: the
        // flows above get round to it a moment after the file has changed,
        // which is a moment of the last file's room on this one's first notes.
        prefs.getReverbFlow().first().forFile(own.settings).let { Player.setReverb(it.enabled, it.type, it.mix) }
        prefs.getChorusFlow().first().forFile(own.settings).let {
            Player.setChorus(it.enabled, it.rate, it.spread, it.mix)
        }
        prefs.getSurroundFlow().first().forFile(own.settings).let { Player.setSurround(it.enabled, it.mix) }
        if (soundCheck) beginSoundCheck()
    }

    /**
     * Starts Sound Check on the file and subsong loaded, at its analysed
     * loudness - or, when that is not known yet, at the average of the tracks
     * analysed so far, with its analysis asked for.
     */
    private fun beginSoundCheck() {
        val uri = currentUri.value
        val subsong = currentSequenceFlow.value
        soundCheckUri = uri
        soundCheckSubsong = subsong
        val measured = uri?.let { soundCheckStore.measurement(it, subsong) }
        soundCheckApplied = measured?.lufs
        Timber.d("soundCheck: begin '$uri' #$subsong known=$measured")
        Player.soundCheckBegin(measured?.lufs ?: Player.SOUND_CHECK_UNKNOWN, soundCheckStore.prior())
        if (uri != null && measured?.complete != true && !soundCheckStore.isSettled(uri, subsong)) {
            analyzer.request(uri, subsong, LoudnessProtocol.PRIORITY_CURRENT)
        }
    }

    /**
     * Before a track starts: when Sound Check is on and the track has never
     * been analysed, has it measured and waits for that - up to a couple of
     * seconds, and only this once - so its level is right from its first
     * sample instead of moving once it is playing. Blocks: call it from the
     * thread that loads the track, never the main one.
     *
     * [stillWanted] is asked once the wait is one [cancelSoundCheckWait] can
     * end, and says whether the track is still the one to play: a wait for a
     * track that another has already been picked over is not sat through.
     */
    fun prepareSoundCheck(stillWanted: () -> Boolean) {
        val on = runBlocking { prefs.getSoundCheckFlow().first() }
        val uri = currentUri.value ?: return
        if (!on) return
        val subsong = currentSequenceFlow.value
        if (soundCheckStore.measurement(uri, subsong) != null || soundCheckStore.isSettled(uri, subsong)) return
        val started = System.currentTimeMillis()
        val lufs = analyzer.awaitLoudness(uri, subsong, SOUND_CHECK_WAIT_MS, stillWanted)
        Timber.d("soundCheck: waited ${System.currentTimeMillis() - started} ms for '$uri' #$subsong: $lufs")
    }

    /** Ends a [prepareSoundCheck] in progress, for a track that is not going to be played after all. */
    fun cancelSoundCheckWait() = analyzer.cancelWaits()

    /** Has the tracks after the one starting analysed next, so they start at their level. */
    fun soundCheckAhead(uris: List<String>) {
        if (!soundCheckOn || uris.isEmpty()) return
        analyzer.analyzeAhead(uris.take(SOUND_CHECK_AHEAD))
    }

    /** What Sound Check is doing now, for the debug view. */
    fun soundCheckStatus(): SoundCheckStatus {
        val stats = FloatArray(Player.SOUND_CHECK_STATS)
        Player.getSoundCheckStats(stats)
        val uri = soundCheckUri
        return SoundCheckStatus(
            settingOn = soundCheckOn,
            nativeOn = stats[0] != 0f,
            processedSeconds = stats[1],
            loudness = stats[2],
            measuredSeconds = stats[3],
            gainDb = stats[4],
            limiterDb = stats[5],
            fixed = stats[6] != 0f,
            tracksStarted = stats[7].toInt(),
            stored = uri?.let { soundCheckStore.measurement(it, soundCheckSubsong) },
            prior = soundCheckStore.prior(),
            library = analyzer.progress.value,
        )
    }

    /**
     * Loads [file] into libopenmpt and populates [modVarsFlow].
     *
     * @return true on success; on failure, de-inits only if this is a
     *   first-time load (i.e., [softStop] wasn't called).
     */
    private fun loadModule(file: ModuleEntity): Boolean {
        val result = Player.loadFromFd(context, file.uri, ModInfo())
        if (result != 0) {
            Timber.e("loadFromFd() returned $result for '${file.filename}'")
            // Only for a grant that is gone. A file deleted or moved since it
            // was queued comes back as LOAD_GONE, and is skipped without the
            // user being told the app has lost its folder.
            if (result == Player.LOAD_UNREADABLE) access.reportLost()
            if (!initialized) Player.deinit()
            return false
        }
        // As the file has its channels, before anything else can say: a
        // subsong or a loop picked for it hands the mutes over on its way to
        // start(), and what it handed over was the last track's.
        resetMutes()
        // A new file's own overrides, and no loop carried over from the last.
        currentUri.value = file.filePath
        startLoop(null)
        val mv = readModVars()
        modVarsFlow.value = mv
        channelLayoutSeen = 0
        positionMs.value = 0L
        Timber.i(
            "loadModule: '${mv.modName}' type=${mv.modType} chn=${mv.chn} seq=${mv.miNumSequences}"
        )
        // Every subsong of a file that has several, so whichever is picked
        // starts at its own level.
        if (soundCheckOn && mv.miNumSequences > 1) analyzer.analyzeSubsongs(file.filePath, mv.miNumSequences)
        return true
    }

    /**
     * Stops the render thread and ends the libopenmpt player session, but leaves
     * the Oboe stream open. Used between queue tracks to avoid teardown overhead.
     */
    private fun softStop() {
        Timber.d("softStop")
        joinRenderThread(2_000)
        Player.endPlayer()
        isPlaying.value = false
        positionMs.value = 0L
    }

    /**
     * Signals the render thread to exit and waits up to [timeoutMs].
     *
     * The loop now spends its time in Thread.sleep rather than inside a
     * blocking native call, so interrupt() genuinely wakes it: the sleep
     * throws, the loop's catch breaks out, and [stopRequest] stops it from
     * being re-entered. Both mechanisms are kept because they cover different
     * moments - interrupt for a thread already asleep, the flag for one about
     * to loop again.
     */
    private fun joinRenderThread(timeoutMs: Long) {
        stopRequest = true
        renderThread?.interrupt()
        renderThread?.join(timeoutMs)
        renderThread = null
    }

    /** Closes the native player if it is currently initialised. */
    private fun deinit() {
        if (!initialized) return
        Player.deinit()
        initialized = false
    }

    /** Clears cached pattern data and module/frame state before loading a new module. */
    private fun clearPatternCache() {
        patternCache.clear()
        modVarsFlow.value = ModVars()
        frameInfoFlow.value = FrameInfo()
    }

    private fun readModVars() = ModVars().also { Player.getModVars(it) }

    /**
     * Reads the module's variables again for a song whose channels changed as
     * it played - the Nintendo 64's voices, which appear as the game first
     * uses them - and keeps the mutes meaning what they meant: kept while the
     * channels only grew, and a solo still a solo over the new ones; dropped
     * when the channels are other ones altogether, named otherwise.
     */
    private fun rereadChannels() {
        val before = modVarsFlow.value
        // The song's length as the load read it: read again it would be the
        // listener's own for the file, taken for the file's.
        val after = readModVars().copy(seqData = before.seqData, durationKnown = before.durationKnown)
        modVarsFlow.value = after
        channelMutes.update { mask ->
            val soloed = soloedChannel
            when {
                !after.instruments.contentEquals(before.instruments) -> {
                    soloedChannel = -1
                    0L
                }
                after.chn > before.chn && soloed in 0 until before.chn &&
                    (mask and (1L shl soloed)) == 0L && isSolo(mask, before.chn) ->
                    mask or (channelBits(after.chn) and channelBits(before.chn).inv())
                else -> mask
            }
        }
        // Every time: the engine keeps what looks like a solo over a channel
        // it meets for the first time, and this is the word on it.
        pushMutes()
    }

    /** The bits of the first [count] channels. */
    private fun channelBits(count: Int): Long =
        if (count >= 64) -1L else (1L shl count.coerceAtLeast(0)) - 1

    /** Whether exactly one of the first [count] channels is playing, the rest muted. */
    private fun isSolo(mask: Long, count: Int): Boolean =
        count > 1 && java.lang.Long.bitCount(channelBits(count) and mask.inv()) == 1

    private fun <T> Flow<T>.blockingFirst(): T = runBlocking { first() }

    /** Re-applies [apply] whenever a preference changes while the engine is initialised. */
    private fun <T> observePref(flow: Flow<T>, apply: (T) -> Unit) {
        flow.distinctUntilChanged().onEach { if (initialized) apply(it) }.launchIn(scope)
    }

    /** The loop! */
    private fun renderLoop() {
        Timber.d("renderLoop: start")
        // A supervisory loop, not a rendering one, and deliberately paced by a
        // plain interval.
        //
        // It used to drive the visualisers, which is why it previously blocked
        // on a native render event and ran at the audio callback's own rate:
        // every row had to be observed here or the pattern view would skip it.
        // It no longer feeds anything visual at all - the views read their own
        // frame-stamped snapshot once per display frame (see PlaybackVisuals)
        // - so all that is left here is watching for three things that are not
        // frame-accurate concerns by nature: the module ending, the audio
        // device disconnecting, and the media session's position readout.
        // 20ms is far finer than any of those need, and unlike the old design
        // it costs one lock-free read per tick instead of waking on, and
        // contending for, the audio thread's mutex ~200 times a second.
        while (!stopRequest) {
            try {
                Thread.sleep(SUPERVISE_INTERVAL_MS)

                // Checked before the paused-gate below: a stream can disconnect
                // (headphones out, or an OEM audio-routing reconfiguration — e.g.
                // Samsung's internal-audio screen recorder) while we're sitting
                // paused for an unrelated reason, and we still want to recover it
                // in the background so resuming later just works.
                if (Player.hasAudioDisconnected()) {
                    Timber.w("renderLoop: audio device disconnected")
                    isPlaying.value = false
                    audioDisconnectedFlow.value++
                    break
                }

                if (paused || stopRequest) continue

                val fi = FrameInfo()
                Player.getFrameInfo(fi)
                frameInfoFlow.value = fi
                if (fi.channelLayout != channelLayoutSeen) {
                    channelLayoutSeen = fi.channelLayout
                    rereadChannels()
                }
                // Where the song was, until a seek on its way has got there.
                if (seeksPending.get() == 0) positionMs.value = fi.time.toLong().coerceAtLeast(0L)

                if (Player.hasModuleEnded()) {
                    Timber.i(
                        "renderLoop: module ended pos=${positionMs.value}ms playAllSequences=$playAllSequences"
                    )

                    if (playAllSequences && !pinnedSubsong) {
                        val nextSeq = currentSequenceFlow.value + 1
                        Timber.i(
                            "renderLoop: trying seq $nextSeq / ${modVarsFlow.value.miNumSequences}"
                        )
                        if (setSequence(nextSeq)) continue
                        Timber.i("renderLoop: no more sequences, ending naturally")
                    }

                    endedNaturally = true
                    isPlaying.value = false
                    moduleEndedFlow.tryEmit(Unit)
                    Timber.i("renderLoop: ended naturally")
                    break
                }
            } catch (e: InterruptedException) {
                // The normal stop path, not a fault: joinRenderThread
                // interrupts to wake this out of its sleep immediately rather
                // than waiting out the interval. Restore the flag so anything
                // further up the stack can still see it.
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                Timber.e(e, "renderLoop: unexpected exception")
                break
            }
        }
        Timber.d("renderLoop: exit stopRequest=$stopRequest endedNaturally=$endedNaturally")
    }
}
