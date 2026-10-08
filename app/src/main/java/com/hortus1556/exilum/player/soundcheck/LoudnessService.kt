package com.hortus1556.exilum.player.soundcheck

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import com.hortus1556.exilum.renderer.Player
import com.hortus1556.exilum.renderer.model.ModVars
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Where Sound Check's analysis runs: a process of its own (":loudness" in the
 * manifest), because it loads each file into the same decoders playback uses
 * and a process has one of each. Here it can play a whole track through as
 * fast as the phone goes, while another plays in the app, and a decoder that
 * falls over takes down this process and nothing else.
 *
 * Driven by [LoudnessAnalyzer] over a Messenger: it asks for (file, subsong)
 * analyses at a priority and gets each result back. One worker thread takes
 * them highest priority first - the track about to play, then the rest of
 * the playing one, the next in the queue, and the library last - at
 * background priority unless something is waiting on it.
 */
class LoudnessService : Service() {

    private class Job(
        val uri: String,
        val subsong: Int,
        val priority: Int,
        /**
         * When whoever is waiting on this stops waiting, on the uptime clock
         * both processes read; 0 for a job analysed whole.
         */
        val deadline: Long,
        val allSubsongs: Boolean,
        val replyTo: Messenger?,
        val order: Long,
    ) : Comparable<Job> {
        val key get() = LoudnessProtocol.key(uri, subsong, deadline > 0)

        @Volatile
        var cancelled = false

        override fun compareTo(other: Job): Int =
            compareValuesBy(this, other, { it.priority }, { it.order })
    }

    private val order = AtomicLong()
    private val queue = PriorityBlockingQueue<Job>()

    /** Queued jobs by key, so asking again at a higher priority moves a job up rather than doubling it. */
    private val queued = HashMap<String, Job>()

    /** The job the worker is on, for a cancel to reach - it is no longer in [queued]. */
    @Volatile
    private var current: Job? = null

    private lateinit var inbox: HandlerThread
    private lateinit var messenger: Messenger
    private lateinit var worker: Thread

    override fun onCreate() {
        super.onCreate()
        inbox = HandlerThread("LoudnessInbox").apply { start() }
        messenger = Messenger(Handler(inbox.looper) { message -> receive(message); true })
        worker = Thread(::work, "LoudnessWorker").apply {
            isDaemon = true
            start()
        }
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        inbox.quitSafely()
        worker.interrupt()
        super.onDestroy()
    }

    private fun receive(message: Message) {
        when (message.what) {
            LoudnessProtocol.MSG_ANALYZE -> {
                val data = message.data ?: return
                val uri = data.getString(LoudnessProtocol.KEY_URI) ?: return
                enqueue(
                    Job(
                        uri = uri,
                        subsong = data.getInt(LoudnessProtocol.KEY_SUBSONG),
                        priority = data.getInt(LoudnessProtocol.KEY_PRIORITY),
                        deadline = data.getLong(LoudnessProtocol.KEY_DEADLINE),
                        allSubsongs = data.getBoolean(LoudnessProtocol.KEY_ALL_SUBSONGS),
                        replyTo = message.replyTo,
                        order = order.incrementAndGet(),
                    )
                )
            }

            LoudnessProtocol.MSG_CANCEL_BELOW -> {
                val priority = message.arg1
                synchronized(queued) {
                    queued.values.filter { it.priority >= priority }.forEach { it.cancelled = true }
                    queued.values.removeAll { it.cancelled }
                }
                // The one being analysed too. It cannot be stopped where it
                // is, but it was out of reach of this altogether, and went on
                // to queue every other subsong of its file after Sound Check
                // had been turned off.
                current?.takeIf { it.priority >= priority }?.cancelled = true
            }
        }
    }

    private fun enqueue(job: Job) {
        synchronized(queued) {
            val existing = queued[job.key]
            if (existing != null && !existing.cancelled) {
                if (existing.priority <= job.priority) return
                existing.cancelled = true
            }
            queued[job.key] = job
        }
        queue.put(job)
    }

    private fun work() {
        while (!Thread.currentThread().isInterrupted) {
            val job = try {
                queue.take()
            } catch (interrupted: InterruptedException) {
                return
            }
            synchronized(queued) {
                if (job.cancelled) return@synchronized
                queued.remove(job.key)
            }
            if (job.cancelled) continue
            Process.setThreadPriority(
                if (job.priority <= LoudnessProtocol.PRIORITY_CURRENT) {
                    Process.THREAD_PRIORITY_DEFAULT
                } else {
                    Process.THREAD_PRIORITY_BACKGROUND
                }
            )
            current = job
            analyze(job)
            current = null
        }
    }

