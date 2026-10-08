package com.hortus1556.exilum.ui.screens.player.components

import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Star
import com.hortus1556.exilum.ui.liquid.LocalLiquidColors
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.animation.togetherWith
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.AnimatedContent
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.liquid.LiquidGlyphButton
import com.hortus1556.exilum.ui.oneui.LocalOneUiColors
import com.hortus1556.exilum.ui.oneui.OneUiMediaButton
import com.hortus1556.exilum.ui.oneui.OneUiPlayPauseButton
import com.hortus1556.exilum.ui.theme.isOneUi
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.hortus1556.exilum.R
import com.hortus1556.exilum.player.PlaybackStatus
import com.hortus1556.exilum.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TransportRow(
    status: PlaybackStatus,
    hasNext: Boolean,
    hasPrev: Boolean,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    isFavourite: Boolean,
    onFavourite: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    // Spread across the full width when it owns a row, packed to one side when
    // it shares one with the seek bar.
    arrangement: Arrangement.Horizontal = Arrangement.SpaceEvenly,
    onQueueSheet: () -> Unit
) {
    if (isLiquid) {
        LiquidTransportRow(
            status = status,
            hasNext = hasNext,
            hasPrev = hasPrev,
            isFavourite = isFavourite,
            onFavourite = onFavourite,
            onPrev = onPrev,
            onPlayPause = onPlayPause,
            onNext = onNext,
            modifier = modifier,
            compact = compact,
            arrangement = arrangement,
            onQueueSheet = onQueueSheet,
        )
        return
    }
    if (isOneUi) {
        OneUiTransportRow(
            status = status,
            hasNext = hasNext,
            hasPrev = hasPrev,
            onPrev = onPrev,
            onPlayPause = onPlayPause,
            onNext = onNext,
            modifier = modifier,
            compact = compact,
            arrangement = arrangement,
            onQueueSheet = onQueueSheet,
        )
        return
    }

    Row(
        modifier = modifier.padding(vertical = if (compact) 0.dp else 4.dp),
        horizontalArrangement = arrangement,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onPrev,
            enabled = hasPrev,
            content = {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    modifier = Modifier.size(if (compact) 28.dp else 32.dp),
                )
            }
        )

        // One round button for every layout - expanded, collapsed and the wide
        // bar. The expanded bar had a FAB, a rounded square of a different size,
        // so the same control changed shape every time the bar was folded.
        FilledIconButton(
            onClick = onPlayPause,
            content = {
                Icon(
                    imageVector = if (status == PlaybackStatus.PLAYING) {
                        Icons.Default.Pause
                    } else {
                        Icons.Default.PlayArrow
                    },
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
            }
        )

        IconButton(
            onClick = onNext,
            enabled = hasNext,
            content = {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    modifier = Modifier.size(if (compact) 28.dp else 32.dp),
                )
            }
        )

        IconButton(
            onClick = onFavourite,
            content = {
                Icon(
                    imageVector = if (isFavourite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = stringResource(
                        if (isFavourite) R.string.desc_remove_favourite else R.string.desc_add_favourite
                    ),
                    modifier = Modifier.size(24.dp),
                )
            }
        )

        IconButton(
            onClick = onQueueSheet,
            content = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = "Open Queue",
                    modifier = Modifier.size(24.dp),
                )
            }
        )
    }
}

/**
 * The transport as Samsung Music lays it out: bare glyphs, play and pause the
 * biggest thing on the row, skip either side of it, and the lesser controls
 * small and gray at the ends. The favourite is not here - One UI puts it by
 * the title, in the top bar.
 */
