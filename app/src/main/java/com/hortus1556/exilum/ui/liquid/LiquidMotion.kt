package com.hortus1556.exilum.ui.liquid

import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.Dp
import com.kyant.shapes.Capsule
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.Shape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker1D
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

// The motion of iOS 26's glass controls, as Kyant0's AndroidLiquidGlass
// catalog (Apache-2.0, github.com/Kyant0/AndroidLiquidGlass) models it: the
// springs, the swelling under the finger, the stretch that follows how fast
// a drop is moving, and the light that follows the finger across it.

/**
 * A value dragged along a range - which tab the tab bar's drop is over -
 * with the drop's own life on top: it swells to [pressedScale] while held or
 * on its way somewhere, stretches with its speed ([velocity], in range spans
 * per second), and settles back to [initialScale] once it has arrived.
 */
class LiquidDragAnimation(
    private val scope: CoroutineScope,
    initialValue: Float,
    private val valueRange: ClosedFloatingPointRange<Float>,
    private val visibilityThreshold: Float,
    private val initialScale: Float,
    private val pressedScale: Float,
    private val onDragStarted: LiquidDragAnimation.(position: Offset) -> Unit,
    private val onDragStopped: LiquidDragAnimation.() -> Unit,
    private val onDrag: LiquidDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
    /**
     * For a drop that sits in something that scrolls - a switch or a slider
     * on a settings page. It then takes a finger only once the finger has
     * gone sideways, and a finger put down and lifted again where it was is
     * [onTap]; one that goes up or down belongs to the page.
     *
     * Taking it on the way down, as the tab bar's drop does, a page scrolled
     * with a thumb that happened to land on a slider did not scroll: the
     * slider jumped to the thumb and was saved there, and went on following
     * the thumb's drift; a switch flipped when the thumb was lifted.
     */
    private val sidewaysOnly: Boolean = false,
    private val onTap: (LiquidDragAnimation.(position: Offset) -> Unit)? = null,
) {
    private val valueSpec = spring(1f, 1000f, visibilityThreshold)
    private val velocitySpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressSpec = spring(1f, 1000f, 0.001f)
    private val scaleXSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, visibilityThreshold * 10f)
    private val pressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)
    private val mutex = MutatorMutex()
    private val tracker = VelocityTracker1D(isDataDifferential = false)

    val value: Float get() = valueAnimation.value
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    /** Follows the finger: moves the drop straight to [value], springing. */
    fun updateValue(value: Float) {
        val target = value.coerceIn(valueRange)
        scope.launch {
            valueAnimation.animateTo(target, valueSpec) { updateVelocity() }
        }
    }

    /**
     * Sends the drop to [value] the way a tap does: it lifts, travels and
     * lands - and lets go the moment it has landed, while the last of its
     * speed dies away on its own, so it is never left swollen.
     */
    fun animateToValue(value: Float) {
        val target = value.coerceIn(valueRange)
        scope.launch {
            mutex.mutate {
                press()
                try {
                    valueAnimation.animateTo(target, valueSpec) { updateVelocity() }
                } finally {
                    // Let go however the move ended, cut short included. A
                    // snap to follow the pages, or another move, cancelled it
                    // part way and skipped this - and the drop stayed lifted,
                    // a lens left standing over the view selector, the tab
                    // bar or a switch.
                    scope.launch { velocityAnimation.animateTo(0f, velocitySpec) }
                    release()
                }
            }
        }
    }

    /**
     * Slides to [value] without lifting: for a change that came from
     * somewhere other than a finger on it - the setting read back, reset,
     * changed elsewhere - which is not a press and should not look like one.
     */
    fun slideTo(value: Float) {
        val target = value.coerceIn(valueRange)
        scope.launch { mutex.mutate { valueAnimation.animateTo(target, valueSpec) } }
    }

    /** Jumps to [value] with no drop animation at all. */
    fun snapTo(value: Float) {
        scope.launch { snapNow(value) }
    }

    /** Moves straight to [value], for following something frame by frame - a page being swiped. */
    suspend fun snapNow(value: Float) {
        valueAnimation.snapTo(value.coerceIn(valueRange))
    }

    fun press() {
        tracker.resetTracking()
        scope.launch { pressAnimation.animateTo(1f, pressSpec) }
        scope.launch { scaleXAnimation.animateTo(pressedScale, scaleXSpec) }
        scope.launch { scaleYAnimation.animateTo(pressedScale, scaleYSpec) }
    }

    fun release() {
        scope.launch {
            withFrameNanos { }
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * visibilityThreshold * 10f
                snapshotFlow { valueAnimation.value }.first { abs(it - valueAnimation.targetValue) < threshold }
            }
            launch { pressAnimation.animateTo(0f, pressSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYSpec) }
        }
    }

    private fun updateVelocity() {
        tracker.addDataPoint(System.nanoTime() / 1_000_000L, valueAnimation.value)
        val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-3f)
        val normalized = tracker.calculateVelocity() / span
        scope.launch { velocityAnimation.animateTo(normalized, velocitySpec) }
    }

    /** Presses, drags and lets go of the drop. */
    val modifier: Modifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (sidewaysOnly) {
                var past = 0f
                val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                    change.consume()
                    past = overSlop
                }
                if (drag == null) {
                    // Lifted where it was put down: a tap. Still down, and it
                    // was the page that took it - or it wandered off up or
                    // down on a page that does not scroll: neither is ours.
                    val last = currentEvent.changes.firstOrNull { it.id == down.id }
                    if (last != null && !last.pressed && !last.isConsumed &&
                        (last.position - down.position).getDistance() <= viewConfiguration.touchSlop
                    ) {
                        last.consume()
                        onTap?.invoke(this@LiquidDragAnimation, down.position)
                    }
                    return@awaitEachGesture
                }
                onDragStarted(down.position)
                press()
                try {
                    if (past != 0f) onDrag(size, Offset(past, 0f))
                    horizontalDrag(drag.id) { change ->
                        val amount = change.positionChange()
                        if (amount.x != 0f) onDrag(size, Offset(amount.x, 0f))
                        change.consume()
                    }
                    onDragStopped()
                } finally {
                    release()
                }
                return@awaitEachGesture
            }
            onDragStarted(down.position)
            press()
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val amount = change.positionChange()
                    if (amount != Offset.Zero) {
                        onDrag(size, amount)
                        change.consume()
                    }
                }
                onDragStopped()
            } finally {
                // Let go even when the gesture was taken away mid-drag.
                release()
            }
        }
    }
}

