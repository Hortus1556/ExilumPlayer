package com.hortus1556.exilum.db

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import java.io.File
import android.util.AtomicFile
import com.hortus1556.exilum.ui.theme.UiStyle
import androidx.datastore.preferences.core.floatPreferencesKey
import android.content.Context
import androidx.compose.ui.graphics.*
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hortus1556.exilum.renderer.Player
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import timber.log.Timber

data class QueueState(
    val index: Int,
    val json: String,
    val positionMs: Long,
    val repeat: Int,
    val shuffle: Boolean,
    /** Shuffle over the whole library - see ModPlayer.shuffleRoot. */
    val shuffleRoot: Boolean = false,
)

/** The reverb as the settings have it - see Player.setReverb. */
data class ReverbSettings(
    val enabled: Boolean = false,
    val type: Int = Player.DEFAULT_REVERB_TYPE,
    val mix: Int = Player.DEFAULT_REVERB_MIX,
)

/** The chorus as the settings have it - see Player.setChorus. */
data class ChorusSettings(
    val enabled: Boolean = false,
    val rate: Int = Player.DEFAULT_CHORUS_RATE,
    val spread: Int = Player.DEFAULT_CHORUS_SPREAD,
    val mix: Int = Player.DEFAULT_CHORUS_MIX,
)

/** The virtual surround as the settings have it - see Player.setSurround. */
data class SurroundSettings(
    val enabled: Boolean = false,
    val mix: Int = Player.DEFAULT_SURROUND_MIX,
)

/** The equalizer as the settings have it - see Player.setEqualizer. */
data class EqualizerSettings(
    val enabled: Boolean = false,
    val bands: List<Int> = List(Player.EQ_BANDS) { 0 },
)

private val Context.dataStore by preferencesDataStore(
    name = "xmp_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler {
        Timber.e("Preferences corrupted, resetting.")
        emptyPreferences()
    }
)

class AppPreferences(context: Context) {

    private val dataStore: DataStore<Preferences> = context.dataStore

    private fun <T> flow(key: Preferences.Key<T>, default: T): Flow<T> =
        dataStore.data.map { it[key] ?: default }

    private fun <T> flowNullable(key: Preferences.Key<T>): Flow<T?> =
        dataStore.data.map { it[key] }

