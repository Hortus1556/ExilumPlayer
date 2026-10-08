package com.hortus1556.exilum.player

import kotlinx.coroutines.flow.mapLatest
import com.hortus1556.exilum.data.LyricsRepository
import com.hortus1556.exilum.data.Lyrics
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.hortus1556.exilum.data.FavouritesRepository
import com.hortus1556.exilum.data.FileOverrides
import com.hortus1556.exilum.data.FileOverridesRepository
import com.hortus1556.exilum.data.LoopPreset
import com.hortus1556.exilum.data.loopPresetCode
import com.hortus1556.exilum.data.PlaylistRepository
import com.hortus1556.exilum.data.SubsongNamesRepository
import com.hortus1556.exilum.data.WHOLE_MODULE
import com.hortus1556.exilum.db.AppPreferences
import com.hortus1556.exilum.db.entity.ModuleEntity
import com.hortus1556.exilum.db.entity.PlaylistEntity
import com.hortus1556.exilum.renderer.model.AudioStats
import com.hortus1556.exilum.renderer.model.FrameInfo
import com.hortus1556.exilum.renderer.model.ModVars
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI state and domain models for the module player. */

enum class PlaybackStatus { IDLE, LOADING, PLAYING, PAUSED }


/** An octave either way - matches the clamp on the native side. */
const val MAX_PITCH_SEMITONES = 12

/** Playback speed bounds and step, in percent - matches the clamp on the native side. */
const val MIN_TEMPO_PERCENT = 10
const val MAX_TEMPO_PERCENT = 200
const val TEMPO_STEP_PERCENT = 5
const val DEFAULT_TEMPO_PERCENT = 100

@Immutable
data class PlayerUiState(
    val currentModule: ModuleEntity? = null,
    val errorMessage: String? = null,
    val playAllSequences: Boolean = false,
    val queue: ImmutableList<ModuleEntity> = persistentListOf(),
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val controlsExpanded: Boolean = true,
    val scopesOnly: Boolean = false,
    val globalPitch: Int = 0,
    /** Playback speed in percent, pitch unchanged. */
    val globalTempo: Int = DEFAULT_TEMPO_PERCENT,
    val modVars: ModVars = ModVars(),
    val currentSequence: Int = 0,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val currentQueueIndex: Int = 0,
    val isShuffle: Boolean = false,
    /** Shuffle over the whole library, the third shuffle state; only with [isShuffle]. */
    val isShuffleRoot: Boolean = false,
    /** The custom loop playing, or null. */
    val loopRegion: LoopRegion? = null,
    /** The words of the file at [lyricsOf], as last read; see [shownLyrics]. */
    val lyrics: Lyrics? = null,
    val lyricsOf: String? = null,
    /** How elapsed time is shown: see TimeDisplay. */
    val timeDisplay: Int = 0,
    // frameInfo deliberately NOT here — see [PlayerViewModel.frameInfo].
) {
    /**
     * The subsong a favourite or playlist entry made from what is playing
     * saves: the one playing, when the file has more than one - or when this
     * entry was already one subsong - and otherwise the file as a whole.
     */
    val subsongToSave: Int
        get() = when {
            // A saved loop playing is what gets saved.
            loopRegion?.presetId != null -> loopPresetCode(loopRegion.presetId)
            modVars.miNumSequences > 1 || (currentModule?.subsong ?: WHOLE_MODULE) >= 0 -> currentSequence
            else -> WHOLE_MODULE
        }

    /** The words of the file playing, where it has any - not those of the file before it, for the moment a load takes. */
    val shownLyrics: Lyrics?
        get() = lyrics?.takeIf { lyricsOf == currentModule?.filePath }

    /** Whether the entry playing is one saved subsong, which does not walk on into the others. */
    private val pinned: Boolean
        get() = (currentModule?.subsong ?: WHOLE_MODULE) >= 0

    /** True when the skip buttons wrap rather than run out. */
    private val looping: Boolean
        get() = repeatMode == Player.REPEAT_MODE_ALL || repeatMode == Player.REPEAT_MODE_ONE

    /**
     * Whether skip-forward has anywhere to go.
     *
     * The queue is not the whole answer. A file with subsongs is several tunes
     * inside one queue entry, and with Subsongs On the skip buttons walk those
     * before they walk the queue - so the last file in a queue still has a next
     * for as long as it has a subsong left. This has to say the same thing as
     * [com.hortus1556.exilum.player.ModPlayer]'s advanceToNext, or the
     * button sits greyed out over something that would have worked.
     */
    val hasNext: Boolean
        get() = queue.isNotEmpty() && (
            looping ||
                // Shuffle over the library deals more at the end of the queue.
                (isShuffle && isShuffleRoot) ||
                currentQueueIndex < queue.lastIndex ||
                (playAllSequences && !pinned && currentSequence < modVars.miNumSequences - 1)
            )

    /** The same backwards; see [hasNext]. */
    val hasPrev: Boolean
        get() = queue.isNotEmpty() && (
            looping ||
                currentQueueIndex > 0 ||
                (playAllSequences && !pinned && currentSequence > 0)
            )
}

