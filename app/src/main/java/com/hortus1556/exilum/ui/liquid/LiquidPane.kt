package com.hortus1556.exilum.ui.liquid

import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * The one piece of glass every window in the app is made of - an alert, a
 * menu, a sheet, the card Now Playing's controls sit on - so that they are
 * alike in everything the settings reach, and what is done to one is done to
 * them all:
 *
 * - the bars' own glass, as thick and as frosted as the glass opacity makes
 *   it, bending [backdrop]; or, where there is nothing to bend - a window of
 *   Android's own - painted in [paint] to look as though it could;
 * - the light a finger raises on it ([rememberLiquidPaneLight]), only where
 *   nothing on it took the touch;
 * - glass on glass: what is on it bends the pane over whatever is behind,
 *   rather than being a hole through it to the app.
 *
 * [shape] is its outline, which what is on it is clipped to; [shadow] how
 * high it floats over the page, nothing for one that does not float.
 */
@Composable
fun LiquidPane(
    shape: Shape,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = LocalLiquidBackdrop.current,
    shadow: Dp = 0.dp,
    paint: Color = LocalLiquidColors.current.windowPaint,
    content: @Composable () -> Unit,
) {
    val colors = LocalLiquidColors.current
    val light = rememberLiquidPaneLight(shape)
    val floats = if (shadow > 0.dp) {
        val alpha = if (colors.isDark) 0.3f else 0.16f
        Modifier.shadow(
            elevation = shadow,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = alpha),
            spotColor = Color.Black.copy(alpha = alpha),
        )
    } else {
        Modifier
    }
    // The size the pane is given is its content's too, so what is on it
    // fills the glass rather than sitting in a corner of it.
    Box(modifier = modifier.then(light.surfaceGestureModifier), propagateMinConstraints = true) {
        if (backdrop != null) {
            val glass = rememberLayerBackdrop()
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .then(floats)
                    .layerBackdrop(glass)
                    .liquidBarGlass(
                        backdrop = backdrop,
                        surface = colors.windowGlass,
                        frost = colors.windowFrost,
                        shape = { shape },
                    )
                    .then(light.modifier),
            )
            CompositionLocalProvider(LocalLiquidBackdrop provides rememberCombinedBackdrop(backdrop, glass)) {
                Box(modifier = Modifier.clip(shape), propagateMinConstraints = true) { content() }
            }
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .then(floats)
                    .liquidGlassPlain(shape, paint)
                    .then(light.modifier),
            )
            Box(modifier = Modifier.clip(shape), propagateMinConstraints = true) { content() }
        }
    }
}

/**
 * The light a finger raises on a window: the whole pane brightening a
 * little, and a glow spread wide round the finger that fades all the way
 * out, with no edge to it anywhere - the same on every window.
 */
@Composable
fun rememberLiquidPaneLight(shape: Shape): LiquidPressHighlight {
    val scope = rememberCoroutineScope()
    return remember(scope, shape) {
        LiquidPressHighlight(scope, shape = shape, lift = 0.015f, reach = 260.dp, diffuse = true)
    }
}

/**
 * The light a finger raises on a line of a window - a menu's, a list's in a
 * sheet: the line brightening as it is picked, and the windows' own glow
 * round the finger, spread along it.
 */
@Composable
fun rememberLiquidRowLight(): LiquidPressHighlight {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        LiquidPressHighlight(scope, shape = RectangleShape, lift = 0.08f, reach = 260.dp, diffuse = true)
    }
}

/**
 * A window's pane where there is nothing behind it to bend, painted: the
 * raised gray in the dark, near white in the light, thick enough to read on
 * whatever it covers.
 */
val LiquidColors.windowPaint: Color
    get() = if (isDark) Color(0xFF2C2C2E).copy(alpha = 0.94f) else Color(0xFFF7F7FA).copy(alpha = 0.96f)
