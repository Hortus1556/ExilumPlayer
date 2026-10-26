package com.hortus1556.exilum.ui.screens.player

import androidx.compose.material.icons.filled.Lyrics
import com.hortus1556.exilum.ui.screens.player.components.views.LyricsView
import com.hortus1556.exilum.ui.components.isWideWindow
import com.hortus1556.exilum.ui.liquid.LiquidPane
import androidx.compose.foundation.layout.ColumnScope
import com.kyant.backdrop.Backdrop
import androidx.compose.foundation.layout.navigationBarsPadding
import com.hortus1556.exilum.ui.liquid.LiquidRefraction
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import com.kyant.shapes.Capsule
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.shadow.Shadow
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.drawBackdrop
import com.hortus1556.exilum.ui.liquid.stretchWith
import com.hortus1556.exilum.ui.liquid.LiquidDragAnimation
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.ui.util.lerp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.staticCompositionLocalOf
import com.hortus1556.exilum.ui.liquid.LiquidPopIn
import com.hortus1556.exilum.ui.liquid.LocalLiquidZoomSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import com.hortus1556.exilum.ui.liquid.LiquidSheetShape
import com.hortus1556.exilum.ui.liquid.frost
import com.hortus1556.exilum.ui.liquid.liquidBarGlass
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.AnimatedContent
import com.hortus1556.exilum.ui.liquid.rememberLiquidPresence
import com.hortus1556.exilum.ui.liquid.nowPlayingDrop
import com.hortus1556.exilum.ui.liquid.nowPlayingGlass
import com.hortus1556.exilum.ui.liquid.liquidPop
import com.hortus1556.exilum.ui.liquid.LocalLiquidPageMotion
import com.hortus1556.exilum.ui.liquid.LocalLiquidBackdrop
import com.hortus1556.exilum.ui.liquid.LiquidPageMotion
import com.hortus1556.exilum.ui.liquid.LiquidGlassCluster
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.hortus1556.exilum.ui.theme.DarkScreenSystemBars
import kotlin.math.PI
import kotlin.math.sin
import com.hortus1556.exilum.ui.liquid.liquidGlassPlain
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.liquid.liquidColorScheme
import com.hortus1556.exilum.ui.liquid.LocalLiquidColors
import com.hortus1556.exilum.ui.liquid.LiquidType
import com.hortus1556.exilum.ui.liquid.LiquidGlassButton
import com.hortus1556.exilum.ui.liquid.LiquidDark
import com.hortus1556.exilum.ui.liquid.ContinuousShape
import com.hortus1556.exilum.ui.liquid.CapsuleShape
import com.hortus1556.exilum.ui.oneui.LocalTrackLook
import com.hortus1556.exilum.ui.oneui.trackLook
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import com.hortus1556.exilum.ui.oneui.LocalOneUiColors
import com.hortus1556.exilum.ui.oneui.OneUiDimens
import com.hortus1556.exilum.ui.oneui.OneUiTopAppBar
import com.hortus1556.exilum.ui.theme.isOneUi
import kotlin.math.roundToInt
import com.hortus1556.exilum.ui.components.AppAlertDialog
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlin.math.abs
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.SheetValue.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hortus1556.exilum.R
import com.hortus1556.exilum.db.entity.ModuleEntity
import com.hortus1556.exilum.player.PlaybackStatus
import com.hortus1556.exilum.player.PlayerUiState
import com.hortus1556.exilum.player.PlayerViewModel
import com.hortus1556.exilum.player.model.PatternData
import com.hortus1556.exilum.data.LoopPreset
import com.hortus1556.exilum.data.defaultSubsongLabel
import com.hortus1556.exilum.data.loopPresetCode
import com.hortus1556.exilum.data.subsongKey
import com.hortus1556.exilum.ui.components.BackButton
import com.hortus1556.exilum.ui.components.PlaylistPickerDialog
import com.hortus1556.exilum.ui.components.RenameSubsongDialog
import com.hortus1556.exilum.ui.screens.player.components.ChipList
import com.hortus1556.exilum.ui.screens.player.components.DurationsSheet
import com.hortus1556.exilum.ui.screens.player.components.OverridesSheet
import com.hortus1556.exilum.ui.screens.player.components.PatternInfoRow
import com.hortus1556.exilum.ui.screens.player.components.SubsongRow
import com.hortus1556.exilum.ui.screens.player.components.PlaybackProgress
import com.hortus1556.exilum.ui.screens.player.components.ScopeGridIcon
import com.hortus1556.exilum.ui.screens.player.components.PlayerBottomAppBar
import com.hortus1556.exilum.ui.screens.player.components.QueueSheet
import com.hortus1556.exilum.ui.screens.player.components.TransportRow
import com.hortus1556.exilum.ui.screens.player.components.views.ChannelView
import com.hortus1556.exilum.ui.screens.player.components.views.DebugView
import com.hortus1556.exilum.player.SoundCheckStatus
import com.hortus1556.exilum.ui.screens.player.components.views.PatternView
import com.hortus1556.exilum.ui.screens.player.components.views.SpectrumView
import com.hortus1556.exilum.ui.theme.AppTheme
import com.hortus1556.exilum.renderer.model.ModVars
import com.hortus1556.exilum.renderer.model.Sequence
import kotlin.time.Duration.Companion.seconds
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinViewModel

private sealed class PlayerAction {
    data class OnDurationClick(val int: Int) : PlayerAction()
    data class OnDurationSheet(val open: Boolean) : PlayerAction()
    data class OnQueueClick(val int: Int) : PlayerAction()
    data class OnQueueSheet(val open: Boolean) : PlayerAction()
    data class OnSeek(val seek: Long) : PlayerAction()
    data object OnAllSequences : PlayerAction()
    data object OnAudioInfo : PlayerAction()
    data object OnBack : PlayerAction()
    data class OnControls(val expanded: Boolean) : PlayerAction()
    data class OnScopesOnly(val tiles: Boolean) : PlayerAction()
    data object OnFavourite : PlayerAction()
    data class OnGlobalPitch(val semitones: Int) : PlayerAction()
    data class OnGlobalTempo(val percent: Int) : PlayerAction()
    data object OnAddToPlaylist : PlayerAction()
    data object OnOverrides : PlayerAction()
    data class OnPlayLoopPreset(val preset: LoopPreset) : PlayerAction()
    data class OnRenameSubsong(val index: Int) : PlayerAction()
    data object OnLoop : PlayerAction()
    data object OnNext : PlayerAction()
    data object OnPlayPause : PlayerAction()
    /** The pager has settled on a view: [index] is its PlayerPage ordinal. */
    data class OnPlayerView(val index: Int) : PlayerAction()
    data object OnPrevious : PlayerAction()
    data object OnShuffle : PlayerAction()
    data object OnStop : PlayerAction()
    data class OnToggleMute(val channel: Int) : PlayerAction()
    data class OnSolo(val channel: Int, val numChannels: Int) : PlayerAction()
}

/**
 * The pages the player can swipe between.
 *
 * A list rather than a count, because which of them exist depends on the
 * format. A chip log has no patterns, so PatternView is left out entirely
 * rather than shown empty - and the pages after it shift up, which is why
 * pages are addressed by identity here and only turned into indices at the
 * pager.
 */
private enum class PlayerPage { Pattern, Channel, Spectrum, Lyrics, Debug }

/** How long the screen takes to fold down into the mini player on the way out. */
private const val PLAYER_CLOSE_MS = 280

/** ...and to unfold back out of it. */
private const val PLAYER_OPEN_MS = 280

/** How much faster the contents fade than the screen collapses. */
private const val PLAYER_FADE_LEAD = 1.6f

/** Roughly the mini player's height - where the collapse stops instead of at nothing. */
private val MiniPlayerHeight = 88.dp

/**
 * Height of the grab strip.
 *
 * Taller than the bar it decorates needs to be, because it is the only way in
 * and out of the other two views and the seek bar sits directly under it -
 * missing it by a few pixels did not do nothing, it dragged the playback
 * position. Grown honestly, in the layout, rather than by hanging an
 * oversized touch area off a smaller strip: that took the handle's measured
 * height with it and left nothing to see.
 */
private val HANDLE_STRIP_HEIGHT = 28.dp
private val HANDLE_THICKNESS = 4.dp
private val HANDLE_WIDTH = 36.dp
private val HANDLE_WIDTH_ACTIVE = 52.dp

/**
 * The same, for a window with the room to spare - see [isWideWindow].
 *
 * Shorter than the narrow strip rather than taller, even though there is more
 * room: the wide bar is meant to be compact, and the grip stays wide enough to
 * find without the band around it being deep.
 */
private val HANDLE_STRIP_HEIGHT_WIDE = 20.dp
private val HANDLE_WIDTH_WIDE = 72.dp
private val HANDLE_WIDTH_WIDE_ACTIVE = 96.dp

private val HANDLE_THICKNESS_ACTIVE = 7.dp
private val HANDLE_GROWTH = HANDLE_THICKNESS_ACTIVE - HANDLE_THICKNESS

/** A page offset closer to a page than this counts as settled on it. */
private const val PAGE_SETTLE_EPSILON = 0.001f

/** How far the handle must be pulled up before the controls come back. */
private const val HANDLE_PULL_THRESHOLD = 40f

/** ...or how fast it has to be flicked, for a drag too short to reach that. */
private const val HANDLE_FLING_VELOCITY = 300f

/** Pulling down is the one worth reaching for, so it asks for less. */
private const val HANDLE_PULL_THRESHOLD_DOWN = 24f
private const val HANDLE_FLING_VELOCITY_DOWN = 190f

