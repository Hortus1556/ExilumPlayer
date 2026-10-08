package com.hortus1556.exilum.data

import android.os.SystemClock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Says when Android has stopped letting the app read the music folder.
 *
 * A grant picked through the folder picker can be withdrawn under the app -
 * by a reinstall, by the system trimming persisted grants, by a provider that
 * renumbers its documents - and the library then fails file by file: a
 * directory that will not list, a module that will not open. Anything that
 * runs into that reports it here, and the UI tells the user once what to do
 * about it rather than leaving files quietly missing.
 */
class StorageAccessEvents {

    // Conflated and delivered once: a report made before anything is showing
    // - a scan at start-up - waits for the screen instead of being dropped,
    // and a second one queued behind it adds nothing.
    private val channel = Channel<Unit>(Channel.CONFLATED)
    private val lastReportAt = AtomicLong(0L)

    /** One emission per loss worth telling the user about. */
    val lost: Flow<Unit> = channel.receiveAsFlow()

    /**
     * Records that something could not be read for want of access.
     *
     * At most once a minute: a scan meeting a withdrawn grant fails on every
     * file it reaches, and one message says all there is to say.
     */
    fun reportLost() {
        val now = SystemClock.elapsedRealtime()
        val last = lastReportAt.get()
        if (last != 0L && now - last < QUIET_MS) return
        if (lastReportAt.compareAndSet(last, now)) channel.trySend(Unit)
    }

    private companion object {
        const val QUIET_MS = 60_000L
    }
}
