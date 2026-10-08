package com.hortus1556.exilum.ui.oneui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * What floating bars blur: the content under them, drawn once into a layer
 * that the bars then draw again, blurred, inside their own shapes. Needs
 * Android 12 for the blur; before it the bars are simply near opaque.
 */
@Stable
class Backdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/** Makes this the content [backdrop] shows, blurred, through the bars over it. */
fun Modifier.backdropSource(backdrop: Backdrop): Modifier = this
    .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
    .drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }

/**
 * Frosted glass in [shape]: whatever of [backdrop] is behind this, blurred by
 * [blur] and washed with [tint]. Where there is no blur, a plain [fallback].
 *
 * The blurred copy is a reference to the backdrop's layer, not a snapshot of
 * it, so it keeps up with the content moving underneath without being drawn
 * again.
 */
@Composable
fun Modifier.frostedGlass(
    backdrop: Backdrop?,
    shape: Shape,
    tint: Color,
    fallback: Color,
    blur: Dp = 28.dp,
): Modifier {
    if (backdrop == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return this.background(fallback, shape)
    }
    val blurred = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .drawBehind {
            if (size.width < 1f || size.height < 1f) return@drawBehind
            val radius = blur.toPx()
            blurred.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
            blurred.record(size = IntSize(size.width.toInt(), size.height.toInt())) {
                val source = backdrop.origin
                translate(source.x - origin.x, source.y - origin.y) {
                    drawLayer(backdrop.layer)
                }
            }
            val outline = shape.createOutline(size, layoutDirection, this)
            clipPath(Path().apply { addOutline(outline) }) {
                drawLayer(blurred)
                drawRect(tint)
            }
        }
}