/** Movement under this is not a direction yet, just a finger settling. */
private const val HANDLE_DIRECTION_EPSILON = 1.5f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    closing: Boolean,
    onCloseRequest: () -> Unit,
    onClosed: () -> Unit,
    modifier: Modifier = Modifier,
    onCoversScreen: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val snackBarHostState = remember { SnackbarHostState() }
    val viewModel = koinViewModel<PlayerViewModel>(
        viewModelStoreOwner = LocalActivity.current as ComponentActivity
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isFavourite by viewModel.isFavourite.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val subsongNames by viewModel.subsongNames.collectAsStateWithLifecycle()
    // Not delegated with `by`, and read only by the two sheets that show it.
    // A slider in the overrides sheet writes this ten times a second while it
    // is dragged; read in this body, every one of those rebuilt the screen
    // lying under the sheet.
    val fileOverrides = viewModel.fileOverrides.collectAsStateWithLifecycle()
    // Not delegated with `by`: the views read it inside their draw passes, so a
    // mute redraws them rather than recomposing this whole screen.
    val channelMutes = viewModel.channelMutes.collectAsStateWithLifecycle()

    // 1 is folded down onto the mini player, 0 is open. Both directions run
    // off this one value, and every way of leaving - the arrow, the back
    // gesture, playback stopping - goes through [onCloseRequest], so none of
    // them can animate differently from the others.
    val fold = remember { Animatable(1f) }
    val miniPlayerPx = with(LocalDensity.current) { MiniPlayerHeight.toPx() }
    // Under Liquid Glass the player is iOS's Now Playing sheet: it rises from
    // the bottom on a spring and can be pulled back down by its top.
    val liquid = isLiquid
    // Whether the sheet's glass buttons are up: they pop in as it rises past
    // half way, stay while it is being pulled about, and pop away as it goes.
    var sheetShown by remember { mutableStateOf(false) }

    BackHandler(enabled = !closing) { onCloseRequest() }

    LaunchedEffect(closing) {
        if (closing) {
            // Announced before the fold, not after: the moment this starts
            // shrinking there is screen behind it to see again.
            onCoversScreen(false)
            sheetShown = false
            // Seen through to the end, whatever takes the fold away on the
            // way. A finger already pulling the sheet when the close began -
            // the last track ending under it, or Back pressed with the other
            // hand - moves this same value, and an animation interrupted like
            // that throws. Uncaught, it ended this effect before onClosed
            // with nothing to run it again: the player was left where the
            // finger let go, closing for ever, with Back and its own button
            // both switched off. Only the interruption is swallowed; if this
            // effect itself is cancelled, ensureActive passes that on.
            var folded = false
            while (!folded) {
                try {
                    fold.animateTo(
                        1f,
                        if (liquid) spring(dampingRatio = 1f, stiffness = 320f) else tween(PLAYER_CLOSE_MS),
                    )
                    folded = true
                } catch (interrupted: CancellationException) {
                    currentCoroutineContext().ensureActive()
                }
            }
            onClosed()
        } else {
            launch {
                snapshotFlow { fold.value }.first { it < 0.5f }
                sheetShown = true
            }
            fold.animateTo(0f, if (liquid) spring(dampingRatio = 0.86f, stiffness = 260f) else tween(PLAYER_OPEN_MS))
            onCoversScreen(true)
        }
    }
    // The sheet's height, for how far a pull has taken it; and the band at
    // its top a pull can start from - the grabber and the bar.
    var sheetHeight by remember { mutableFloatStateOf(1f) }
    val grabBand = with(LocalDensity.current) {
        WindowInsets.statusBars.getTop(this) + 64.dp.toPx()
    }
    val closingNow by rememberUpdatedState(closing)
    val zoomSource = LocalLiquidZoomSource.current
    val density = LocalDensity.current
    val sheetPull = if (liquid) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (closingNow || fold.value > 0.02f || down.position.y > grabBand) return@awaitEachGesture
                val tracker = VelocityTracker()
                var pulled = 0f
                var pulling = false
                while (true) {
                    val event = awaitPointerEvent()
                    // A close that began with the finger still down has the
                    // fold from here on: no more of the pull is followed, and
                    // letting go neither asks again nor springs the sheet
                    // back open against it.
                    if (closingNow) return@awaitEachGesture
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    tracker.addPosition(change.uptimeMillis, change.position)
                    val dy = change.positionChange().y
                    pulled += dy
                    if (!pulling && pulled > viewConfiguration.touchSlop) {
                        pulling = true
                        // What is behind the sheet shows as it comes down.
                        onCoversScreen(false)
                    }
                    if (pulling) {
                        change.consume()
                        val to = (fold.value + dy / sheetHeight).coerceIn(0f, 1f)
                        scope.launch { fold.snapTo(to) }
                    }
                }
                if (pulling) {
                    val velocity = tracker.calculateVelocity().y
                    if (fold.value > 0.25f || velocity > 1400f) {
                        onCloseRequest()
                    } else {
                        scope.launch {
                            fold.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 420f))
                            onCoversScreen(true)
                        }
                    }
                }
            }
        }
    } else {
        Modifier
    }

    val queueSheetState = rememberBottomSheetState(
        initialValue = Hidden,
        enabledValues = setOf(Hidden, Expanded)
    )
    val durationsSheetState = rememberBottomSheetState(
        initialValue = Hidden,
        enabledValues = setOf(Hidden, Expanded)
    )

    // One read of live playback state per display frame, shared by every
    // visualiser on this screen - see PlaybackVisuals.
    //
    // Stopped entirely while nothing is playing or a sheet covers the view:
    // there is no point requesting display frames to redraw something that
    // cannot change or cannot be seen. Scope traces are the only expensive
    // part of the read (they walk each channel's sample data), so they are
    // only asked for while the view that draws them is the one on screen.
    val viewCovered = queueSheetState.isVisible || durationsSheetState.isVisible
    // Whether the view the pager has settled on is the channel view, the only
    // one with traces. Kept here, as the pager reports it, and not read back
    // from a stored setting as it was: between two files the list of views
    // passes for a moment through another file's, the pager settles there
    // and comes back, and the store - catching up a write behind - was left
    // on the view passed through, with the channels on screen and no traces.
    var channelsShown by remember { mutableStateOf(false) }
    val visuals = rememberPlaybackVisuals(
        active = state.status == PlaybackStatus.PLAYING && !viewCovered,
        withScopes = channelsShown,
    )

    var showQueue by remember { mutableStateOf(false) }
    var showDurations by remember { mutableStateOf(false) }
    // Saved with the screen, because whether the screen is open is. Android
    // can drop the app while it sits in the background with the player up;
    // coming back, the player was restored open over a process with nothing
    // loaded and this flag back at false - so the one thing that closes an
    // empty player (below) never ran, and what was left was a blank screen
    // over the whole app that only Back got rid of. Remembered across that,
    // it closes at once.
    //
    // At once is also before the service has had its say. With auto-resume
    // on, the last queue is on its way back by then - read from disk a moment
    // after this screen is first drawn - and the player closes ahead of it
    // all the same: the tune comes back in the mini player, not in here.
    // Nothing this screen can read tells a queue still on its way from one
    // that is not coming, and of the two a player to open again is the one
    // to live with.
    var hasLoadedOnce by rememberSaveable { mutableStateOf(false) }
    var showAudioInfo by remember { mutableStateOf(false) }
    var audioInfoText by remember { mutableStateOf("") }

    // derivedStateOf so this (and PlayerScreen's own recomposition) only reacts
    // when the pattern index itself changes, not on every row.
    val patternIndex by remember(visuals) {
        derivedStateOf {
            @Suppress("UNUSED_EXPRESSION") visuals.positionTick
            visuals.snapshot.pattern
        }
    }
    val channels = state.modVars.chn
    // Fetched off the main thread: on a cache miss this makes many JNI calls
    // guarded by the same native mutex the audio render callback and engine
    // teardown use. Called inline on the main thread, a stall on that mutex
    // (e.g. libopenmpt/Oboe teardown after an audio-route disconnect, such as
    // screen recording forcing a routing change) freezes the whole UI, since
    // Compose recomposition and this call share the same thread.
    //
    // Fetched again for another file as well as for another pattern, which is
    // what the whole of modVars is a key for. The channel count alone stood
    // there once, and it tells two files apart only when they differ in it: a
    // folder of four-channel modules skipped through, each caught still in its
    // first pattern, changed neither key - so the grid went on showing the
    // last song's pattern under the new song's moving row until the new song
    // reached another. modVars is the new file's as soon as the engine has
    // loaded it, which the queue's current entry is not.
    var patternData by remember { mutableStateOf(PatternData()) }
    LaunchedEffect(patternIndex, state.modVars) {
        patternData = if (patternIndex < 0 || channels == 0) {
            PatternData()
        } else {
            withContext(Dispatchers.Default) { viewModel.getPatternData(patternIndex) }
        }
    }

    LaunchedEffect(state.currentModule) {
        if (state.currentModule != null) hasLoadedOnce = true
        if (hasLoadedOnce && state.currentModule == null) onCloseRequest()
    }

    LaunchedEffect(showAudioInfo) {
        while (isActive && showAudioInfo) {
            audioInfoText = viewModel.getAudioStats()
            delay(3.seconds)
        }
    }

    if (showAudioInfo) {
        PlayerAlertDialog(
            onDismissRequest = { showAudioInfo = false },
            icon = Icons.Default.Info,
            title = stringResource(R.string.dialog_title_audio_info),
            content = { Text(text = audioInfoText) },
        )
    }

    var showPlaylistPicker by remember { mutableStateOf(false) }
    if (showPlaylistPicker) {
        PlaylistPickerDialog(
            playlists = playlists,
            onDismiss = { showPlaylistPicker = false },
            onPick = { playlist ->
                showPlaylistPicker = false
                viewModel.addToPlaylist(playlist)
                val text = context.getString(R.string.snack_added_to_playlist, playlist.name)
                scope.launch { snackBarHostState.showSnackbar(text) }
            },
        )
    }

    var renamingSubsong by remember { mutableStateOf<Int?>(null) }
    var showOverrides by remember { mutableStateOf(false) }
    if (showOverrides && state.currentModule != null) {
        OverridesSheet(
            state = state,
            overrides = { fileOverrides.value },
            names = subsongNames,
            // What is being heard, which is where a loop point is wanted.
            positionMs = { visuals.snapshot.timeMs },
            onDismiss = { showOverrides = false },
            onTimeDisplay = viewModel::setTimeDisplay,
            onDuration = viewModel::setDurationOverride,
            onSetting = viewModel::setOverride,
            onTryLoop = viewModel::tryLoop,
            onStopLoop = viewModel::stopLoop,
            onSaveLoop = viewModel::saveLoopPreset,
            onPlayPreset = viewModel::playLoopPreset,
            onRenamePreset = { renamingSubsong = loopPresetCode(it.id) },
            onDeletePreset = viewModel::deleteLoopPreset,
        )
    }

    renamingSubsong?.let { index ->
        val uri = state.currentModule?.filePath.orEmpty()
        RenameSubsongDialog(
            initialName = subsongNames[subsongKey(uri, index)].orEmpty(),
            placeholder = defaultSubsongLabel(index),
            onDismiss = { renamingSubsong = null },
            onConfirm = { name ->
                renamingSubsong = null
                viewModel.renameSubsong(index, name)
            },
        )
    }

    // Bottom-anchored and painted, so what the collapse uncovers is the same
    // ground the screen behind it stands on rather than whatever happens to be
    // under the window.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { sheetHeight = it.height.toFloat().coerceAtLeast(1f) }
            .then(sheetPull),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val progress = fold.value
                    if (progress == 0f) return@graphicsLayer
                    if (liquid) {
                        // iOS's zoom: the mini player's glass grows into the
                        // whole screen, and shrinks back into it going away.
                        val w = size.width
                        val h = size.height
                        val source = zoomSource?.rect?.takeIf { !it.isEmpty } ?: with(density) {
                            Rect(20.dp.toPx(), h * 0.8f, w - 20.dp.toPx(), h * 0.8f + 54.dp.toPx())
                        }
                        val left = lerp(0f, source.left, progress)
                        val top = lerp(0f, source.top, progress)
                        val right = lerp(w, source.right, progress)
                        val bottom = lerp(h, source.bottom, progress)
                        val scale = ((right - left) / w).coerceAtLeast(0.01f)
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = left
                        translationY = top
                        // What shows of it is a window its current height,
                        // rounded from the screen's corners to a capsule.
                        val corner = lerp(with(density) { 34.dp.toPx() }, source.height / 2f, progress)
                        shape = ZoomWindowShape(height = (bottom - top) / scale, radius = corner / scale)
                        clip = true
                        // The very end of going away fades into the mini
                        // player it has become.
                        alpha = if (progress > 0.85f) ((1f - progress) / 0.15f).coerceIn(0f, 1f) else 1f
                        return@graphicsLayer
                    }
                    // Squeezed onto the mini player's height, pinned to the
                    // bottom edge so it folds onto the bar rather than towards
                    // the middle of the screen.
                    val floor = if (size.height > 0f) miniPlayerPx / size.height else 0f
                    scaleY = 1f - progress * (1f - floor)
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    // Runs ahead of the fold, so what is left at the end of it
                    // is an empty bar settling rather than a legible screen
                    // being crushed.
                    alpha = (1f - progress * PLAYER_FADE_LEAD).coerceAtLeast(0f)
                },
        ) {
            // Under Liquid Glass the glass buttons pop in as the player opens
            // and away as it folds back down, as a sheet's bar items do.
            CompositionLocalProvider(
                LocalLiquidPageMotion provides LiquidPageMotion(barShift = { 0f }, present = sheetShown),
                LocalNowPlayingReveal provides { ((0.75f - fold.value) / 0.55f).coerceIn(0f, 1f) },
            ) {
            PlayerScreenContent(
                modifier = modifier,
                snackBarHostState = snackBarHostState,
                state = state,
                isFavourite = isFavourite,
                visuals = visuals,
                patternData = patternData,
                queueSheetState = queueSheetState,
                durationsSheetState = durationsSheetState,
                showQueue = showQueue,
                showDurations = showDurations,
                channelMutes = { channelMutes.value },
                subsongNames = subsongNames,
                loopPresets = { fileOverrides.value.loops },
                soundCheckStatus = viewModel::soundCheckStatus,
                onAction = { action ->
                    when (action) {
                        PlayerAction.OnBack -> onCloseRequest()

                        PlayerAction.OnAddToPlaylist -> showPlaylistPicker = true

                        PlayerAction.OnOverrides -> showOverrides = true

                        is PlayerAction.OnPlayLoopPreset -> {
                            viewModel.playLoopPreset(action.preset)
                            // Taken away as well as hidden - see OnDurationClick.
                            scope.launch { durationsSheetState.hide() }
                                .invokeOnCompletion { cause -> if (cause == null) showDurations = false }
                        }

                        is PlayerAction.OnRenameSubsong -> renamingSubsong = action.index

                        PlayerAction.OnPlayPause -> viewModel.togglePlayPause()

                        PlayerAction.OnStop -> viewModel.stop()

                        is PlayerAction.OnToggleMute -> viewModel.toggleMute(action.channel)

                        is PlayerAction.OnSolo -> viewModel.soloChannel(action.channel, action.numChannels)

                        PlayerAction.OnFavourite -> viewModel.toggleFavourite()

                is PlayerAction.OnGlobalPitch -> viewModel.setGlobalPitch(action.semitones)

                        is PlayerAction.OnGlobalTempo -> viewModel.setGlobalTempo(action.percent)

                        is PlayerAction.OnControls -> viewModel.setControlsExpanded(action.expanded)

                        is PlayerAction.OnScopesOnly -> viewModel.setScopesOnly(action.tiles)

                        PlayerAction.OnPrevious -> viewModel.previous()

                        PlayerAction.OnNext -> viewModel.next()

                        PlayerAction.OnShuffle -> viewModel.toggleShuffle()

                        PlayerAction.OnLoop -> viewModel.toggleLoop()

                        PlayerAction.OnAllSequences -> viewModel.toggleAllSequences()

                        PlayerAction.OnAudioInfo -> showAudioInfo = true

                        is PlayerAction.OnPlayerView -> channelsShown = action.index == PlayerPage.Channel.ordinal

                        is PlayerAction.OnSeek -> viewModel.seek(action.seek)

                        is PlayerAction.OnQueueClick -> viewModel.playAtIndex(action.int)

                        is PlayerAction.OnQueueSheet -> {
                            showQueue = action.open
                            scope.launch {
                                if (showQueue) {
                                    queueSheetState.expand()
                                } else {
                                    queueSheetState.hide()
                                }
                            }
                        }

                        is PlayerAction.OnDurationClick -> {
                            viewModel.setSequence(action.int)
                            // Hidden, and then taken away. Hiding alone left
                            // the sheet built: under Liquid Glass, where it is
                            // drawn in the app's own window and goes only when
                            // it is no longer composed, it simply stayed up;
                            // elsewhere it slid off and left its window behind
                            // it, invisible and over the whole player, taking
                            // every touch until Back was pressed.
                            //
                            // Taken away only by a hide that ran to its end.
                            // A second row tapped while the sheet is sliding
                            // off starts another hide, which cancels this one,
                            // and a cancelled hide removing the sheet snatched
                            // it away half way down. The second one finishes
                            // the slide, and removes it then.
                            scope.launch { durationsSheetState.hide() }
                                .invokeOnCompletion { cause -> if (cause == null) showDurations = false }
                        }

                        is PlayerAction.OnDurationSheet -> {
                            showDurations = action.open
                            scope.launch {
                                if (showDurations) {
                                    durationsSheetState.expand()
                                } else {
                                    durationsSheetState.hide()
                                }
                            }
                        }
                    }
                }
            )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerScreenContent(
    snackBarHostState: SnackbarHostState,
    state: PlayerUiState,
    isFavourite: Boolean,
    visuals: PlaybackVisuals,
    patternData: PatternData,
    queueSheetState: SheetState,
    durationsSheetState: SheetState,
    showQueue: Boolean,
    showDurations: Boolean,
    modifier: Modifier = Modifier,
    channelMutes: () -> Long = { 0L },
    subsongNames: Map<String, String> = emptyMap(),
    /** The file's saved loops, read only while the sheet that lists them is up. */
    loopPresets: () -> List<LoopPreset> = { emptyList() },
    soundCheckStatus: () -> SoundCheckStatus? = { null },
    onAction: (PlayerAction) -> Unit
) {
    // Read from state, not remembered here: a bar the user tucked away has to
    // stay away across a switch of player view and across a restart, and a
    // local remember is lost to both.
    val controlsExpanded = state.controlsExpanded

    // Shows the channel view as a grid of scope tiles and nothing else. From
    // state, like controlsExpanded: it was a local remember, and leaving the
    // player and coming back put the rows back every time.
    val scopesOnly = state.scopesOnly

    // What the format has, which decides both the views and the row above the
    // seek bar. Pos, pat, row, speed and BPM are a tracker's own vocabulary,
    // and on a format that has none of them that row was five labels reading
    // zero - so it goes, and the bar is that much shorter. What those formats
    // do have is several tunes in one file, which is worth a row more than the
    // readout ever was and had been sitting in the chip list behind the fold.
    val hasPatterns = state.modVars.hasPatterns
    val hasChannels = state.modVars.hasChannels
    val hasSubsongs = state.modVars.miNumSequences > 1
    val subsongsInRow = !hasPatterns && hasSubsongs

    // Only the views with something in them. A format with no patterns has no
    // grid to draw, and a chip whose emulation cannot hand back its voices has
    // no traces - and a channel view of flat lines reads as a fault rather
    // than as an absence, so it goes rather than sits there empty. The
    // spectrum is of the output itself, which everything played has; and the
    // debug view is always there, reporting state rather than sound.
    // And the words, for a file that carries any.
    val lyrics = state.shownLyrics
    val pages = remember(hasPatterns, hasChannels, lyrics != null) {
        buildList {
            if (hasPatterns) add(PlayerPage.Pattern)
            if (hasChannels) add(PlayerPage.Channel)
            add(PlayerPage.Spectrum)
            if (lyrics != null) add(PlayerPage.Lyrics)
            add(PlayerPage.Debug)
        }
    }

    // The view the listener picked, by what it is and not by where it stands
    // in the list - and only while the player is open, nothing written back.
    // Until one is picked, and wherever the format has not got it, the first
    // view there is: the pattern view, or the channel view where there are no
    // patterns. Not the view last settled on - that was once written back for
    // every format, so one register log that had only the debug view left
    // every file after it opening there.
    //
    // Without it every change of track put a chip tune back on its first
    // view: between two files the list passes through the default one (see
    // channelsShown above), which is two fresh pagers, each starting at 0.
    // Nobody minded while the first view was the one worth watching; someone
    // watching the spectrum lost it at every track.
    var wantedPage by rememberSaveable { mutableStateOf<PlayerPage?>(null) }
    val initialIndex = pages.indexOf(wantedPage).coerceAtLeast(0)

    // Keyed on the page list, so a format with a different set of views gets a
    // fresh pager. Without this the state outlived the list it indexed into:
    // going from three pages to two left currentPage pointing past the end,
    // and the pager drew the page it was on over the page it was moving to.
    val pagerState = key(pages) {
        rememberPagerState(
            initialPage = initialIndex,
            pageCount = { pages.size },
        )
    }
    val pagerScope = rememberCoroutineScope()

    // Where the pager settles is reported (below), for the screen to know
    // whether the traces are wanted - and nothing more.
    //
    // A stored page used to drive the pager as well, animating to it whenever
    // it changed, and that is what left a fast swipe on the handle stuck
    // halfway between two views. The store only caught up once a page had
    // settled and the write had come back, so during a run of quick swipes it
    // lagged behind
    // the page the handle was heading for: it started animating back to a page
    // already left, the next swipe cancelled that, and each change of it
    // restarted this effect - cancelling its own animation part way, then
    // finding the pager already counted as on the target and doing nothing.
    // A cancelled programmatic scroll is simply left where it stopped, and this
    // pager takes no gestures of its own to snap it back. So the buttons now
    // move the pager themselves, and where it settles is only ever an output.

    // And whatever else interrupts a page change, it cannot leave the pager
    // between two pages: once nothing is scrolling, a fractional offset is
    // finished off onto the page it is nearest.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress to pagerState.currentPageOffsetFraction }
            .collect { (scrolling, fraction) ->
                if (!scrolling && abs(fraction) > PAGE_SETTLE_EPSILON) {
                    // A swipe landing meanwhile takes the pager over and
                    // cancels this - which must not end the watch itself.
                    try {
                        pagerState.animateScrollToPage(pagerState.currentPage)
                    } catch (interrupted: CancellationException) {
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
    }

    LaunchedEffect(pagerState, pages) {
        snapshotFlow { pagerState.settledPage }
            .collect { index ->
                val page = pages.getOrNull(index) ?: return@collect
                onAction(PlayerAction.OnPlayerView(page.ordinal))
            }
    }

    // Only a move made by hand names the view wanted. The pager takes no
    // gestures of its own, so once it stands, where it heads can only have
    // come from the handle or a button; where a fresh one starts is not a
    // choice - it may be the fallback of a list without the view wanted, and
    // must not take its place.
    LaunchedEffect(pagerState, pages) {
        snapshotFlow { pagerState.targetPage }
            .drop(1)
            .collect { index -> pages.getOrNull(index)?.let { wantedPage = it } }
    }

    val wide = isWideWindow()

    // Shared by the expanded and the compact transport rows. Derived on the
    // state rather than here, because the mini bar asks the same question and
    // the two answers were drifting apart.
    val hasNext = state.hasNext
    val hasPrev = state.hasPrev

    if (state.currentModule == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        )
        return
    }

    val oneUi = isOneUi
    val oneUiColors = LocalOneUiColors.current
    val playing = state.status == PlaybackStatus.PLAYING
    // What this track looks like under One UI - its colors, standing in for
    // album art, and its waveform - for the playback line.
    val look = remember(state.currentModule.filePath, oneUiColors.isDark) {
        trackLook(state.currentModule.filePath, oneUiColors.isDark)
    }
    // Liquid Glass draws the player the way Music draws Now Playing: always
    // dark, on the colors of what is playing.
    val liquid = isLiquid
    val nowPlayingLook = remember(state.currentModule.filePath) {
        trackLook(state.currentModule.filePath, dark = true)
    }

    // Only meaningful on the channel view, so it is only there - and it is
    // the whole reason the chip for it is gone.
    val scopesToggle: @Composable () -> Unit = {
        AnimatedVisibility(
            visible = pages.getOrNull(pagerState.currentPage) == PlayerPage.Channel
        ) {
            IconButton(
                onClick = { onAction(PlayerAction.OnScopesOnly(!scopesOnly)) },
                content = {
                    Icon(
                        imageVector = ScopeGridIcon,
                        contentDescription = stringResource(
                            R.string.desc_scopes_only
                        ),
                        tint = if (scopesOnly) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            LocalContentColor.current
                        },
                    )
                }
            )
        }
    }

    CompositionLocalProvider(LocalTrackLook provides if (liquid) nowPlayingLook else look) {
    NowPlayingSurface(enabled = liquid, modifier = modifier) {
    // The views as they are drawn, for the glass of the panel over them to
    // bend: they run on underneath it to the bottom of the screen.
    val viewsBackdrop = rememberLayerBackdrop()
    Scaffold(
        modifier = if (liquid) Modifier else modifier,
        containerColor = if (liquid) Color.Transparent else MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackBarHostState) },
        topBar = {
            if (liquid) {
                val page = pages.getOrNull(pagerState.targetPage)
                LiquidNowPlayingTopBar(
                    title = page?.let { stringResource(it.title()) }.orEmpty(),
                    onClose = { onAction(PlayerAction.OnBack) },
                    scopesShown = page == PlayerPage.Channel,
                    scopesOnly = scopesOnly,
                    onScopes = { onAction(PlayerAction.OnScopesOnly(!scopesOnly)) },
                    // They live down in the title row now, in any window:
                    // the bar is a name and nothing else.
                    buttonsBelow = true,
                )
            } else if (oneUi) {
                // One UI: the title and what the file is on the left, as in
                // Samsung Music, with the favourite beside them rather than
                // down in the transport.
                OneUiTopAppBar(
                    title = {
                        Text(
                            text = state.modVars.modName.ifBlank { state.currentModule.name },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    subtitle = {
                        Text(
                            text = state.modVars.modType,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = { BackButton(onBack = { onAction(PlayerAction.OnBack) }) },
                    actions = {
                        scopesToggle()
                        IconButton(
                            onClick = { onAction(PlayerAction.OnFavourite) },
                            content = {
                                Icon(
                                    imageVector = if (isFavourite) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = stringResource(
                                        if (isFavourite) R.string.desc_remove_favourite else R.string.desc_add_favourite
                                    ),
                                    tint = if (isFavourite) oneUiColors.accent else LocalContentColor.current,
                                )
                            }
                        )
                    },
                )
            } else CenterAlignedTopAppBar(
                title = {
                    Column(
                        verticalArrangement = Arrangement.Top,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        content = {
                            Text(
                                text = state.modVars.modName.ifBlank {
                                    state.currentModule.name
                                },
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Text(
                                text = state.modVars.modType,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                },
                navigationIcon = { BackButton(onBack = { onAction(PlayerAction.OnBack) }) },
                actions = { scopesToggle() }
            )
        },
        bottomBar = {
            // The same controls either way. Under Liquid Glass they sit on a
            // card of glass over the bottom of the view; elsewhere on the
            // style's own bar.
            val panel: @Composable ColumnScope.() -> Unit = {
                ViewSwipeHandle(
                    pagerState = pagerState,
                    wide = wide,
                    divider = !oneUi && !liquid,
                    onControlsExpanded = { onAction(PlayerAction.OnControls(it)) },
                )
                // Music's Now Playing puts the title under the artwork, on
                // the left, with the favourite at its end.
                if (liquid) {
                    val onChannels = pages.getOrNull(pagerState.targetPage) == PlayerPage.Channel
                    LiquidNowPlayingTitle(
                        title = state.modVars.modName.ifBlank { state.currentModule.name },
                        subtitle = state.modVars.modType,
                        before = {
                            LiquidPopIn(visible = onChannels) {
                                NowPlayingScopesButton(
                                    scopesOnly = scopesOnly,
                                    onScopes = { onAction(PlayerAction.OnScopesOnly(!scopesOnly)) },
                                )
                            }
                        },
                        after = { NowPlayingCloseButton(onClose = { onAction(PlayerAction.OnBack) }) },
                    )
                }

                if (wide) {
                    WidePlayerBar(
                        state = state,
                        visuals = visuals,
                        isFavourite = isFavourite,
                        hasNext = hasNext,
                        hasPrev = hasPrev,
                        pages = pages,
                        currentView = pagerState.currentPage,
                        // Not which page, but where between them: the
                        // drop rides this, so it glides with the pages
                        // instead of being told a number afterwards.
                        viewPosition = {
                            pagerState.currentPage + pagerState.currentPageOffsetFraction
                        },
                        expanded = controlsExpanded,
                        // The button gives a position in the list on
                        // screen. Straight to the pager; the page is reported
                        // once it settles there, like any other way of
                        // getting to it.
                        onSelectView = { index ->
                            if (index in pages.indices) {
                                pagerScope.launch { pagerState.animateScrollToPage(index) }
                            }
                        },
                        onAction = onAction,
                    )
                } else {

                // Collapsing swaps two rows for one: the readout goes
                // away, the playback bar rises into its slot, and a
                // compact transport row takes the slot the playback bar
                // just left. PlaybackProgress itself is never moved
                // between branches - it keeps its seek Animatable across
                // the transition instead of restarting mid-drag, and that
                // is why it stays in the same Row below whether the window
                // is wide or not.
                AnimatedVisibility(
                    visible = controlsExpanded && (hasPatterns || subsongsInRow),
                    enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
                ) {
                    if (hasPatterns) {
                        PatternInfoRow(visuals = visuals)
                    } else {
                        SubsongRow(
                            isSubSongs = state.playAllSequences,
                            onPlaySubSongs = { onAction(PlayerAction.OnAllSequences) },
                            onShowDurations = {
                                onAction(PlayerAction.OnDurationSheet(true))
                            },
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Wide enough, and pos/pat/row sits beside the seek bar
                    // instead of above it - a whole row of height handed
                    // back to the visualiser.
                    PlaybackProgress(
                        visuals = visuals,
                        onSeek = { onAction(PlayerAction.OnSeek(it)) },
                        modifier = Modifier.weight(1f),
                        timeDisplay = state.timeDisplay,
                        loopStartMs = state.loopRegion?.startMs ?: -1,
                        loopEndMs = state.loopRegion?.endMs ?: -1,
                        playing = playing,
                    )
                }

                AnimatedVisibility(
                    visible = !controlsExpanded,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    TransportRow(
                        modifier = Modifier.fillMaxWidth(),
                        status = state.status,
                        hasNext = hasNext,
                        hasPrev = hasPrev,
                        isFavourite = isFavourite,
                        compact = true,
                                    onPrev = { onAction(PlayerAction.OnPrevious) },
                        onPlayPause = { onAction(PlayerAction.OnPlayPause) },
                        onNext = { onAction(PlayerAction.OnNext) },
                        onFavourite = { onAction(PlayerAction.OnFavourite) },
                        onQueueSheet = { onAction(PlayerAction.OnQueueSheet(true)) }
                    )
                }

                // Everything below the playback bar collapses together
                // under the chevron in the top bar. Shrinking the bar's
                // own height is what makes the room: the Scaffold hands
                // the freed space straight to the visualiser above.
                AnimatedVisibility(
                    visible = controlsExpanded,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(modifier = Modifier.height(if (liquid) 12.dp else 4.dp))
                        ChipList(
                            isShuffle = state.isShuffle,
                            isShuffleRoot = state.isShuffleRoot,
                            repeatMode = state.repeatMode,
            globalPitch = state.globalPitch,
            onGlobalPitch = { onAction(PlayerAction.OnGlobalPitch(it)) },
            globalTempo = state.globalTempo,
            onGlobalTempo = { onAction(PlayerAction.OnGlobalTempo(it)) },
                            isSubSongs = state.playAllSequences,
                            onShuffle = { onAction(PlayerAction.OnShuffle) },
                            onLoop = { onAction(PlayerAction.OnLoop) },
                            onAddToPlaylist = { onAction(PlayerAction.OnAddToPlaylist) },
                            onPlaySubSongs = { onAction(PlayerAction.OnAllSequences) },
                            onShowDurations = { onAction(PlayerAction.OnDurationSheet(true)) },
                            onAudioInfo = { onAction(PlayerAction.OnAudioInfo) },
                            onOverrides = { onAction(PlayerAction.OnOverrides) },
                            // Not twice: they are in the row above. Not at
                            // all for a file with one subsong or none.
                            withSubsongs = hasSubsongs && !subsongsInRow,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        TransportRow(
                            modifier = Modifier.fillMaxWidth(),
                            status = state.status,
                            hasNext = hasNext,
                            hasPrev = hasPrev,
                            isFavourite = isFavourite,
                                            onPrev = { onAction(PlayerAction.OnPrevious) },
                            onPlayPause = { onAction(PlayerAction.OnPlayPause) },
                            onNext = { onAction(PlayerAction.OnNext) },
                            onFavourite = { onAction(PlayerAction.OnFavourite) },
                            onQueueSheet = { onAction(PlayerAction.OnQueueSheet(true)) }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
                }
            }
            if (liquid) {
                LiquidNowPlayingPanel(views = viewsBackdrop, content = panel)
            } else {
                // One UI: the controls on a card with round top corners, laid
                // over the bottom of the view like a sheet, its grip the only
                // edge it needs.
                PlayerBottomAppBar(
                    contentPadding = PaddingValues(bottom = 8.dp),
                    shape = if (oneUi) PanelShape else RectangleShape,
                    containerColor = when {
                        oneUi -> oneUiColors.card
                        liquid -> Color.Transparent
                        else -> BottomAppBarDefaults.containerColor
                    },
                    shadowElevation = if (oneUi && !oneUiColors.isDark) 8.dp else 0.dp,
                    content = panel,
                )
            }
        },
        content = { contentPadding ->
            // How much of the bottom of a view lies under the controls: their
            // corners' worth under One UI's card, and none under Liquid Glass,
            // where the views end at the panel's edge. Most views scroll and
            // do not mind; one that stands on a baseline does.
            val viewBottomInset = when {
                oneUi -> minOf(OneUiDimens.CardCorner, contentPadding.calculateBottomPadding())
                else -> 0.dp
            }
            Box(
                // Deliberately NOT declaring preferredFrameRate here anymore.
                // This modifier (tried at both FrameRateCategory.High and 30f
                // across earlier attempts at this same bug) is the one thing
                // that was present through every round of the "constant,
                // app-specific, screen-recording-invisible" jitter the user
                // kept reporting - a symptom pattern consistent with a
                // non-native requested refresh rate causing a real panel-level
                // artifact (e.g. PWM dimming interaction on an LTPO display)
                // that wouldn't show up in a captured framebuffer at all.
                // Letting the system's own default frame-rate arbitration run
                // removes that variable entirely.
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        // Under One UI's panel as far as its corners reach,
                        // so what shows past them is the view, not a gap.
                        if (oneUi) {
                            PaddingValues(
                                top = contentPadding.calculateTopPadding(),
                                bottom = (contentPadding.calculateBottomPadding() - OneUiDimens.CardCorner)
                                    .coerceAtLeast(0.dp),
                            )
                        } else {
                            // Under Liquid Glass too the views stop where the
                            // panel starts: its edge is where they end, not a
                            // pane laid over the bottom of them.
                            contentPadding
                        }
                    )

            ) {
                // beyondViewportPageCount stays at its default of 0: these are
                // the most expensive things in the app to compose, and the two
                // that are not on screen have no business being built until a
                // swipe actually starts bringing one in.
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (liquid) Modifier.layerBackdrop(viewsBackdrop) else Modifier),
                    // Keyed by which view it is, not by where it sits. The
                    // list loses its first entry on a format with no patterns,
                    // and an index key would have handed Channel the slot -
                    // and the saved state - that belonged to Pattern.
                    // getOrNull, not indexing: when the list loses its first
                    // entry the pager rebuilds its key map from the count it
                    // still holds, so for one frame it asks about an index
                    // that no longer exists. Loading a pattern-less format
                    // while a tracker was open crashed here.
                    key = { pages.getOrNull(it)?.name ?: it },
                    // The handle is the only thing that turns these pages. A
                    // swipe anywhere on a view belongs to the view - panning a
                    // pattern, scrolling channels - and having both meanings
                    // on the same gesture made each of them unreliable.
                    userScrollEnabled = false,
                    // All of them stay built, the same way the sections do.
                    //
                    // They were briefly built only once a page had landed,
                    // which cost the animation its fluidity for no gain: what
                    // is expensive about these is the drawing, and the pager
                    // only ever draws the one on screen. Composing them costs
                    // little and costs it once, so the swipe has nothing to
                    // compete with and the drag is sampled properly - which is
                    // what was cancelling swipes here.
                    beyondViewportPageCount = pages.size - 1,
                ) { page ->
                    // Whether any of this page is on screen, or a swipe is
                    // heading for it - for the two views drawn from the
                    // playback tickets. Drawing, as the note above says, is
                    // what costs, and a page the pager leaves off screen is
                    // not spared all of it: a layer whose ticket moved is
                    // recorded again whether or not anything then draws it.
                    // So those views are told, and stop following.
                    //
                    // By where the pager is rather than which page it counts
                    // itself on: that number turns over half way through a
                    // swipe, with the page it is leaving still half in view.
                    // Derived, so the views hear of it when the answer
                    // changes and not on every frame of the swipe.
                    val onScreen = remember(pagerState, page) {
                        derivedStateOf {
                            page == pagerState.targetPage ||
                                abs(pagerState.currentPage + pagerState.currentPageOffsetFraction - page) <
                                1f - PAGE_SETTLE_EPSILON
                        }
                    }
                    // Clipped to its own page.
                    //
                    // Compose does not clip a node's drawing to its bounds,
                    // and these views draw beyond theirs: the pattern pans and
                    // zooms, the channels scroll. With every page built and
                    // drawn, whatever spilled past an edge landed on the page
                    // beside it - which is how two views ended up sharing one
                    // screen.
                    Box(Modifier.fillMaxSize().clipToBounds()) {
                        when (pages.getOrNull(page)) {
                            PlayerPage.Pattern -> PatternView(
                                modifier = Modifier.fillMaxSize(),
                                module = state.currentModule,
                                pattern = patternData,
                                visuals = visuals,
                                mutes = channelMutes,
                                live = { onScreen.value },
                                onToggleMute = { onAction(PlayerAction.OnToggleMute(it)) },
                                onSolo = { ch, n -> onAction(PlayerAction.OnSolo(ch, n)) },
                            )

                            PlayerPage.Channel -> ChannelView(
                                modifier = Modifier.fillMaxSize(),
                                numChannels = state.modVars.chn,
                                instrumentNames = state.modVars.instruments.toPersistentList(),
                                // Both lists: a channel's instrument and sample
                                // fields are numbered differently and libopenmpt
                                // can report one in place of the other - see
                                // voiceName().
                                sampleNames = state.modVars.samples.toPersistentList(),
                                visuals = visuals,
                                scopesOnly = scopesOnly,
                                mutes = channelMutes,
                                live = { onScreen.value },
                                onToggleMute = { onAction(PlayerAction.OnToggleMute(it)) },
                                onSolo = { ch, n -> onAction(PlayerAction.OnSolo(ch, n)) },
                            )

                            PlayerPage.Spectrum -> SpectrumView(
                                modifier = Modifier.fillMaxSize(),
                                // By the screen's own rule: playing, and
                                // no sheet over the views.
                                playing = visuals.active,
                                // Live from the moment a swipe heads for it.
                                live = page == pagerState.currentPage || page == pagerState.targetPage,
                                bottomInset = viewBottomInset,
                                // Under glass, the columns' mirror shows through it.
                                reflect = liquid,
                            )

                            PlayerPage.Lyrics -> lyrics?.let { words ->
                                LyricsView(
                                    modifier = Modifier.fillMaxSize(),
                                    lyrics = words,
                                    visuals = visuals,
                                    live = { onScreen.value },
                                    bottomInset = viewBottomInset,
                                    onSeek = { onAction(PlayerAction.OnSeek(it)) },
                                )
                            } ?: Unit

                            PlayerPage.Debug -> DebugView(
                                modifier = Modifier.fillMaxSize(),
                                state = state,
                                visuals = visuals,
                                soundCheckStatus = soundCheckStatus,
                                // Live from the moment a swipe heads for it.
                                live = page == pagerState.currentPage || page == pagerState.targetPage,
                            )

                            // The page went away underneath the pager. Nothing
                            // to draw for one frame; the count catches up.
                            null -> Unit
                        }
                    }
                }
            }

            if (showDurations) {
                DurationsSheet(
                    sheetState = durationsSheetState,
                    seqData = state.modVars.seqData.toPersistentList(),
                    currentSequence = state.currentSequence,
                    uri = state.currentModule?.filePath.orEmpty(),
                    names = subsongNames,
                    onRename = { onAction(PlayerAction.OnRenameSubsong(it)) },
                    presets = loopPresets(),
                    activePresetId = state.loopRegion?.presetId,
                    onPreset = { onAction(PlayerAction.OnPlayLoopPreset(it)) },
                    onItemClick = { onAction(PlayerAction.OnDurationClick(it)) },
                    onDismiss = { onAction(PlayerAction.OnDurationSheet(false)) },
                )
            }

            if (showQueue) {
                QueueSheet(
                    sheetState = queueSheetState,
                    queue = state.queue,
                    currentIndex = state.currentQueueIndex,
                    onItemClick = { onAction(PlayerAction.OnQueueClick(it)) },
                    onDismiss = { onAction(PlayerAction.OnQueueSheet(false)) },
                    names = subsongNames,
                )
            }
        }
    )
    }
    }
}


/** How much of Now Playing's content shows over its colours, as it opens. */
private val LocalNowPlayingReveal = staticCompositionLocalOf { { 1f } }

/** The top [height] of what it clips, with [radius] corners: the window the zoom shows. */
private class ZoomWindowShape(private val height: Float, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(
            RoundRect(0f, 0f, size.width, height.coerceIn(0f, size.height), CornerRadius(radius.coerceAtLeast(0f)))
        )
}

/**
 * Under Liquid Glass, the player drawn as Music's Now Playing, in the app's
 * own mode, light or dark. Elsewhere, [content] as it is.
 */
@Composable
private fun NowPlayingSurface(
    enabled: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    // It used to be dark whatever the app was in, as Music's is. Music is
    // dark because the artwork's colours are painted behind it, though, and
    // that wash is gone from here: a dark player in a light app was only a
    // setting not being listened to.
    val colors = LocalLiquidColors.current
    if (colors.isDark) DarkScreenSystemBars()
    val scheme = remember(colors) { liquidColorScheme(colors) }
    // What the glass over it bends. The colours of the track used to be
    // painted here, the way Music paints the artwork's behind Now Playing -
    // but the views are opaque, so all that was ever seen of them was the
    // band above and below: a coloured frame around a black rectangle. The
    // player is dark now, and the views have the whole of it.
    val backdrop = rememberLayerBackdrop()
    Box(modifier = modifier.fillMaxSize()) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .layerBackdrop(backdrop)
            .drawBehind { drawRect(if (colors.isDark) Color.Black else Color.White) },
    )
        val reveal = LocalNowPlayingReveal.current
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
            CompositionLocalProvider(
                LocalLiquidColors provides colors,
                LocalContentColor provides colors.label,
                LocalLiquidBackdrop provides backdrop,
            ) {
                // The colours come first, the player on them as it opens.
                Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = reveal() }) { content() }
            }
        }
    }
}

/**
 * Under Liquid Glass, the player's controls on a card of glass: rounded all
 * round and clear of the edges, floating over the bottom of the view, which
 * runs on under it and shows through - frosted and bent at the rim, as the
 * tab bar shows the page under it.
 *
 * The glass is its own layer, and what is on it - the chips, the view
 * selector, the buttons - bends that layer over the view rather than the view
 * alone: glass on glass, not holes cut through the card.
 */
@Composable
private fun LiquidNowPlayingPanel(
    views: Backdrop,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ground = LocalLiquidBackdrop.current
    val behind = rememberCombinedBackdrop(ground ?: views, views)
    // The windows' own pane: the tab bar's glass, from the same slider -
    // clear at Clear, frosted at Frosted - and the same light, where nothing
    // on it took the touch; the chips and buttons light themselves.
    LiquidPane(
        shape = ContinuousShape(30.dp),
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 8.dp)
            .padding(bottom = 6.dp),
        backdrop = behind,
        shadow = 16.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/**
 * Now Playing's top: the grabber of the sheet it is, the button that lowers
 * it in a glass circle on the left, the view's name in the middle -
 * crossfading as the view changes - and the view's own action on glass on
 * the right, which pops in with its view and away without it.
 *
 * A column of two: the grabber has its own strip, and everything else shares
 * one row and so one centre line, rather than each being nudged onto it by
 * padding of its own and drifting apart.
 *
 * As short as the glass on it, and shorter still where the window is wide
 * enough to carry the two buttons down beside the favourite: a bar's height
 * here is height taken from the view, which is the thing worth looking at.
 */
@Composable
private fun LiquidNowPlayingTopBar(
    title: String,
    onClose: () -> Unit,
    scopesShown: Boolean,
    scopesOnly: Boolean,
    onScopes: () -> Unit,
    buttonsBelow: Boolean,
) {
    val presence = rememberLiquidPresence()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(TopAppBarDefaults.windowInsets)
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 3.dp, bottom = 3.dp)
                .size(width = 32.dp, height = 4.dp)
                .clip(CapsuleShape)
                .background(LocalLiquidColors.current.tertiaryLabel),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (buttonsBelow) 24.dp else 36.dp),
        ) {
            if (!buttonsBelow) {
                NowPlayingCloseButton(
                    onClose = onClose,
                    modifier = Modifier.align(Alignment.CenterStart).liquidPop(presence),
                )
            }
            AnimatedContent(
                targetState = title,
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 60)) + scaleIn(tween(220), initialScale = 0.92f)) togetherWith
                        fadeOut(tween(140))
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer { alpha = presence.value.coerceIn(0f, 1f) },
                label = "now_playing_title",
            ) { name ->
                Text(text = name, style = LiquidType.Subheadline, color = LocalLiquidColors.current.label, maxLines = 1)
            }
            if (!buttonsBelow) {
                // The glass and its glyph come and go together, on the same
                // spring both ways, as iOS 26's bar buttons do.
                LiquidPopIn(
                    visible = scopesShown,
                    modifier = Modifier.align(Alignment.CenterEnd).liquidPop(presence),
                ) {
                    NowPlayingScopesButton(scopesOnly = scopesOnly, onScopes = onScopes)
                }
            }
        }
    }
}

