package com.hortus1556.exilum.ui.liquid

import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Text
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Glass under the finger, iOS 26's way: it swells and bounces back rather
 * than dimming. [grow] is how much bigger it gets while held.
 */
fun Modifier.liquidPress(interactionSource: InteractionSource, grow: Float = 1.08f): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) grow else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow),
        label = "liquid_press",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** A button of glass: an [icon] in a circle of [liquidGlassPlain], that swells when pressed. */
@Composable
fun LiquidGlassButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = LiquidDimens.BarButton,
    /** The glyph inside it; the usual 22 unless the button is a smaller one. */
    glyph: Dp = 22.dp,
    tint: Color = LocalLiquidColors.current.label,
    enabled: Boolean = true,
    /** The glass's wash; the theme's when unspecified. */
    glass: Color = Color.Unspecified,
) {
    val source = remember { MutableInteractionSource() }
    val backdrop = LocalLiquidBackdrop.current
    val scope = rememberCoroutineScope()
    val light = remember(scope) { LiquidPressHighlight(scope) }
    Box(
        modifier = modifier
            .size(size)
            .then(
                if (backdrop != null) {
                    // Real glass over the page it sits on.
                    Modifier
                        .then(light.gestureModifier)
                        .liquidButtonGlass(
                            backdrop,
                            if (glass == Color.Unspecified) LocalLiquidColors.current.buttonGlass else glass,
                            frost = LocalLiquidColors.current.frost,
                            layerBlock = { liquidGel(light) },
                        )
                        .then(light.modifier)
                } else {
                    Modifier
                        .liquidPress(source)
                        .shadow(elevation = 6.dp, shape = CapsuleShape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.10f))
                        .liquidGlassPlain(
                            CapsuleShape,
                            if (glass == Color.Unspecified) LocalLiquidColors.current.glassTint(1.6f) else glass,
                        )
                }
            )
            .clip(CapsuleShape)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(glyph))
    }
}

/**
 * The media playback bar iOS 26 uses in Music: thumbless, a thick capsule
 * filled to where playback is, that grows thicker while it is being
 * scrubbed. [fill] and [rest] are the two parts' colors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiquidScrubber(
    fraction: () -> Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    fill: Color,
    rest: Color,
    modifier: Modifier = Modifier,
) {
    // Where it is comes in as a function, read while drawing and nowhere
    // else. Handed in as a number it changed every frame the music played,
    // and everything that took it was built again sixty times a second - the
    // slider, its track, the times beside it - to move one line a few pixels.
    // Material's Slider underneath was there only for its gesture: its thumb
    // was invisible and its track drawn here. The gesture is here now too.
    var held by remember { mutableStateOf(false) }
    val thick by animateFloatAsState(
        targetValue = if (held) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium),
        label = "scrubber",
    )
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction().coerceIn(0f, 1f), 0f..1f)
                setProgress { target ->
                    change(target.coerceIn(0f, 1f))
                    finished()
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    // A press lands where it is, as the slider's did, and a
                    // drag carries on from there.
                    val down = awaitFirstDown()
                    held = true
                    change((down.position.x / size.width).coerceIn(0f, 1f))
                    down.consume()
                    horizontalDrag(down.id) { moved ->
                        change((moved.position.x / size.width).coerceIn(0f, 1f))
                        moved.consume()
                    }
                    held = false
                    finished()
                }
            }
            // A layer of its own: its tick is then its own to draw again,
            // and not the whole panel it sits on.
            .graphicsLayer(),
    ) {
        val thickness = (7f + 7f * thick).dp.toPx()
        val y = size.height / 2f
        val r = CornerRadius(thickness / 2f)
        val grow = 1f + 0.03f * thick
        val width = size.width * grow
        val left = (size.width - width) / 2f
        drawRoundRect(rest, Offset(left, y - thickness / 2f), Size(width, thickness), r)
        val filled = width * fraction().coerceIn(0f, 1f)
        if (filled > 0f) {
            // The whole track again in the fill, cut off where the fill ends:
            // the round start and the square end Music draws, and nothing
            // made to draw them - it was a path built for every frame a
            // track played.
            clipRect(left, y - thickness / 2f, left + filled, y + thickness / 2f) {
                drawRoundRect(fill, Offset(left, y - thickness / 2f), Size(width, thickness), r)
            }
        }
    }
}

/** A rounded-square icon of a settings row: a white glyph on [color]. */
@Composable
fun LiquidIconSquare(
    imageVector: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    shape: Shape = ContinuousShape(8.dp),
) {
    Box(
        modifier = modifier
            .size(LiquidDimens.RowIcon)
            .clip(shape)
            .drawBehind {
                drawRect(color)
                // The faint top-lit sheen of an iOS settings icon.
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = imageVector, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
    }
}

/**
 * Plain text-colored glyph button, as iOS draws transport and bar icons
 * without glass. With [halo], a soft disc of light rises behind the glyph
 * while it is held, as behind Now Playing's transport.
 */
@Composable
fun LiquidGlyphButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: Dp = 26.dp,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
    halo: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val glow by animateFloatAsState(
        targetValue = if (halo && pressed) 1f else 0f,
        animationSpec = if (pressed) tween(80) else tween(320),
        label = "glyph_halo",
    )
    CompositionLocalProvider(LocalContentColor provides tint) {
        Box(
            modifier = modifier
                .size(glyph + 22.dp)
                .drawBehind {
                    if (glow > 0f) {
                        drawCircle(Color.White.copy(alpha = 0.16f * glow), radius = size.minDimension / 2f * (0.8f + 0.2f * glow))
                    }
                }
                // iOS glyph buttons give way and dim.
                .liquidPress(source, grow = 0.88f)
                .clip(CapsuleShape)
                .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                .alpha(if (enabled) 1f else 0.3f),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(glyph))
        }
    }
}

/**
 * The field an iOS alert asks for a name in: a shallow rounded box with a
 * hairline around it, the placeholder in gray, and the caret in the tint.
 * Material's outlined field, with its floating label, is not what an alert
 * looks like on iOS.
 */
@Composable
fun LiquidAlertField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    autoFocus: Boolean = false,
) {
    val colors = LocalLiquidColors.current
    val shape = ContinuousShape(8.dp)
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) {
        if (autoFocus) runCatching { focus.requestFocus() }
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = LiquidType.Body.copy(color = colors.label),
        cursorBrush = SolidColor(colors.tint),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (singleLine) 38.dp else 60.dp)
            .background(colors.tertiaryFill, shape)
            .border(1.dp, colors.separator, shape)
            .focusRequester(focus),
        decorationBox = { field ->
            Box(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = LiquidType.Body,
                        color = colors.tertiaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                field()
            }
        },
    )
}
