package com.hortus1556.exilum.ui.oneui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/*
 * One UI 9's media player: a playback line in the colors of what is playing
 * with a soft waveform rising off the played part and moving with the music.
 */

/**
 * How what is playing looks: its colors, left to right along the line, and
 * the waveform's shape. One UI 9 takes the colors from the album art; a
 * module has none, so both come from the track itself - every track its own,
 * the same every time it plays.
 */
class TrackLook(val palette: List<Color>, val seed: Int)

val LocalTrackLook = staticCompositionLocalOf<TrackLook?> { null }

/** Three neighbouring hues and a shape, picked by [key] (the file). */
fun trackLook(key: String, dark: Boolean): TrackLook {
    val hash = key.hashCode()
    val hue = ((hash.toLong() and 0xffffffffL) % 360L).toFloat()
    val saturation = if (dark) 0.62f else 0.72f
    val value = if (dark) 0.98f else 0.86f
    return TrackLook(
        palette = listOf(0f, 42f, 84f).map { Color.hsv((hue + it) % 360f, saturation, value) },
        seed = hash,
    )
}

/** The track's palette where there is one, the accent where there is not. */
@Composable
fun currentTrackPalette(): List<Color> =
    LocalTrackLook.current?.palette ?: LocalOneUiColors.current.accent.let { listOf(it, it) }

/** The track's waveform shape. */
@Composable
fun currentTrackSeed(): Int = LocalTrackLook.current?.seed ?: 0

/**
 * How far the waveform's own motion has run, in seconds - advancing only while
 * [playing], so a paused waveform holds still rather than moving to no music.
 */
@Composable
fun rememberWaveformClock(playing: Boolean): () -> Float {
    val seconds = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) seconds.floatValue += (now - last) / 1_000_000_000f
                last = now
            }
        }
    }
    return { seconds.floatValue }
}

/** A stable 0..1 per [salt] of the track [seed], for the waveform's phases. */
private fun hashed(seed: Int, salt: Int): Float {
    val x = sin((seed and 0x3ff) * 0.713f + salt * 12.9898f) * 43758.547f
    return x - kotlin.math.floor(x)
}

/** How tall each of the waveform's layers stands against the tallest, back to front. */
private val WAVE_LAYER_SCALE = floatArrayOf(1f, 0.78f, 0.55f)

/** And how opaque each is. */
private val WAVE_LAYER_ALPHA = floatArrayOf(0.30f, 0.40f, 0.55f)

/**
 * What [drawWaveform] keeps from one frame to the next.
 *
 * The waveform is drawn again on every frame the music plays, and it used to
 * make everything it draws with afresh each time: a path for each layer, the
 * line's gradient and the shader behind it, the outlines' stroke. That was a
 * few hundred objects a second, native ones among them, for as long as a
 * track played. Held here they are made once, and the gradient again only
 * for another track or another width.
 */
class WaveformScratch {
    /**
     * One path for all the layers, emptied between them: a layer is drawn
     * before the next is laid out, as the channel view's traces are.
     */
    internal val path = Path()

    private var gradient: Brush? = null
    private var gradientPalette: List<Color> = emptyList()
    private var gradientWidth = 0f

    private var stroke: Stroke? = null

    /** The played line's gradient of [palette], across [width]. */
    internal fun brush(palette: List<Color>, width: Float): Brush {
        gradient?.let { if (width == gradientWidth && palette == gradientPalette) return it }
        return Brush.horizontalGradient(palette + palette.last(), startX = 0f, endX = width).also {
            gradient = it
            gradientPalette = palette
            gradientWidth = width
        }
    }

    /** The stroke the layers are outlined with, [width] thick. */
    internal fun outline(width: Float): Stroke {
        stroke?.let { if (it.width == width) return it }
        return Stroke(width = width).also { stroke = it }
    }
}