/** The button that lowers Now Playing, wherever the window puts it. */
@Composable
private fun NowPlayingCloseButton(onClose: () -> Unit, modifier: Modifier = Modifier) {
    LiquidGlassButton(
        icon = Icons.Default.KeyboardArrowDown,
        contentDescription = stringResource(R.string.desc_back_button),
        onClick = onClose,
        size = 34.dp,
        glyph = 24.dp,
        tint = LocalLiquidColors.current.label,
        glass = LocalLiquidColors.current.nowPlayingGlass,
        modifier = modifier,
    )
}

/** The scopes-only toggle, which belongs to the channel view alone. */
@Composable
private fun NowPlayingScopesButton(
    scopesOnly: Boolean,
    onScopes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalLiquidColors.current
    LiquidGlassButton(
        icon = ScopeGridIcon,
        contentDescription = stringResource(R.string.desc_scopes_only),
        onClick = onScopes,
        size = 34.dp,
        glyph = 21.dp,
        tint = if (scopesOnly) colors.tint else colors.label,
        glass = colors.nowPlayingGlass,
        modifier = modifier,
    )
}

/**
 * The title under the artwork: the track in bold and what it is under it in
 * gray, with the bar's own two buttons at the end - the one that lowers the
 * player and the view's own action, brought down from the top bar so the bar
 * is a name and nothing else. The favourite is not here; it is in the
 * transport, where stop used to be.
 */
