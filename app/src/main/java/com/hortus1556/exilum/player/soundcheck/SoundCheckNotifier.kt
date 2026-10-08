package com.hortus1556.exilum.player.soundcheck

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hortus1556.exilum.MainActivity
import com.hortus1556.exilum.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Sound Check's library analysis as a notification: how many files have been
 * analysed and how many are left, while it runs, and how many were done once
 * it has finished.
 *
 * Only for the library pass - the one started by turning Sound Check on,
 * which can take a long while on a big library. The few tracks analysed
 * ahead of playback are too quick to be worth telling anyone about.
 */
class SoundCheckNotifier(context: Context) {

    private val app = context.applicationContext
    private val manager = NotificationManagerCompat.from(app)

    /** Follows [progress] for as long as [scope] lives. */
    fun follow(progress: StateFlow<LoudnessAnalyzer.Progress>, scope: CoroutineScope) {
        scope.launch {
            // What a process before this one left up. The notification is the
            // system's once posted and outlives whoever posted it: the app
            // killed in the middle of a pass left "analysing 40 of 900" in the
            // shade with nothing analysing, and nothing here knew to take it
            // down - this starts out believing none is showing.
            manager.cancel(ID)
            var shown = false
            // A StateFlow hands a slow collector only the latest value, so the
            // pause below is all the throttling this needs.
            progress
                .collect { now ->
                    when {
                        now.running && now.total > 0 -> {
                            show(progressNotification(now))
                            shown = true
                        }
                        // It ran and finished: one last word, which goes away
                        // on its own.
                        shown && now.total > 0 && now.done >= now.total -> {
                            show(doneNotification(now.total))
                            shown = false
                        }
                        // Turned off halfway: nothing left to report.
                        shown -> {
                            manager.cancel(ID)
                            shown = false
                        }
                    }
                    // Android drops a burst of updates to one notification;
                    // a file analysed every few milliseconds would be one.
                    delay(UPDATE_INTERVAL_MS)
                }
        }
    }

    private fun progressNotification(progress: LoudnessAnalyzer.Progress) =
        builder()
            .setContentTitle(app.getString(R.string.soundcheck_notification_title))
            .setContentText(
                app.getString(
                    R.string.soundcheck_notification_progress,
                    progress.done,
                    (progress.total - progress.done).coerceAtLeast(0),
                )
            )
            .setProgress(progress.total, progress.done, false)
            // Not ongoing: there is no foreground service behind it, so if the
            // app dies it stays, and one that cannot be swiped away then stays
            // until the app is next opened.
            .setOnlyAlertOnce(true)
            .build()

    private fun doneNotification(total: Int) =
        builder()
            .setContentTitle(app.getString(R.string.soundcheck_notification_done_title))
            .setContentText(app.getString(R.string.soundcheck_notification_done, total))
            .setAutoCancel(true)
            .setTimeoutAfter(DONE_TIMEOUT_MS)
            .build()

    /** Opens the app; made once, where it was made again for every update. */
    private val openApp by lazy {
        PendingIntent.getActivity(
            app,
            0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private var channelReady = false

    private fun builder(): NotificationCompat.Builder {
        ensureChannel()
        return NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_soundcheck)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setContentIntent(openApp)
    }

    private fun show(notification: android.app.Notification) {
        // Nothing to do where notifications were refused; the settings screen
        // shows the same progress.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.notify(ID, notification)
    }

    private fun ensureChannel() {
        if (channelReady || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = app.getSystemService(NotificationManager::class.java) ?: return
        channelReady = true
        if (system.getNotificationChannel(CHANNEL) != null) return
        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                app.getString(R.string.soundcheck_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = app.getString(R.string.soundcheck_channel_desc)
                setShowBadge(false)
            }
        )
    }

    private companion object {
        const val CHANNEL = "sound_check"
        const val ID = 4201
        const val UPDATE_INTERVAL_MS = 500L
        const val DONE_TIMEOUT_MS = 15_000L
    }
}