/** Bridges [Player] state to the UI via [PlayerUiState]. */
@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    private val appContext: Context,
    private val player: ModPlayer,
    private val prefs: AppPreferences,
    private val favourites: FavouritesRepository,
    private val playlistRepo: PlaylistRepository,
    private val subsongNamesRepo: SubsongNamesRepository,
    private val overridesRepo: FileOverridesRepository,
    private val lyricsRepo: LyricsRepository,
) : ViewModel() {

    val state: StateFlow<PlayerUiState>
        field = MutableStateFlow(PlayerUiState())

    /**
     * Per-frame playback position/pattern snapshot, updated ~30 times/sec by the
     * native render loop. Deliberately kept out of [state]: folding a field that
     * changes on every tick into the same object every other screen element reads
     * would force the whole player screen (transport controls, chips, top bar...)
     * to recompose on every tick instead of just the handful of views that
     * actually show live position (measured at the root of a real view-lag bug -
     * not an audio/PerformanceMode issue at all).
     */
    val frameInfo: StateFlow<FrameInfo> get() = player.frameInfoFlow

    /**
     * The listener's channel mutes, bit n for channel n - one copy for every
     * view that shows them. Kept out of [state] like [frameInfo], so a mute
     * redraws the views that show it rather than recomposing the whole screen.
     */
    val channelMutes: StateFlow<Long> get() = player.channelMutesFlow

    /**
     * Whether the module now playing is starred. Declared before [init] on
     * purpose: viewModelScope runs on Main.immediate, so the collector below
     * writes to this backing field synchronously during construction.
     */
    val isFavourite: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** Every playlist, for adding what is playing to one. */
    val playlists: StateFlow<ImmutableList<PlaylistEntity>>
        field = MutableStateFlow<ImmutableList<PlaylistEntity>>(persistentListOf())

    /** Names given to subsongs, keyed by subsongKey - see SubsongNamesRepository. */
    val subsongNames: StateFlow<Map<String, String>>
        field = MutableStateFlow(emptyMap())

    /** The playing file's own overrides - see the overrides sheet. */
    val fileOverrides: StateFlow<FileOverrides> get() = player.fileOverridesFlow

    init {
        prefs.getControlsExpandedFlow().onEach { expanded ->
            state.update { it.copy(controlsExpanded = expanded) }
        }.launchIn(viewModelScope)

        prefs.getScopesOnlyFlow().onEach { tiles ->
            state.update { it.copy(scopesOnly = tiles) }
        }.launchIn(viewModelScope)

        // The stored modes, which ModPlayer writes on every change however it
        // was made - a button here, the browser, the notification, Android Auto.
        prefs.getGlobalShuffleFlow().onEach { shuffle ->
            state.update { it.copy(isShuffle = shuffle) }
        }.launchIn(viewModelScope)

        prefs.getGlobalShuffleRootFlow().onEach { root ->
            state.update { it.copy(isShuffleRoot = root) }
        }.launchIn(viewModelScope)

        prefs.getGlobalRepeatFlow().onEach { mode ->
            state.update { it.copy(repeatMode = mode) }
        }.launchIn(viewModelScope)

        prefs.getGlobalPitchFlow().onEach { semitones ->
            state.update { it.copy(globalPitch = semitones) }
        }.launchIn(viewModelScope)

        prefs.getGlobalTempoFlow().onEach { percent ->
            state.update { it.copy(globalTempo = percent) }
        }.launchIn(viewModelScope)

        player.modVarsFlow.onEach { mv ->
            state.update { it.copy(modVars = mv) }
        }.launchIn(viewModelScope)

        player.currentSequenceFlow.onEach { seq ->
            state.update { it.copy(currentSequence = seq) }
        }.launchIn(viewModelScope)

        // Combined with moduleLoadedFlow rather than collected on its own.
        // [play]/[playAll] set LOADING optimistically and rely on this to clear
        // it, but isPlaying is a conflated StateFlow: a track change flips it
        // false->true within ~10ms on the loader thread (softStop() then
        // start()), and this collector - already sitting at true from the
        // previous track - never sees that as a change, so it never emits.
        // LOADING then stuck permanently, which broke the play/pause button
        // (it compares against PLAYING) and blanked ChannelView/DebugView
        // (their fetch loops are keyed on it). moduleLoadedFlow is a monotonic
        // counter bumped after start(), so it always emits and always at a
        // point where isPlaying has settled.
        combine(player.isPlaying, player.moduleLoadedFlow) { playing, _ -> playing }
            .onEach { playing ->
                state.update {
                    it.copy(
                        status = when {
                            playing -> PlaybackStatus.PLAYING
                            it.currentModule != null -> PlaybackStatus.PAUSED
                            else -> PlaybackStatus.IDLE
                        }
                    )
                }
            }.launchIn(viewModelScope)

        player.queueFlow.onEach { queue ->
            val empty = queue.isEmpty()
            // The entry playing as well as the list. It was left to the index
            // collector below alone, and an index is not news when it is the
            // same number as before: a queue restored at the start with its
            // first entry current - or any queue, with shuffle on, which
            // always starts at 0 - played with nothing here to show for it,
            // no mini player and no way into the player screen; and a queue
            // loaded from a car over one already here left the old file as
            // the one the star and the overrides were written to.
            val index = player.currentIndexFlow.value
            val current = queue.getOrNull(index)
            state.update {
                it.copy(
                    queue = queue.toImmutableList(),
                    currentModule = if (empty) null else current ?: it.currentModule,
                    currentQueueIndex = if (current != null) index else it.currentQueueIndex,
                    status = if (empty) PlaybackStatus.IDLE else it.status,
                )
            }
        }.launchIn(viewModelScope)

        player.currentIndexFlow.onEach { index ->
            val file = player.queueFlow.value.getOrNull(index) ?: return@onEach
            state.update {
                it.copy(
                    currentModule = file,
                    currentQueueIndex = index,
                    currentSequence = if (player.isReordering) it.currentSequence else 0,
                )
            }
        }.launchIn(viewModelScope)

        // The subsong the engine is on, read once the load is over. This used
        // to write 0, after the loader had already selected the subsong of an
        // entry saved as one: the engine played subsong 3 of a favourite
        // while this said 0 for the whole track - so the star showed empty,
        // tapping it starred the first subsong instead, and a length or a
        // loop set by hand was filed under the wrong one.
        player.moduleLoadedFlow.onEach {
            state.update { it.copy(currentSequence = player.currentSequenceFlow.value) }
        }.launchIn(viewModelScope)

        // The words of what has just loaded: its own, from its tags, or a
        // .lrc's beside it. Asked once a load, of the file loaded.
        player.moduleLoadedFlow
            .mapLatest {
                val module = state.value.currentModule ?: return@mapLatest null
                module.filePath to lyricsRepo.ofPlaying(module)
            }
            .onEach { read -> state.update { it.copy(lyrics = read?.second, lyricsOf = read?.first) } }
            .launchIn(viewModelScope)

        prefs.getGlobalSubSongsFlow().onEach { subsongs ->
            state.update { it.copy(playAllSequences = subsongs) }
        }.launchIn(viewModelScope)

        // Re-subscribed whenever the track changes, since "is this starred"
        // is a question about one particular module, not a global flag.
        // The subsong is part of the question: on a file with several, the star
        // is for the one playing.
        state
            .map { s -> s.currentModule?.filePath?.let { it to s.subsongToSave } }
            .distinctUntilChanged()
            .flatMapLatest { key ->
                if (key == null) flowOf(false) else favourites.isFavourite(key.first, key.second)
            }
            .onEach { isFavourite.value = it }
            .launchIn(viewModelScope)

        playlistRepo.playlists
            .onEach { playlists.value = it.toImmutableList() }
            .launchIn(viewModelScope)

        subsongNamesRepo.names
            .onEach { subsongNames.value = it }
            .launchIn(viewModelScope)

        player.loopRegionFlow
            .onEach { region -> state.update { it.copy(loopRegion = region) } }
            .launchIn(viewModelScope)

        prefs.getTimeDisplayFlow()
            .onEach { mode -> state.update { it.copy(timeDisplay = mode) } }
            .launchIn(viewModelScope)
    }

    /** Returns a formatted string of current Oboe audio stream statistics. */
    fun getAudioStats(): String {
        val stats = AudioStats()
        player.getAudioStats(stats)
        return """
            Audio API: ${stats.audioApi}
            Audio Format: ${stats.audioFormat}
            Audio Glitches: ${stats.xrunCount} (system), ${stats.underrunCount} (app)
            Buffer: ${stats.bufferSize} / ${stats.bufferCapacity} frames
            Frames Per Burst: ${stats.framesPerBurst}
            Performance Mode: ${stats.perfMode}
            Sample Rate: ${stats.sampleRate} Hz
            Sharing Mode: ${stats.sharingMode}
        """.trimIndent()
    }

    /** Loads [file] as a single-item queue and starts playback. */
    fun play(file: ModuleEntity) {
        state.update { it.copy(status = PlaybackStatus.LOADING, currentModule = file) }
        ensureServiceRunning()
        player.loadQueue(listOf(file), startAt = 0)
    }

    /**
     * Loads [files] as a queue, optionally shuffled, and starts playback at [startAt].
     *
     * [keepRoot] false for a collection shuffling itself - see ModPlayer.loadQueue.
     */
    fun playAll(
        files: ImmutableList<ModuleEntity>,
        startAt: Int,
        isShuffle: Boolean = state.value.isShuffle,
        repeatMode: Int = state.value.repeatMode,
        keepRoot: Boolean = true,
    ) {
        if (files.isEmpty()) return
        val startIndex = startAt.coerceIn(0, files.lastIndex)

        state.update {
            it.copy(
                status = PlaybackStatus.LOADING,
                currentModule = files[startIndex],
                isShuffle = isShuffle,
                repeatMode = repeatMode,
            )
        }
        ensureServiceRunning()
        player.loadQueue(files, startIndex, isShuffle, repeatMode, keepRoot)
    }

    /** Stars or unstars whatever is playing - the subsong playing, on a file with several. */
    fun toggleFavourite() {
        val current = state.value
        val module = current.currentModule ?: return
        val subsong = current.subsongToSave
        viewModelScope.launch {
            if (favourites.toggle(module, subsong)) rememberSubsongName(current, module, subsong)
        }
    }

    /** Adds what is playing to [playlist], as the subsong playing on a file with several. */
    fun addToPlaylist(playlist: PlaylistEntity) {
        val current = state.value
        val module = current.currentModule ?: return
        val subsong = current.subsongToSave
        viewModelScope.launch {
            playlistRepo.addToPlaylist(playlist.id, module.copy(subsong = subsong))
            rememberSubsongName(current, module, subsong)
        }
    }

    /** Changes the playing file's overrides. */
    private fun updateOverrides(transform: (FileOverrides) -> FileOverrides) {
        val uri = state.value.currentModule?.filePath ?: return
        viewModelScope.launch { overridesRepo.update(uri, transform) }
    }

    /** The playing file's own value for setting [key]; null goes back to the settings screen's. */
    fun setOverride(key: String, value: Int?) = updateOverrides {
        it.copy(settings = if (value == null) it.settings - key else it.settings + (key to value))
    }

    /** A length for the subsong playing, for a file that cannot say; null takes it away. */
    fun setDurationOverride(ms: Int?) {
        val sequence = state.value.currentSequence
        updateOverrides {
            it.copy(durations = if (ms == null || ms <= 0) it.durations - sequence else it.durations + (sequence to ms))
        }
    }

    fun setTimeDisplay(mode: Int) {
        viewModelScope.launch { prefs.setTimeDisplay(mode) }
    }

    /** Loops [startMs] to [endMs] of what is playing, unsaved, from the start of it. */
    fun tryLoop(startMs: Int, endMs: Int) {
        if (endMs <= startMs) return
        player.startLoop(LoopRegion(startMs, endMs))
    }

    /** Stops looping, leaving playback where it is. */
    fun stopLoop() = player.startLoop(null)

    /** Saves [startMs] to [endMs] as a loop preset of the file playing, and plays it. */
    fun saveLoopPreset(startMs: Int, endMs: Int) {
        if (endMs <= startMs) return
        val current = state.value
        val uri = current.currentModule?.filePath ?: return
        val subsong = if (current.modVars.miNumSequences > 1) current.currentSequence else WHOLE_MODULE
        viewModelScope.launch {
            var saved: LoopPreset? = null
            overridesRepo.update(uri) { own ->
                val preset = LoopPreset(own.nextLoopId, subsong, startMs, endMs)
                saved = preset
                own.copy(loops = own.loops + preset, nextLoopId = own.nextLoopId + 1)
            }
            saved?.let { player.startLoop(LoopRegion(it.startMs, it.endMs, it.id)) }
        }
    }

    /** Plays [preset] round and round, in its own subsong. */
    fun playLoopPreset(preset: LoopPreset) {
        val region = LoopRegion(preset.startMs, preset.endMs, preset.id)
        if (preset.subsong >= 0 && preset.subsong != state.value.currentSequence) {
            // The loop once its subsong is the one playing, not before: the
            // switch is carried out off this thread, and clears whatever loop
            // is set as it lands.
            player.setSequence(preset.subsong) { player.startLoop(region) }
        } else {
            player.startLoop(region)
        }
    }

    /**
     * Deletes [preset], with its name and wherever it was starred or put in a
     * playlist - a saved entry for a loop that no longer exists would play
     * something else.
     */
    fun deleteLoopPreset(preset: LoopPreset) {
        val uri = state.value.currentModule?.filePath ?: return
        val code = loopPresetCode(preset.id)
        if (state.value.loopRegion?.presetId == preset.id) player.startLoop(null)
        viewModelScope.launch {
            overridesRepo.update(uri) { own -> own.copy(loops = own.loops.filterNot { it.id == preset.id }) }
            subsongNamesRepo.rename(uri, code, "")
            favourites.remove(uri, code)
            playlistRepo.removeEverywhere(uri, code)
        }
    }

    /** Names subsong [index] of the file playing; blank takes the name away. */
    fun renameSubsong(index: Int, name: String) {
        val module = state.value.currentModule ?: return
        viewModelScope.launch { subsongNamesRepo.rename(module.filePath, index, name) }
    }

    /** Keeps the format's own name for a saved subsong, so lists can show it unloaded. */
    private suspend fun rememberSubsongName(state: PlayerUiState, module: ModuleEntity, subsong: Int) {
        if (subsong < 0) return
        val name = state.modVars.seqData.getOrNull(subsong)?.name.orEmpty()
        subsongNamesRepo.rememberIfMissing(module.filePath, subsong, name)
    }

    fun togglePlayPause() =
        if (state.value.status == PlaybackStatus.PLAYING) player.pause() else player.play()

    fun seek(posMs: Long) = player.seekTo(player.currentMediaItemIndex, posMs)

    fun next() = player.next()

    fun previous() = player.previous()

    /**
     * The Stop button: ends the queue and closes the player. Not Media3's
     * stop(), which is what a headset or a car sends and keeps the queue -
     * see ModPlayer.handleStop.
     */
    fun stop() = player.stopAndClearQueue()

    fun playAtIndex(index: Int) = player.jumpToIndex(index)

    /**
     * Off, on, then shuffle over the whole library, then off again.
     *
     * Stored here as well as by ModPlayer: Media3 drops the on/off call on its
     * way there when the command is not currently available, and the setting
     * should not be lost with it.
     */
    fun toggleShuffle() {
        val current = state.value
        when {
            !current.isShuffle -> {
                player.shuffleModeEnabled = true
                state.update { it.copy(isShuffle = true, isShuffleRoot = false) }
                viewModelScope.launch {
                    prefs.setGlobalShuffle(true)
                    prefs.setGlobalShuffleRoot(false)
                }
            }

            !current.isShuffleRoot -> {
                player.setShuffleRoot(true)
                state.update { it.copy(isShuffleRoot = true) }
                viewModelScope.launch { prefs.setGlobalShuffleRoot(true) }
            }

            else -> {
                player.setShuffleRoot(false)
                // For when the player was not over the library after all - a
                // stored setting with nothing loaded - and still has shuffle on.
                player.shuffleModeEnabled = false
                state.update { it.copy(isShuffle = false, isShuffleRoot = false) }
                viewModelScope.launch {
                    prefs.setGlobalShuffle(false)
                    prefs.setGlobalShuffleRoot(false)
                }
            }
        }
    }

    fun toggleLoop() {
        val newMode = when (state.value.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        player.setRepeatMode(newMode)
        state.update { it.copy(repeatMode = newMode) }
        viewModelScope.launch { prefs.setGlobalRepeat(newMode) }
    }

    fun setSequence(index: Int) = player.setSequence(index)

    /** Sound Check's state, for the debug view. */
    fun soundCheckStatus(): SoundCheckStatus = player.soundCheckStatus()

    fun toggleMute(channel: Int) = player.toggleMute(channel)

    fun soloChannel(channel: Int, numChannels: Int) = player.soloChannel(channel, numChannels)

    fun toggleAllSequences() {
        val playAllSequences = !state.value.playAllSequences
        player.setPlayAllSequences(playAllSequences)
        viewModelScope.launch { prefs.setGlobalSubSongs(playAllSequences) }
        state.update { it.copy(playAllSequences = playAllSequences) }
    }

    /**
     * Shifts playback by [semitones], an octave either way.
     *
     * Stored rather than only sent to the engine, so it survives a restart and
     * so the chip can show it - and clamped here as well as natively, because
     * what is stored is what the UI reads back.
     */
    fun setGlobalPitch(semitones: Int) {
        val clamped = semitones.coerceIn(-MAX_PITCH_SEMITONES, MAX_PITCH_SEMITONES)
        if (clamped == state.value.globalPitch) return
        player.setGlobalPitch(clamped)
        viewModelScope.launch { prefs.setGlobalPitch(clamped) }
    }

    /** Sets the playback speed in percent, stored like [setGlobalPitch]. */
    fun setGlobalTempo(percent: Int) {
        val clamped = percent.coerceIn(MIN_TEMPO_PERCENT, MAX_TEMPO_PERCENT)
        if (clamped == state.value.globalTempo) return
        player.setGlobalTempo(clamped)
        viewModelScope.launch { prefs.setGlobalTempo(clamped) }
    }

    /** Shows or hides the chips and transport row under the playback bar. */
    fun setControlsExpanded(expanded: Boolean) {
        if (expanded == state.value.controlsExpanded) return
        viewModelScope.launch { prefs.setControlsExpanded(expanded) }
    }

    /** Shows channel view as a grid of scope tiles, or as rows. Kept across visits. */
    fun setScopesOnly(tiles: Boolean) {
        if (tiles == state.value.scopesOnly) return
        state.update { it.copy(scopesOnly = tiles) }
        viewModelScope.launch { prefs.setScopesOnly(tiles) }
    }

    fun getPatternData(patternIndex: Int) = player.getPatternData(patternIndex)

    suspend fun getLastDirectoryUri(): String? = prefs.getLastDirectoryUri()

    private fun ensureServiceRunning() {
        appContext.startService(Intent(appContext, PlayerService::class.java))
    }
}