    private suspend fun <T> get(key: Preferences.Key<T>, default: T): T =
        dataStore.data.map { it[key] ?: default }.firstOrNull() ?: default

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) =
        dataStore.edit { it[key] = value }

    /**
     * Puts every setting back to what it starts as - and leaves what is kept
     * here without being one: the music folder, the formats the library was
     * last tested against, and where the saved queue had got to.
     *
     * It used to empty the store. That forgot the music folder, and with it
     * tracker.conf stopped being written; the folder picked again was a
     * folder with a config, so the old file was restored over every favourite
     * and playlist made in between. Left alone, the folder is the same value
     * to everything that follows it, and nothing starts over.
     */
    suspend fun resetAll() = dataStore.edit { prefs ->
        val kept: Set<Preferences.Key<*>> = setOf(
            lastDirectoryUri, testedFormats,
            queueJson, queueIndex, queueShuffle, queueShuffleRoot, queueRepeat, queuePositionMs,
        )
        prefs.asMap().keys.filterNot { it in kept }.forEach { prefs.remove(it) }
    }

    /* Debug */
    private val fileLogging = booleanPreferencesKey("file_logging")
    fun getFileLoggingFlow() = flow(fileLogging, false)
    suspend fun setFileLogging(v: Boolean) = set(fileLogging, v)

    /* Application */
    /**
     * Which overall design language the app is drawn in - see UiStyle. Held
     * as that style's own number, not its place in the list, so the one the
     * app starts in can change without moving anybody's choice.
     */
    private val uiStyle = intPreferencesKey("ui_style")
    fun getUiStyleFlow() = flow(uiStyle, UiStyle.LiquidGlass.stored)
    suspend fun setUiStyle(v: Int) = set(uiStyle, v)

    // On by default: the app was only ever dark before there was a choice.
    private val darkMode = booleanPreferencesKey("dark_mode")
    fun getDarkModeFlow() = flow(darkMode, true)
    suspend fun setDarkMode(v: Boolean) = set(darkMode, v)

    // How much Liquid Glass frosts what is behind it: 0 clear, 1 all but
    // solid. 0.4 is the wash iOS puts over its bars.
    private val liquidGlassOpacity = floatPreferencesKey("liquid_glass_opacity")
    fun getLiquidGlassOpacityFlow() = flow(liquidGlassOpacity, DEFAULT_GLASS_OPACITY)
    suspend fun setLiquidGlassOpacity(v: Float) = set(liquidGlassOpacity, v.coerceIn(0f, 1f))

    // Whether the glass bends what is behind it - see LiquidRefraction. Off,
    // it is a plain blur; the device may rule it out whatever this says.
    private val liquidRefraction = booleanPreferencesKey("liquid_refraction")
    fun getLiquidRefractionFlow() = flow(liquidRefraction, true)
    suspend fun setLiquidRefraction(v: Boolean) = set(liquidRefraction, v)

    /* File Browser */
    private val lastDirectoryUri = stringPreferencesKey("last_directory_uri")
    fun getLastDirectoryFlow() = flowNullable(lastDirectoryUri)
    suspend fun getLastDirectoryUri() = get(lastDirectoryUri, "").ifBlank { null }
    suspend fun setLastDirectoryUri(v: String) = set(lastDirectoryUri, v)

    /** The formats the library was last tested against - see ModuleRepository.retestAfterDecoderChanges. */
    private val testedFormats = stringPreferencesKey("tested_formats")
    suspend fun getTestedFormats() = get(testedFormats, "")
    suspend fun setTestedFormats(v: String) = set(testedFormats, v)

    private val globalShuffle = booleanPreferencesKey("global_shuffle")
    fun getGlobalShuffleFlow() = flow(globalShuffle, false)
    suspend fun setGlobalShuffle(v: Boolean) = set(globalShuffle, v)

    /** Shuffle over the whole library, the third shuffle state. Only meaningful with shuffle on. */
    private val globalShuffleRoot = booleanPreferencesKey("global_shuffle_root")
    fun getGlobalShuffleRootFlow() = flow(globalShuffleRoot, false)
    suspend fun setGlobalShuffleRoot(v: Boolean) = set(globalShuffleRoot, v)

    // Repeat, stored beside shuffle for the same reason: both are how the
    // listener wants things played, not something about one queue. 0 is Media3's
    // REPEAT_MODE_OFF - a literal, because Player here is the native one.
    private val globalRepeat = intPreferencesKey("global_repeat")
    fun getGlobalRepeatFlow() = flow(globalRepeat, 0)
    suspend fun setGlobalRepeat(v: Int) = set(globalRepeat, v)

    private val globalSubSongs = booleanPreferencesKey("global_subsongs")
    fun getGlobalSubSongsFlow() = flow(globalSubSongs, false)
    suspend fun setGlobalSubSongs(v: Boolean) = set(globalSubSongs, v)

    /* Player (libopenmpt) */
    private val stereoSeparation = intPreferencesKey("stereo_separation")
    fun getStereoSeparationFlow() = flow(stereoSeparation, Player.DEFAULT_STEREO_SEPARATION)
    suspend fun setStereoSeparation(v: Int) = set(stereoSeparation, v)

    private val ditherType = intPreferencesKey("dither_type")
    fun getDitherTypeFlow() = flow(ditherType, Player.DEFAULT_DITHER)
    suspend fun setDitherType(v: Int) = set(ditherType, v)

    private val volumeRamping = intPreferencesKey("volume_ramping")
    fun getVolumeRampingFlow() = flow(volumeRamping, Player.DEFAULT_VOLUME_RAMPING)
    suspend fun setVolumeRamping(v: Int) = set(volumeRamping, v)

    // The Hively replayer's own two. Separate keys from libopenmpt's: they
    // scale different things and a tune only ever goes through one of them.
    // Separation is on libopenmpt's 0-200 scale, where 200 is the Amiga's hard
    // panning. It used to be 0-100 with 100 as the hard panning, under the old
    // key - so a value stored there is doubled on the way out rather than
    // silently halving the width of whoever set it.
    private val hivelyStereo = intPreferencesKey("hively_stereo_200")
    private val hivelyStereoOld = intPreferencesKey("hively_stereo")
    fun getHivelyStereoFlow() = dataStore.data.map {
        it[hivelyStereo] ?: it[hivelyStereoOld]?.let { old -> (old * 2).coerceIn(0, 200) }
            ?: Player.DEFAULT_HIVELY_STEREO
    }
    suspend fun setHivelyStereo(v: Int) = set(hivelyStereo, v)

    private val hivelyGain = intPreferencesKey("hively_gain")
    fun getHivelyGainFlow() = flow(hivelyGain, Player.DEFAULT_HIVELY_GAIN)
    suspend fun setHivelyGain(v: Int) = set(hivelyGain, v)

    // AdPlug's. There was a stereo spread here once ("adplug_stereo"): gone -
    // an AdLib tune is heard where the chip put it.
    private val adplugGain = intPreferencesKey("adplug_gain")
    fun getAdPlugGainFlow() = flow(adplugGain, Player.DEFAULT_ADPLUG_GAIN)
    suspend fun setAdPlugGain(v: Int) = set(adplugGain, v)

    private val adplugCore = intPreferencesKey("adplug_core")
    fun getAdPlugCoreFlow() = flow(adplugCore, Player.DEFAULT_ADPLUG_CORE)
    suspend fun setAdPlugCore(v: Int) = set(adplugCore, v)

    // Not a sound setting: it is how long to play a tune that has no end and
    // no tag saying when to stop.
    private val gmeLength = intPreferencesKey("gme_default_length")
    fun getGmeLengthFlow() = flow(gmeLength, Player.DEFAULT_GME_LENGTH)
    suspend fun setGmeLength(v: Int) = set(gmeLength, v)

    // One set per console, because they are different machines - see
    // Player.GME_NES and friends, whose values are what keys these.
    private fun gmeVolumeKey(console: Int) = intPreferencesKey("gme_volume_$console")
    fun getGmeVolumeFlow(console: Int) =
        flow(gmeVolumeKey(console), Player.DEFAULT_GME_VOLUME)
    suspend fun setGmeVolume(console: Int, v: Int) = set(gmeVolumeKey(console), v)

    private fun gmeStereoKey(console: Int) = intPreferencesKey("gme_stereo_$console")
    fun getGmeStereoFlow(console: Int) =
        flow(gmeStereoKey(console), Player.DEFAULT_GME_STEREO)
    suspend fun setGmeStereo(console: Int, v: Int) = set(gmeStereoKey(console), v)

    private fun gmePanKey(console: Int, voice: Int) =
        intPreferencesKey("gme_pan_${console}_$voice")
    fun getGmePanFlow(console: Int, voice: Int) =
        flow(gmePanKey(console, voice), Player.DEFAULT_GME_PAN)
    suspend fun setGmePan(console: Int, voice: Int, v: Int) =
        set(gmePanKey(console, voice), v)

    private val gsfStereo = intPreferencesKey("gsf_stereo")
    fun getGsfStereoFlow() = flow(gsfStereo, Player.DEFAULT_GSF_STEREO)
    suspend fun setGsfStereo(v: Int) = set(gsfStereo, v)

    private val gsfLowpass = booleanPreferencesKey("gsf_lowpass")
    fun getGsfLowpassFlow() = flow(gsfLowpass, Player.DEFAULT_GSF_LOWPASS)
    suspend fun setGsfLowpass(v: Boolean) = set(gsfLowpass, v)

    private fun gsfPanKey(channel: Int) = intPreferencesKey("gsf_pan_$channel")
    fun getGsfPanFlow(channel: Int) = flow(gsfPanKey(channel), Player.DEFAULT_GSF_PAN)
    suspend fun setGsfPan(channel: Int, v: Int) = set(gsfPanKey(channel), v)

    private val twoSfStereo = intPreferencesKey("twosf_stereo")
    fun getTwoSfStereoFlow() = flow(twoSfStereo, Player.DEFAULT_TWOSF_STEREO)
    suspend fun setTwoSfStereo(v: Int) = set(twoSfStereo, v)

    private val twoSfInterpolation = intPreferencesKey("twosf_interpolation")
    fun getTwoSfInterpolationFlow() = flow(twoSfInterpolation, Player.DEFAULT_TWOSF_INTERPOLATION)
    suspend fun setTwoSfInterpolation(v: Int) = set(twoSfInterpolation, v)

    private fun twoSfPanKey(channel: Int) = intPreferencesKey("twosf_pan_$channel")
    fun getTwoSfPanFlow(channel: Int) = flow(twoSfPanKey(channel), Player.DEFAULT_TWOSF_PAN)
    suspend fun setTwoSfPan(channel: Int, v: Int) = set(twoSfPanKey(channel), v)

    private val usfStereo = intPreferencesKey("usf_stereo")
    fun getUsfStereoFlow() = flow(usfStereo, Player.DEFAULT_USF_STEREO)
    suspend fun setUsfStereo(v: Int) = set(usfStereo, v)

    private val usfHle = booleanPreferencesKey("usf_hle")
    fun getUsfHleFlow() = flow(usfHle, Player.DEFAULT_USF_HLE)
    suspend fun setUsfHle(v: Boolean) = set(usfHle, v)

    private val psfStereo = intPreferencesKey("psf_stereo")
    fun getPsfStereoFlow() = flow(psfStereo, Player.DEFAULT_PSF_STEREO)
    suspend fun setPsfStereo(v: Int) = set(psfStereo, v)

    private val psfReverb = booleanPreferencesKey("psf_reverb")
    fun getPsfReverbFlow() = flow(psfReverb, Player.DEFAULT_PSF_REVERB)
    suspend fun setPsfReverb(v: Boolean) = set(psfReverb, v)

    private val dspStereo = intPreferencesKey("dsp_stereo")
    fun getDspStereoFlow() = flow(dspStereo, Player.DEFAULT_DSP_STEREO)
    suspend fun setDspStereo(v: Int) = set(dspStereo, v)

    private val audioStereo = intPreferencesKey("audio_stereo")
    fun getAudioStereoFlow() = flow(audioStereo, Player.DEFAULT_AUDIO_STEREO)
    suspend fun setAudioStereo(v: Int) = set(audioStereo, v)

    private val gmeEcho = booleanPreferencesKey("gme_snes_echo")
    fun getGmeEchoFlow() = flow(gmeEcho, Player.DEFAULT_GME_ECHO)
    suspend fun setGmeEcho(v: Boolean) = set(gmeEcho, v)

    private val gmeInterpolation = intPreferencesKey("gme_snes_interpolation")
    fun getGmeInterpolationFlow() =
        flow(gmeInterpolation, Player.DEFAULT_GME_INTERPOLATION)
    suspend fun setGmeInterpolation(v: Int) = set(gmeInterpolation, v)

    private val interpolationType = intPreferencesKey("interpolation_type")
    fun getInterpolationTypeFlow() = flow(interpolationType, Player.DEFAULT_INTERPOLATION)
    suspend fun setInterpolationType(v: Int) = set(interpolationType, v)


    /** How elapsed time is shown in the player: see TimeDisplay. */
    private val timeDisplay = intPreferencesKey("time_display")
    fun getTimeDisplayFlow() = flow(timeDisplay, 0)
    suspend fun setTimeDisplay(v: Int) = set(timeDisplay, v)

    private val globalPitch = intPreferencesKey("global_pitch")
    fun getGlobalPitchFlow() = flow(globalPitch, 0)
    suspend fun setGlobalPitch(v: Int) = set(globalPitch, v)

    /** Playback speed in percent, without the pitch. */
    private val globalTempo = intPreferencesKey("global_tempo")
    fun getGlobalTempoFlow() = flow(globalTempo, 100)
    suspend fun setGlobalTempo(v: Int) = set(globalTempo, v)

    private val controlsExpanded = booleanPreferencesKey("controls_expanded")
    fun getControlsExpandedFlow() = flow(controlsExpanded, true)
    suspend fun setControlsExpanded(v: Boolean) = set(controlsExpanded, v)

    // Channel view as a grid of scope tiles. Stored for the same reason as the
    // controls bar: it is how the listener likes to read the view, and leaving
    // the player is not a change of mind.
    private val scopesOnly = booleanPreferencesKey("scopes_only")
    fun getScopesOnlyFlow() = flow(scopesOnly, false)
    suspend fun setScopesOnly(v: Boolean) = set(scopesOnly, v)

    /* Oboe */
    private val oboeAudioApi = intPreferencesKey("oboe_audio_api")
    fun getOboeAudioApiFlow() = flow(oboeAudioApi, Player.OBOE_AUDIO_API_UNSPECIFIED)
    suspend fun setOboeAudioApi(v: Int) = set(oboeAudioApi, v)

    /* Effects - on everything played; see Player.setReverb and the rest. */
    private val fxReverbOn = booleanPreferencesKey("fx_reverb_on")
    private val fxReverbType = intPreferencesKey("fx_reverb_type")
    private val fxReverbMix = intPreferencesKey("fx_reverb_mix")
    fun getReverbFlow(): Flow<ReverbSettings> = dataStore.data.map {
        ReverbSettings(
            enabled = it[fxReverbOn] ?: false,
            type = it[fxReverbType] ?: Player.DEFAULT_REVERB_TYPE,
            mix = it[fxReverbMix] ?: Player.DEFAULT_REVERB_MIX,
        )
    }.distinctUntilChanged()
    suspend fun setReverbOn(v: Boolean) = set(fxReverbOn, v)
    suspend fun setReverbType(v: Int) = set(fxReverbType, v)
    suspend fun setReverbMix(v: Int) = set(fxReverbMix, v)

    private val fxChorusOn = booleanPreferencesKey("fx_chorus_on")
    private val fxChorusRate = intPreferencesKey("fx_chorus_rate")
    private val fxChorusSpread = intPreferencesKey("fx_chorus_spread")
    private val fxChorusMix = intPreferencesKey("fx_chorus_mix")
    fun getChorusFlow(): Flow<ChorusSettings> = dataStore.data.map {
        ChorusSettings(
            enabled = it[fxChorusOn] ?: false,
            rate = it[fxChorusRate] ?: Player.DEFAULT_CHORUS_RATE,
            spread = it[fxChorusSpread] ?: Player.DEFAULT_CHORUS_SPREAD,
            mix = it[fxChorusMix] ?: Player.DEFAULT_CHORUS_MIX,
        )
    }.distinctUntilChanged()
    suspend fun setChorusOn(v: Boolean) = set(fxChorusOn, v)
    suspend fun setChorusRate(v: Int) = set(fxChorusRate, v)
    suspend fun setChorusSpread(v: Int) = set(fxChorusSpread, v)
    suspend fun setChorusMix(v: Int) = set(fxChorusMix, v)

    private val fxSurroundOn = booleanPreferencesKey("fx_surround_on")
    private val fxSurroundMix = intPreferencesKey("fx_surround_mix")
    fun getSurroundFlow(): Flow<SurroundSettings> = dataStore.data.map {
        SurroundSettings(
            enabled = it[fxSurroundOn] ?: false,
            mix = it[fxSurroundMix] ?: Player.DEFAULT_SURROUND_MIX,
        )
    }.distinctUntilChanged()
    suspend fun setSurroundOn(v: Boolean) = set(fxSurroundOn, v)
    suspend fun setSurroundMix(v: Int) = set(fxSurroundMix, v)

    private val fxEqOn = booleanPreferencesKey("fx_eq_on")
    private fun fxEqBandKey(band: Int) = intPreferencesKey("fx_eq_band_$band")
    fun getEqualizerFlow(): Flow<EqualizerSettings> = dataStore.data.map { prefs ->
        EqualizerSettings(
            enabled = prefs[fxEqOn] ?: false,
            bands = List(Player.EQ_BANDS) { prefs[fxEqBandKey(it)] ?: 0 },
        )
    }.distinctUntilChanged()
    suspend fun setEqOn(v: Boolean) = set(fxEqOn, v)
    suspend fun setEqBand(band: Int, db: Int) = set(fxEqBandKey(band), db)
    /** Every band back to 0 dB, at once. */
    suspend fun resetEqBands() = dataStore.edit { prefs ->
        repeat(Player.EQ_BANDS) { prefs.remove(fxEqBandKey(it)) }
    }

    /* Playback */
    private val autoResume = booleanPreferencesKey("auto_resume")
    fun getAutoResumeFlow() = flow(autoResume, false)
    suspend fun getAutoResume() = get(autoResume, false)
    suspend fun setAutoResume(v: Boolean) = set(autoResume, v)

    /** Sound Check: every track at the same loudness - see Player.setSoundCheck. */
    private val soundCheck = booleanPreferencesKey("sound_check")
    fun getSoundCheckFlow() = flow(soundCheck, DEFAULT_SOUND_CHECK)
    suspend fun setSoundCheck(v: Boolean) = set(soundCheck, v)


    /** Where the queue used to be kept; read once, by a version that finds it there. */
    private val queueJson = stringPreferencesKey("queue_json")
    private val queueIndex = intPreferencesKey("queue_index")
    private val queueShuffle = booleanPreferencesKey("queue_shuffle")
    private val queueShuffleRoot = booleanPreferencesKey("queue_shuffle_root")
    private val queueRepeat = intPreferencesKey("queue_repeat")
    private val queuePositionMs = longPreferencesKey("queue_position_ms")

    // The queue itself - a hundred kilobytes of JSON for a long one - is a
    // file of its own, written when the queue changes and at no other time.
    // It was a key in this store, and this store is one file, written whole
    // on every change to any key in it: the queue went back to disk every
    // five seconds while music played and with every setting anyone moved,
    // and every collector of every setting in the app was handed it again.
    // Where it is first used, off the main thread: finding the app's files
    // directory is a trip to disk.
    private val queueFile by lazy { AtomicFile(File(context.filesDir, "queue.json")) }

    /** Writes the queue itself. Where playback is in it is [saveQueueProgress]. */
    suspend fun saveQueue(json: String) = withContext(Dispatchers.IO) {
        val out = queueFile.startWrite()
        try {
            out.write(json.encodeToByteArray())
            queueFile.finishWrite(out)
        } catch (e: IOException) {
            queueFile.failWrite(out)
            Timber.w(e, "Queue not saved")
        }
    }

    /** Where playback is in the saved queue, and how it goes through it. */
    suspend fun saveQueueProgress(
        index: Int,
        shuffle: Boolean,
        shuffleRoot: Boolean,
        repeat: Int,
        positionMs: Long,
    ) = dataStore.edit {
        // The queue's old place here, emptied once it has a file of its own.
        it.remove(queueJson)
        it[queueIndex] = index
        it[queueShuffle] = shuffle
        it[queueShuffleRoot] = shuffleRoot
        it[queueRepeat] = repeat
        it[queuePositionMs] = positionMs
    }

    private suspend fun readQueue(): String? = withContext(Dispatchers.IO) {
        runCatching { queueFile.readFully().decodeToString() }.getOrNull()
    }

    suspend fun getQueueState(): QueueState? {
        val data = dataStore.data.firstOrNull() ?: return null
        val json = readQueue()?.takeIf { it.isNotBlank() }
            ?: data[queueJson]?.takeIf { it.isNotBlank() }
            ?: return null
        return QueueState(
            json = json,
            index = data[queueIndex] ?: 0,
            shuffle = data[queueShuffle] ?: false,
            shuffleRoot = data[queueShuffleRoot] ?: false,
            repeat = data[queueRepeat] ?: 0,
            positionMs = data[queuePositionMs] ?: 0L,
        )
    }

    suspend fun clearQueueState() = dataStore.edit {
        withContext(Dispatchers.IO) { queueFile.delete() }
        it.remove(queueJson)
        it.remove(queueIndex)
        it.remove(queueShuffle)
        it.remove(queueShuffleRoot)
        it.remove(queueRepeat)
        it.remove(queuePositionMs)
    }
}

/** The glass opacity the app starts with. */
const val DEFAULT_GLASS_OPACITY = 0.4f

/**
 * Sound Check until the setting is changed: on, so a library of every era
 * and format plays at one loudness from the first track.
 */
const val DEFAULT_SOUND_CHECK = true
