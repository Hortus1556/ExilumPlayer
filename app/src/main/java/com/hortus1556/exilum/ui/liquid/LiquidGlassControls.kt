package com.hortus1556.exilum.ui.liquid

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlin.math.abs
import kotlin.math.roundToInt

// iOS 26's switch and slider, after the LiquidToggle and LiquidSlider of
// Kyant0's AndroidLiquidGlass catalog (Apache-2.0): a white knob that,
// held, turns into a clear lens over the track - swelling, trailing the
// finger with a little stretch, and springing to where it lands.

/** The most a moving knob is ever stretched by its speed. */
internal const val MaxStretch = 0.06f

/** Squashes and stretches a knob with [velocity], never past [max] - and thinned by only half that. */
internal fun GraphicsLayerScope.stretchWith(velocity: Float, pressedX: Float, pressedY: Float, max: Float = MaxStretch) {
    val speed = abs(velocity)
    scaleX = pressedX / (1f - (speed * 0.75f).coerceIn(0f, max))
    scaleY = pressedY * (1f - (speed * 0.25f).coerceIn(0f, max / 2f))
}

/** The knob's glass: white at rest, a clear lens over [trackBackdrop] while held. */
private fun Modifier.knobGlass(
    drop: LiquidDragAnimation,
    backdrop: com.kyant.backdrop.Backdrop,
    lensHeight: Float,
    lensAmount: Float,
    highlightScale: Float,
    velocityScale: Float,
    dark: Boolean,
    shadowOffset: DpOffset = DpOffset(0.dp, 4.dp),
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { Capsule() },
    effects = {
        // At rest the knob is plain white, laid over all of this: nothing of
        // what is behind it shows, and a page of sliders had as many blurs
        // worked out behind white as it had knobs, again for every frame it
        // scrolled. Only once it lifts is there glass to see through.
        val progress = drop.pressProgress
        if (progress > 0f) {
            blur(8.dp.toPx() * (1f - progress))
            if (LiquidRefraction.on) lens(lensHeight.dp.toPx() * progress, lensAmount.dp.toPx() * progress, chromaticAberration = true)
        }
    },
    highlight = {
        val ambient = Highlight.Ambient
        ambient.copy(
            width = ambient.width * highlightScale,
            blurRadius = ambient.blurRadius * highlightScale,
            alpha = drop.pressProgress,
        )
    },
    // A white knob on a white cell needs its shadow to be seen at all.
    shadow = {
        Shadow(radius = 4.dp, offset = shadowOffset, color = Color.Black.copy(alpha = if (dark) 0.05f else 0.16f))
    },
    innerShadow = {
        val progress = drop.pressProgress
        InnerShadow(radius = 4.dp * progress, alpha = progress)
    },
    layerBlock = { stretchWith(drop.velocity / velocityScale, drop.scaleX, drop.scaleY) },
    onDrawSurface = { drawRect(Color.White.copy(alpha = 1f - drop.pressProgress)) },
)

/**
 * iOS 26's switch: a green track and a white knob. Tapped, the knob lifts
 * into glass, slides over and settles; it can also be dragged across, and
 * lands on whichever side it is let go nearer.
 */
