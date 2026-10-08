package com.hortus1556.exilum.ui.screens.player.components

import androidx.compose.runtime.derivedStateOf
import com.hortus1556.exilum.ui.liquid.LocalLiquidColors
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Box
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.liquid.LiquidType
import com.hortus1556.exilum.ui.liquid.LiquidScrubber
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.sp
import com.hortus1556.exilum.ui.oneui.LocalOneUiColors
import com.hortus1556.exilum.ui.oneui.OneUiSeekThumb
import com.hortus1556.exilum.ui.oneui.OneUiType
import com.hortus1556.exilum.ui.oneui.WaveformScratch
import com.hortus1556.exilum.ui.oneui.currentTrackPalette
import com.hortus1556.exilum.ui.oneui.currentTrackSeed
import com.hortus1556.exilum.ui.oneui.drawWaveform
import com.hortus1556.exilum.ui.oneui.rememberWaveformClock
import com.hortus1556.exilum.ui.theme.isOneUi
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.hortus1556.exilum.data.TimeDisplay
import com.hortus1556.exilum.ui.screens.player.PlaybackVisuals
import com.hortus1556.exilum.ui.theme.AppTheme
import com.hortus1556.exilum.util.formatMs
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** How far the position must drop in one step to count as a rewind rather
 * than ordinary playback, as a fraction of the song. */
private const val REWIND_THRESHOLD = 0.02f

/** Long enough to read as a rewind, short enough not to lie about where
 * playback actually is. */
private const val REWIND_DURATION_MS = 320

/** How close the engine must get to a requested seek before the bar starts
 * following the engine again. */
private const val SEEK_SETTLE = 0.01f

/** Give up waiting for a seek to land after this many updates, so a seek the
 * engine never honours cannot freeze the bar. */
private const val SEEK_SETTLE_TIMEOUT_UPDATES = 60

/** Matches the height of the expressive slider track this replaces. */
private val TRACK_HEIGHT = 16.dp

/** Clearance left either side of the thumb, so it reads as sitting in the
 * track rather than on top of it. */
private val TRACK_GAP = 6.dp

private data class Progress(val fraction: Float, val totalMs: Int)