    private fun analyze(job: Job) {
        // What is left of the wait, now that the job's turn has come. The
        // whole of it used to be spent from here, however long the job had
        // sat behind another: a track tapped while a library file was being
        // analysed was measured for two seconds after its listener had
        // stopped waiting, and started late and at the wrong level for it.
        val budgetMs = if (job.deadline > 0) (job.deadline - SystemClock.uptimeMillis()).toInt() else 0
        if (job.deadline > 0 && budgetMs <= 0) {
            // None: said at once, so nobody waits out the rest for it. The
            // whole analysis of the track is queued behind this as its own job.
            reply(job.replyTo, LoudnessProtocol.MSG_RESULT, Bundle().apply {
                putString(LoudnessProtocol.KEY_URI, job.uri)
                putInt(LoudnessProtocol.KEY_SUBSONG, job.subsong)
                putInt(LoudnessProtocol.KEY_ERROR, 0)
                putFloat(LoudnessProtocol.KEY_LUFS, Player.SOUND_CHECK_UNKNOWN)
                putFloat(LoudnessProtocol.KEY_SECONDS, 0f)
                putBoolean(LoudnessProtocol.KEY_COMPLETE, false)
                putInt(LoudnessProtocol.KEY_SUBSONGS, 1)
            })
            return
        }

        reply(job.replyTo, LoudnessProtocol.MSG_STARTED, Bundle().apply {
            putString(LoudnessProtocol.KEY_URI, job.uri)
            putInt(LoudnessProtocol.KEY_SUBSONG, job.subsong)
        })

        val out = FloatArray(Player.LOUDNESS_RESULTS)
        val started = System.nanoTime()
        val result = runCatching {
            Player.analyzeLoudness(
                context = this,
                uri = Uri.parse(job.uri),
                subsong = job.subsong,
                maxSeconds = LoudnessProtocol.MAX_SECONDS,
                budgetMs = budgetMs,
                out = out,
            )
        }.getOrElse {
            Log.w(TAG, "analysis threw for ${job.uri}", it)
            Int.MIN_VALUE
        }

        var subsongs = 1
        if (result == 0) {
            subsongs = runCatching { ModVars().also { Player.getModVars(it) }.miNumSequences }.getOrDefault(1)
        }
        runCatching { Player.releaseModule() }

        val complete = result == 0 && (out[3] != 0f || out[1] >= LoudnessProtocol.MAX_SECONDS - 1)
        Log.i(
            TAG,
            "analysed ${job.uri} #${job.subsong}: result $result, ${out[0]} LUFS over ${out[1]} s" +
                " (${out[2]} s gated), complete $complete, in ${(System.nanoTime() - started) / 1_000_000} ms"
        )
        reply(job.replyTo, LoudnessProtocol.MSG_RESULT, Bundle().apply {
            putString(LoudnessProtocol.KEY_URI, job.uri)
            putInt(LoudnessProtocol.KEY_SUBSONG, job.subsong)
            putInt(LoudnessProtocol.KEY_ERROR, result)
            putFloat(LoudnessProtocol.KEY_LUFS, out[0])
            putFloat(LoudnessProtocol.KEY_SECONDS, out[1])
            putBoolean(LoudnessProtocol.KEY_COMPLETE, complete)
            putInt(LoudnessProtocol.KEY_SUBSONGS, subsongs)
        })

        // The file's other subsongs, at the same priority, once its first is
        // known - unless the job was cancelled while it ran.
        if (job.allSubsongs && result == 0 && !job.cancelled) {
            for (subsong in 1 until subsongs.coerceAtMost(LoudnessProtocol.MAX_SUBSONGS)) {
                enqueue(
                    Job(job.uri, subsong, job.priority, 0L, false, job.replyTo, order.incrementAndGet())
                )
            }
        }
    }

    private fun reply(to: Messenger?, what: Int, data: Bundle) {
        if (to == null) return
        try {
            to.send(Message.obtain(null, what).apply { this.data = data })
        } catch (gone: RemoteException) {
            // The app went away; what was analysed is simply not kept.
        }
    }

    private companion object {
        const val TAG = "Exilum Loudness"
    }
}

/** What [LoudnessService] and [LoudnessAnalyzer] say to each other. */
internal object LoudnessProtocol {
    const val MSG_ANALYZE = 1
    const val MSG_CANCEL_BELOW = 2
    const val MSG_STARTED = 10
    const val MSG_RESULT = 11

    const val KEY_URI = "uri"
    const val KEY_SUBSONG = "subsong"
    const val KEY_PRIORITY = "priority"
    /** For a job someone is waiting on: when they stop, as SystemClock.uptimeMillis; 0 otherwise. */
    const val KEY_DEADLINE = "deadline"
    const val KEY_ALL_SUBSONGS = "all"
    const val KEY_ERROR = "error"
    const val KEY_LUFS = "lufs"
    const val KEY_SECONDS = "seconds"
    const val KEY_COMPLETE = "complete"
    const val KEY_SUBSONGS = "subsongs"

    /** The track about to play, with a time budget: someone is waiting. */
    const val PRIORITY_URGENT = 0
    /** The rest of the track playing, or its other subsongs. */
    const val PRIORITY_CURRENT = 1
    /** The next tracks in the queue. */
    const val PRIORITY_AHEAD = 2
    /** The rest of the library. */
    const val PRIORITY_LIBRARY = 3

    /** How much of a track is analysed: its first five minutes stand for it. */
    const val MAX_SECONDS = 300

    /** Subsongs of one file analysed in the background at most. */
    const val MAX_SUBSONGS = 64

    fun key(uri: String, subsong: Int, quick: Boolean) = "$subsong|${if (quick) "q" else "f"}|$uri"
}
