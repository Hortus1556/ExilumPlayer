package com.hortus1556.exilum

import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.liquid.LocalLiquidOverlayHost
import com.hortus1556.exilum.ui.liquid.LiquidOverlayLayer
import com.hortus1556.exilum.ui.liquid.LiquidOverlayHost
import com.hortus1556.exilum.ui.liquid.LocalLiquidZoomSource
import com.hortus1556.exilum.ui.liquid.LiquidZoomSource
import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.hortus1556.exilum.db.entity.ModuleEntity
import com.hortus1556.exilum.player.PlayerService
import com.hortus1556.exilum.player.PlayerViewModel
import com.hortus1556.exilum.ui.MainNavigation
import com.hortus1556.exilum.ui.screens.player.PlayerScreen
import com.hortus1556.exilum.ui.theme.AppTheme
import com.hortus1556.exilum.util.requestNotificationPermission
import com.hortus1556.exilum.util.requestWriteStoragePermission
import com.hortus1556.exilum.util.setEdgeToEdgeConfig
import com.hortus1556.exilum.util.toModuleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinViewModel
import timber.log.Timber

class MainActivity : ComponentActivity() {

    private val pendingPlayerModule = MutableStateFlow<ModuleEntity?>(null)

    /** The file another app last asked for, while its name is still being looked up. */
    private var viewIntentLookup: Job? = null

    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val notificationPermission = registerForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        callback = { /* No-Op */ }
    )

    private val storagePermission = registerForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        callback = { /* No-Op */ }
    )

    override fun onStart() {
        super.onStart()
        if (controllerFuture != null) return
        val sessionToken = SessionToken(
            this,
            ComponentName(this, PlayerService::class.java)
        )
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    /**
     * Handles an [Intent.ACTION_VIEW] intent from an external app (e.g. a file manager).
     * Converts the URI to a [ModuleEntity] and queues it for navigation to the player screen.
     */
    private fun handleViewIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        Timber.d("Handle Intent Uri: $uri")
        // The name and the size are asked of the other app's provider, which
        // answers when it gets round to it: off the main thread, then, where
        // waiting for it used to hold up the first frame. A file opened while
        // an earlier one is still being asked about takes its place, instead
        // of the two landing in whichever order their providers answered.
        viewIntentLookup?.cancel()
        viewIntentLookup = lifecycleScope.launch {
            val module = withContext(Dispatchers.IO) { uri.toModuleEntity(applicationContext) }
                ?: return@launch
            pendingPlayerModule.value = module
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setEdgeToEdgeConfig()

        requestNotificationPermission {
            @SuppressLint("InlinedApi")
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestWriteStoragePermission {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        // Only for an activity being started, and started by that intent. One
        // built again - a rotation, a fold, dark mode coming on - is handed
        // the same intent, and so is a task brought back from Recents after
        // it was left: playing it each time restarted the tune from 0:00,
        // reopened the player and replaced whatever queue was playing by then
        // with that one file.
        val fromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !fromHistory) handleViewIntent(intent)

        setContent {
            AppTheme {
                // The player is a layer over the main screen, not a
                // destination navigated to.
                //
                // It used to be a NavDisplay entry, and that is what made the
                // back arrow and the back gesture behave differently no matter
                // what was tried: they arrive on separate channels. The gesture
                // reaches NavDisplay through a NavigationEventDispatcher, the
                // arrow can only edit the back stack, and the API that would
                // let one imitate the other is internal to the library. As a
                // layer there is one back handler and one animation, and the
                // two cannot diverge because there is nothing left to diverge.
                // Where the player zooms out of under Liquid Glass: the mini
                // player's glass, which the tab bar and the player both see.
                val liquidZoom = remember { LiquidZoomSource() }
                var playerOpen by rememberSaveable { mutableStateOf(false) }
                var playerClosing by remember { mutableStateOf(false) }
                var playerCovers by remember { mutableStateOf(false) }

                val playerViewModel = koinViewModel<PlayerViewModel>(
                    viewModelStoreOwner = LocalActivity.current as ComponentActivity
                )

                LaunchedEffect(Unit) {
                    pendingPlayerModule.collect { module ->
                        if (module != null) {
                            playerViewModel.play(module)
                            playerOpen = true
                            playerClosing = false
                            pendingPlayerModule.value = null
                        }
                    }
                }

                // Back for everything under the player, switched off while the
                // player is up. Which handler answers back is decided by the
                // order they were added, not by what is on top: after a
                // rotation with the player open, the player's handler is added
                // before the ones in the tabs underneath (those are composed
                // later, during layout), and back went up a folder in a browser
                // nobody could see instead of closing the player. Back on again
                // as soon as the player starts folding away and stops taking
                // it, so a second press during the fold is not left to the
                // system, which would close the app.
                val mainBack = rememberNavigationEventDispatcherOwner(
                    enabled = !playerOpen || playerClosing,
                )

                // Where alerts and menus are drawn: over the whole app,
                // inside its own window, so their glass has the app itself
                // to bend. See LiquidOverlay.
                val liquid = isLiquid
                val overlays = remember { LiquidOverlayHost() }
                // Out of Liquid Glass nothing draws these windows, and every
                // one of them has gone to its own Dialog or away: nothing on
                // the stack is anyone's any more.
                LaunchedEffect(liquid) { if (!liquid) overlays.clear() }
                val appBackdrop = rememberLayerBackdrop()
                CompositionLocalProvider(
                    LocalLiquidZoomSource provides liquidZoom,
                    LocalLiquidOverlayHost provides if (liquid) overlays else null,
                ) {
                Box(modifier = Modifier.fillMaxSize()) {
                // Recorded only while a window is open to bend it: the rest
                // of the time nothing reads it, and keeping it would record
                // the whole app a second time on every frame anything moved.
                val recording = liquid && overlays.entries.isNotEmpty()
                Box(
                    modifier = if (recording) {
                        Modifier.fillMaxSize().layerBackdrop(appBackdrop)
                    } else {
                        Modifier.fillMaxSize()
                    }
                ) {
                    // Kept composed underneath, but not drawn while the
                    // player actually covers it - so folding either way
                    // uncovers the real screen rather than a blank, and
                    // nothing is drawn twice while the player is up. Skipping
                    // the draw rather than the composition is what keeps that
                    // cheap: there is no rebuild on the way back.
                    Box(
                        modifier = Modifier.drawWithContent {
                            if (!playerCovers) drawContent()
                        }
                    ) {
                        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides mainBack) {
                            MainNavigation(
                                onNavigateToPlayer = {
                                    playerClosing = false
                                    playerOpen = true
                                },
                                onBack = ::finish,
                                // For the mini player underneath, which stops
                                // its playback line while it cannot be seen.
                                covered = { playerCovers },
                            )
                        }
                    }

                    if (playerOpen) {
                        PlayerScreen(
                            closing = playerClosing,
                            onCloseRequest = { playerClosing = true },
                            onClosed = {
                                playerOpen = false
                                playerClosing = false
                                playerCovers = false
                            },
                            onCoversScreen = { playerCovers = it },
                        )
                    }
                    }
                    if (liquid) LiquidOverlayLayer(host = overlays, backdrop = appBackdrop)
                }
                }
            }
        }
    }
}
