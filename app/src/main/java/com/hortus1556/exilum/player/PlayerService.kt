package com.hortus1556.exilum.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.hortus1556.exilum.MainActivity
import com.hortus1556.exilum.R
import com.hortus1556.exilum.db.AppPreferences
import com.hortus1556.exilum.db.dao.ModuleDao
import com.hortus1556.exilum.db.dao.PlaylistDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * [MediaLibraryService] that bridges [ModPlayer] to the Media3 session API.
 */
@OptIn(UnstableApi::class)
class PlayerService : MediaLibraryService() {

    private companion object {
        const val NOTIFICATION_ID = 669
        const val CHANNEL_ID = "669"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val player: ModPlayer by inject()
    private val prefs: AppPreferences by inject()
    private val playlistDao: PlaylistDao by inject()
    private val metadataDao: ModuleDao by inject()

    private lateinit var mediaLibrarySession: MediaLibrarySession

    override fun onCreate() {
        super.onCreate()

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaLibrarySession = MediaLibrarySession
            .Builder(this, player, AutoBrowseCallback(this, prefs, playlistDao, metadataDao, scope))
            .setId(getString(R.string.app_name) + "_session")
            .setSessionActivity(sessionActivity)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.app_name)
                .build()
        )

        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                Timber.e("Foreground service start not allowed")
            }
        })

        // Re-arms the collectors a previous onDestroy tore down - the player
        // outlives this service, its scope does not.
        player.attach()

        scope.launch(Dispatchers.Main) { player.restoreQueue() }
    }

    override fun onDestroy() {
        mediaLibrarySession.release()
        player.abandonAudioFocus()
        player.releaseEngine()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Asked to stop, and nothing more. The engine is let go of in
        // onDestroy, which is when the service really goes - and it does not
        // always: stopSelf leaves a service standing while something is bound
        // to it, a car for one. Released here as well, the player was left
        // detached inside a live service, and Play from the car then started
        // a track that never advanced, never saved its place and did not
        // pause when the headphones came out.
        if (!isPlaybackOngoing) stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaLibrarySession
}
