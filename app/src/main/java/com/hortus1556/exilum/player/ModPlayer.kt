package com.hortus1556.exilum.player

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.hortus1556.exilum.R
import com.hortus1556.exilum.data.ModuleRepository
import com.hortus1556.exilum.data.isLoopPreset
import com.hortus1556.exilum.data.loopPresetId
import com.hortus1556.exilum.db.AppPreferences
import com.hortus1556.exilum.db.entity.ModuleEntity
import com.hortus1556.exilum.renderer.model.AudioStats
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Media3 [SimpleBasePlayer] backed by [PlayerEngine].
 */
@OptIn(UnstableApi::class)
class ModPlayer(
    private val context: Context,
    private val engine: PlayerEngine,
    private val prefs: AppPreferences,
    private val modules: ModuleRepository,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    /** Shuffle and repeat settings that persist across queue reloads. */
    private var repeatMode = REPEAT_MODE_OFF
    private var shuffleMode = false

    /**
     * Shuffle over the whole library - the third state of the shuffle button,
     * after on. Implies [shuffleMode].
     *
     * The queue is not the library: that can be tens of thousands of files,
     * and the queue is also the Media3 timeline, rebuilt on every state update
     * and sent to the notification. It is a window of [ROOT_WINDOW] picks drawn
     * from [rootBag], dealt again when playback reaches its end - which is what
     * makes it endless, whatever the repeat mode says.
     */
    private var shuffleRoot = false

    /** Every playable module under the root folder, as of the last deal that read it. */
    private var rootPool: List<ModuleEntity> = emptyList()
    private var rootPoolPath: String? = null

    /**
     * The pool shuffled, dealt from the front. Refilled with a fresh shuffle
     * only once it is empty, so every file comes round once before any comes
     * round twice.
     */
    private val rootBag = ArrayDeque<ModuleEntity>()

    /** The queue [shuffleRoot] took over, and where playback was in it - for turning it off. */
    private var queueBeforeRoot: List<ModuleEntity> = emptyList()
    private var indexBeforeRoot = 0

    private var rootJob: Job? = null

    /** Current playback order; rebuilt by [applyQueueOrder]. */
    private val queue = mutableListOf<ModuleEntity>()

    /** Original (pre-shuffle) order, used to restore sequential play and persist state. */
    private val originalQueue = mutableListOf<ModuleEntity>()

    /** Mirror of [queue] as [MediaItem]s for [getState]; the current item has real metadata. */
    private val playlist = mutableListOf<MediaItem>()

    private var currentIndex = 0

    /** +1 when navigating forward, -1 when navigating backward. Used by [skipUnplayable]. */
    private var lastNavDirection = 1

    /** Counts consecutive skip attempts so we don't loop forever on an unplayable queue. */
    private var skipAttempts = 0

    /** True while [loadAndStartAt] is running a background [Thread]. */
    @Volatile
    private var isLoading = false

    /**
     * Counts the loads asked for: bumped by every [loadAndStartAt], whether
     * or not it could start, and by whatever empties or stops the queue. A
     * load that finds it moved on since it began has been overtaken, and
     * makes way instead of starting its track.
     */
    private val loadGeneration = AtomicInteger()

    /**
     * Where the load that overtook the one running asked to pick up, kept for
     * when that one has got out of its way. Main thread only.
     *
     * Whether it plays is not kept beside it: that is
     * [PlayerEngine.pauseRequested], which a pause or a Play made during the
     * wait goes on changing - see [loadAndStartAt].
     */
    private var overtakingResume: ResumePoint? = null

    /**
     * Where a track that is loaded again picks back up: after the audio
     * device changed under it, or from the saved queue. [subsong] below zero
     * and [loop] null for a position alone.
     */
    private class ResumePoint(val positionMs: Long, val subsong: Int = -1, val loop: LoopRegion? = null)

    /**
     * True after a stop that came from outside the app - a headset's stop
     * key, a car, the notification swiped away: the engine has let the module
     * go and the queue is still here, for Play to start again. See [handleStop].
     */
    private var stopped = false

    /**
     * Whether the queue ran out or was closed since [attach]: what tells a
     * queue that ended, whose saved state goes, from one that was never
     * loaded, whose saved state is the last session's and stays.
     */
    private var queueEnded = false

    /** True when the user explicitly paused; prevents audio-focus regain from auto-resuming. */
    @Volatile
    private var userPaused = false

    /**
     * Desired seek target while a seek is in-flight. Held until the engine
     * position converges, then reset to -1. Guards against position flicker.
     */
    @Volatile
    private var pendingSeekPositionMs = -1L

    /**
     * True while shuffle is being toggled. Prevents [currentIndexFlow] from
     * triggering a metadata reload mid-reorder.
     */
    @Volatile
    var isReordering = false

    /** Per-frame snapshots from the render thread. */
    val frameInfoFlow by engine::frameInfoFlow

    /** True while the engine render loop is running. */
    val isPlaying by engine::isPlaying

    /** Current multi-sequence index, emitted on sequence change. */
    val currentSequenceFlow by engine::currentSequenceFlow

    /** Module metadata, emitted once after each successful load. */
    val modVarsFlow by engine::modVarsFlow

    /** The listener's channel mutes, bit n for channel n. See [PlayerEngine.channelMutes]. */
    val channelMutesFlow by engine::channelMutes

    /** Index of the currently playing item in [queue]; emitted on navigation. */
    val currentIndexFlow: StateFlow<Int>
        field = MutableStateFlow(0)

    /** Snapshot of the current playback queue; emitted when the queue changes. */
    val queueFlow: StateFlow<List<ModuleEntity>>
        field = MutableStateFlow(emptyList())

    /** Incremented each time a new module finishes loading; triggers metadata sync. */
    val moduleLoadedFlow: StateFlow<Int>
        field = MutableStateFlow(0)

    private val audioFocus = AudioFocusHandler(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    /**
     * Scope for the collectors that bridge the engine to the queue.
     *
     * A var, and rebuilt by [attach], because this class is a process-lifetime
     * singleton while the service that owns the engine is not. [releaseEngine]
     * cancels it, and a cancelled scope stays cancelled - every later launch on
     * it hands back an already-cancelled job that runs nothing at all.
     */
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Whether [attach] has run since the last [releaseEngine]. */
    private var attached = false
    private var positionUpdateJob: Job? = null

    /**
     * Pauses playback when headphones/Bluetooth are disconnected, matching how
     * every mainstream media app behaves — never keep blasting audio out of the
     * speaker because the wired/BT output disappeared mid-track.
     */
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            // Whatever is playing at this instant, or is not. The dying stream
            // often gets here first: the engine has already stopped on it, and
            // returning on "not playing" left nothing to say the listener had
            // just pulled their headphones out - so the recovery that reopens
            // the stream started the track again on the speaker. The same for
            // a track paused for a phone call, which must not come back on the
            // speaker when the call ends.
            userPaused = true
            // A track still on its way in is paused too: it comes up held.
            if (!engine.isPlaying.value && !isLoading) return
            Timber.i("ACTION_AUDIO_BECOMING_NOISY — pausing playback")
            engine.pause()
            invalidateState()
        }
    }

    init {
        attach()
    }

    /**
     * (Re)establishes everything a live service needs; a no-op when it is
     * already established.
     *
     * Called from [PlayerService.onCreate], not only from the constructor.
     * Android destroys the service whenever it decides playback is over - the
     * task swiped away while paused, a system reclaim - which runs
     * [releaseEngine], but Koin then hands the next service the same instance.
     * Without this, that instance came back with a cancelled scope and an
     * unregistered receiver, so for the rest of the process there was no
     * auto-advance at the end of a track, no position updates, no queue
     * persistence and no audio-disconnect recovery. The symptom was playback
     * that stopped between tracks and only came back by restarting the app.
     */
    fun attach() {
        if (attached) return
        attached = true
        queueEnded = false
        if (!scope.isActive) scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        ContextCompat.registerReceiver(
            context,
            becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        scope.launch {
            prefs.getGlobalSubSongsFlow().collect { value ->
                engine.playAllSequences = value
            }
        }

        scope.launch {
            prefs.getGlobalTempoFlow().collect { percent ->
                tempoPercent = percent
                invalidateState()
            }
        }

        // The stored shuffle and repeat, for whatever gets queued first - a
        // media button, Android Auto - before any screen has said otherwise.
        // Not over a queue already loaded, which brought its own.
        scope.launch {
            val repeat = prefs.getGlobalRepeatFlow().first()
            val shuffle = prefs.getGlobalShuffleFlow().first()
            val root = prefs.getGlobalShuffleRootFlow().first()
            if (originalQueue.isEmpty()) {
                repeatMode = repeat
                shuffleMode = shuffle
                shuffleRoot = shuffle && root
                isRepeatingOne(repeatMode == REPEAT_MODE_ONE)
                invalidateState()
            }
        }

        // Combined with moduleLoadedFlow, not collected on its own: see the
        // counter's doc in loadEnded. On its own this would miss a track
        // change entirely (conflated false->true), which here would mean
        // position updates stopped at softStop() and never restarted.
        scope.launch {
            combine(engine.isPlaying, moduleLoadedFlow) { playing, _ -> playing }
                .collect { playing ->
                    Timber.i(
                        "isPlaying collector: playing=$playing " +
                            "endedNaturally=${engine.endedNaturally} idx=$currentIndex/${queue.size}"
                    )
                    invalidateState()
                    if (playing) startPositionUpdates() else stopPositionUpdates()
                }
        }

        // Dedicated event for queue auto-advance, instead of piggybacking on the
        // isPlaying StateFlow's true->false transition: StateFlow conflates equal
        // consecutive values, so a racing stop()/pause() clearing endedNaturally
        // right as the render loop also finishes could previously suppress the
        // advance entirely, leaving playback stuck with a dead Play button.
        scope.launch {
            engine.moduleEndedFlow.collect {
                mainHandler.post { advanceToNext() }
            }
        }

        // The disconnects already dealt with. The flow hands a new collector
        // the count as it stands, and this is collected again each time the
        // service comes back: after one disconnect in the life of the process
        // every later attach found a count above zero and "recovered" from
        // it - tore the engine down and started the current track from the
        // top, whether or not anything was meant to be playing.
        val handled = engine.audioDisconnectedFlow.value
        scope.launch {
            engine.audioDisconnectedFlow.collect { generation ->
                if (generation <= handled) return@collect
                Timber.w("Audio device disconnected — reopening stream and reloading current track")
                // Where it was, in full: the reload opens the file as it comes,
                // which is its first subsong with no loop set. Of no track at
                // all while another is on its way in: what the engine says
                // then is partly the last track's and partly the next one's.
                val track = queue.getOrNull(currentIndex).takeUnless { isLoading }
                val resume = ResumePoint(
                    positionMs = engine.positionMs.value,
                    subsong = engine.currentSequenceFlow.value,
                    loop = engine.loopRegion.value,
                )
                stopPositionUpdates()
                invalidateState()
                Thread {
                    engine.handleAudioDisconnect()
                    mainHandler.post {
                        // Nothing to bring back - or it is back already: a
                        // track picked while the stream was being taken down
                        // opens a new one as it loads, and needs no second
                        // load on top of its own.
                        if (queue.isEmpty() || stopped || engine.initialized) return@post
                        // The stream is recovered so that playing on works,
                        // not to start anything: a track that was paused - by
                        // the listener, by a call, or by the headphones coming
                        // out, which is very often what the disconnect was -
                        // comes back paused. This used to start it regardless,
                        // out of the phone's speaker. Read here, after the
                        // teardown, so a Play or a pause made meanwhile counts.
                        loadAndStartAt(
                            index = currentIndex,
                            // Only for the track it was taken from.
                            resume = resume.takeIf { queue.getOrNull(currentIndex) === track },
                            autoStart = !(engine.pauseRequested || userPaused),
                        )
                    }
                }.start()
            }
        }
    }

    /** Enables or disables single-track repeat in the engine. */
    fun isRepeatingOne(value: Boolean) = engine.isRepeatingOne(value)

    /** A subsong's length for the media session: a length set by hand for it, or its own. */
    private fun durationOf(sequence: Int): Int =
        engine.fileOverrides.value.durations[sequence]
            ?: engine.modVarsFlow.value.sequenceDuration(sequence)

    /** Shifts playback by [semitones] without changing the tempo. */
    fun setGlobalPitch(semitones: Int) = engine.setGlobalPitch(semitones)

    /** Plays at [percent] speed without changing the pitch. */
    fun setGlobalTempo(percent: Int) = engine.setGlobalTempo(percent)

    /** The loaded file's own overrides. */
    val fileOverridesFlow by engine::fileOverrides

    /** The custom loop playing, or null. */
    val loopRegionFlow by engine::loopRegion

    /** Plays [region] round and round from its start; null stops looping where playback is. */
    fun startLoop(region: LoopRegion?) = engine.startLoop(region)

    /**
     * The playback speed, as the media session reports it. A controller - the
     * notification, a car - moves its position along between updates at this
     * rate, and at one it would drift from what is heard at any other tempo.
     */
    private var tempoPercent = 100

    /**
     * Switches to sequence [index], off the caller's thread, and runs [then]
     * once it has - see [PlayerEngine.setSequenceAsync].
     */
    fun setSequence(index: Int, then: (() -> Unit)? = null) = engine.setSequenceAsync(index, then)

    /** Mutes or unmutes one channel. */
    fun toggleMute(channel: Int) = engine.toggleMute(channel)

    /** Solos one channel of [numChannels], or lifts the solo. */
    fun soloChannel(channel: Int, numChannels: Int) = engine.soloChannel(channel, numChannels)

    /** Enables or disables automatic playback of all module sequences. */
    fun setPlayAllSequences(value: Boolean) {
        engine.playAllSequences = value
    }

    /**
     * Loads [files] into the queue and starts playback at [startAt].
     *
     * With shuffle over the library on, [files] only chooses the first track:
     * the rest is dealt from the root, and [files] is kept for when it is
     * turned off. [keepRoot] false is for a queue that asked to be shuffled
     * in itself - a playlist or the favourites shuffle button - which turns
     * it back to plain shuffle; so does asking for no shuffle at all.
     */
    fun loadQueue(
        files: List<ModuleEntity>,
        startAt: Int,
        shuffleMode: Boolean = this.shuffleMode,
        repeatMode: Int = this.repeatMode,
        keepRoot: Boolean = true,
    ) {
        if (files.isEmpty()) return
        val root = shuffleRoot && shuffleMode && keepRoot
        if (!root) {
            rootJob?.cancel()
            shuffleRoot = false
            queueBeforeRoot = emptyList()
        }
        this.shuffleMode = shuffleMode
        this.repeatMode = repeatMode
        isRepeatingOne(repeatMode == REPEAT_MODE_ONE)
        persistModes()
        userPaused = false
        queueEnded = false
        // Another queue's failures are not this one's - see skipUnplayable.
        skipAttempts = 0

        requestAudioFocus()

        originalQueue.clear()
        if (root) {
            val start = startAt.coerceIn(0, files.lastIndex)
            queueBeforeRoot = files.toList()
            indexBeforeRoot = start
            // The chosen track on its own for now, so it starts at once; the
            // picks behind it follow when the library has been read.
            originalQueue.add(files[start])
            applyQueueOrder(startAt = 0)
            loadAndStartAt(currentIndex)
            persistQueue()
            dealRootWindow()
            return
        }
        originalQueue.addAll(files)
        applyQueueOrder(startAt)
        loadAndStartAt(currentIndex)
        persistQueue()
    }

    /**
     * Turns shuffle over the whole library on or off.
     *
     * On keeps the current track and deals the queue behind it from the root.
     * Off also turns shuffle off, being the step after it in the button's
     * cycle, and puts back the queue it took over.
     */
    fun setShuffleRoot(enabled: Boolean) {
        if (enabled == shuffleRoot) return
        if (!enabled) {
            leaveShuffleRoot()
            return
        }
        shuffleMode = true
        shuffleRoot = true
        persistModes()
        if (queue.isEmpty()) {
            invalidateState()
            return
        }
        queueBeforeRoot = originalQueue.toList()
        indexBeforeRoot = currentOriginalIndex()
        invalidateState()
        dealRootWindow()
    }

    /** Advances to the next item (or loops/stops depending on repeat mode). */
    fun next() = advanceToNext(skip = true)

    /**
     * Goes to the previous item, or seeks to the start of the current track
     * if more than 3 seconds have elapsed.
     */
    fun previous() {
        if (engine.positionMs.value > 3_000L) {
            pendingSeekPositionMs = 0L
            engine.seek(0)
            invalidateState()
            clearPendingSeekAfterDelay()
        } else {
            advanceToPrevious(skip = true)
        }
    }

    /** Jumps directly to [index] in the current queue. */
    fun jumpToIndex(index: Int) {
        if (index !in queue.indices) return
        lastNavDirection = if (index >= currentIndex) 1 else -1
        navigate(index)
    }

    /** Requests / re-requests audio focus from the system. */
    fun requestAudioFocus() = audioFocus.request(
        onGain = {
            if (!userPaused) {
                when {
                    engine.paused -> engine.resume()

                    // Delayed-focus case: engine loaded and waiting to start (not paused, not playing).
                    // Not while a track is still loading, which looks the same
                    // from here: its loader starts it, and a second start()
                    // beside that one ran on a module only half set up.
                    !isLoading && !stopped &&
                        engine.initialized && !engine.isPlaying.value && !engine.endedNaturally -> {
                        engine.pauseRequested = false
                        Thread { engine.start() }.start()
                    }
                }
            }
        },
        onDuck = { engine.duck() },
        onEndDuck = { engine.restoreVolume() },
        // Non-duckable transient loss (e.g. a phone call): pause but stay resumable —
        // do NOT set userPaused, so onGain above will restart playback automatically.
        onPause = { engine.pause() },
        // Permanent loss (another app took over playback): pause and require the user
        // to explicitly resume, same as if they'd paused it themselves.
        onPermanentLoss = {
            userPaused = true
            engine.pause()
        },
    )

    /** Releases audio focus. Called from [PlayerService.onDestroy]. */
    fun abandonAudioFocus() = audioFocus.abandon()

    /** Returns cached pattern data for the pattern view. */
    fun getPatternData(patternIndex: Int) = engine.getPatternData(patternIndex)

    /** Fills [stats] with current Oboe stream statistics. */
    fun getAudioStats(stats: AudioStats) = engine.getAudioStats(stats)

    fun soundCheckStatus(): SoundCheckStatus = engine.soundCheckStatus()

    /**
     * Flushes the queue state synchronously, cancels the coroutine scope, and
     * stops the engine. Called from [PlayerService.onDestroy].
     */
    fun releaseEngine() {
        if (!attached) return
        attached = false
        // Both halves of what persistQueue decides, not just the saving half.
        // Clearing it was a coroutine on the scope cancelled two lines down, so
        // a queue that ran out and then had the service torn down left its old
        // state on disk - and the next time the service came up, restoreQueue
        // found it and reloaded the file that had just finished, on top of an
        // engine still being released on another thread.
        //
        // Blocking, and left so: this is the last chance to write where a
        // paused queue was, and the process is often gone a moment after the
        // service is. The work itself is done off this thread all the same -
        // see saveQueueState - and this one only waits for it.
        runBlocking { writeQueueState() }
        scope.cancel("Releasing Engine")
        runCatching { context.unregisterReceiver(becomingNoisyReceiver) }
        Thread { engine.release() }.start()
    }

    /**
     * Restores the persisted queue state and continues playback, when
     * [AppPreferences.getAutoResume] is enabled. Called on service start.
     *
     * @return true if a non-empty queue was successfully restored.
     */
    suspend fun restoreQueue(): Boolean {
        // Off means off, as the setting says: the last queue is not loaded,
        // paused or otherwise. It used to be loaded and left waiting, which put
        // the last file back in the player on every launch - and a module loaded
        // and not started is exactly what the delayed-focus branch of
        // requestAudioFocus starts playing when focus next comes back. The
        // saved state is kept, so the system's own resume control still works.
        if (!prefs.getAutoResume()) return false
        val state = prefs.getQueueState() ?: return false
        // Off the main thread: a long queue is a megabyte of JSON, and this
        // runs as the app is drawing its first frames. Caught in there, where
        // the only thing thrown is the decoder's own complaint: out here it
        // would catch this coroutine being cancelled too - the service gone
        // again before the queue was read - and a saved queue is not to be
        // deleted for that.
        val files = withContext(Dispatchers.Default) {
            try {
                Json.decodeFromString<List<ModuleEntity>>(state.json)
            } catch (e: Exception) {
                Timber.e(e, "Failed to restore queue")
                null
            }
        }
        if (files == null) {
            clearSavedQueue()
            return false
        }
        if (files.isEmpty() || originalQueue.isNotEmpty()) return false

        queueEnded = false
        skipAttempts = 0
        originalQueue.addAll(files)
        shuffleMode = state.shuffle
        // The window that was playing, kept in its order; the next deal reads
        // the library again. The queue it replaced was not saved.
        shuffleRoot = state.shuffle && state.shuffleRoot
        repeatMode = state.repeat
        isRepeatingOne(repeatMode == REPEAT_MODE_ONE)
        applyQueueOrder(startAt = state.index)

        userPaused = false
        requestAudioFocus()
        loadAndStartAt(
            index = currentIndex,
            resume = state.positionMs.takeIf { it > 0L }?.let { ResumePoint(it) },
            autoStart = true,
        )
        invalidateState()
        return true
    }

    /**
     * Constructs the immutable player state snapshot that Media3 uses for
     * notification metadata, transport controls, and session state.
     */
    // What getState hands Media3, kept between calls. It runs every half
    // second while music plays - for the position - and it built an item for
    // every entry in the queue each time, and the commands besides, for a
    // playlist that had not changed.
    private val commands = Player.Commands.Builder().addAll(
        COMMAND_PLAY_PAUSE,
        COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        COMMAND_SEEK_TO_MEDIA_ITEM,
        COMMAND_SEEK_TO_PREVIOUS,
        COMMAND_SEEK_TO_NEXT,
        COMMAND_SEEK_FORWARD,
        COMMAND_SEEK_BACK,
        COMMAND_GET_CURRENT_MEDIA_ITEM,
        COMMAND_GET_METADATA,
        COMMAND_GET_TIMELINE,
        COMMAND_STOP,
        COMMAND_SET_MEDIA_ITEM,
        COMMAND_CHANGE_MEDIA_ITEMS,
        COMMAND_SET_REPEAT_MODE,
        COMMAND_SET_SHUFFLE_MODE,
    ).build()

    // The playlist's items, kept by a count of the playlist's changes and not
    // by its hash, which was every item's title and artwork hashed again on
    // each call - for a queue of thousands, twice a second. And an entry is
    // made again only when it is the one that changed: the track that was
    // current and the one that is now, where every entry used to be rebuilt
    // at each track change.
    private val playlistItems = ArrayList<MediaItemData>()

    /** Bumped wherever [playlist] is rebuilt; [itemsVersion] is the one [playlistItems] was made from. */
    private var playlistVersion = 0
    private var itemsVersion = -1

    /** The entry of [playlistItems] that carries a duration, and the duration it carries. */
    private var itemsCurrent = -1
    private var itemsDurationMs = 0

    /** Set when the current entry of [playlist] was given its real metadata, for [getState] to make it again. */
    private var itemsStale = false

    /** Where [getState] last said playback was, and when it said so - see [stateDrifted]. */
    private var publishedPositionMs = 0L
    private var publishedAtMs = 0L

    private fun itemData(index: Int, durationMs: Int): MediaItemData {
        val item = playlist[index]
        // Prefixed with the position, because Media3 refuses a playlist
        // with two items of one uid - and one file saved as two subsongs,
        // or added twice, is two items with one path.
        val uid = "$index:" + item.mediaId.ifEmpty {
            item.localConfiguration?.uri?.toString() ?: ""
        }
        val durationUs =
            if (index == currentIndex && durationMs > 0) durationMs * 1_000L else C.TIME_UNSET
        return MediaItemData.Builder(uid)
            .setMediaItem(item)
            .setIsSeekable(true)
            .setDurationUs(durationUs)
            .build()
    }

    override fun getState(): State {
        val durationMs = durationOf(engine.currentSequenceFlow.value)
        if (itemsVersion != playlistVersion) {
            itemsVersion = playlistVersion
            playlistItems.clear()
            playlist.indices.mapTo(playlistItems) { itemData(it, durationMs) }
        } else if (itemsStale || itemsCurrent != currentIndex || itemsDurationMs != durationMs) {
            if (itemsCurrent in playlistItems.indices) {
                playlistItems[itemsCurrent] = itemData(itemsCurrent, durationMs)
            }
            if (currentIndex in playlistItems.indices) {
                playlistItems[currentIndex] = itemData(currentIndex, durationMs)
            }
        }
        itemsStale = false
        itemsCurrent = currentIndex
        itemsDurationMs = durationMs

        val position =
            if (pendingSeekPositionMs >= 0) pendingSeekPositionMs else engine.positionMs.value
        publishedPositionMs = position
        publishedAtMs = SystemClock.elapsedRealtime()
        val playbackState = when {
            // Stopped from outside with the queue kept: nothing is loaded.
            playlist.isEmpty() || stopped -> STATE_IDLE
            engine.endedNaturally -> STATE_ENDED
            else -> STATE_READY
        }

        return State.Builder()
            .setAvailableCommands(commands)
            // A copy that cannot change: the list itself is edited in place
            // the next time round, and a state already handed over must not
            // change with it.
            .setPlaylist(ImmutableList.copyOf(playlistItems))
            .setShuffleModeEnabled(shuffleMode)
            .setRepeatMode(repeatMode)
            .setPlaybackParameters(PlaybackParameters(tempoPercent / 100f))
            .setCurrentMediaItemIndex(currentIndex)
            .setPlayWhenReady(engine.isPlaying.value, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setContentPositionMs(position)
            .setSeekForwardIncrementMs(10_000L) // 10 Seconds
            .setSeekBackIncrementMs(10_000L) // 10 Seconds
            .build()
    }

    /** Starts or resumes the engine, or loads the current item if not yet initialised. */
    @SuppressLint("BinaryOperationInTimber")
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        Timber.d(
            "handleSetPlayWhenReady: playWhenReady=$playWhenReady " +
                "isPlaying=${engine.isPlaying.value} initialized=${engine.initialized} " +
                "isLoading=$isLoading queue=${queue.size}"
        )
        // A no-op while attached. For a command that reaches a player whose
        // service let go of it and stayed alive - bound by a car, say - so
        // that what it starts has its collectors: auto-advance, the position,
        // the saved queue.
        attach()
        if (!playWhenReady) {
            userPaused = true
            engine.pause()
        } else if (!engine.isPlaying.value) {
            userPaused = false
            requestAudioFocus()
            when {
                // Stopped from outside: the module was let go, and is loaded again.
                stopped -> loadAndStartAt(currentIndex)
                // A track on its way in: its loader starts it, and this only
                // has to say that it should play when it does. Starting the
                // engine from here ran start() on the main thread beside the
                // loader's own, on a module the loader was still setting up.
                isLoading -> engine.pauseRequested = false
                !engine.initialized -> loadAndStartAt(currentIndex)
                engine.paused -> engine.resume()
                // Media3's default Player.play() seeks to the default position (without
                // clearing endedNaturally) before calling us when playbackState was
                // STATE_ENDED. Previously none of these branches matched that combination
                // (initialized, not paused, still endedNaturally), so Play silently did
                // nothing. Reload the current track from the top instead.
                engine.endedNaturally -> loadAndStartAt(currentIndex)
                // Off the main thread: start() reads every setting the track
                // plays with before it plays a note.
                else -> {
                    engine.pauseRequested = false
                    Thread { engine.start() }.start()
                }
            }
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    /** Stores the new repeat mode and persists it with the queue. */
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        this.repeatMode = repeatMode
        isRepeatingOne(repeatMode == REPEAT_MODE_ONE)
        invalidateState()
        persistModes()
        persistQueue()
        return Futures.immediateVoidFuture()
    }

    /**
     * Toggles shuffle mode. Rebuilds [queue] so the currently-playing track stays
     * at the front, preserving its real metadata item across the reorder.
     */
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        // Media3 knows shuffle as on or off. On, while over the library, it
        // already is; off leaves the library and puts the old queue back.
        if (shuffleRoot) {
            if (!shuffleModeEnabled) leaveShuffleRoot() else invalidateState()
            return Futures.immediateVoidFuture()
        }
        shuffleMode = shuffleModeEnabled
        persistModes()
        if (queue.isEmpty()) {
            invalidateState()
            return Futures.immediateVoidFuture()
        }

        isReordering = true
        val currentFile = queue.getOrNull(currentIndex)
        applyQueueOrder(
            startAt = currentFile?.let { originalQueue.indexOf(it) }?.coerceAtLeast(0) ?: 0,
            retainCurrentItem = playlist.getOrNull(currentIndex),
        )
        isReordering = false
        persistQueue()
        return Futures.immediateVoidFuture()
    }

    /**
     * Routes seek commands: next/previous navigate the queue; all other commands
     * seek within the current item.
     */
    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT,
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> advanceToNext(skip = true)

            COMMAND_SEEK_TO_PREVIOUS,
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> previous()

            else -> {
                // Another entry of the queue, picked from a list that shows
                // it - a car's, a watch's. The index was ignored, and the
                // position that comes with such a pick is "unset", which as
                // an Int is 1: the track playing went back to its first
                // millisecond and the one picked never played.
                if (mediaItemIndex != currentIndex && mediaItemIndex in queue.indices) {
                    jumpToIndex(mediaItemIndex)
                    return Futures.immediateVoidFuture()
                }
                // Nothing is loaded to seek in: Play starts it from the top.
                if (stopped) return Futures.immediateVoidFuture()
                val target = if (positionMs == C.TIME_UNSET) 0L else positionMs
                pendingSeekPositionMs = target
                engine.seek(target.toInt())
                invalidateState()
                clearPendingSeekAfterDelay()
            }
        }
        return Futures.immediateVoidFuture()
    }

    /**
     * A stop from outside the app: a headset's or a keyboard's stop key, a
     * car, another controller, the notification swiped away.
     *
     * Stops the engine and keeps the queue. This used to be [clearQueue], the
     * app's own Stop button, which is the end of the queue: it emptied it,
     * closed the player and deleted the saved state - so one press on a
     * headset left nothing to play again, and nothing for auto-resume or the
     * system's resume control to bring back.
     */
    override fun handleStop(): ListenableFuture<*> {
        if (queue.isEmpty()) return Futures.immediateVoidFuture()
        stopped = true
        userPaused = true
        pendingSeekPositionMs = -1L
        // A track still loading is not started after this - see loadEnded.
        loadGeneration.incrementAndGet()
        engine.cancelSoundCheckWait()
        Thread { engine.stop() }.start()
        persistQueue()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    /** Stops playback and empties the queue: the app's own Stop button, which closes the player. */
    fun stopAndClearQueue() = clearQueue()

    /**
     * Accepts a pre-resolved item list from [AutoBrowseCallback] (or playback
     * resumption) and loads the queue starting at [startIndex]. Queue expansion
     * (sibling scanning) is done upstream, so the list is trusted as-is.
     */
    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        val files = mediaItems.mapNotNull { item ->
            val uri = item.localConfiguration?.uri ?: return@mapNotNull null
            ModuleEntity(
                filePath = uri.toString(),
                filename = item.mediaMetadata.title?.toString() ?: uri.lastPathSegment ?: "Unknown",
                fileSize = 0L,
                fileExtension = uri.lastPathSegment?.substringAfterLast('.', "") ?: "",
            )
        }
        if (files.isNotEmpty()) loadQueue(files, startAt = startIndex.coerceIn(0, files.lastIndex))
        return Futures.immediateVoidFuture()
    }

    /**
     * Builds [queue] and [playlist] from [originalQueue], applying shuffle if enabled.
     *
     * @param startAt Desired starting index within [originalQueue]; with shuffle on,
     *   that item is placed first and the rest are shuffled behind it.
     * @param retainCurrentItem Optional real-metadata item to keep at the current index.
     */
    private fun applyQueueOrder(
        startAt: Int = 0,
        retainCurrentItem: MediaItem? = null,
        // A window dealt from the library is shuffled already, and kept in the
        // order it was dealt.
        shuffle: Boolean = shuffleMode && !shuffleRoot,
    ) {
        Timber.d("applyQueueOrder shuffle=$shuffle startAt=$startAt size=${originalQueue.size}")
        queue.clear()
        playlist.clear()

        if (shuffle) {
            val shuffled = originalQueue.toMutableList()
            val first = shuffled.removeAt(startAt.coerceIn(0, shuffled.lastIndex))
            shuffled.shuffle()
            queue.add(first)
            queue.addAll(shuffled)
            currentIndex = 0
        } else {
            queue.addAll(originalQueue)
            currentIndex = startAt.coerceIn(0, queue.lastIndex)
        }

        playlist.addAll(queue.map { it.toMediaItem() })
        // Xmp metadata belongs to the loaded item, not whatever lands at this index.
        retainCurrentItem?.let { playlist[currentIndex] = it }
        playlistVersion++
        // The one copy of the list made for whoever collects it: the view
        // model wants an immutable list, and made its own from a plain one.
        queueFlow.value = queue.toImmutableList()
        currentIndexFlow.value = currentIndex
        invalidateState()
    }

    /**
     * Stops the engine, clears all queue state, and notifies observers.
     *
     * The stop is the point of this and used to be missing from two of the
     * three callers. An empty queue is the end of playback - there is nothing
     * left to play and no screen left showing it - but the engine was left
     * running: the render thread had exited on its own at the end of the last
     * module, so nobody ever told the native side to stop, and Oboe kept
     * calling into a decoder that had finished. What that sounded like was a
     * file starting over and stuttering with nothing anywhere saying it was
     * playing, and it outlived the player screen, the notification and the
     * task, because none of those were what was making the sound.
     *
     * Off the main thread, like [handleStop]: stopping joins the render thread
     * and then waits for an audio callback that may be in flight.
     */
    private fun clearQueue() {
        // A track still loading learns from this that it is no longer
        // wanted, and is let go of rather than started - see loadEnded.
        loadGeneration.incrementAndGet()
        engine.cancelSoundCheckWait()
        Thread { engine.stop() }.start()
        playlist.clear()
        playlistVersion++
        queue.clear()
        originalQueue.clear()
        currentIndex = 0
        currentIndexFlow.value = 0
        queueFlow.value = emptyList()
        stopped = false
        queueEnded = true
        persistQueue()
        invalidateState()
    }

    /**
     * Loads the module at [index] on a background thread and starts it.
     *
     * One load runs at a time. One asked for while another is running is not
     * started beside it - two loads at once is one module freed under the
     * other - but it is not dropped either, which it used to be: the load
     * running is told it has been overtaken ([loadGeneration]), gives up at
     * its next step, and the track wanted now is loaded when it has
     * ([loadEnded]). The wait for Sound Check sits inside a load and is up to
     * two and a half seconds long, so a second tap in the browser, or two
     * presses of next, landed in it easily: the first track then played under
     * the second one's name, and the second was never played at all.
     *
     * @param resume Where to pick the track back up, for one loaded again.
     * @param autoStart False to bring the track up paused: loaded, at
     *   [resume], and held there for Play.
     */
    private fun loadAndStartAt(index: Int, resume: ResumePoint? = null, autoStart: Boolean = true) {
        // A no-op while attached - see handleSetPlayWhenReady.
        attach()
        val file = queue.getOrNull(index) ?: return
        stopped = false
        // Before the guard below, not after it: this is how the load already
        // running finds out.
        val generation = loadGeneration.incrementAndGet()
        // How this track comes up, whatever was asked of the last one: a
        // pause made before it was picked is not a pause of it. One made from
        // here on is, and holds it - while it loads, and before that while a
        // load it overtook is still getting out of its way. See
        // PlayerEngine.pauseRequested.
        engine.pauseRequested = !autoStart
        if (isLoading) {
            // Only where to pick up is kept for loadEnded. How the track was
            // asked to come up used to be kept with it and put back when its
            // turn came, over whatever had been said since: a call that came
            // in after a second tap in the browser was played over, and Play
            // pressed while the stream was being recovered for a paused
            // track brought it up paused.
            overtakingResume = resume
            // It may be sitting in the Sound Check wait, for a track nobody
            // is going to hear.
            engine.cancelSoundCheckWait()
            return
        }
        isLoading = true
        // Read here, on the thread the queue belongs to; the loader used to
        // read it from its own.
        val ahead = queue
            .subList(index + 1, minOf(queue.size, index + 1 + PlayerEngine.SOUND_CHECK_AHEAD))
            .map { it.filePath }

        Thread {
            val loaded = try {
                loadTrack(file, generation, resume, ahead)
            } catch (e: Exception) {
                // Whatever a storage provider or a decoder's helpers throw
                // that the engine did not expect. Uncaught on this thread it
                // was the whole app gone, for one file that only needed
                // skipping - and with auto-resume on and that file the saved
                // one, gone again at every start.
                Timber.e(e, "loadAndStartAt: loading ${file.name} failed")
                false
            }
            mainHandler.post { loadEnded(generation, loaded) }
        }.start()
    }

    /**
     * The loader thread's part of [loadAndStartAt].
     *
     * @return false when [file] could not be loaded.
     */
    private fun loadTrack(file: ModuleEntity, generation: Int, resume: ResumePoint?, ahead: List<String>): Boolean {
        Timber.d("loadAndStartAt: loading file=${file.name} resume=${resume?.positionMs}")
        if (!engine.loadNext(file)) return false
        // Overtaken while it loaded: nothing of this track is shown or
        // started. loadEnded takes it from here.
        if (loadGeneration.get() != generation) return true

        // An entry saved as one subsong plays that subsong: selected
        // before anything is heard or measured, and the engine told
        // not to walk on into the others when it ends.
        engine.pinnedSubsong = file.subsong >= 0
        if (isLoopPreset(file.subsong)) {
            // A loop preset: its subsong, then its loop, from the start of it.
            val preset = engine.loopPreset(file.filePath, loopPresetId(file.subsong))
            if (preset == null) {
                Timber.w("loadAndStartAt: loop preset ${file.subsong} of ${file.name} is gone")
                engine.pinnedSubsong = false
            } else {
                if (preset.subsong >= 0) engine.setSequence(preset.subsong)
                engine.startLoop(LoopRegion(preset.startMs, preset.endMs, preset.id))
            }
        } else if (file.subsong >= 0) {
            if (!engine.setSequence(file.subsong)) {
                Timber.w("loadAndStartAt: no subsong ${file.subsong} in ${file.name}")
            }
        } else if (resume != null && resume.subsong > 0) {
            // Loaded again where it was: the subsong it was in, which for an
            // entry that names none would otherwise be the file's first -
            // with the position of another subsong then sought in it.
            engine.setSequence(resume.subsong)
        }
        // And the loop that was going round, without going back to its start.
        resume?.loop?.let { engine.startLoop(it, fromStart = false) }

        // Replace the placeholder item with real metadata from libopenmpt.
        val duration = durationOf(currentSequenceFlow.value)
        val realItem = file.toMediaItem(file.toRealMetadata(duration.toLong()))
        mainHandler.post {
            // Where the track is now, which is not always where it was when
            // the load began - shuffle turned on meanwhile moves it - and
            // only while it is still the one wanted. Written by the index the
            // load started with, this put one track's title on another, and
            // on a queue emptied meanwhile threw out of bounds on the main
            // thread: Stop pressed while a track loaded closed the app.
            val current = queue.getOrNull(currentIndex)
            if (loadGeneration.get() == generation && current != null &&
                current.filePath == file.filePath && current.subsong == file.subsong
            ) {
                playlist[currentIndex] = realItem
                itemsStale = true
                invalidateState()
            }
        }

        // A track Sound Check has never analysed is measured before
        // it is heard - see PlayerEngine.prepareSoundCheck.
        engine.prepareSoundCheck { loadGeneration.get() == generation }
        // Overtaken during that wait, which is where it mostly happens.
        if (loadGeneration.get() != generation) return true
        engine.start()
        engine.soundCheckAhead(ahead)
        // After start() either way: a track brought up paused is sought
        // like any paused track.
        if (resume != null && resume.positionMs > 0L) {
            engine.seek(resume.positionMs.toInt())
            pendingSeekPositionMs = resume.positionMs
        }
        return true
    }

    /**
     * The end of a load, on the main thread - where [isLoading] is cleared, so
     * that nothing can be asked for between a load finishing and what is done
     * about how it finished.
     */
    private fun loadEnded(generation: Int, loaded: Boolean) {
        isLoading = false
        when {
            // Overtaken: by another track, which is loaded now, as it asked
            // to be; or by the queue being emptied or stopped, and then what
            // this load put in the engine is let go of - the stop that came
            // with that may have run before this load had finished.
            loadGeneration.get() != generation -> {
                if (stopped || queue.getOrNull(currentIndex) == null) {
                    Thread { engine.stop() }.start()
                } else {
                    // As the last word on playing stands now, which is what
                    // it asked for unless a pause or a Play has come since.
                    loadAndStartAt(currentIndex, overtakingResume, autoStart = !engine.pauseRequested)
                }
            }

            !loaded -> skipUnplayable()

            else -> {
                // A track that loads ends the run of ones that would not.
                // Without this the count only ever grew: one stale entry in a
                // queue on repeat added to it every time round, until it
                // reached the queue's size and the whole queue was thrown
                // away with everything else in it still playable.
                skipAttempts = 0
                // Bumped here, once engine.start() has run and engine.isPlaying
                // holds its settled value, rather than when the metadata is
                // posted.
                //
                // That ordering is the entire point of this counter. isPlaying
                // is a conflated StateFlow, and a track change flips it
                // false->true within ~10ms on the loader thread (softStop() then
                // start()); a main-thread collector that was already sitting at
                // true never observes that as a change and so never emits at
                // all. Anything waiting on that edge silently stayed where it
                // was - the UI status stuck on LOADING for the rest of the
                // session, which killed the play/pause button and left
                // ChannelView/DebugView blank, since their fetch loops are
                // keyed on it. A counter always differs from its previous
                // value, so it always emits, which is what lets those
                // collectors re-read isPlaying at a point where it is correct.
                moduleLoadedFlow.value++
                invalidateState()
                // Came up paused, and Play was pressed in the moment between
                // start() deciding that and this: the press is not lost.
                if (engine.paused && !engine.pauseRequested) engine.resume()
            }
        }
    }

    /**
     * Skips the current unplayable file, continuing in [lastNavDirection].
     * Clears the queue if every item fails to load.
     */
    private fun skipUnplayable() {
        skipAttempts++
        if (skipAttempts >= queue.size) {
            skipAttempts = 0
            clearQueue()
            return
        }
        // A skip, not an ending: with repeat-one on, "repeat this track" on a
        // track that will not load only tried it again until the queue ran out.
        if (lastNavDirection < 0) advanceToPrevious(skip = true) else advanceToNext(skip = true)
    }

    /** Navigates to [to], loads and starts the module there. */
    private fun navigate(to: Int) {
        currentIndex = to
        currentIndexFlow.value = to
        pendingSeekPositionMs = -1L
        // Cleared here rather than left to the load thread's resetSequenceState.
        // The invalidateState below runs while that thread is still starting up,
        // and with the flag still set it publishes STATE_ENDED - so every gap
        // between two queue tracks told the media session playback had finished,
        // which is exactly the condition MediaSessionService uses to drop the
        // notification and let the service be torn down mid-queue.
        engine.clearEndedNaturally()
        invalidateState()
        // What starts playing here is playing by request, like a queue just
        // loaded. Next, previous or a pick from the queue did neither of
        // these: after another app had taken the focus for good, the track
        // they started played over it with nobody listening for a call; and
        // after a pause, the first call to interrupt it left it paused for
        // ever, since a pause the listener made is not undone when focus
        // returns. A no-op when the focus is already held, as it is when one
        // track simply follows another.
        userPaused = false
        requestAudioFocus()
        loadAndStartAt(to)
        persistQueue()
    }

    /** Whether the entry playing is one saved subsong - see [PlayerEngine.pinnedSubsong]. */
    private fun currentIsPinned(): Boolean = (queue.getOrNull(currentIndex)?.subsong ?: -1) >= 0

    /**
     * Sends a skip to subsong [index] of the entry playing, and to
     * [queueInstead] if the decoder will not start it.
     *
     * The switch is not waited for, so the skip is answered before anyone
     * knows whether it worked - and the count of subsongs it was picked from
     * is only what the file says it holds: a track of an NSF, a SID or an AHX
     * that will not start is counted with the rest. While the answer was
     * waited for, a skip onto one of those went on through the queue. Once
     * it was not, it did nothing at all. The refusal now comes back here and
     * moves the queue as it used to - unless the listener has stopped, or has
     * gone somewhere else, in the meantime.
     */
    private fun skipToSubsong(index: Int, queueInstead: () -> Unit) {
        val entry = queue.getOrNull(currentIndex)
        engine.setSequenceAsync(
            index,
            refused = {
                mainHandler.post {
                    if (entry != null && !stopped && !isLoading && queue.getOrNull(currentIndex) === entry) {
                        queueInstead()
                    }
                }
            },
        )
    }

    /**
     * Moves to the next subsong, if the module has one left.
     *
     * A file holding several tunes - an NSF with a game's soundtrack, a module
     * with subsongs - is one queue entry with several things inside it, so
     * "next" has to walk those before it walks the queue. Without this,
     * skipping from the first tune of a single-file queue emptied the queue
     * and stopped playback.
     *
     * @return true when a switch is on its way - see [skipToSubsong] for one
     *   that does not get there.
     */
    private fun advanceSubsong(): Boolean {
        // Stopped, or the engine let go of with the service, there is no
        // module for the subsong to be one of: the queue moves on instead.
        if (stopped || !engine.initialized || !engine.playAllSequences || currentIsPinned()) return false
        val sequences = engine.modVarsFlow.value.miNumSequences
        if (sequences <= 1) return false
        // From where a switch still on its way is headed, so a second press
        // before the first has landed moves on again.
        val next = engine.sequenceTarget + 1
        if (next >= sequences) return false
        // Not waited for: the switch takes the decoder's mutex, and this is
        // the main thread. The index is one the module counts, which is not
        // yet one it will play.
        skipToSubsong(next) { nextInQueue() }
        return true
    }

    /**
     * The same thing backwards, so skip works in both directions.
     *
     * A file with subsongs is several tunes to listen to, and the skip
     * buttons are how you move between tunes - it only went forward, so you
     * could walk into a subsong and had no way back out except reloading the
     * file.
     *
     * @return true when a switch is on its way, as for [advanceSubsong].
     */
    private fun retreatSubsong(): Boolean {
        if (stopped || !engine.initialized || !engine.playAllSequences || currentIsPinned()) return false
        val sequences = engine.modVarsFlow.value.miNumSequences
        if (sequences <= 1) return false
        val previous = engine.sequenceTarget - 1
        if (previous < 0) return false
        skipToSubsong(previous) { previousInQueue() }
        return true
    }

    /**
     * Advances forward according to the current [repeatMode].
     *
     * [skip] is whether the listener asked to move on, as opposed to the track
     * having ended. Repeat-one is about the second: it repeats a track when it
     * ends. It used to answer the first the same way, so with it on the skip
     * buttons reloaded the track already playing and there was no way to get
     * to another one short of turning repeat off. A skip under repeat-one now
     * moves on like repeat-all - through the subsongs, then the queue, round
     * to the start - and the new track repeats in turn.
     */
    private fun advanceToNext(skip: Boolean = false) {
        lastNavDirection = 1
        // The subsongs are walked from here for a skip only. A track that
        // ended has had them walked already, where they are walked at all,
        // by the render loop - which ends it when the next one will not
        // start. Asking for that same subsong again from here, now that the
        // answer is not waited for, counted as having moved: a file with a
        // subsong that would not play was where the queue stopped, with the
        // rest of it never played and Play only bringing the same file to
        // the same place.
        if (skip && advanceSubsong()) return
        nextInQueue(repeatThis = repeatMode == REPEAT_MODE_ONE && !skip)
    }

    /**
     * The queue's own part of [advanceToNext]: on to another entry, whatever
     * subsongs this one has left. [repeatThis] plays the same entry again.
     */
    private fun nextInQueue(repeatThis: Boolean = false) {
        when {
            repeatThis -> navigate(currentIndex)
            currentIndex + 1 < queue.size -> navigate(currentIndex + 1)
            shuffleRoot -> dealNextRootWindow()
            repeatMode != REPEAT_MODE_OFF -> navigate(0)
            else -> clearQueue()
        }
    }

    /** Where the current track sits in [originalQueue], or 0. */
    private fun currentOriginalIndex(): Int {
        val current = queue.getOrNull(currentIndex) ?: return 0
        return originalQueue.indexOfFirst {
            it.filePath == current.filePath && it.subsong == current.subsong
        }.coerceAtLeast(0)
    }

    /**
     * Every playable module under the root folder the browser was given.
     * Read again when [refresh] is set or the root has changed - so files
     * indexed since are dealt too - and otherwise kept.
     */
    private suspend fun loadRootPool(refresh: Boolean): List<ModuleEntity> {
        val root = prefs.getLastDirectoryUri() ?: return emptyList()
        if (refresh || rootPoolPath != root || rootPool.isEmpty()) {
            rootPool = withContext(Dispatchers.IO) { modules.getDescendantModules(root) }
            rootPoolPath = root
            rootBag.clear()
        }
        return rootPool
    }

    /** Up to [count] picks from [rootBag], never [exclude], refilling the bag as it runs out. */
    private fun drawFromRoot(count: Int, exclude: ModuleEntity?): List<ModuleEntity> {
        val wanted = minOf(count, rootPool.size)
        val picks = ArrayList<ModuleEntity>(wanted)
        var refills = 0
        while (picks.size < wanted) {
            if (rootBag.isEmpty()) {
                // Twice at most: a pool of one file, which is the one excluded,
                // would otherwise refill forever.
                if (rootPool.isEmpty() || refills++ >= 2) break
                rootBag.addAll(rootPool.shuffled())
            }
            val next = rootBag.removeFirst()
            if (exclude != null && next.filePath == exclude.filePath) continue
            picks.add(next)
        }
        return picks
    }

    /** Keeps the current track first and deals the rest of the window behind it. */
    private fun dealRootWindow() {
        rootJob?.cancel()
        rootJob = scope.launch {
            val pool = loadRootPool(refresh = true)
            if (!shuffleRoot || queue.isEmpty()) return@launch
            if (pool.isEmpty()) {
                Timber.w("shuffle over the library: nothing indexed under the root folder")
                return@launch
            }
            val current = queue.getOrNull(currentIndex) ?: return@launch
            val window = listOf(current) + drawFromRoot(ROOT_WINDOW - 1, exclude = current)
            isReordering = true
            val retained = playlist.getOrNull(currentIndex)
            originalQueue.clear()
            originalQueue.addAll(window)
            applyQueueOrder(startAt = 0, retainCurrentItem = retained)
            isReordering = false
            persistQueue()
        }
    }

    /** The end of a window: deal a new one and play its first pick. */
    private fun dealNextRootWindow() {
        rootJob?.cancel()
        rootJob = scope.launch {
            loadRootPool(refresh = rootBag.isEmpty())
            val last = queue.getOrNull(currentIndex)
            val window = drawFromRoot(ROOT_WINDOW, exclude = last)
            if (!shuffleRoot) return@launch
            if (window.isEmpty()) {
                // Nothing to deal from: end the way the queue would have.
                if (repeatMode != REPEAT_MODE_OFF) navigate(0) else clearQueue()
                return@launch
            }
            originalQueue.clear()
            originalQueue.addAll(window)
            applyQueueOrder(startAt = 0)
            navigate(0)
        }
    }

    /** Back from the library to the queue it replaced, with shuffle off. */
    private fun leaveShuffleRoot() {
        rootJob?.cancel()
        shuffleRoot = false
        shuffleMode = false
        persistModes()
        val before = queueBeforeRoot
        queueBeforeRoot = emptyList()
        val current = queue.getOrNull(currentIndex)
        if (current == null) {
            invalidateState()
            return
        }
        if (before.isNotEmpty()) {
            // The track playing stays playing. Where it came from the library
            // and not from that queue, it goes in after the place playback left.
            val restored = before.toMutableList()
            if (restored.none { it.filePath == current.filePath && it.subsong == current.subsong }) {
                restored.add((indexBeforeRoot + 1).coerceAtMost(restored.size), current)
            }
            originalQueue.clear()
            originalQueue.addAll(restored)
        }
        // With nothing saved to go back to - restored after the app was closed
        // - the window playing becomes the queue, in its order.
        isReordering = true
        applyQueueOrder(
            startAt = currentOriginalIndex(),
            retainCurrentItem = playlist.getOrNull(currentIndex),
        )
        isReordering = false
        persistQueue()
    }

    /** Advances backward according to the current [repeatMode]; see [advanceToNext] for [skip]. */
    private fun advanceToPrevious(skip: Boolean = false) {
        lastNavDirection = -1
        val repeatThis = repeatMode == REPEAT_MODE_ONE && !skip
        if (!repeatThis && retreatSubsong()) return
        previousInQueue(repeatThis)
    }

    /** The queue's own part of [advanceToPrevious], as [nextInQueue] is of [advanceToNext]. */
    private fun previousInQueue(repeatThis: Boolean = false) {
        when {
            repeatThis -> navigate(currentIndex)
            currentIndex > 0 -> navigate(currentIndex - 1)
            repeatMode != REPEAT_MODE_OFF -> navigate(queue.lastIndex)
            else -> navigate(0)
        }
    }

    /**
     * Clears [pendingSeekPositionMs] shortly after a seek, regardless of play
     * state. The 500 ms loop in [startPositionUpdates] only runs while playing,
     * so a seek issued while paused (e.g. dragging the seekbar, or [previous]
     * near the start of a track) previously left the seekbar frozen on the
     * seeked-to position forever — this is the pause-safe fallback for that.
     */
    private fun clearPendingSeekAfterDelay() {
        scope.launch {
            delay(300L)
            pendingSeekPositionMs = -1L
            invalidateState()
        }
    }

    /**
     * Whether what Media3 was last told has stopped being true of the track
     * playing: its length changed, or it is not where a track left to play
     * on from the position given would be by now - a loop gone round, a
     * subsong that followed another, a seek still running.
     */
    private fun stateDrifted(): Boolean {
        if (durationOf(engine.currentSequenceFlow.value) != itemsDurationMs) return true
        val expected = publishedPositionMs +
            (SystemClock.elapsedRealtime() - publishedAtMs) * tempoPercent / 100
        return abs(engine.positionMs.value - expected) >= POSITION_SLACK_MS
    }

    /** Starts the 500 ms position-update loop, also flushing queue state every 5 s. */
    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            var lastPersist = 0L
            while (true) {
                delay(500L.milliseconds)
                // Clear a pending seek once the engine position has converged.
                val seekLanded = pendingSeekPositionMs >= 0 &&
                    abs(engine.positionMs.value - pendingSeekPositionMs) < 2_000L
                if (seekLanded) pendingSeekPositionMs = -1L
                // Only when there is something to say. Media3 moves the
                // position on by itself between states, at the speed it was
                // given, so a state every half second told it nothing - and
                // each one is a pass over the whole playlist inside Media3,
                // which for a queue of thousands was the main thread held up
                // twice a second for as long as music played.
                if (seekLanded || stateDrifted()) invalidateState()
                val now = System.currentTimeMillis()
                if (now - lastPersist > 5_000) {
                    persistQueue()
                    lastPersist = now
                }
            }
        }
    }

    /** Cancels the position-update loop. */
    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    /**
     * Stores shuffle and repeat as the listener's settings, not only as part of
     * the queue.
     *
     * They lived in the queue state alone, and every way of starting playback
     * handed in modes of its own - the file browser its toolbar's, favourites
     * shuffle-off - so picking another module put back whatever that screen
     * held and the player's own buttons were forgotten. Every change now lands
     * here, and every screen reads it back.
     */
    private fun persistModes() {
        val repeat = repeatMode
        val shuffle = shuffleMode
        val root = shuffleRoot
        scope.launch {
            prefs.setGlobalRepeat(repeat)
            prefs.setGlobalShuffle(shuffle)
            prefs.setGlobalShuffleRoot(root)
        }
    }

    /**
     * Saves the current queue and playback position to [AppPreferences] so it
     * can be restored after process death.
     */
    private fun persistQueue() {
        scope.launch { writeQueueState() }
    }

    /**
     * Saves the queue, or forgets the saved one when this queue has ended.
     *
     * And neither, where no queue was loaded at all. "Empty" used to mean
     * "ended", and the service is torn down with an empty queue every time
     * the app is opened, looked at and left without anything being played -
     * so that deleted the queue the last session had saved, and auto-resume,
     * the system's resume control and a headset's play button then found
     * nothing to bring back.
     */
    private suspend fun writeQueueState() {
        when {
            originalQueue.isNotEmpty() -> saveQueueState()
            queueEnded -> clearSavedQueue()
        }
    }

    /**
     * Where the saved queue is written and deleted: one at a time and in the
     * order asked, off the main thread.
     */
    private val queueWriter = Dispatchers.IO.limitedParallelism(1)

    /** What the queue last written looked like: see [saveQueueState]. */
    @Volatile
    private var savedQueue = 0

    /**
     * Saves the queue when it has changed, and where playback is in it
     * always.
     *
     * This runs every five seconds while music plays, for the position, and it
     * used to turn the whole queue into JSON each time - a hundred kilobytes
     * for a long one, built and thrown away and written back to disk for a
     * queue that had not changed. Its hash costs next to nothing: the strings
     * in it keep theirs.
     *
     * Next to nothing for each entry, that is, and a folder tapped in the
     * browser is thousands of them: the hash, the JSON when it is needed and
     * the search for the current entry are made on [queueWriter], from a copy
     * of the queue taken here. They were made on the main thread, in the
     * frames the mini player was sliding in over.
     */
    private suspend fun saveQueueState() {
        val snapshot = originalQueue.toList()
        val current = queue.getOrNull(currentIndex)
        val index = currentIndex
        val shuffle = shuffleMode
        val root = shuffleRoot
        val repeat = repeatMode
        val positionMs = engine.positionMs.value
        withContext(queueWriter) {
            val signature = snapshot.hashCode()
            if (signature != savedQueue) {
                prefs.saveQueue(Json.encodeToString(snapshot))
                savedQueue = signature
            }
            prefs.saveQueueProgress(
                index = if (shuffle) {
                    current?.let { snapshot.indexOf(it) }?.coerceAtLeast(0) ?: 0
                } else {
                    index
                },
                shuffle = shuffle,
                shuffleRoot = root,
                repeat = repeat,
                positionMs = positionMs,
            )
        }
    }

    /** Forgets the saved queue, and that one was saved. */
    private suspend fun clearSavedQueue() = withContext(queueWriter) {
        prefs.clearQueueState()
        savedQueue = 0
    }

    /** Placeholder artwork for the media notification. */
    private fun drawableUri(resId: Int): Uri =
        "android.resource://${context.packageName}/$resId".toUri()

    /**
     * The one placeholder every item carries, made once: a queue is thousands
     * of items, and each used to have the same address parsed for it again.
     */
    private val albumArt: Uri by lazy { drawableUri(R.drawable.aa_album_art) }

    /**
     * Constructs a [MediaMetadata] with the given [title] and [artist], the app's placeholder
     * album art, and an optional [durationMs]. Used to populate media session metadata for the
     * lock-screen / notification; artwork is a static drawable because module files carry no
     * embedded images.
     */
    private fun buildMetadata(
        title: String,
        artist: String,
        durationMs: Long? = null
    ): MediaMetadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .apply { durationMs?.let { setDurationMs(it) } }
        .setArtworkUri(albumArt)
        .setIsPlayable(true)
        .build()

    /**
     * Builds a [MediaItem] with placeholder metadata for initial queue population.
     * Real metadata (loaded from libopenmpt) is injected by [loadAndStartAt] later.
     */
    private fun ModuleEntity.toMediaItem(
        metadata: MediaMetadata = buildMetadata(name, type)
    ): MediaItem = MediaItem.Builder()
        .setUri(uri)
        .setMediaId(filePath)
        .setMediaMetadata(metadata)
        .build()

    /** Builds [MediaMetadata] from libopenmpt after the module has been loaded successfully. */
    private fun ModuleEntity.toRealMetadata(durationMs: Long): MediaMetadata = buildMetadata(
        title = modVarsFlow.value.modName.ifBlank { filename },
        artist = modVarsFlow.value.modType.ifBlank { fileExtension.uppercase() },
        durationMs = durationMs,
    )

    private companion object {
        /** Picks dealt at a time in shuffle over the library - see [shuffleRoot]. */
        const val ROOT_WINDOW = 100

        /**
         * How far the position may be from where Media3 takes it to be before
         * it is told again - see [stateDrifted]. A second is what Media3
         * itself counts as a jump rather than as playing on.
         */
        const val POSITION_SLACK_MS = 1_000L
    }
}
