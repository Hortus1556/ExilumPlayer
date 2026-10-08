package com.hortus1556.exilum.ui.oneui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import kotlinx.coroutines.launch

/**
 * One UI's press feedback: the whole thing washes gray while it is held and
 * fades back when let go - no ripple spreading from the finger. A tap too
 * quick to see still flashes, so it never goes unanswered.
 */
internal object OneUiIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        HighlightNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = -1
}

private class HighlightNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {

    private val strength = Animatable(0f)
    private var held = 0

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        held++
                        coroutineScope.launch { strength.animateTo(1f, tween(durationMillis = 90)) }
                    }

                    is PressInteraction.Release -> {
                        held = (held - 1).coerceAtLeast(0)
                        if (held == 0) {
                            coroutineScope.launch {
                                if (strength.value < 1f) strength.animateTo(1f, tween(durationMillis = 60))
                                strength.animateTo(0f, tween(durationMillis = 300))
                            }
                        }
                    }

                    // Cancelled, most often because the press turned into a
                    // scroll: it was never a tap, so no flash either.
                    is PressInteraction.Cancel -> {
                        held = (held - 1).coerceAtLeast(0)
                        if (held == 0) {
                            coroutineScope.launch { strength.animateTo(0f, tween(durationMillis = 200)) }
                        }
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = strength.value
        if (alpha > 0f) {
            drawRect(color = currentValueOf(LocalOneUiColors).highlight, alpha = alpha)
        }
    }
}

/**
 * Shrinks a little while held, and springs back: One UI's buttons and tabs
 * give under the finger.
 */
internal fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.94f,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "press_scale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