@Composable
private fun LiquidNowPlayingTitle(
    title: String,
    subtitle: String,
    before: (@Composable RowScope.() -> Unit)? = null,
    after: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = LiquidType.Title3,
                color = LocalLiquidColors.current.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = LiquidType.Body,
                color = LocalLiquidColors.current.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        before?.invoke(this)
        after?.invoke(this)
    }
}

/**
 * The controls bar for a window with room to spare.
 *
 * A different bar rather than the narrow one rearranged. Height is scarce on a
 * phone, which is what the narrow bar is built around - stacked rows, controls
 * spread across the full width to stay thumb-sized, and a handle to fold the
 * whole thing away when the visualiser wants the room. On a large screen none
 * of that applies: there is height to spare and width going unused, so this
 * packs everything to the sides, keeps it all on screen at once, and has
 * nothing to collapse.
 */
@Composable
private fun WidePlayerBar(
    state: PlayerUiState,
    visuals: PlaybackVisuals,
    isFavourite: Boolean,
    hasNext: Boolean,
    hasPrev: Boolean,
    pages: List<PlayerPage>,
    currentView: Int,
    viewPosition: () -> Float,
    expanded: Boolean,
    onSelectView: (Int) -> Unit,
    onAction: (PlayerAction) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        // View picker on the left, the toggles that were chips on the right.
        Row(verticalAlignment = Alignment.CenterVertically) {
            ViewSelector(
                pages = pages,
                current = currentView,
                position = viewPosition,
                onSelect = onSelectView,
            )
            ChipList(
                modifier = Modifier.weight(1f),
                isShuffle = state.isShuffle,
                isShuffleRoot = state.isShuffleRoot,
                repeatMode = state.repeatMode,
                globalPitch = state.globalPitch,
                onGlobalPitch = { onAction(PlayerAction.OnGlobalPitch(it)) },
                globalTempo = state.globalTempo,
                onGlobalTempo = { onAction(PlayerAction.OnGlobalTempo(it)) },
                isSubSongs = state.playAllSequences,
                onShuffle = { onAction(PlayerAction.OnShuffle) },
                onLoop = { onAction(PlayerAction.OnLoop) },
                onAddToPlaylist = { onAction(PlayerAction.OnAddToPlaylist) },
                onPlaySubSongs = { onAction(PlayerAction.OnAllSequences) },
                onShowDurations = { onAction(PlayerAction.OnDurationSheet(true)) },
                onAudioInfo = { onAction(PlayerAction.OnAudioInfo) },
                onOverrides = { onAction(PlayerAction.OnOverrides) },
                withSubsongs = state.modVars.miNumSequences > 1,
            )
        }

        // Transport packed to the left, seek bar taking everything after it.
        Row(verticalAlignment = Alignment.CenterVertically) {
            TransportRow(
                status = state.status,
                hasNext = hasNext,
                hasPrev = hasPrev,
                isFavourite = isFavourite,
                compact = true,
                arrangement = Arrangement.spacedBy(2.dp),
                onPrev = { onAction(PlayerAction.OnPrevious) },
                onPlayPause = { onAction(PlayerAction.OnPlayPause) },
                onNext = { onAction(PlayerAction.OnNext) },
                onFavourite = { onAction(PlayerAction.OnFavourite) },
                onQueueSheet = { onAction(PlayerAction.OnQueueSheet(true)) },
            )
            PlaybackProgress(
                visuals = visuals,
                onSeek = { onAction(PlayerAction.OnSeek(it)) },
                modifier = Modifier.weight(1f),
                timeDisplay = state.timeDisplay,
                loopStartMs = state.loopRegion?.startMs ?: -1,
                loopEndMs = state.loopRegion?.endMs ?: -1,
                playing = state.status == PlaybackStatus.PLAYING,
            )
        }

        // The only thing the handle folds away here. Everything else in this
        // bar is a control, and a bar you cannot reach the controls of is not
        // worth the height it gives back - the readout is the one part that is
        // only ever being read.
        AnimatedVisibility(
            visible = expanded && state.modVars.hasPatterns,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            PatternInfoRow(visuals = visuals)
        }
    }
}