/**
 * The light glass gives off under a finger: a faint brightening of the whole
 * piece and a soft glow around where it is being touched, springing in on
 * press and fading as it lets go. [position] is where the glow sits, from
 * the size of what is lit and where the finger is.
 */
class LiquidPressHighlight(
    private val scope: CoroutineScope,
    /** The outline of what is being lit; the light stops at it. */
    private val shape: Shape = Capsule(),
    /** How much the whole piece brightens under a finger. */
    private val lift: Float = 0.08f,
    /**
     * How far the glow reaches from the finger; unspecified, half again the
     * piece's shorter side - which on a button is all of it. A window gives a
     * finger-sized reach here, or the whole pane lights up as one.
     */
    private val reach: Dp = Dp.Unspecified,
    /**
     * Whether the glow fades from the finger all the way out, with no edge to
     * it anywhere, as a window's does - rather than a button's, bright out to
     * halfway and only then fading, which spread over a window read as a disc.
     */
    private val diffuse: Boolean = false,
    /** Where the glow sits, from the size of what is lit and where the finger is. Last, for a trailing lambda. */
    private val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
) {
    private val spec = spring(0.5f, 300f, 0.001f)
    private val offsetSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)
    private val pressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private var start = Offset.Zero

    val pressProgress: Float get() = pressAnimation.value

    /** How far the finger has moved since it came down. */
    val offset: Offset get() = positionAnimation.value - start

    /**
     * Draws the light, over whatever it is applied to and no further than
     * its [shape]. Over the box it is laid out in rather than its shape -
     * which is what filling a rect does - a capsule under a finger lights up
     * as a rectangle, corners and all.
     */
    val modifier: Modifier = Modifier.drawWithContent {
        drawContent()
        val progress = pressAnimation.value
        if (progress <= 0f) return@drawWithContent
        val outline = shape.createOutline(size, layoutDirection, this)
        if (lift > 0f) drawOutline(outline, Color.White.copy(alpha = lift * progress), blendMode = BlendMode.Plus)
        val at = position(size, positionAnimation.value)
        val radius = if (reach.isSpecified) reach.toPx() else size.minDimension * 1.5f
        val center = Offset(at.x.coerceIn(0f, size.width), at.y.coerceIn(0f, size.height))
        drawOutline(
            outline,
            brush = if (diffuse) {
                Brush.radialGradient(
                    *Array(DiffuseStops.size) { i ->
                        DiffuseStops[i] to Color.White.copy(alpha = DiffuseGlow * DiffuseFalloff[i] * progress)
                    },
                    center = center,
                    radius = radius,
                )
            } else {
                Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.15f * progress),
                    0.5f to Color.White.copy(alpha = 0.15f * progress),
                    1f to Color.Transparent,
                    center = center,
                    radius = radius,
                )
            },
            blendMode = BlendMode.Plus,
        )
    }

    /** Follows the finger, without taking the touch from anything else. */
    val gestureModifier: Modifier = watch(yieldToChildren = false)

    /**
     * The same for a surface with controls on it - a window's glass: it only
     * lights where nothing on it took the touch, so a button pressed on it
     * lights the button and not the whole window behind it as well.
     */
    val surfaceGestureModifier: Modifier = watch(yieldToChildren = true)

    /** A finger came down at [at], in the lit thing's own coordinates. */
    fun press(at: Offset) {
        start = at
        scope.launch { positionAnimation.snapTo(at) }
        scope.launch { pressAnimation.animateTo(1f, spec) }
    }

    /** It moved to [to]. */
    fun move(to: Offset) {
        scope.launch { positionAnimation.snapTo(to) }
    }

    /** It let go. */
    fun release() {
        scope.launch { pressAnimation.animateTo(0f, spec) }
        scope.launch { positionAnimation.animateTo(start, offsetSpec) }
    }

    private fun watch(yieldToChildren: Boolean): Modifier = Modifier.pointerInput(yieldToChildren) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = yieldToChildren)
            press(down.position)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                move(change.position)
            }
            release()
        }
    }
}