/**
 * Draws One UI 9's playback line, centred on the area's height: the played
 * part a thick rounded line in a gradient of [palette], with a soft waveform
 * of translucent layers rising off it - tapering to nothing at either end,
 * swaying with [clock] and swelling with [level], the loudness right now -
 * then a gap of [gap] around the head, and the rest of the line in [rest].
 * [boost] makes the waveform's layers more opaque, for a small one to read.
 * [scratch] is what it draws with, kept by whoever calls this every frame.
 *
 * The waveform is drawn above the area rather than in it, [waveHeight] tall,
 * so the area can be exactly as tall as the line and the thumb that sits on
 * it; leave room above.
 */
fun DrawScope.drawWaveform(
    fraction: Float,
    palette: List<Color>,
    rest: Color,
    seed: Int,
    clock: Float,
    scratch: WaveformScratch,
    level: Float = 0f,
    line: Dp = 6.dp,
    waveHeight: Dp = 20.dp,
    gap: Dp = 0.dp,
    boost: Float = 1f,
) {
    val thickness = line.toPx()
    val y = size.height / 2f
    val start = thickness / 2f
    val end = size.width - thickness / 2f
    val head = start + (end - start) * fraction.coerceIn(0f, 1f)
    val brush = scratch.brush(palette, size.width)

    // The waveform, played part only.
    val span = head - start
    if (span > thickness * 2f) {
        val tallest = waveHeight.toPx() * (0.6f + 0.4f * level.coerceIn(0f, 1f))
        val top = y - thickness / 2f
        val step = 3f
        val outline = scratch.outline(1.dp.toPx())
        val path = scratch.path
        // Three layers, each its own mix of slow swells; the front one the
        // lowest and the most opaque.
        for (layer in WAVE_LAYER_SCALE.indices) {
            val scale = WAVE_LAYER_SCALE[layer]
            val alpha = WAVE_LAYER_ALPHA[layer]
            val color = lerp(palette[layer % palette.size], Color.White, 0.45f)
            val f1 = 1.5f + 2.5f * hashed(seed, layer * 3)
            val f2 = 3f + 4f * hashed(seed, layer * 3 + 1)
            val p = hashed(seed, layer * 3 + 2)
            path.reset()
            path.moveTo(start, top)
            var x = start
            while (x < head) {
                x = (x + step).coerceAtMost(head)
                val u = (x - start) / span
                // Rises out of the line and settles back into it at the head.
                val envelope = sin(PI.toFloat() * u).coerceAtLeast(0f).pow(0.8f)
                val swell = 0.55f +
                    0.3f * sin(2f * PI.toFloat() * (f1 * x / size.width + p + clock * 0.35f)) +
                    0.15f * sin(2f * PI.toFloat() * (f2 * x / size.width - clock * 0.6f))
                path.lineTo(x, top - tallest * scale * envelope * swell.coerceIn(0f, 1f))
            }
            path.lineTo(head, top)
            path.close()
            drawPath(path, color = color.copy(alpha = (alpha * boost).coerceAtMost(1f)))
            drawPath(path, color = color.copy(alpha = (alpha * 1.4f * boost).coerceAtMost(1f)), style = outline)
        }
    }

    // The rest of the line, clear of the head.
    val restFrom = head + gap.toPx()
    if (restFrom < end) {
        drawLine(rest, Offset(restFrom, y), Offset(end, y), strokeWidth = thickness, cap = StrokeCap.Round)
    }
    // The played line, over the foot of the waveform.
    val playedTo = head - gap.toPx()
    if (playedTo > start) {
        drawLine(brush, Offset(start, y), Offset(playedTo, y), strokeWidth = thickness, cap = StrokeCap.Round)
    }
}

/**
 * The mini player's playback line: the same line and waveform, smaller, with
 * nothing to grab. Reads [progress] while drawing, so it redraws with it
 * without recomposing anything.
 *
 * [visible] is whether it can be seen at all. Where it cannot - under the
 * open player, or slid away below the screen with the bars - it stands as
 * it was last drawn: the waveform's clock stops and [progress] is not read,
 * and those two are all that ever redraws it. Nothing skips drawing what is
 * out of sight by itself: a layer whose state moved is recorded again
 * whether or not anything goes on to draw it, so this kept laying out three
 * paths on every frame behind a player that covered the whole screen.
 */
