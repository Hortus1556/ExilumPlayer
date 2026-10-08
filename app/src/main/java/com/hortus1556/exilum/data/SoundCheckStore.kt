package com.hortus1556.exilum.data

import android.content.Context
import com.hortus1556.exilum.renderer.Player
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The loudness Sound Check has analysed, per file and subsong, and how much
 * of the track the figure covers.
 *
 * A figure from a whole track (or its first five minutes) is kept for good; a
 * partial one - a quick look taken because the track was about to play - only
 * until the whole track has been analysed. Also keeps the average over every
 * track analysed, which a track not yet analysed starts from, and which files
 * could not be analysed at all, so they are not tried again and again.
 *
 * Written by the main process only: the analysis runs in a process of its own
 * and reports back. A measurement is a number that can be taken again, so it
 * lives in a preferences file of its own rather than in the database or
 * tracker.conf.
 */
class SoundCheckStore(context: Context) {

    data class Measurement(val lufs: Float, val seconds: Float, val complete: Boolean)

    // Opened where it is first used - the analysis, off the main thread -
    // rather than as the app starts, when the whole file was read from disk
    // on the main thread before the first frame.
    private val app = context.applicationContext
    private val prefs by lazy { app.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    /**
     * Everything the file holds, read once: answers come from here, and a
     * change is made here at once and written to the file later, with the
     * others made since - see [changed].
     *
     * A preferences file is written whole, and this one has an entry for
     * every file and subsong in the library. Each result of the library pass
     * used to be an edit of its own, so every track analysed wrote all the
     * tracks analysed before it out again: megabytes at a time for a large
     * library, gigabytes over a pass - and a write still waiting when a
     * service was started or the app left was finished on the main thread,
     * which is where a tap on a file during the pass sometimes stalled.
     */
    private val entries: ConcurrentHashMap<String, Any> by lazy {
        ConcurrentHashMap<String, Any>().apply {
            for ((key, value) in prefs.all) if (value != null) put(key, value)
        }
    }

    /** The keys changed since the file was last written. */
    private val unwritten = HashSet<String>()
    private val writeQueued = AtomicBoolean()
    private val writer by lazy {
        Executors.newSingleThreadScheduledExecutor { Thread(it, "SoundCheckStore").apply { isDaemon = true } }
    }

    private fun key(uri: String, subsong: Int) = "$subsong|$uri"

    fun measurement(uri: String, subsong: Int): Measurement? {
        val parts = (entries[key(uri, subsong)] as? String)?.split(';') ?: return null
        val lufs = parts.getOrNull(0)?.toFloatOrNull() ?: return null
        return Measurement(
            lufs = lufs,
            seconds = parts.getOrNull(1)?.toFloatOrNull() ?: 0f,
            complete = parts.getOrNull(2) == "1",
        )
    }

    /** The loudness analysed for [uri] as [subsong], in LUFS, or null. */
    fun loudness(uri: String, subsong: Int): Float? = measurement(uri, subsong)?.lufs

    /** Whether [uri] as [subsong] has had its whole analysis - or could not be analysed. */
    fun isSettled(uri: String, subsong: Int): Boolean =
        measurement(uri, subsong)?.complete == true || entries.containsKey(FAILED + key(uri, subsong))

    /**
     * Keeps [lufs], analysed over [seconds] of [uri] as [subsong] - unless
     * what is kept already covers more of it.
     */
    fun save(uri: String, subsong: Int, lufs: Float, seconds: Float, complete: Boolean) {
        val previous = measurement(uri, subsong)
        if (previous != null && (previous.complete || previous.seconds >= seconds) && !complete) return
        keep(key(uri, subsong), "$lufs;$seconds;${if (complete) 1 else 0}")
        drop(FAILED + key(uri, subsong))
        drop(STRIKES + key(uri, subsong))
        if (previous == null) {
            // A running average that the most recent few hundred tracks make up.
            val count = entries[PRIOR_COUNT] as? Int ?: 0
            val weight = count.coerceAtMost(PRIOR_WINDOW - 1)
            val average = if (count == 0) lufs else ((entries[PRIOR] as? Float ?: lufs) * weight + lufs) / (weight + 1)
            keep(PRIOR, average)
            keep(PRIOR_COUNT, count + 1)
        }
    }

    /** [uri] as [subsong] could not be analysed: it is not tried again. */
    fun markFailed(uri: String, subsong: Int) {
        keep(FAILED + key(uri, subsong), true)
    }

    /** How many times the analysis process has died with [uri] as [subsong] in it. */
    fun strikes(uri: String, subsong: Int): Int = entries[STRIKES + key(uri, subsong)] as? Int ?: 0

    /**
     * The analysis process died with [uri] as [subsong] in it. Once is not
     * held against the file - Android takes that process away for reasons of
     * its own, whatever it is in the middle of. At [limit] times it is given
     * up on, as [markFailed].
     *
     * @return true when this was the time that gave it up.
     */
    fun strike(uri: String, subsong: Int, limit: Int): Boolean {
        val strikes = strikes(uri, subsong) + 1
        keep(STRIKES + key(uri, subsong), strikes)
        if (strikes < limit) return false
        markFailed(uri, subsong)
        return true
    }

    /** The average loudness of the tracks analysed so far, or Player.SOUND_CHECK_UNKNOWN. */
    fun prior(): Float =
        if ((entries[PRIOR_COUNT] as? Int ?: 0) > 0) {
            entries[PRIOR] as? Float ?: Player.SOUND_CHECK_UNKNOWN
        } else {
            Player.SOUND_CHECK_UNKNOWN
        }

    /** How many tracks have had their whole analysis. */
    fun completeCount(): Int = entries.values.count { it is String && it.endsWith(";1") }

    private fun keep(key: String, value: Any) {
        entries[key] = value
        changed(key)
    }

    private fun drop(key: String) {
        if (entries.remove(key) != null) changed(key)
    }

    /**
     * Notes that [key] is to be written, and sees that a write is on its way:
     * one for everything changed in the next [WRITE_DELAY_MS], rather than
     * one each. What is lost if the app dies before it is a few results,
     * which are measured again.
     */
    private fun changed(key: String) {
        synchronized(unwritten) { unwritten += key }
        if (writeQueued.compareAndSet(false, true)) {
            writer.schedule(Runnable { write() }, WRITE_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Writes what has changed, on [writer]'s thread and with commit: apply
     * leaves the write for Android to finish on the main thread if the app is
     * left, or a service started, before it is done.
     */
    private fun write() {
        writeQueued.set(false)
        val keys = synchronized(unwritten) { ArrayList(unwritten).also { unwritten.clear() } }
        if (keys.isEmpty()) return
        val editor = prefs.edit()
        for (key in keys) {
            when (val value = entries[key]) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                else -> editor.remove(key)
            }
        }
        editor.commit()
    }

    private companion object {
        const val FILE = "sound_check"
        // Keys that no file's can collide with: a file's start with its subsong number.
        const val PRIOR = " prior"
        const val PRIOR_COUNT = " prior_count"
        const val FAILED = " failed "
        const val STRIKES = " strikes "
        const val PRIOR_WINDOW = 200

        /** How long changes are gathered before they are written together. */
        const val WRITE_DELAY_MS = 30_000L
    }
}
