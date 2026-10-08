package com.hortus1556.exilum.ui.oneui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * One UI's switch: a pill that fills with the accent when on, a white thumb
 * that springs across it and stretches while held.
 *
 * With [onCheckedChange] null it only shows the state - for a row that is
 * itself the toggle, which passes its [interactionSource] so the thumb still
 * stretches when the row is held.
 */
@Composable
fun OneUiSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = LocalOneUiColors.current
    val haptic = LocalHapticFeedback.current
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()

    val position by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow),
        label = "switch_position",
    )
    val stretch by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "switch_stretch",
    )
    val track by animateColorAsState(
        targetValue = if (checked) colors.accent else colors.switchTrackOff,
        animationSpec = tween(durationMillis = 180),
        label = "switch_track",
    )
    val thumb by animateColorAsState(
        targetValue = if (checked) Color.White else colors.switchThumbOff,
        animationSpec = tween(durationMillis = 180),
        label = "switch_thumb",
    )

    val toggle = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            interactionSource = source,
            indication = null,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = {
                haptic.performHapticFeedback(
                    if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff
                )
                onCheckedChange(it)
            },
        )
    } else {
        Modifier
    }

    Canvas(
        modifier = modifier
            .then(toggle)
            .size(width = 50.dp, height = 28.dp)
            .alpha(if (enabled) 1f else 0.4f),
    ) {
        val radius = size.height / 2f
        drawRoundRect(color = track, cornerRadius = CornerRadius(radius))

        val inset = 3.dp.toPx()
        val diameter = size.height - inset * 2f
        val thumbWidth = diameter + stretch * 6.dp.toPx()
        val travel = size.width - inset * 2f - thumbWidth
        drawRoundRect(
            color = thumb,
            topLeft = Offset(inset + travel * position, inset),
            size = Size(thumbWidth, diameter),
            cornerRadius = CornerRadius(diameter / 2f),
        )
    }
}

/**
 * One UI's slider: a thick rounded track filled with the accent, and a white
 * thumb with a shadow that swells while it is dragged. [centered] fills from
 * the middle, for a value either side of zero.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneUiSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    centered: Boolean = false,
) {
    val colors = LocalOneUiColors.current
    val source = remember { MutableInteractionSource() }
    val dragged by source.collectIsDraggedAsState()
    val pressed by source.collectIsPressedAsState()
    val thumbScale by animateFloatAsState(
        targetValue = if (dragged || pressed) 1.15f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "slider_thumb",
    )
    val active = if (enabled) colors.accent else colors.accent.copy(alpha = 0.38f)
    val inactive = colors.sliderTrack
    val tick = if (colors.isDark) Color.White.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.25f)

    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = source,
        thumb = {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        scaleX = thumbScale
                        scaleY = thumbScale
                    }
                    .shadow(elevation = if (enabled) 3.dp else 0.dp, shape = CircleShape)
                    .background(if (enabled) Color.White else colors.switchThumbOff, CircleShape)
                    .border(
                        width = 0.5.dp,
                        color = if (colors.isDark) Color.Transparent else Color(0x1A000000),
                        shape = CircleShape,
                    ),
            )
        },
        track = { state ->
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
            ) {
                val thickness = 6.dp.toPx()
                val y = size.height / 2f
                val span = state.valueRange.endInclusive - state.valueRange.start
                val fraction = if (span > 0f) {
                    ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
                } else {
                    0f
                }
                drawLine(
                    color = inactive,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = thickness,
                    cap = StrokeCap.Round,
                )
                val from = if (centered) size.width / 2f else 0f
                val to = size.width * fraction
                if (to != from) {
                    drawLine(
                        color = active,
                        start = Offset(from, y),
                        end = Offset(to, y),
                        strokeWidth = thickness,
                        cap = StrokeCap.Round,
                    )
                }
                if (state.steps > 0) {
                    val stops = state.steps + 1
                    for (i in 0..stops) {
                        drawCircle(
                            color = tick,
                            radius = 1.5.dp.toPx(),
                            center = Offset(size.width * i / stops, y),
                        )
                    }
                }
            }
        },
    )
}
