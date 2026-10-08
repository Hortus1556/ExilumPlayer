package com.hortus1556.exilum.ui.liquid

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.drawscope.rotate

/**
 * iOS's search field: a gray capsule with the magnifying glass and a gray
 * "Search" in it, the round clear button once something is typed, and -
 * while it has the keyboard - iOS 26's glass close button beside it, that
 * clears the search and puts the keyboard away.
 */
@Composable
fun LiquidSearchField(
    state: TextFieldState,
    placeholder: String,
    modifier: Modifier = Modifier,
    onCleared: () -> Unit = {},
    clearDescription: String? = null,
    /** On the field itself rather than the row with the close button - for a FocusRequester. */
    fieldModifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** The keyboard's search key; without it the key just closes the keyboard. */
    onSearch: (() -> Unit)? = null,
) {
    val colors = LocalLiquidColors.current
    val focus = LocalFocusManager.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            state = state,
            modifier = Modifier.weight(1f).then(fieldModifier),
            enabled = enabled,
            textStyle = LiquidType.Body.copy(color = colors.label),
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            onKeyboardAction = { default ->
                if (onSearch != null) onSearch() else default()
            },
            interactionSource = source,
            cursorBrush = SolidColor(colors.tint),
            decorator = { field ->
                Row(
                    modifier = Modifier
                        .height(38.dp)
                        .clip(CapsuleShape)
                        .background(colors.tertiaryFill)
                        .padding(start = 10.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = colors.secondaryLabel,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (state.text.isEmpty()) {
                            Text(text = placeholder, style = LiquidType.Body, color = colors.secondaryLabel, maxLines = 1)
                        }
                        field()
                    }
                    if (state.text.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Filled.Cancel,
                            contentDescription = clearDescription,
                            tint = colors.secondaryLabel,
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CapsuleShape)
                                .clickable(role = Role.Button) {
                                    state.clearText()
                                    onCleared()
                                },
                        )
                    }
                }
            },
        )
        AnimatedVisibility(
            visible = focused,
            enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
        ) {
            // Painted glass, with nothing behind it to bend. What it would
            // have bent is the page this field is on - there is no other
            // backdrop here - and a layer drawn into itself is a crash where
            // it is not a hall of mirrors.
            CompositionLocalProvider(LocalLiquidBackdrop provides null) {
            LiquidGlassButton(
                icon = Icons.Default.Close,
                contentDescription = clearDescription,
                onClick = {
                    state.clearText()
                    onCleared()
                    focus.clearFocus()
                },
                modifier = Modifier.padding(start = 8.dp),
                size = 38.dp,
            )
            }
        }
    }
}

/**
 * iOS's activity indicator: twelve rounded spokes, the brightest stepping
 * round the circle and the rest trailing off behind it. [progress] under 1
 * draws only that many of them, as the pull-to-refresh one fills in while
 * the page is pulled.
 */
@Composable
fun LiquidActivityIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    spinning: Boolean = true,
    progress: () -> Float = { 1f },
) {
    val colors = LocalLiquidColors.current
    val step by if (spinning) {
        rememberInfiniteTransition(label = "activity").animateFloat(
            initialValue = 0f,
            targetValue = 12f,
            animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
            label = "activity_step",
        )
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    }
    Canvas(modifier = modifier.size(size)) {
        val shown = (progress().coerceIn(0f, 1f) * 12f)
        val lead = step.toInt()
        val r = this.size.minDimension / 2f
        val length = r * 0.46f
        val width = r * 0.17f
        for (i in 0 until 12) {
            if (i >= shown) break
            val age = ((lead - i) % 12 + 12) % 12
            val alpha = if (spinning) 1f - age / 12f * 0.75f else 0.55f
            rotate(degrees = i * 30f) {
                drawRoundRect(
                    color = colors.secondaryLabel.copy(alpha = colors.secondaryLabel.alpha * alpha),
                    topLeft = Offset(center.x - width / 2f, center.y - r),
                    size = Size(width, length),
                    cornerRadius = CornerRadius(width / 2f),
                )
            }
        }
    }
}
