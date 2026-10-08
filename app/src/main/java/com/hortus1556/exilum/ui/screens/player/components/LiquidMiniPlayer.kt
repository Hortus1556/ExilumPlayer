package com.hortus1556.exilum.ui.screens.player.components

import kotlinx.coroutines.delay
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.hortus1556.exilum.ui.liquid.LiquidBarsState
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.composed
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hortus1556.exilum.R
import com.hortus1556.exilum.player.PlaybackStatus
import com.hortus1556.exilum.player.PlayerUiState
import com.hortus1556.exilum.ui.liquid.ContinuousShape
import com.hortus1556.exilum.ui.liquid.LiquidGlyphButton
import com.hortus1556.exilum.ui.liquid.LiquidType
import com.hortus1556.exilum.ui.liquid.LocalLiquidColors
import com.hortus1556.exilum.ui.oneui.trackLook

/**
 * The mini player as iOS 26's Music draws it in the tab bar's accessory: the
 * track's artwork (here its colors) on a small rounded square, the title
 * with what it is under it, and play/pause and skip as bare glyphs. The
 * glass it sits on is the accessory's. [inline] is the tighter form it takes
 * beside the folded tab bar.
 *
 * Slid to the left it goes and takes the playback with it; lifted, it opens
 * Now Playing. Given [bars], the glass it sits on moves with it.
 */
@Composable
fun LiquidMiniPlayer(
    state: PlayerUiState,
    onTap: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    inline: Boolean,
    bars: LiquidBarsState? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalLiquidColors.current
    val playing = state.status == PlaybackStatus.PLAYING
    val key = state.currentModule?.filePath.orEmpty()
    val look = remember(key, colors.isDark) { trackLook(key, colors.isDark) }
    val source = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .fillMaxSize()
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onTap)
            .then(if (bars != null) Modifier.miniPlayerPulls(bars, onStop, onTap) else Modifier)
            .padding(start = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(ContinuousShape(9.dp))
                .background(Brush.linearGradient(look.palette)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(38.dp),
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = state.modVars.modName.ifEmpty { state.currentModule?.name.orEmpty() },
                style = LiquidType.Subheadline.copy(fontWeight = FontWeight.SemiBold),
                color = colors.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!inline) {
                Text(
                    text = state.modVars.modType,
                    style = LiquidType.Footnote,
                    color = colors.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AnimatedContent(
            targetState = playing,
            transitionSpec = {
                (scaleIn(initialScale = 0.5f) + fadeIn(tween(120))) togetherWith (scaleOut(targetScale = 0.5f) + fadeOut(tween(90)))
            },
            label = "mini_play_pause",
        ) { isPlaying ->
            LiquidGlyphButton(
                icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null,
                onClick = onPlayPause,
                glyph = 26.dp,
                tint = colors.label,
            )
        }
        LiquidGlyphButton(
            icon = Icons.Filled.FastForward,
            contentDescription = null,
            onClick = onNext,
            enabled = state.hasNext,
            glyph = 24.dp,
            tint = colors.label,
        )
    }
}

/** Which way the mini player is being pulled, once it is clear which. */
private enum class Pull { None, Away, Up }

/**
 * The two pulls the mini player answers: to the left it goes, and what is
 * playing stops with it; upwards it lifts a little and lets go into Now
 * Playing.
 *
 * One gesture rather than two draggables, since the way it is going has to
 * be settled once and then kept to - a slide that wanders upwards is still
 * a slide. What moves is the accessory's glass, through [bars], so the whole
 * thing goes rather than its contents sliding out of a capsule left behind.
 */
private fun Modifier.miniPlayerPulls(
    bars: LiquidBarsState,
    onStop: () -> Unit,
    onOpen: () -> Unit,
): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var width by remember { mutableFloatStateOf(0f) }
    val settle: () -> Unit = {
        scope.launch {
            animate(Offset.VectorConverter, bars.accessoryShift, Offset.Zero, animationSpec = spring()) { v, _ ->
                bars.accessoryShift = v
            }
        }
    }
    this
        .onSizeChanged { width = it.width.toFloat() }
        .pointerInput(bars) {
            val slop = viewConfiguration.touchSlop
            val lift = 14.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var pull = Pull.None
                var travel = Offset.Zero
                var ours = true
                // Against how far the finger has come, not where it is: the
                // capsule is being moved under it, so its own position in
                // here moves with it.
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, Offset.Zero)
                while (ours) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    travel += change.positionChange()
                    tracker.addPosition(change.uptimeMillis, travel)
                    if (pull == Pull.None) {
                        val x = travel.x
                        val y = travel.y
                        when {
                            x < -slop && abs(x) > abs(y) -> pull = Pull.Away
                            y < -slop && abs(y) >= abs(x) -> pull = Pull.Up
                            // Rightwards or downwards is nobody's here: let it
                            // be, rather than holding on to a gesture that
                            // does nothing and swallowing the tap it ends in.
                            abs(x) > slop || abs(y) > slop -> ours = false
                        }
                    }
                    if (pull != Pull.None) {
                        change.consume()
                        bars.accessoryShift = if (pull == Pull.Away) {
                            Offset(x = travel.x.coerceAtMost(0f), y = 0f)
                        } else {
                            // Only a hint of a lift: it is a flick, not a drag.
                            Offset(x = 0f, y = (travel.y * 0.3f).coerceAtMost(0f))
                        }
                    }
                }
                val shift = bars.accessoryShift
                val speed = tracker.calculateVelocity()
                when {
                    // Thrown or carried: a flick puts it away as surely as
                    // dragging it a third of the way across does.
                    pull == Pull.Away && width > 0f &&
                        (-shift.x >= width * 0.3f || speed.x <= -600f) -> scope.launch {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        launch {
                            animate(
                                Offset.VectorConverter,
                                shift,
                                Offset(x = -width, y = 0f),
                                initialVelocity = Offset(x = speed.x.coerceAtMost(0f), y = 0f),
                                animationSpec = spring(stiffness = 1600f),
                            ) { v, _ -> bars.accessoryShift = v }
                        }
                        // The playback goes while it is still on its way out,
                        // with only enough of a head start to be past the edge
                        // by then: what comes back in its place should not
                        // have to wait out the whole flight first.
                        delay(70)
                        onStop()
                        // The bar forgets where it was pushed to once it has
                        // gone. Putting it back here instead would stand it
                        // up again for the frame before it goes.
                        delay(500)
                        if (bars.accessoryShift != Offset.Zero) settle()
                    }

                    pull == Pull.Up && (-shift.y >= lift * 0.3f || speed.y <= -500f) -> {
                        onOpen()
                        settle()
                    }

                    pull != Pull.None -> settle()
                }
            }
        }
}
