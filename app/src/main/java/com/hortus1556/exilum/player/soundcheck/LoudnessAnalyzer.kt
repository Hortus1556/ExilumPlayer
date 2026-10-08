package com.hortus1556.exilum.player.soundcheck

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import com.hortus1556.exilum.data.SoundCheckStore
import com.hortus1556.exilum.db.dao.ModuleDao
import com.hortus1556.exilum.renderer.Player
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * The main process's side of Sound Check's analysis: asks [LoudnessService]
 * to measure tracks, keeps what comes back in [SoundCheckStore], and tells
 * whoever listens when a track's loudness becomes known.
 *
 * Tracks are analysed before they are needed wherever that can be arranged -
 * the whole library once Sound Check is on, the next few in the queue as each
 * track starts - so that by the time a track plays its loudness is usually
 * already known and its level is right from the first sample. A track that
 * is not can be waited for briefly: see [awaitLoudness].
 */
class LoudnessAnalyzer(
    context: Context,
    val store: SoundCheckStore,
    private val modules: ModuleDao,
) {
    private val app = context.applicationContext

    /** Progress of the library analysis, for the settings screen. */
    data class Progress(val done: Int, val total: Int, val running: Boolean)

    val progress: StateFlow<Progress>
        field = MutableStateFlow(Progress(0, 0, false))

    /**
     * What the analysis is working through, by title: the file being
     * analysed now - the library's, or one wanted sooner for playback - and
     * the library files still to come, in the order they will be.
     */
    data class Queue(val now: String?, val next: List<String>)

    val queue: StateFlow<Queue>
        field = MutableStateFlow(Queue(null, emptyList()))

    /** Called on the analyser's reply thread with (uri, subsong, lufs) whenever a loudness is kept. */
    @Volatile
    var onLoudness: ((String, Int, Float) -> Unit)? = null

    private val replyThread = HandlerThread("LoudnessReplies").apply { start() }
    private val replies = Messenger(Handler(replyThread.looper) { message -> receive(message); true })

    private val lock = Any()
    private var service: Messenger? = null
    private var binding = false
    private val pending = ArrayList<Message>()

    /** The job the service said it was working on - blamed if the service dies. */
    @Volatile
    private var running: Pair<String, Int>? = null

    /** Who is waiting for which (uri, subsong). */
    private val waiters = ConcurrentHashMap<String, MutableList<CompletableFuture<Float?>>>()

    /** Library files still to hear back about, for [progress]. */
    private val libraryOutstanding = ConcurrentHashMap.newKeySet<String>()

    /** The same, in the order they were asked for, and what each is called - for [queue]. Under [lock]. */
    private val libraryOrder = LinkedHashSet<String>()
    private val titles = HashMap<String, String>()

    /**
     * The library files not yet handed to the service, and the ones it has
     * and has not answered for. Under [lock]. See [feedLibrary].
     */
    private val libraryWaiting = ArrayDeque<String>()
    private val librarySent = HashSet<String>()

    /** Whether the library is wanted analysed - asked for again if the analysis process has to be restarted. */
    @Volatile
    private var libraryWanted = false

    @Volatile
    private var lostService = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // The library pass, told as it goes: done and left, in a notification.
        SoundCheckNotifier(app).follow(progress, scope)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val messenger = Messenger(binder)
            val queued = synchronized(lock) {
                service = messenger
                binding = false
                ArrayList(pending).also { pending.clear() }
            }
            // The process was restarted after dying, and everything it had
            // queued went with it: the library is asked for again, less any
            // file it has died on too often.
            val relist = lostService && libraryWanted
            lostService = false
            // Sent from a thread of the analyser's own: this is the main
            // thread, and each of these is a call into another process.
            scope.launch {
                queued.forEach { send(messenger, it) }
                if (relist) analyzeLibrary()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The analysis process died: a decoder that could not take a
            // file, or Android taking back a process that had nothing on
            // screen. Everything is asked for afresh by whoever still wants
            // it, the file it died in included - until that has happened to
            // the same file too often to be chance. See blame.
            synchronized(lock) { service = null }
            lostService = true
            running?.let { (uri, subsong) ->
                Timber.w("soundCheck: analysis process died on '$uri' #$subsong")
                blame(uri, subsong)
                finish(uri, subsong, null)
            }
            running = null
            libraryOutstanding.clear()
            synchronized(lock) {
                libraryOrder.clear()
                libraryWaiting.clear()
                librarySent.clear()
            }
            publishQueue()
            progress.value = progress.value.copy(running = false)
        }
    }

    /**
     * Counts the analysis process's death against the file it was in.
     *
     * One death used to settle it: the file was marked as one that cannot be
     * analysed and never tried again, by the pass or when played. But the
     * process is a background one with nothing to show, and Android takes it
     * away whenever the app is not in front - with whatever file happened to
     * be in it, which over a pass of hours was one healthy file after another
     * left for good at the level of a track never measured. A file is given
     * up on when the process has died in it more than once; more often still
     * where Android says the process was taken rather than fell.
     */
    private fun blame(uri: String, subsong: Int) {
        val limit = if (wasTakenAway()) STRIKES_WHEN_TAKEN else STRIKES_WHEN_CRASHED
        if (store.strike(uri, subsong, limit)) {
            Timber.w("soundCheck: '$uri' #$subsong is not tried again")
        }
    }

    /**
     * Whether Android says the analysis process was ended from outside
     * rather than crashed - where it says at all, which is from Android 11,
     * and only when it has already written the death down.
     */
    private fun wasTakenAway(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val manager = app.getSystemService(ActivityManager::class.java) ?: return false
        val last = runCatching { manager.getHistoricalProcessExitReasons(app.packageName, 0, EXIT_REASONS_READ) }
            .getOrDefault(emptyList())
            .firstOrNull { it.processName.endsWith(PROCESS_SUFFIX) }
            ?: return false
        // An older death of the same process says nothing about this one.
        if (System.currentTimeMillis() - last.timestamp > EXIT_REASON_FRESH_MS) return false
        return when (last.reason) {
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR -> false
            else -> true
        }
    }

    private fun waiterKey(uri: String, subsong: Int) = "$subsong|$uri"

    private fun bind() {
        synchronized(lock) {
            if (service != null || binding) return
            binding = true
        }
        val bound = runCatching {
            app.bindService(Intent(app, LoudnessService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) synchronized(lock) { binding = false }
    }

    private fun send(message: Message) {
        val target = synchronized(lock) {
            service ?: run {
                pending += message
                null
            }
        }
        if (target == null) bind() else send(target, message)
    }

    private fun send(target: Messenger, message: Message) {
        try {
            target.send(message)
        } catch (e: RemoteException) {
            Timber.w(e, "soundCheck: could not reach the analysis process")
        }
    }

    /** Asks for [uri] as [subsong] to be analysed, whole unless [budgetMs] is above zero. */
    fun request(uri: String, subsong: Int, priority: Int, budgetMs: Int = 0, allSubsongs: Boolean = false) {
        send(Message.obtain(null, LoudnessProtocol.MSG_ANALYZE).apply {
            replyTo = replies
            data = Bundle().apply {
                putString(LoudnessProtocol.KEY_URI, uri)
                putInt(LoudnessProtocol.KEY_SUBSONG, subsong)
                putInt(LoudnessProtocol.KEY_PRIORITY, priority)
                // As a time on the clock both processes read, not as a length:
                // the service takes off what the job spent waiting its turn.
                putLong(
                    LoudnessProtocol.KEY_DEADLINE,
                    if (budgetMs > 0) SystemClock.uptimeMillis() + budgetMs else 0L,
                )
                putBoolean(LoudnessProtocol.KEY_ALL_SUBSONGS, allSubsongs)
            }
        })
    }

    /**
     * The loudness of [uri] as [subsong], waiting up to [timeoutMs] for it.
     *
     * Known already: at once. Otherwise the track is analysed as the most
     * urgent job, for as much of it as fits in the wait, and the whole of it
     * after that - so what comes back in time may cover only its beginning,
     * and the whole figure follows through [onLoudness]. Null when nothing
     * came in time, or when [stillWanted] says - once, as the wait begins -
     * that the answer is no longer wanted; after that [cancelWaits] ends it.
     * Blocks: never on the main thread.
     */
    fun awaitLoudness(uri: String, subsong: Int, timeoutMs: Long, stillWanted: () -> Boolean = { true }): Float? {
        store.measurement(uri, subsong)?.let { if (it.complete) return it.lufs }
        val future = CompletableFuture<Float?>()
        waiters.getOrPut(waiterKey(uri, subsong)) { java.util.Collections.synchronizedList(ArrayList()) } += future
        return try {
            // Asked only now that the future is where cancelWaits finds it:
            // whoever changes their mind does so before this and is seen
            // here, or after it and is heard there.
            if (!stillWanted()) return null
            val budget = (timeoutMs - AWAIT_MARGIN_MS).coerceAtLeast(MIN_BUDGET_MS).toInt()
            request(uri, subsong, LoudnessProtocol.PRIORITY_URGENT, budgetMs = budget)
            request(uri, subsong, LoudnessProtocol.PRIORITY_CURRENT)
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (timedOut: Exception) {
            store.loudness(uri, subsong)
        } finally {
            waiters[waiterKey(uri, subsong)]?.remove(future)
        }
    }

    /**
     * Ends every [awaitLoudness] in progress, with nothing: the track being
     * waited for is not going to be played. The analysis asked for goes on,
     * and is kept.
     */
    fun cancelWaits() {
        waiters.values.forEach { list -> synchronized(list) { list.forEach { it.complete(null) } } }
    }

    /** Has [uris] (subsong 0, then their other subsongs) analysed next, ahead of the library. */
    fun analyzeAhead(uris: List<String>) {
        uris.filter { !store.isSettled(it, 0) }
            .forEach { request(it, 0, LoudnessProtocol.PRIORITY_AHEAD, allSubsongs = true) }
    }

    /** Has the other subsongs of [uri], [count] in all, analysed soon. */
    fun analyzeSubsongs(uri: String, count: Int) {
        for (subsong in 0 until count.coerceAtMost(LoudnessProtocol.MAX_SUBSONGS)) {
            if (!store.isSettled(uri, subsong)) request(uri, subsong, LoudnessProtocol.PRIORITY_CURRENT)
        }
    }

    /**
     * Has every file in the library not yet analysed analysed, behind
     * everything else. Returns at once: the library is read and gone through
     * on a thread of the analyser's own.
     */
    fun startLibrary() {
        libraryWanted = true
        scope.launch { analyzeLibrary() }
    }

    /** Lists every file in the library not yet analysed and starts handing them to the service. */
    private suspend fun analyzeLibrary() {
        val library = modules.getAllValidModules()
        val files = library.map { it.filePath }
        // The files the analysis process has died in go last: whatever took
        // it away the first time is likeliest to still be there straight
        // after it comes back, and would take the same file with it again.
        val todo = files.filter { !store.isSettled(it, 0) }.sortedBy { store.strikes(it, 0) }
        synchronized(lock) {
            // Turned off while the library was being read.
            if (!libraryWanted) return
            libraryOutstanding.clear()
            libraryOutstanding.addAll(todo)
            libraryOrder.clear()
            libraryOrder.addAll(todo)
            libraryWaiting.clear()
            libraryWaiting.addAll(todo)
            librarySent.clear()
            titles.clear()
            library.forEach { titles[it.filePath] = it.moduleName.ifBlank { it.filename } }
        }
        publishQueue()
        progress.value = Progress(files.size - todo.size, files.size, todo.isNotEmpty())
        Timber.i("soundCheck: ${todo.size} of ${files.size} library files to analyse")
        feedLibrary()
    }

    /**
     * Hands the service library files until it holds [LIBRARY_WINDOW] it has
     * not answered for; called again as each answer comes.
     *
     * The whole library used to be asked for at once, a message a file. They
     * piled up while the analysis process was being started and were all sent
     * the moment it connected, from the main thread: thousands of calls into
     * another process a moment after launch, at every launch until the pass
     * was done - the file browser stuttering as it first drew.
     */
    private fun feedLibrary() {
        val next = synchronized(lock) {
            val batch = ArrayList<String>()
            while (librarySent.size < LIBRARY_WINDOW) {
                val uri = libraryWaiting.removeFirstOrNull() ?: break
                // Analysed meanwhile because it was played: not again.
                if (store.isSettled(uri, 0)) continue
                librarySent += uri
                batch += uri
            }
            batch
        }
        next.forEach { request(it, 0, LoudnessProtocol.PRIORITY_LIBRARY, allSubsongs = true) }
    }

    /** Drops the library analysis still queued - Sound Check was turned off. */
    fun stopLibrary() {
        libraryWanted = false
        libraryOutstanding.clear()
        val connected = synchronized(lock) {
            libraryOrder.clear()
            libraryWaiting.clear()
            librarySent.clear()
            // And what was asked for before the service was there to be
            // told: left where it was, it was sent as soon as the service
            // connected and analysed with Sound Check off.
            pending.removeAll { message ->
                message.what == LoudnessProtocol.MSG_ANALYZE &&
                    (message.peekData()?.getInt(LoudnessProtocol.KEY_PRIORITY) ?: 0) >= LoudnessProtocol.PRIORITY_AHEAD
            }
            service != null
        }
        publishQueue()
        progress.value = progress.value.copy(running = false)
        if (!connected) return
        send(Message.obtain(null, LoudnessProtocol.MSG_CANCEL_BELOW).apply {
            arg1 = LoudnessProtocol.PRIORITY_AHEAD
        })
    }

    private fun receive(message: Message) {
        val data = message.data ?: return
        val uri = data.getString(LoudnessProtocol.KEY_URI) ?: return
        val subsong = data.getInt(LoudnessProtocol.KEY_SUBSONG)
        when (message.what) {
            LoudnessProtocol.MSG_STARTED -> {
                running = uri to subsong
                publishQueue()
            }

            LoudnessProtocol.MSG_RESULT -> {
                running = null
                val error = data.getInt(LoudnessProtocol.KEY_ERROR)
                val lufs = data.getFloat(LoudnessProtocol.KEY_LUFS)
                val seconds = data.getFloat(LoudnessProtocol.KEY_SECONDS)
                val complete = data.getBoolean(LoudnessProtocol.KEY_COMPLETE)
                if (error == 0 && lufs > Player.SOUND_CHECK_UNKNOWN + 1f) {
                    store.save(uri, subsong, lufs, seconds, complete)
                    finish(uri, subsong, store.loudness(uri, subsong))
                } else if (error == 0) {
                    // Played through and silent: nothing to level, and nothing to try again.
                    if (complete) store.markFailed(uri, subsong)
                    finish(uri, subsong, null)
                } else {
                    Timber.w("soundCheck: '$uri' #$subsong could not be analysed ($error)")
                    if (error != Player.LOAD_UNREADABLE) store.markFailed(uri, subsong)
                    finish(uri, subsong, null)
                }
                if (subsong == 0) {
                    if (libraryOutstanding.remove(uri)) {
                        synchronized(lock) { libraryOrder.remove(uri) }
                        val p = progress.value
                        progress.value = Progress(p.done + 1, p.total, libraryOutstanding.isNotEmpty())
                    }
                    // One answered for, one more handed over.
                    if (synchronized(lock) { librarySent.remove(uri) }) feedLibrary()
                }
                publishQueue()
            }
        }
    }

    /** Tells [queue] what is being analysed and what is left. */
    private fun publishQueue() {
        val now = running?.first
        queue.value = synchronized(lock) {
            Queue(
                now = now?.let(::titleOf),
                next = libraryOrder.mapNotNull { uri -> if (uri == now) null else titleOf(uri) },
            )
        }
    }

    /** A file's title - its module name, or its file name - or, for one not in the library, the end of its address. */
    private fun titleOf(uri: String): String =
        synchronized(lock) { titles[uri] } ?: android.net.Uri.decode(uri).substringAfterLast('/')

    private fun finish(uri: String, subsong: Int, lufs: Float?) {
        waiters[waiterKey(uri, subsong)]?.let { list ->
            synchronized(list) { list.forEach { it.complete(lufs) } }
        }
        if (lufs != null) onLoudness?.invoke(uri, subsong, lufs)
    }

    private companion object {
        /** Of a wait, what is left for the answer to come back in after the analysis stops. */
        const val AWAIT_MARGIN_MS = 250L
        const val MIN_BUDGET_MS = 200L

        /** Library files the service is given ahead of the one it is on - see [feedLibrary]. */
        const val LIBRARY_WINDOW = 8

        /** Deaths of the analysis process in one file before it is given up on - see [blame]. */
        const val STRIKES_WHEN_CRASHED = 2
        const val STRIKES_WHEN_TAKEN = 4

        /** The end of the analysis process's name: android:process of LoudnessService in the manifest. */
        const val PROCESS_SUFFIX = ":loudness"
        const val EXIT_REASONS_READ = 8
        const val EXIT_REASON_FRESH_MS = 10_000L
    }
}