@Composable
fun LiquidSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @Suppress("UNUSED_PARAMETER") interactionSource: MutableInteractionSource? = null,
) {
    val colors = LocalLiquidColors.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val span = with(density) { 20.dp.toPx() }
    val on = colors.green
    val off = if (colors.isDark) Color(0xFF787880).copy(alpha = 0.36f) else Color(0xFF787878).copy(alpha = 0.2f)
    val cell = colors.cell
    val trackBackdrop = rememberLayerBackdrop()
    val behind = rememberCanvasBackdrop { drawRect(cell) }
    val seen = rememberCombinedBackdrop(behind, trackBackdrop)

    val current by rememberUpdatedState(checked)
    val onChange by rememberUpdatedState(onCheckedChange)
    // What a finger on it last made it, so the change coming back from the
    // setting is not taken for one from elsewhere and played a second time.
    var touched by remember { mutableStateOf<Boolean?>(null) }
    val set: (Boolean) -> Unit = { now ->
        if (now != current) {
            haptic.performHapticFeedback(if (now) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            onChange?.invoke(now)
        }
    }
    val drop = remember(scope, span) {
        LiquidDragAnimation(
            scope = scope,
            initialValue = if (checked) 1f else 0f,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {
                // A drag leaves it on the side it was let go.
                val now = targetValue >= 0.5f
                animateToValue(if (now) 1f else 0f)
                touched = now
                set(now)
            },
            onDrag = { _, amount ->
                updateValue(targetValue + amount.x / span)
            },
            // On a page that scrolls: see LiquidDragAnimation.
            sidewaysOnly = true,
            // A tap flips it.
            onTap = {
                val now = !current
                animateToValue(if (now) 1f else 0f)
                touched = now
                set(now)
            },
        )
    }
    LaunchedEffect(checked) {
        if (touched == checked) {
            touched = null
            return@LaunchedEffect
        }
        // From elsewhere: over it goes, without the lift a press gives it.
        val target = if (checked) 1f else 0f
        if (drop.targetValue != target) drop.slideTo(target)
    }

    Box(
        modifier = modifier
            .size(width = 64.dp, height = 28.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .semantics {
                role = Role.Switch
                toggleableState = ToggleableState(checked)
                if (enabled && onCheckedChange != null) {
                    onClick {
                        set(!current)
                        true
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(trackBackdrop)
                .drawBehind {
                    drawRoundRect(
                        color = lerp(off, on, drop.value.coerceIn(0f, 1f)),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                    )
                },
        )
        Box(
            modifier = Modifier
                .layout { measurable, constraints ->
                    val inset = 2.dp.roundToPx()
                    val placeable = measurable.measure(Constraints.fixed(40.dp.roundToPx(), 24.dp.roundToPx()))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        val travel = constraints.maxWidth - placeable.width - inset * 2
                        placeable.place(
                            inset + (travel * drop.value.coerceIn(0f, 1f)).roundToInt(),
                            (constraints.maxHeight - placeable.height) / 2,
                        )
                    }
                }
                .then(if (enabled && onCheckedChange != null) drop.modifier else Modifier)
                .knobGlass(drop, seen, lensHeight = 5f, lensAmount = 10f, highlightScale = 1f / 1.5f, velocityScale = 50f, dark = colors.isDark),
        )
    }
}

/**
 * iOS 26's slider: a thin track filled with the tint, and the same knob as
 * the switch - white, lifting into a lens while it is moved. It can be
 * picked up and dragged, or the track tapped to send it there. [centered]
 * fills from the middle, for a value either side of nothing. [standing] says
 * it has been turned a quarter turn by whoever uses it, its start at the
 * bottom: the knob's shadow then still falls down the screen, not across it.
 */
@Composable
fun LiquidSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    centered: Boolean = false,
    standing: Boolean = false,
) {
    val colors = LocalLiquidColors.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val cell = colors.cell
    val inactive = if (colors.isDark) Color(0xFF787880).copy(alpha = 0.36f) else Color(0xFF787878).copy(alpha = 0.2f)
    val active = colors.tint
    val trackBackdrop = rememberLayerBackdrop()
    val behind = rememberCanvasBackdrop { drawRect(cell) }
    val seen = rememberCombinedBackdrop(behind, trackBackdrop)
    val onChange by rememberUpdatedState(onValueChange)
    val onFinished by rememberUpdatedState(onValueChangeFinished)
    val start = valueRange.start
    val end = valueRange.endInclusive
    val width = (end - start).coerceAtLeast(1e-6f)

    // A value on one of the steps, where there are steps.
    val snap: (Float) -> Float = { v ->
        if (steps > 0) {
            val stepSize = width / (steps + 1)
            (start + ((v - start) / stepSize).roundToInt() * stepSize).coerceIn(start, end)
        } else {
            v.coerceIn(start, end)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.CenterStart,
    ) {
        val trackPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val grab = with(LocalDensity.current) { 24.dp.toPx() }
        var dragging by remember { mutableStateOf(false) }
        var lastReported by remember { mutableFloatStateOf(value) }
        val report: (Float) -> Unit = { v ->
            if (v != lastReported) {
                if (steps > 0) haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                lastReported = v
                onChange(v)
            }
        }
        val drop = remember(scope, trackPx, start, end) {
            LiquidDragAnimation(
                scope = scope,
                initialValue = value.coerceIn(start, end),
                valueRange = start..end,
                visibilityThreshold = width / 1000f,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = { position ->
                    dragging = true
                    // Touched away from the knob: it goes there first.
                    val knobX = trackPx * (this.value - start) / width
                    if (abs(position.x - knobX) > grab) {
                        val there = start + (position.x / trackPx).coerceIn(0f, 1f) * width
                        updateValue(there)
                        report(snap(there))
                    }
                },
                onDragStopped = {
                    val landed = snap(targetValue)
                    animateToValue(landed)
                    report(landed)
                    dragging = false
                    onFinished?.invoke()
                },
                onDrag = { _, amount ->
                    val next = (targetValue + amount.x / trackPx * width).coerceIn(start, end)
                    updateValue(next)
                    report(snap(next))
                },
                // On a page that scrolls: see LiquidDragAnimation.
                sidewaysOnly = true,
                // The track tapped: the knob goes there.
                onTap = { position ->
                    val there = snap(start + (position.x / trackPx).coerceIn(0f, 1f) * width)
                    animateToValue(there)
                    report(there)
                    onFinished?.invoke()
                },
            )
        }
        LaunchedEffect(value) {
            lastReported = value
            if (!dragging && abs(drop.targetValue - value) > width / 1000f) drop.snapTo(value)
        }
        val progress = { ((drop.value - start) / width).coerceIn(0f, 1f) }

        // The track, captured for the knob's lens to look at.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .layerBackdrop(trackBackdrop)
                .drawBehind {
                    val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
                    drawRoundRect(inactive, cornerRadius = r)
                    val at = size.width * progress()
                    val from = if (centered) size.width / 2f else 0f
                    val left = minOf(from, at)
                    val right = maxOf(from, at)
                    if (right > left) {
                        drawRoundRect(
                            color = active,
                            topLeft = androidx.compose.ui.geometry.Offset(left, 0f),
                            size = androidx.compose.ui.geometry.Size(right - left, size.height),
                            cornerRadius = r,
                        )
                    }
                },
        )
        // Where a finger goes down: the whole slider.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (enabled) drop.modifier else Modifier)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, steps)
                    if (enabled) {
                        setProgress { target ->
                            val landed = snap(target)
                            drop.animateToValue(landed)
                            onChange(landed)
                            onFinished?.invoke()
                            true
                        }
                    }
                },
        )
        Box(
            modifier = Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(Constraints.fixed(40.dp.roundToPx(), 24.dp.roundToPx()))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        val w = placeable.width.toFloat()
                        val x = (-w / 2f + trackPx * progress()).coerceIn(-w / 4f, trackPx - w * 3f / 4f)
                        placeable.place(x.roundToInt(), (constraints.maxHeight - placeable.height) / 2)
                    }
                }
                .knobGlass(
                    drop, seen, lensHeight = 10f, lensAmount = 14f, highlightScale = 2f / 3f, velocityScale = 10f,
                    dark = colors.isDark,
                    // Down the screen is towards the slider's start once it is on end.
                    shadowOffset = if (standing) DpOffset((-4).dp, 0.dp) else DpOffset(0.dp, 4.dp),
                ),
        )
    }
}