/**
 * The views as buttons.
 *
 * The swipe still works; this is for the case the swipe is bad at, which is
 * going somewhere that is not next door. On a narrow window there is no room
 * for it and the handle is the only way.
 */
@Composable
private fun ViewSelector(
    pages: List<PlayerPage>,
    current: Int,
    position: () -> Float,
    onSelect: (Int) -> Unit,
) {
    if (isOneUi) {
        OneUiViewSelector(pages = pages, current = current, onSelect = onSelect)
        return
    }
    if (isLiquid) {
        LiquidViewSelector(pages = pages, position = position, onSelect = onSelect)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        pages.forEachIndexed { index, page ->
            val (icon, label) = when (page) {
                PlayerPage.Pattern -> Icons.Default.GridOn to R.string.desc_view_pattern
                PlayerPage.Channel -> Icons.Default.GraphicEq to R.string.desc_view_channel
                PlayerPage.Spectrum -> Icons.Default.Equalizer to R.string.desc_view_spectrum
                PlayerPage.Lyrics -> Icons.Default.Lyrics to R.string.desc_view_lyrics
                PlayerPage.Debug -> Icons.Default.BugReport to R.string.desc_view_debug
            }
            val selected = index == current
            IconButton(
                onClick = { onSelect(index) },
                content = {
                    Icon(
                        imageVector = icon,
                        contentDescription = stringResource(label),
                        modifier = Modifier.size(20.dp),
                        tint = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            )
        }
    }
}

/** The top corners of One UI's controls panel. */
private val PanelShape = RoundedCornerShape(topStart = OneUiDimens.CardCorner, topEnd = OneUiDimens.CardCorner)

private fun PlayerPage.icon(): ImageVector = when (this) {
    PlayerPage.Pattern -> Icons.Default.GridOn
    PlayerPage.Channel -> Icons.Default.GraphicEq
    PlayerPage.Spectrum -> Icons.Default.Equalizer
    PlayerPage.Lyrics -> Icons.Default.Lyrics
    PlayerPage.Debug -> Icons.Default.BugReport
}

/** A view's name, for the top of Now Playing. */
private fun PlayerPage.title(): Int = when (this) {
    PlayerPage.Pattern -> R.string.player_view_pattern
    PlayerPage.Channel -> R.string.player_view_channels
    PlayerPage.Spectrum -> R.string.player_view_spectrum
    PlayerPage.Lyrics -> R.string.player_view_lyrics
    PlayerPage.Debug -> R.string.player_view_debug
}

private fun PlayerPage.label(): Int = when (this) {
    PlayerPage.Pattern -> R.string.desc_view_pattern
    PlayerPage.Channel -> R.string.desc_view_channel
    PlayerPage.Spectrum -> R.string.desc_view_spectrum
    PlayerPage.Lyrics -> R.string.desc_view_lyrics
    PlayerPage.Debug -> R.string.desc_view_debug
}

/**
 * The views as One UI's segmented control: a gray pill with the one showing
 * lifted out on a white capsule that slides to the one picked.
 */
@Composable
private fun OneUiViewSelector(
    pages: List<PlayerPage>,
    current: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = LocalOneUiColors.current
    val slot = 44.dp
    val at by animateFloatAsState(
        targetValue = current.toFloat(),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "view_selector",
    )
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .clip(CircleShape)
            .background(colors.background)
            .padding(3.dp),
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset((at * slot.toPx()).roundToInt(), 0) }
                .size(slot, 36.dp)
                .shadow(elevation = 1.dp, shape = CircleShape)
                .background(if (colors.isDark) colors.dialog else colors.card, CircleShape),
        )
        Row {
            pages.forEachIndexed { index, page ->
                val selected = index == current
                Box(
                    modifier = Modifier
                        .size(slot, 36.dp)
                        .clip(CircleShape)
                        .clickable(onClickLabel = stringResource(page.label())) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = page.icon(),
                        contentDescription = stringResource(page.label()),
                        modifier = Modifier.size(20.dp),
                        tint = if (selected) colors.accent else colors.textSecondary,
                    )
                }
            }
        }
    }
}