// A diffuse glow's fall from the finger out: a bell, e^-(2t)^2, so it has no
// edge anywhere; at its brightest a little under a button's.
private const val DiffuseGlow = 0.13f
private val DiffuseStops = floatArrayOf(0f, 0.15f, 0.3f, 0.45f, 0.6f, 0.75f, 0.9f, 1f)
private val DiffuseFalloff = floatArrayOf(1f, 0.91f, 0.7f, 0.45f, 0.24f, 0.1f, 0.04f, 0f)

/**
 * How a glass button gives under the finger: it swells by a few points,
 * leans after the finger with a pull that runs out the further it goes, and
 * stretches along the way it is being pulled.
 */
fun GraphicsLayerScope.liquidGel(highlight: LiquidPressHighlight) {
    val height = size.height
    val width = size.width
    if (height <= 0f || width <= 0f) return
    val progress = highlight.pressProgress
    // A few points wider and a few points taller - the same few points, not
    // the same fraction. The glass is drawn into a buffer its own size, so
    // whatever this pushes past its edge is cut off square: a fraction taken
    // from the height and given to a bar ten times as wide takes the round
    // ends clean off it.
    val swell = progress * 4.dp.toPx()
    val scaleW = 1f + swell / width
    val scaleH = 1f + swell / height
    val offset = highlight.offset
    val reach = min(width, height)
    // Leans after the finger, but never further than a few points.
    val lean = 6.dp.toPx()
    translationX = (reach * tanh(0.05f * offset.x / reach)).coerceIn(-lean, lean)
    translationY = (reach * tanh(0.05f * offset.y / reach)).coerceIn(-lean, lean)
    val angle = atan2(offset.y, offset.x)
    val maxDrag = 4.dp.toPx() / height
    val longest = maxOf(width, height)
    // However far the finger goes, the glass stretches only so far after it:
    // the pull counts up to one button's length, and never adds more than 5%.
    val pullX = abs(cos(angle) * offset.x / longest).coerceAtMost(1f)
    val pullY = abs(sin(angle) * offset.y / longest).coerceAtMost(1f)
    scaleX = scaleW + (maxDrag * pullX * min(width / height, 1f)).coerceAtMost(0.05f)
    scaleY = scaleH + (maxDrag * pullY * min(height / width, 1f)).coerceAtMost(0.05f)
}
