package com.hortus1556.exilum.ui.screens.player.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.media3.common.Player
import com.hortus1556.exilum.R
import com.hortus1556.exilum.db.entity.ModuleEntity
import com.hortus1556.exilum.player.PlaybackStatus
import com.hortus1556.exilum.player.PlayerUiState
import com.hortus1556.exilum.ui.theme.AppTheme
import com.hortus1556.exilum.ui.theme.isOneUi
import com.hortus1556.exilum.ui.oneui.LocalOneUiColors
import com.hortus1556.exilum.ui.oneui.LocalTrackLook
import com.hortus1556.exilum.ui.oneui.OneUiWaveformProgress
import com.hortus1556.exilum.ui.oneui.trackLook
import com.hortus1556.exilum.renderer.model.FrameInfo
import com.hortus1556.exilum.renderer.model.ModVars
import kotlin.math.abs
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MiniPlayerBar(
    state: PlayerUiState,
    frameInfo: State<FrameInfo>,
    onTap: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onDismiss: () -> Unit,
    /**
     * Whether the bar can be seen at all: false under the open player, or
     * slid away below the screen. It stays built either way, and all this
     * stops is One UI's playback line, the one thing here that moves without
     * being touched - see OneUiWaveformProgress.
     */
    visible: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val isPlaying = state.status == PlaybackStatus.PLAYING

    var componentWidth by remember { mutableFloatStateOf(0f) }
    var swipeOffset by remember { mutableFloatStateOf(0f) }

    // A track arriving puts the bar back where it belongs.
    //
    // Swiped away, it is left a whole width off to the side and fully faded,
    // which is right while it goes: the card it sits in is on its way out. But
    // that exit takes a third of a second, and a file tapped within it brought
    // the same bar back in still standing there - under One UI an empty card,
    // with nothing on it to open the player or stop the music.
    val hasTrack = state.currentModule != null
    LaunchedEffect(hasTrack) {
        if (hasTrack) swipeOffset = 0f
    }

    val dismissDirection by remember {
        derivedStateOf {
            when {
                swipeOffset > 2f -> SwipeToDismissBoxValue.StartToEnd
                swipeOffset < -2f -> SwipeToDismissBoxValue.EndToStart
                else -> SwipeToDismissBoxValue.Settled
            }
        }
    }

    val hasNext = state.hasNext
    val hasPrev = state.hasPrev

    // Under One UI the bar sits in a frosted card of its own (see
    // MainNavigation): it draws no background, fades as it is swiped away
    // instead of uncovering a stop button, and has One UI's playback line.
    val oneUi = isOneUi
    // The track's own colors, standing in for album art: on the tile and
    // along the waveform.
    val dark = LocalOneUiColors.current.isDark
    val trackKey = state.currentModule?.filePath.orEmpty()
    val look = remember(trackKey, dark) { trackLook(trackKey, dark) }

    LaunchedEffect(dismissDirection) {
        if (dismissDirection != SwipeToDismissBoxValue.Settled) {
            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
        }
    }

    val bgAlignment = when (dismissDirection) {
        SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
        SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
        SwipeToDismissBoxValue.Settled -> Alignment.Center
    }

    Box(
        modifier = Modifier
            .onSizeChanged { componentWidth = it.width.toFloat() }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    swipeOffset = (swipeOffset + delta).coerceIn(-componentWidth, componentWidth)
                },
                onDragStopped = {
                    scope.launch {
                        if (componentWidth > 0f && abs(swipeOffset) / componentWidth >= 0.75f) {
                            val target = if (swipeOffset > 0f) componentWidth else -componentWidth
                            animate(initialValue = swipeOffset, targetValue = target) { v, _ ->
                                swipeOffset = v
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            onDismiss()
                        } else {
                            animate(
                                initialValue = swipeOffset,
                                targetValue = 0f,
                                animationSpec = spring(),
                            ) { v, _ -> swipeOffset = v }
                        }
                    }
                }
            )
    ) {
        if (!oneUi) Box(
            modifier = Modifier
                .matchParentSize()
                .padding(6.dp)
                .background(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                )
                .padding(horizontal = 20.dp),
            contentAlignment = bgAlignment,
        ) {
            Icon(
                imageVector = Icons.Default.StopCircle,
                contentDescription = stringResource(R.string.desc_stop),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(swipeOffset.toInt(), 0) }
                .graphicsLayer {
                    if (oneUi && componentWidth > 0f) {
                        alpha = (1f - abs(swipeOffset) / componentWidth).coerceIn(0f, 1f)
                    }
                }
        ) {
            ListItem(
                modifier = Modifier.padding(6.dp),
                shapes = ListItemDefaults.shapes(
                    shape = MaterialTheme.shapes.small,
                    focusedShape = MaterialTheme.shapes.small,
                    pressedShape = MaterialTheme.shapes.small,
                ),
                onClick = onTap,
                colors = ListItemDefaults.colors(
                    containerColor = if (oneUi) Color.Transparent else MaterialTheme.colorScheme.onSecondary
                ),
                leadingContent = {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        // The glyph is white: on a light Material theme it is
                        // drawn in the text color instead, or it is not seen.
                        colorFilter = if (!oneUi && MaterialTheme.colorScheme.background.luminance() > 0.5f) {
                            ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            null
                        },
                        modifier = Modifier
                            .size(48.dp)
                            // One UI puts the artwork on a tile of its own -
                            // and the glyph is white, lost on a light card.
                            .then(
                                if (oneUi) {
                                    Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Brush.linearGradient(look.palette))
                                } else {
                                    Modifier
                                }
                            )
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
                    )
                },
                trailingContent = {
                    Row {
                        IconButton(
                            onClick = onPrevious,
                            enabled = hasPrev,
                            content = {
                                Icon(
                                    imageVector = Icons.Default.SkipPrevious,
                                    contentDescription = null
                                )
                            }
                        )
                        IconButton(
                            onClick = onPlayPause,
                            content = {
                                val icon = if (isPlaying) {
                                    Icons.Default.Pause
                                } else {
                                    Icons.Default.PlayArrow
                                }
                                Icon(imageVector = icon, contentDescription = null)
                            }
                        )
                        IconButton(
                            onClick = onNext,
                            enabled = hasNext,
                            content = {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = null
                                )
                            }
                        )
                    }
                },
                content = {
                    Text(
                        text = state.modVars.modName.ifEmpty {
                            state.currentModule?.name.orEmpty()
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                supportingContent = {
                    Column {
                        Text(
                            text = state.modVars.modType,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        if (oneUi) {
                            CompositionLocalProvider(LocalTrackLook provides look) {
                                OneUiWaveformProgress(
                                    progress = {
                                        val fi = frameInfo.value
                                        val duration = fi.totalTime.toFloat().coerceAtLeast(1f)
                                        (fi.time.toFloat() / duration).coerceIn(0f, 1f)
                                    },
                                    playing = isPlaying,
                                    modifier = Modifier.padding(top = 2.dp),
                                    visible = visible,
                                )
                            }
                        } else {
                            LinearWavyProgressIndicator(
                                // Read frameInfo.value inside this lambda, not
                                // eagerly in the composable body: the lambda-based
                                // progress API exists specifically so the read
                                // happens at draw time and only invalidates this
                                // indicator, not the whole MiniPlayerBar (buttons,
                                // SwipeToDismissBox, etc). Capturing an
                                // already-computed val here would defeat that -
                                // frameInfo now updates ~200Hz (native renders in
                                // ~5ms sub-chunks, see renderOpenMpt in
                                // player-libopenmpt.cpp), so getting this wrong
                                // would recompose the entire bar at that rate.
                                progress = {
                                    val fi = frameInfo.value
                                    val duration = fi.totalTime.toFloat().coerceAtLeast(1f)
                                    (fi.time.toFloat() / duration).coerceIn(0f, 1f)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                amplitude = if (isPlaying) {
                                    WavyProgressIndicatorDefaults.indicatorAmplitude
                                } else {
                                    { 0f }
                                }
                            )
                        }
                    }
                }
            )
        }
    }
}

/***********
 * Preview *
 ***********/

private data class MiniPlayerPreviewState(
    val description: String,
    val state: PlayerUiState,
    val frameInfo: FrameInfo
)

private class MiniPlayerPreviewParameter : PreviewParameterProvider<MiniPlayerPreviewState> {

    private val sampleFiles = persistentListOf(
        ModuleEntity(
            fileExtension = "far",
            moduleName = "A Journey Into Sound",
            moduleType = "Farandole Composer",
        ),
        ModuleEntity(
            fileExtension = "it",
            moduleName = "Beneath the Fallen Stars",
            moduleType = "Impulse Tracker",
        ),
        ModuleEntity(
            fileExtension = "mod",
            moduleName = "alpharapii",
            moduleType = "Amiga Protracker/Compatible",
        ),
        ModuleEntity(
            fileExtension = "mod",
            moduleName = "Chiptune No. 184",
            moduleType = "Amiga Protracker/Compatible",
        ),
    )

    private val itModVars = ModVars(name = "Beneath the Fallen Stars", type = "Impulse Tracker")

    private val baseFrameInfo = FrameInfo(time = 47_000, totalTime = 237_000)

    override val values = sequenceOf(
        MiniPlayerPreviewState(
            description = "Playing - mid queue",
            state = PlayerUiState(
                status = PlaybackStatus.PLAYING,
                currentModule = sampleFiles[1],
                currentQueueIndex = 1,
                queue = sampleFiles,
                modVars = itModVars,
            ),
            frameInfo = baseFrameInfo,
        ),
        MiniPlayerPreviewState(
            description = "Paused - mid queue",
            state = PlayerUiState(
                status = PlaybackStatus.PAUSED,
                currentModule = sampleFiles[1],
                currentQueueIndex = 1,
                queue = sampleFiles,
                modVars = itModVars,
            ),
            frameInfo = baseFrameInfo,
        ),
        MiniPlayerPreviewState(
            description = "Playing - first track (no prev)",
            state = PlayerUiState(
                status = PlaybackStatus.PLAYING,
                currentModule = sampleFiles[0],
                currentQueueIndex = 0,
                queue = sampleFiles,
                modVars = itModVars,
            ),
            frameInfo = baseFrameInfo.copy(time = 12_000),
        ),
        MiniPlayerPreviewState(
            description = "Playing - last track (no next)",
            state = PlayerUiState(
                status = PlaybackStatus.PLAYING,
                currentModule = sampleFiles[3],
                currentQueueIndex = 3,
                queue = sampleFiles,
                modVars = itModVars,
            ),
            frameInfo = baseFrameInfo.copy(time = 190_000),
        ),
        MiniPlayerPreviewState(
            description = "Playing - repeat all",
            state = PlayerUiState(
                status = PlaybackStatus.PLAYING,
                currentModule = sampleFiles[3],
                currentQueueIndex = 3,
                queue = sampleFiles,
                repeatMode = Player.REPEAT_MODE_ALL,
                modVars = itModVars,
            ),
            frameInfo = baseFrameInfo,
        ),
    )

    override fun getDisplayName(index: Int): String {
        return values.toList()[index].description
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview(
    @PreviewParameter(MiniPlayerPreviewParameter::class) params: MiniPlayerPreviewState
) {
    AppTheme {
        MiniPlayerBar(
            state = params.state,
            frameInfo = remember { mutableStateOf(params.frameInfo) },
            onTap = {},
            onPlayPause = {},
            onNext = {},
            onPrevious = {},
            onDismiss = {},
        )
    }
}