/**
 * Elapsed / total time and the seek bar.
 *
 * No extrapolation. The position comes from a frame-stamped snapshot read
 * once per display frame and is already both exact and latency-compensated,
 * so there is nothing to smooth over - projecting from it could only add
 * error. The one exception is going *backwards*: see below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackProgress(
    visuals: PlaybackVisuals,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** How the two times read - see TimeDisplay. */
    timeDisplay: Int = TimeDisplay.CLOCK,
    /** A custom loop's two ends, drawn on the bar; -1 for none. */
    loopStartMs: Int = -1,
    loopEndMs: Int = -1,
    /** Whether the music is moving - under One UI the line waves only then. */
    playing: Boolean = false,
) {
    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    // Where the user asked playback to go, held until the engine reports
    // having got there.
    //
    // Releasing the slider used to hand the bar straight back to the engine's
    // reported position - which is still the *old* one, because the position
    // is latency-compensated and the pre-seek audio is still in the output
    // buffer. The bar therefore snapped back to where it came from and only
    // reached the new spot once that buffer drained, which reads as the drag
    // not having taken. Paused it never resolved at all: nothing is being
    // rendered, so no newer position ever arrives.
    var pendingSeek by remember { mutableStateOf<Float?>(null) }

    // Normally this just tracks the real position exactly, snapping to it on
    // every update - the underlying value already advances smoothly, so
    // animating it would only add lag. It animates in one case: when the
    // position jumps *backwards* because the song looped. Snapping there
    // teleports a full-width bar back to the start in a single frame, which
    // reads as a glitch rather than as a repeat, so it is walked back instead.
    val displayed = remember { Animatable(0f) }

    LaunchedEffect(visuals) {
        var previous = Progress(0f, 0)
        var waitingForSeek = 0
        snapshotFlow {
            @Suppress("UNUSED_EXPRESSION") visuals.infoTick
            val s = visuals.snapshot
            val total = s.totalTimeMs
            Progress(
                fraction = (s.timeMs.toFloat() / total.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f),
                totalMs = total,
            )
        }.collect { current ->
            val target = pendingSeek
            if (target != null) {
                waitingForSeek++
                val arrived = abs(current.fraction - target) < SEEK_SETTLE
                if (!arrived && waitingForSeek < SEEK_SETTLE_TIMEOUT_UPDATES) {
                    // Still the pre-seek position; keep showing where the user
                    // put it rather than snapping back to where it came from.
                    previous = current
                    return@collect
                }
                pendingSeek = null
                waitingForSeek = 0
                // Guarded like the rewind below: a slider release landing at
                // the same moment interrupts this snap in the same way.
                try {
                    displayed.snapTo(current.fraction)
                } catch (interrupted: CancellationException) {
                    currentCoroutineContext().ensureActive()
                }
                previous = current
                return@collect
            }

            val loopedBack = current.totalMs == previous.totalMs &&
                previous.fraction - current.fraction > REWIND_THRESHOLD
            if (loopedBack) {
                // Suspends for the duration of the rewind. Updates arriving
                // meanwhile are conflated by snapshotFlow, so the next one
                // collected is the current position rather than a backlog.
                //
                // Guarded, because the rewind can be cut short: releasing the
                // slider while it runs snaps this same Animatable, and an
                // interrupted animateTo throws CancellationException. Uncaught,
                // that ended this whole collector without a word - and with it
                // every later update, so the bar stopped where it was, usually
                // at the end of the scale after a loop, and would not follow
                // playback or a seek again until the player was reopened. Only
                // an interrupted animation is swallowed; if this effect itself
                // is being cancelled, ensureActive passes that on.
                try {
                    displayed.animateTo(
                        targetValue = current.fraction,
                        animationSpec = tween(REWIND_DURATION_MS, easing = FastOutSlowInEasing),
                    )
                } catch (interrupted: CancellationException) {
                    currentCoroutineContext().ensureActive()
                }
            } else {
                // A different total means a different track, not a repeat -
                // that should start clean, not rewind through the old song.
                try {
                    displayed.snapTo(current.fraction)
                } catch (interrupted: CancellationException) {
                    currentCoroutineContext().ensureActive()
                }
            }
            previous = current
        }
    }

    // Via derivedStateOf so a changing total recomposes this, but a merely
    // advancing position does not - that arrives through displayed.value.
    val totalMs by remember(visuals) {
        derivedStateOf {
            @Suppress("UNUSED_EXPRESSION") visuals.infoTick
            visuals.snapshot.totalTimeMs
        }
    }
    val duration = totalMs.toFloat().coerceAtLeast(1f)
    // Where the bar is, as a function: read where it is drawn and where the
    // times are worked out, never in this body - it moves every frame, and
    // read here it rebuilt all of this every frame the music played.
    val position: () -> Float = { if (isSeeking) seekPosition else displayed.value }
    val activeTrackColor = MaterialTheme.colorScheme.primary
    val inactiveTrackColor = MaterialTheme.colorScheme.secondaryContainer
    val loopMarkColor = MaterialTheme.colorScheme.tertiary
    // Smaller in a precise unit, so a seven-figure byte count leaves the bar
    // some room.
    val timeStyle = if (timeDisplay == TimeDisplay.CLOCK) {
        MaterialTheme.typography.titleMedium
    } else {
        MaterialTheme.typography.labelSmall
    }

    // The times as text, which changes a few times a second at most:
    // derived, so this is rebuilt when the text changes and not when the
    // position merely moves.
    // The clock shows whole seconds, so what is derived every frame is
    // the second, and the text is only put together when it ticks over;
    // the precise displays change every frame and are formatted as often.
    //
    // For One UI as for Liquid Glass. One UI's bar was handed the position
    // as a number instead, read in this body - so every frame the music
    // played rebuilt all of it: this, the slider, the waveform's canvas and
    // both times, sixty to a hundred and twenty times a second.
    val clock = timeDisplay == TimeDisplay.CLOCK
    val elapsedAt by remember(clock, duration) {
        derivedStateOf {
            val at = (position() * duration).toLong()
            if (clock) at / 1000 * 1000 else at
        }
    }
    val elapsed = remember(elapsedAt, timeDisplay) {
        if (clock) elapsedAt.formatMs() else TimeDisplay.format(elapsedAt, timeDisplay)
    }

    if (isLiquid) {
        // Music counts down what is left on the right, with a minus.
        val remainingAt by remember(clock, duration) {
            derivedStateOf {
                if (clock) ((1f - position()) * duration).toLong().coerceAtLeast(0L) / 1000 * 1000 else 0L
            }
        }
        val remaining = remember(remainingAt, timeDisplay, totalMs) {
            if (clock) "-" + remainingAt.formatMs() else TimeDisplay.format(totalMs.toLong(), timeDisplay)
        }
        LiquidSeekBar(
            fraction = position,
            elapsed = elapsed,
            remaining = remaining,
            loopMarks = if (loopStartMs >= 0 && loopEndMs > loopStartMs) {
                loopStartMs / duration to loopEndMs / duration
            } else {
                null
            },
            onValueChange = {
                isSeeking = true
                seekPosition = it
            },
            onValueChangeFinished = {
                onSeek((seekPosition * duration).toLong())
                pendingSeek = seekPosition
                scope.launch { displayed.snapTo(seekPosition) }
                isSeeking = false
            },
            modifier = modifier,
        )
        return
    }

    if (isOneUi) {
        OneUiSeekBar(
            fraction = position,
            playing = playing,
            // How loud it is right now, the loudest channel - read while
            // drawing, so the waveform moves with the music without this
            // recomposing at the rate the levels change.
            level = {
                @Suppress("UNUSED_EXPRESSION") visuals.levelTick
                val channels = visuals.snapshot.numChannels.coerceIn(0, visuals.smoothedLevel.size)
                var loudest = 0f
                for (ch in 0 until channels) loudest = maxOf(loudest, visuals.smoothedLevel[ch])
                loudest.coerceIn(0f, 1f)
            },
            elapsed = elapsed,
            total = if (clock) {
                totalMs.toLong().formatMs()
            } else {
                TimeDisplay.format(totalMs.toLong(), timeDisplay)
            },
            loopMarks = if (loopStartMs >= 0 && loopEndMs > loopStartMs) {
                loopStartMs / duration to loopEndMs / duration
            } else {
                null
            },
            onValueChange = {
                isSeeking = true
                seekPosition = it
            },
            onValueChangeFinished = {
                onSeek((seekPosition * duration).toLong())
                pendingSeek = seekPosition
                scope.launch { displayed.snapTo(seekPosition) }
                isSeeking = false
            },
            modifier = modifier,
        )
        return
    }

    // Material's own bar still takes the position as a number.
    val displayValue = position()

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        content = {
            Text(
                modifier = Modifier.padding(horizontal = 6.dp),
                // Derived from the same value the bar draws, so the two move
                // together - including counting down through a rewind rather
                // than snapping to zero while the bar is still travelling.
                text = if (timeDisplay == TimeDisplay.CLOCK) {
                    (displayValue * duration).toLong().formatMs()
                } else {
                    TimeDisplay.format((displayValue * duration).toLong(), timeDisplay)
                },
                style = timeStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Slider(
                modifier = Modifier.weight(1f),
                value = displayValue,
                // Own track rather than SliderDefaults.Track.
                //
                // The stock expressive track drops its whole remaining
                // segment - and the stop-indicator dot inside it - the moment
                // there is no longer room for them beside the thumb, so
                // approaching the end of a song made a visible chunk of the
                // bar pop out of existence in one frame (the active segment
                // also changing from a squared to a rounded end as it did).
                // Here the remaining segment simply shrinks to nothing, which
                // is the same information without the discontinuity, and
                // there is no fixed-size dot that has to fit anywhere.
                track = {
                    val fraction = displayValue.coerceIn(0f, 1f)
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(TRACK_HEIGHT)
                    ) {
                        val height = size.height
                        val radius = CornerRadius(height / 2f, height / 2f)
                        val gap = TRACK_GAP.toPx()
                        val thumbX = size.width * fraction

                        val activeWidth = (thumbX - gap).coerceIn(0f, size.width)
                        if (activeWidth > 0f) {
                            drawRoundRect(
                                color = activeTrackColor,
                                size = Size(activeWidth, height),
                                cornerRadius = radius,
                            )
                        }

                        val restStart = (thumbX + gap).coerceIn(0f, size.width)
                        val restWidth = size.width - restStart
                        if (restWidth > 0f) {
                            drawRoundRect(
                                color = inactiveTrackColor,
                                topLeft = Offset(restStart, 0f),
                                size = Size(restWidth, height),
                                cornerRadius = radius,
                            )
                        }

                        // The loop's two ends, as ticks taller than the track.
                        if (loopStartMs >= 0 && loopEndMs > loopStartMs) {
                            val markWidth = 2.dp.toPx()
                            for (mark in intArrayOf(loopStartMs, loopEndMs)) {
                                val x = (size.width * (mark / duration)).coerceIn(0f, size.width)
                                drawRect(
                                    color = loopMarkColor,
                                    topLeft = Offset(x - markWidth / 2f, -height),
                                    size = Size(markWidth, height * 3f),
                                )
                            }
                        }
                    }
                },
                onValueChange = {
                    isSeeking = true
                    seekPosition = it
                },
                onValueChangeFinished = {
                    onSeek((seekPosition * duration).toLong())
                    // Hold the bar where it was released. Without this it
                    // would show the engine's stale position for as long as
                    // the already-rendered audio takes to drain - and forever
                    // while paused, where nothing new is ever rendered.
                    pendingSeek = seekPosition
                    scope.launch { displayed.snapTo(seekPosition) }
                    isSeeking = false
                },
            )

            Text(
                modifier = Modifier.padding(horizontal = 6.dp),
                text = if (timeDisplay == TimeDisplay.CLOCK) {
                    totalMs.toLong().formatMs()
                } else {
                    TimeDisplay.format(totalMs.toLong(), timeDisplay)
                },
                style = timeStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    )
}

/**
 * The seek bar as One UI 9 draws it: a thick line in the track's colors with
 * a soft waveform rising off the played part and moving with the music, a
 * glowing ringed dot to drag, the rest of the line gray, and the two times
 * underneath at either end rather than beside it.
 *
 * [fraction] is where it is, as a function: the line reads it while drawing
 * and the slider in its own small scope, so the position moving rebuilds
 * neither this nor the times under it.
 */
@Composable
private fun OneUiSeekBar(
    fraction: () -> Float,
    playing: Boolean,
    level: () -> Float,
    elapsed: String,
    total: String,
    loopMarks: Pair<Float, Float>?,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOneUiColors.current
    val source = remember { MutableInteractionSource() }
    val palette = currentTrackPalette()
    val seed = currentTrackSeed()
    val clock = rememberWaveformClock(playing)
    val scratch = remember { WaveformScratch() }
    val timeStyle = OneUiType.Summary.copy(fontSize = 13.sp, fontFeatureSettings = "tnum")

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        OneUiSeekSlider(
            fraction = fraction,
            interactionSource = source,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    // A layer of its own, unclipped - the waveform rises well
                    // above the line. It redraws every frame the music plays,
                    // and without one, what that recorded again each time was
                    // whichever layer it sat in: the controls panel around it.
                    .graphicsLayer()
            ) {
                drawWaveform(
                    fraction = fraction(),
                    palette = palette,
                    rest = if (colors.isDark) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.14f),
                    seed = seed,
                    clock = clock(),
                    scratch = scratch,
                    level = if (playing) level() else 0f,
                    line = 6.dp,
                    waveHeight = 22.dp,
                    gap = 15.dp,
                )
                loopMarks?.let { (from, to) ->
                    val markWidth = 2.dp.toPx()
                    for (mark in floatArrayOf(from, to)) {
                        val x = (size.width * mark).coerceIn(0f, size.width)
                        drawRect(
                            color = colors.textSecondary,
                            topLeft = Offset(x - markWidth / 2f, 0f),
                            size = Size(markWidth, size.height),
                        )
                    }
                }
            }
        }
        // Pulled up into the slider's touch margin, so the times sit right
        // under the line they belong to.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val lift = 10.dp.roundToPx()
                    layout(placeable.width, (placeable.height - lift).coerceAtLeast(0)) {
                        placeable.place(0, -lift)
                    }
                }
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = elapsed, style = timeStyle, color = colors.textSecondary)
            Text(text = total, style = timeStyle, color = colors.textSecondary)
        }
    }
}