@Composable
fun OneUiWaveformProgress(
    progress: () -> Float,
    playing: Boolean,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    val colors = LocalOneUiColors.current
    val palette = currentTrackPalette()
    val seed = currentTrackSeed()
    val clock = rememberWaveformClock(playing && visible)
    val scratch = remember { WaveformScratch() }
    // Where the line stood when it was last drawn in sight. Not state: only
    // the draw pass below reads it, and it must not be what redraws it.
    val shown = remember { FloatArray(1) }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(22.dp)
            // A layer of its own, and unclipped, as the waveform rises out of
            // the area. Without one, what was recorded again on every frame
            // was whichever layer this happened to sit in - the mini player's
            // whole row, its title and subtitle with it.
            .graphicsLayer(),
    ) {
        val fraction = if (visible) progress().also { shown[0] = it } else shown[0]
        // Down in the area, the waveform rising in the room above the line -
        // most of the height, the line being thin: at this size it is the
        // waveform that says the music is playing.
        translate(top = size.height / 2f - 2.5.dp.toPx()) {
            drawWaveform(
                fraction = fraction,
                palette = palette,
                rest = colors.sliderTrack,
                seed = seed,
                clock = clock(),
                scratch = scratch,
                // Swelling as if it were loud: the mini player has no level
                // to go by, and at rest the waveform is barely there.
                level = 0.8f,
                line = 4.dp,
                waveHeight = 17.dp,
                boost = 1.35f,
            )
        }
    }
}

/**
 * The seek bar's thumb: a dot in the track's color, ringed in white and
 * glowing, that swells while it is held.
 */
@Composable
fun OneUiSeekThumb(interactionSource: MutableInteractionSource) {
    val color = currentTrackPalette().last()
    val dragged by interactionSource.collectIsDraggedAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val grow by animateFloatAsState(
        targetValue = if (dragged || pressed) 1.25f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "seek_thumb",
    )
    Box(
        modifier = Modifier
            .size(22.dp)
            .graphicsLayer {
                scaleX = grow
                scaleY = grow
            }
            .drawBehind {
                // The glow.
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(color.copy(alpha = 0.55f), Color.Transparent),
                        radius = size.minDimension,
                    ),
                    radius = size.minDimension,
                )
                drawCircle(Color.White, radius = size.minDimension / 2f)
                drawCircle(color, radius = size.minDimension / 2f - 2.5.dp.toPx())
            },
    )
}

/**
 * A transport button the One UI way: just the glyph, no container, washing
 * gray in a circle and giving under the finger while held.
 */
@Composable
fun OneUiMediaButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: Dp = 28.dp,
    enabled: Boolean = true,
    tint: Color = LocalOneUiColors.current.text,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(glyph + 20.dp)
            .pressScale(source, pressedScale = 0.88f)
            .clip(CircleShape)
            .clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else 0.3f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(glyph))
    }
}

/**
 * Play and pause, big and bare, as in Samsung Music; the glyph turns into the
 * other one rather than being swapped.
 */
@Composable
fun OneUiPlayPauseButton(
    playing: Boolean,
    playIcon: ImageVector,
    pauseIcon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: Dp = 48.dp,
) {
    val colors = LocalOneUiColors.current
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(glyph + 20.dp)
            .pressScale(source, pressedScale = 0.86f)
            .clip(CircleShape)
            .clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = playing,
            transitionSpec = {
                (scaleIn(initialScale = 0.6f) + fadeIn(tween(120))) togetherWith
                    (scaleOut(targetScale = 0.6f) + fadeOut(tween(90)))
            },
            label = "play_pause",
        ) { isPlaying ->
            Icon(
                imageVector = if (isPlaying) pauseIcon else playIcon,
                contentDescription = null,
                tint = colors.text,
                modifier = Modifier.size(glyph),
            )
        }
    }
}