/**
 * The views under Liquid Glass, with the tab bar's own drop over them.
 *
 * The same thing it is down there: a lens that swells out of the glass when
 * it is touched, magnifying the icon it is over, stretches along the way it
 * is being thrown and settles on the one it lands nearest. It can be dragged
 * from view to view, and a tap on any of them sends it there.
 *
 * [position] is where the pager is, in pages, fractions and all - not which
 * page it is on. The drop follows it frame by frame, so whatever moves the
 * pages moves the drop with them: a tap here, a swipe on the page itself, a
 * drag of the handle. It used to be handed the page number instead, and a
 * number changes halfway through a scroll - so a jump from the first view to
 * the third yanked the drop back to the middle on the way and then sent it
 * on again, which is the stutter that looked like it had stuck.
 */
@Composable
private fun LiquidViewSelector(
    pages: List<PlayerPage>,
    position: () -> Float,
    onSelect: (Int) -> Unit,
) {
    val slot = 46.dp
    val tall = 34.dp
    val colors = LocalLiquidColors.current
    val backdrop = LocalLiquidBackdrop.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val slotPx = with(density) { slot.toPx() }
    val count = pages.size.coerceAtLeast(1)
    val last = (count - 1).toFloat()
    // What the drop bends, over whatever is behind the bar: the icons
    // themselves, so what it passes over is magnified rather than blurred
    // away - the tab bar does the same with its tabs.
    val icons = rememberLayerBackdrop()
    // Worked out once, unconditionally: this used to be decided inside the
    // drawBackdrop call, in a branch on whether the player had a backdrop of
    // its own - so the slot it remembers in came and went with that branch,
    // and the drop lost what it was holding, mid-drag, whenever it did.
    val bent = rememberCombinedBackdrop(backdrop ?: icons, icons)
    // Read through these rather than captured: the drop is remembered, and
    // the lambdas handed in here are made afresh every recomposition.
    val where = rememberUpdatedState(position)
    val select = rememberUpdatedState(onSelect)
    // While this is set the drop is moving under its own power - a finger on
    // it, or a tap sending it somewhere - and the pages are following it,
    // not the other way round. It is handed back the moment the pager has
    // arrived where the drop already is, so the two never pull at once.
    var own by remember { mutableStateOf(false) }
    val drop = remember(scope, count) {
        LiquidDragAnimation(
            scope = scope,
            initialValue = 0f,
            valueRange = 0f..last,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.22f,
            onDragStarted = { own = true },
            onDragStopped = {
                val target = targetValue.roundToInt().coerceIn(0, count - 1)
                animateToValue(target.toFloat())
                select.value(target)
                scope.launch {
                    // Held until the pages have caught up, so letting go
                    // never snaps the drop back to where they still are.
                    withTimeoutOrNull(1_500L) {
                        snapshotFlow { where.value() }.first { abs(it - target) < 0.002f }
                    }
                    own = false
                }
            },
            onDrag = { _, amount -> updateValue(targetValue + amount.x / slotPx) },
        )
    }
    LaunchedEffect(drop) {
        snapshotFlow { where.value() }.collect { at ->
            if (!own) drop.snapNow(at.coerceIn(0f, last))
        }
    }
    // Which icon is lit: whichever the drop is nearest, read off the drop so
    // it is the same answer while dragging as while the pages move. Derived,
    // so the row is recomposed when the answer changes and not every frame.
    val picked by remember(drop, count) {
        derivedStateOf { drop.value.roundToInt().coerceIn(0, count - 1) }
    }
    Box(
        modifier = Modifier
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .then(
                if (backdrop != null) {
                    Modifier.liquidBarGlass(backdrop, colors.nowPlayingGlass, frost = colors.frost)
                } else {
                    Modifier.liquidGlassPlain(CapsuleShape, colors.nowPlayingGlass, highlight = 0.7f)
                }
            )
            .padding(3.dp),
    ) {
        Row(modifier = Modifier.layerBackdrop(icons)) {
            pages.forEachIndexed { index, page ->
                val tint by animateColorAsState(
                    targetValue = if (index == picked) colors.label else colors.secondaryLabel,
                    label = "liquid_view_tint",
                )
                Box(
                    modifier = Modifier
                        .size(slot, tall)
                        .clip(CapsuleShape)
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClickLabel = stringResource(page.label()),
                        ) {
                            if (index != picked) {
                                own = true
                                drop.animateToValue(index.toFloat())
                                select.value(index)
                                scope.launch {
                                    withTimeoutOrNull(1_500L) {
                                        snapshotFlow { where.value() }
                                            .first { abs(it - index) < 0.002f }
                                    }
                                    own = false
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = page.icon(),
                        contentDescription = stringResource(page.label()),
                        modifier = Modifier.size(19.dp),
                        tint = tint,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                // Offset rather than a transform over it: the drop is placed
                // where it is, so the glass bends what it is actually over
                // as it goes - and being an offset it moves within the bar
                // without making the bar any wider.
                .size(slot, tall)
                .offset { IntOffset((drop.value * slotPx).roundToInt(), 0) }
                .then(drop.modifier)
                .drawBackdrop(
                    backdrop = bent,
                    shape = { Capsule() },
                    effects = {
                        val progress = drop.pressProgress
                        if (LiquidRefraction.on) {
                            lens(8.dp.toPx() * progress, 12.dp.toPx() * progress, chromaticAberration = true)
                        }
                    },
                    highlight = { Highlight.Default.copy(alpha = drop.pressProgress) },
                    shadow = { Shadow(alpha = drop.pressProgress) },
                    innerShadow = {
                        val progress = drop.pressProgress
                        InnerShadow(radius = 6.dp * progress, alpha = progress)
                    },
                    layerBlock = {
                        // Stretched along the way it is going, and thinned to
                        // match - within limits.
                        stretchWith(drop.velocity / 10f, drop.scaleX, drop.scaleY, max = 0.04f)
                    },
                    onDrawSurface = {
                        // Kept lit while it is held. The tab bar's drop can
                        // afford to clear as it swells, because a page is
                        // showing through it; here there is only the dark
                        // behind the player, and clearing left a black hole.
                        drawRect(colors.nowPlayingDrop(drop.pressProgress))
                    },
                ),
        )
    }
}
/**
 * The thickened top edge of the bottom bar, doubling as a grab handle for
 * changing view.
 *
 * It is the only way to change view: the pages themselves take no gestures,
 * so a swipe on a view can go on meaning what that view needs it to mean.
 *
 * Being the first thing in the bar is what makes it follow the bar down when
 * the controls collapse: it is the bar's own edge, not something positioned
 * over it.
 */
@Composable
private fun ViewSwipeHandle(
    pagerState: PagerState,
    wide: Boolean,
    onControlsExpanded: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    // Accumulated rather than acted on per delta: this is a one-shot switch,
    // not something that follows the finger, so it has to know how far the
    // whole drag went before deciding anything.
    var pulled by remember { mutableFloatStateOf(0f) }
    var pulling by remember { mutableStateOf(false) }
    var swiping by remember { mutableStateOf(false) }

    // Which way the pull is going, as -1, 0 or 1. Through derivedStateOf so
    // the handle recomposes when that direction changes rather than on every
    // delta of the drag.
    val pullDirection by remember {
        derivedStateOf {
            when {
                !pulling -> 0
                pulled > HANDLE_DIRECTION_EPSILON -> 1
                pulled < -HANDLE_DIRECTION_EPSILON -> -1
                else -> 0
            }
        }
    }

    // Includes the drag itself, not just the page animation it ends in - the
    // handle has to answer while the finger is still on it, and the pager only
    // starts moving once the gesture is over.
    val paging = swiping || pagerState.isScrollInProgress
    val engaged = paging || pullDirection != 0

    // A bigger target on a bigger screen, where the bar has the room for it
    // and the reach to it is longer.
    val restWidth = if (wide) HANDLE_WIDTH_WIDE else HANDLE_WIDTH
    val activeWidth = if (wide) HANDLE_WIDTH_WIDE_ACTIVE else HANDLE_WIDTH_ACTIVE
    val stripHeight = if (wide) HANDLE_STRIP_HEIGHT_WIDE else HANDLE_STRIP_HEIGHT
    val handleWidth by animateDpAsState(
        targetValue = if (paging) activeWidth else restWidth,
        label = "handleWidth",
    )
    // The vertical counterpart of that widening: it thickens instead, and
    // anchors to the edge the pull is coming from so the growth goes the way
    // the finger is going. -1 pins the top edge, 1 pins the bottom.
    val handleThickness by animateDpAsState(
        targetValue = if (pullDirection != 0) HANDLE_THICKNESS_ACTIVE else HANDLE_THICKNESS,
        label = "handleThickness",
    )
    // Half the growth, in the direction of the pull, which keeps the edge the
    // growth starts from exactly where it was. Animated in its own right
    // rather than derived from the thickness, so the two stay in step on the
    // way back as well - a shift that snapped to zero while the thickness
    // eased back would jump.
    val handleShift by animateDpAsState(
        targetValue = when (pullDirection) {
            1 -> HANDLE_GROWTH / 2
            -1 -> -HANDLE_GROWTH / 2
            else -> 0.dp
        },
        label = "handleShift",
    )
    val liquid = isLiquid
    val handleColor by animateColorAsState(
        targetValue = when {
            // Now Playing's grabber: white, brighter while it is held.
            liquid && engaged -> LocalLiquidColors.current.label.copy(alpha = 0.8f)
            liquid -> LocalLiquidColors.current.label.copy(alpha = 0.3f)
            engaged -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        label = "handleColor",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            // Both axes come out of one gesture, decided once.
            //
            // They were a vertical draggable stacked on a horizontal
            // scrollable, each waiting for the touch slop on its own axis, and
            // a slightly diagonal swipe could be claimed by the wrong one -
            // after which the other never saw it and the swipe did nothing.
            //
            // Sideways is also a discrete decision now rather than dragging
            // the pager along with the finger. Dragging it moved the page by
            // exactly what the finger moved, so a short flick across a thin
            // strip was a few percent of a page wide - under any sane snap
            // threshold, so the pager returned it to where it started. Which
            // way the finger went is the whole of what a swipe here means.
            .pointerInput(pagerState) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    val tracker = VelocityTracker()
                    var drift = Offset.Zero
                    var sideways: Boolean? = null

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        tracker.addPointerInputChange(change)
                        drift += change.positionChange()

                        if (sideways == null && drift.getDistance() > slop) {
                            sideways = abs(drift.x) > abs(drift.y)
                            pulling = sideways == false
                            swiping = sideways == true
                        }
                        if (sideways != null) {
                            change.consume()
                            if (sideways == false) pulled = drift.y
                        }
                    }

                    val velocity = tracker.calculateVelocity()
                    when (sideways) {
                        true -> {
                            // From where the pager is heading rather than where
                            // it is: two quick swipes are two pages, and the
                            // second no longer re-aims at the page the first
                            // was already on its way to.
                            val page = pagerState.targetPage + when {
                                drift.x < -slop || velocity.x < -HANDLE_FLING_VELOCITY -> 1
                                drift.x > slop || velocity.x > HANDLE_FLING_VELOCITY -> -1
                                else -> 0
                            }
                            if (page in 0 until pagerState.pageCount) {
                                scope.launch { pagerState.animateScrollToPage(page) }
                            }
                        }

                        false -> {
                            // Downwards asks for less than upwards. Collapsing
                            // is the move made to give the visualiser more
                            // room, so it is the one worth reaching for, and
                            // overshooting costs one pull back.
                            val down = pulled > HANDLE_PULL_THRESHOLD_DOWN ||
                                velocity.y > HANDLE_FLING_VELOCITY_DOWN
                            val up = pulled < -HANDLE_PULL_THRESHOLD ||
                                velocity.y < -HANDLE_FLING_VELOCITY
                            when {
                                down -> onControlsExpanded(false)
                                up -> onControlsExpanded(true)
                            }
                        }

                        null -> Unit
                    }
                    pulling = false
                    swiping = false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (divider) HorizontalDivider(modifier = Modifier.fillMaxWidth())
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(stripHeight),
            contentAlignment = Alignment.Center,
            content = {
                Box(
                    modifier = Modifier
                        .offset(y = handleShift)
                        .size(width = handleWidth, height = handleThickness)
                        .clip(CircleShape)
                        .background(handleColor)
                )
            }
        )
    }
}

@Composable
private fun PlayerAlertDialog(
    onDismissRequest: () -> Unit,
    icon: ImageVector,
    title: String,
    content: @Composable (() -> Unit)
) {
    AppAlertDialog(
        onDismissRequest = onDismissRequest,
        icon = { Icon(imageVector = icon, contentDescription = null) },
        title = { Text(text = title) },
        text = content,
        confirmButton = {
            TextButton(
                onClick = onDismissRequest,
                content = { Text(text = stringResource(R.string.close)) }
            )
        }
    )
}

/***********
 * Preview *
 ***********/

private val previewQueue = Array(10) {
    val number = it + 1
    ModuleEntity(
        filePath = "content://preview/$number",
        filename = "a_journey_into_sound.far",
        fileExtension = "far",
        fileSize = 123456L,
        moduleName = "A Journey Into Sound",
        moduleType = "Farandole Composer",
    )
}.toPersistentList()

private val previewPlayerState = PlayerUiState(
    status = PlaybackStatus.PLAYING,
    currentModule = previewQueue[1],
    queue = previewQueue,
    currentQueueIndex = 1,
    repeatMode = 2,
    currentSequence = 2,
    modVars = ModVars(
        name = "Beneath the Fallen Stars",
        type = "Impulse Tracker",
        seqData = arrayOf(
            Sequence(entryPoint = 0, duration = 237_000),
            Sequence(entryPoint = 0, duration = 237_000),
            Sequence(entryPoint = 0, duration = 237_000),
        ),
        instruments = Array(12) { "Instrument ${it + 1}" },
    ),
)

private class PlayerPreviewParameter :
    PreviewParameterProvider<Triple<PlayerUiState, Boolean, Boolean>> {
    override val values = sequenceOf(
        Triple(previewPlayerState, false, false),
        Triple(previewPlayerState, true, false), // queue sheet
        Triple(previewPlayerState, false, true), // durations sheet
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun Preview(
    @PreviewParameter(PlayerPreviewParameter::class) params: Triple<PlayerUiState, Boolean, Boolean>
) {
    val (state, showQueue, showDurations) = params
    val queueSheetState = SheetState(
        enabledValues = setOf(Hidden, Expanded),
        initialValue = if (showQueue) Expanded else Hidden,
        positionalThreshold = { 1f },
        velocityThreshold = { 1f },
    )
    val durationsSheetState = SheetState(
        enabledValues = setOf(Hidden, Expanded),
        initialValue = if (showDurations) Expanded else Hidden,
        positionalThreshold = { 1f },
        velocityThreshold = { 1f },
    )
    AppTheme {
        PlayerScreenContent(
            state = state,
            isFavourite = false,
            visuals = remember { PlaybackVisuals() },
            snackBarHostState = SnackbarHostState(),
            patternData = PatternData(),
            queueSheetState = queueSheetState,
            durationsSheetState = durationsSheetState,
            showQueue = showQueue,
            showDurations = showDurations,
            onAction = {},
        )
    }
}