/**
 * Material's slider under One UI's line, there for its thumb and its gesture.
 *
 * It takes where it is as a number, so somewhere has to read the position
 * while composing - and this is that place, and all of it. What is built again
 * on every frame the music plays is this one call, which moves the thumb;
 * [track] is handed over as it came, so the line and its waveform are not
 * built again with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OneUiSeekSlider(
    fraction: () -> Float,
    interactionSource: MutableInteractionSource,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    track: @Composable () -> Unit,
) {
    Slider(
        modifier = Modifier.fillMaxWidth(),
        value = fraction(),
        interactionSource = interactionSource,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        thumb = { OneUiSeekThumb(interactionSource) },
        track = { track() },
    )
}

/**
 * The seek bar as Music draws it on iOS 26: no thumb, a thick capsule filled
 * white to where playback is, swelling while a finger is on it, the times in
 * small type under its two ends - what has played, and what is left counting
 * down.
 */
@Composable
private fun LiquidSeekBar(
    fraction: () -> Float,
    elapsed: String,
    remaining: String,
    loopMarks: Pair<Float, Float>?,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeStyle = LiquidType.Caption1.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val colors = LocalLiquidColors.current
    val timeColor = colors.secondaryLabel
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
        Box {
            LiquidScrubber(
                fraction = fraction,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                fill = colors.label.copy(alpha = 0.85f),
                rest = colors.label.copy(alpha = if (colors.isDark) 0.22f else 0.14f),
                modifier = Modifier.fillMaxWidth(),
            )
            loopMarks?.let { (from, to) ->
                Canvas(modifier = Modifier.matchParentSize()) {
                    val markWidth = 2.dp.toPx()
                    val height = 16.dp.toPx()
                    for (mark in floatArrayOf(from, to)) {
                        val x = (size.width * mark).coerceIn(0f, size.width)
                        drawRoundRect(
                            color = colors.label.copy(alpha = 0.7f),
                            topLeft = Offset(x - markWidth / 2f, (size.height - height) / 2f),
                            size = Size(markWidth, height),
                            cornerRadius = CornerRadius(markWidth / 2f),
                        )
                    }
                }
            }
        }
        // Pulled up into the slider's touch margin, so the times sit right
        // under the line they belong to - though not quite as far as the room
        // they take: pulled all the way up, they all but touched the line.
        // What goes below that room is only the empty foot of their line of
        // text, so the panel is no taller for it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val room = 10.dp.roundToPx()
                    val lift = 6.dp.roundToPx()
                    layout(placeable.width, (placeable.height - room).coerceAtLeast(0)) {
                        placeable.place(0, -lift)
                    }
                },
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = elapsed, style = timeStyle, color = timeColor)
            Text(text = remaining, style = timeStyle, color = timeColor)
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface {
            PlaybackProgress(
                visuals = remember { PlaybackVisuals() },
                onSeek = {}
            )
        }
    }
}