@Composable
private fun OneUiTransportRow(
    status: PlaybackStatus,
    hasNext: Boolean,
    hasPrev: Boolean,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier,
    compact: Boolean,
    arrangement: Arrangement.Horizontal,
    onQueueSheet: () -> Unit,
) {
    val colors = LocalOneUiColors.current
    Row(
        modifier = modifier.padding(vertical = if (compact) 0.dp else 6.dp),
        horizontalArrangement = arrangement,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OneUiMediaButton(
            icon = Icons.Default.SkipPrevious,
            contentDescription = "Previous",
            onClick = onPrev,
            enabled = hasPrev,
            glyph = if (compact) 30.dp else 38.dp,
        )
        OneUiPlayPauseButton(
            playing = status == PlaybackStatus.PLAYING,
            playIcon = Icons.Default.PlayArrow,
            pauseIcon = Icons.Default.Pause,
            onClick = onPlayPause,
            glyph = if (compact) 40.dp else 54.dp,
        )
        OneUiMediaButton(
            icon = Icons.Default.SkipNext,
            contentDescription = "Next",
            onClick = onNext,
            enabled = hasNext,
            glyph = if (compact) 30.dp else 38.dp,
        )
        OneUiMediaButton(
            icon = Icons.AutoMirrored.Filled.QueueMusic,
            contentDescription = "Open Queue",
            onClick = onQueueSheet,
            glyph = 24.dp,
            tint = colors.textSecondary,
        )
    }
}

/**
 * The transport as Music on iOS 26 draws Now Playing's: white glyphs with
 * nothing behind them, play and pause the biggest, back and forward the
 * doubled triangles either side of it, the lesser two small and dimmer at the
 * ends. A glyph gives way under the finger and a soft disc of light rises
 * behind it; play and pause swap with a shrink and a grow rather than a cut.
 */
@Composable
private fun LiquidTransportRow(
    status: PlaybackStatus,
    hasNext: Boolean,
    hasPrev: Boolean,
    isFavourite: Boolean,
    onFavourite: () -> Unit,
    onPrev: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier,
    compact: Boolean,
    arrangement: Arrangement.Horizontal,
    onQueueSheet: () -> Unit,
) {
    val label = LocalLiquidColors.current.label
    val dim = label.copy(alpha = 0.62f)
    Row(
        modifier = modifier.padding(vertical = if (compact) 0.dp else 8.dp),
        horizontalArrangement = arrangement,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The favourite sits where stop used to. Stopping is what closing
        // the player or sliding the mini player away does; starring a tune is
        // the thing wanted while it plays, and this row is where the thumb is.
        LiquidGlyphButton(
            icon = if (isFavourite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            contentDescription = stringResource(
                if (isFavourite) R.string.desc_remove_favourite else R.string.desc_add_favourite
            ),
            onClick = onFavourite,
            glyph = 24.dp,
            tint = if (isFavourite) LocalLiquidColors.current.tint else dim,
            halo = true,
        )
        LiquidGlyphButton(
            icon = Icons.Rounded.FastRewind,
            contentDescription = "Previous",
            onClick = onPrev,
            enabled = hasPrev,
            glyph = if (compact) 32.dp else 40.dp,
            tint = label,
            halo = true,
        )
        val playing = status == PlaybackStatus.PLAYING
        Box(contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = playing,
                transitionSpec = {
                    (scaleIn(initialScale = 0.4f, animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)) +
                        fadeIn(tween(110))) togetherWith
                        (scaleOut(targetScale = 0.4f, animationSpec = tween(120)) + fadeOut(tween(90)))
                },
                label = "liquid_play_pause",
            ) { isPlaying ->
                LiquidGlyphButton(
                    icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    onClick = onPlayPause,
                    glyph = if (compact) 42.dp else 54.dp,
                    tint = label,
                    halo = true,
                )
            }
        }
        LiquidGlyphButton(
            icon = Icons.Rounded.FastForward,
            contentDescription = "Next",
            onClick = onNext,
            enabled = hasNext,
            glyph = if (compact) 32.dp else 40.dp,
            tint = label,
            halo = true,
        )
        LiquidGlyphButton(
            icon = Icons.AutoMirrored.Rounded.QueueMusic,
            contentDescription = "Open Queue",
            onClick = onQueueSheet,
            glyph = 24.dp,
            tint = dim,
            halo = true,
        )
    }
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface {
            TransportRow(
                modifier = Modifier.fillMaxWidth(),
                status = PlaybackStatus.PLAYING,
                hasNext = false,
                hasPrev = true,
                onPrev = {},
                onPlayPause = {},
                onNext = {},
                isFavourite = true,
                onFavourite = {},
                onQueueSheet = {},
            )
        }
    }
}
