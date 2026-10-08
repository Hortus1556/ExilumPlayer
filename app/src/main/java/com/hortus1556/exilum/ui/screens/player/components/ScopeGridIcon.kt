package com.hortus1556.exilum.ui.screens.player.components

import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Four scope tiles: the scopes-only channel layout, as an icon.
 *
 * Drawn rather than picked from the Material set, which has grid icons but
 * none carrying a trace - and the trace is the whole point of the mode this
 * turns on.
 */
val ScopeGridIcon: ImageVector
    @Composable get() = remember { buildScopeGridIcon() }

private fun buildScopeGridIcon(): ImageVector = ImageVector.Builder(
    name = "ScopeGrid",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    val stroke = SolidColor(Color.Black)

    // Tile frames, then a waveform inside each. Two cycles top-left and
    // bottom-right, one broader cycle in the other two, so the icon reads as
    // several channels playing different things rather than a plain grid.
    fun tile(x: Float, y: Float) {
        path(
            stroke = stroke,
            strokeLineWidth = 1.4f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(x, y + 1.6f)
            arcToRelative(1.6f, 1.6f, 0f, false, true, 1.6f, -1.6f)
            lineToRelative(5.6f, 0f)
            arcToRelative(1.6f, 1.6f, 0f, false, true, 1.6f, 1.6f)
            lineToRelative(0f, 5.6f)
            arcToRelative(1.6f, 1.6f, 0f, false, true, -1.6f, 1.6f)
            lineToRelative(-5.6f, 0f)
            arcToRelative(1.6f, 1.6f, 0f, false, true, -1.6f, -1.6f)
            close()
        }
    }

    fun trace(x: Float, y: Float, fast: Boolean) {
        path(
            stroke = stroke,
            strokeLineWidth = 1.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            val midY = y + 4.4f
            moveTo(x + 1.6f, midY)
            if (fast) {
                lineTo(x + 2.8f, midY - 2f)
                lineTo(x + 4.0f, midY + 2f)
                lineTo(x + 5.2f, midY - 2f)
                lineTo(x + 6.4f, midY + 2f)
                lineTo(x + 7.2f, midY)
            } else {
                lineTo(x + 3.2f, midY - 2.4f)
                lineTo(x + 5.6f, midY + 2.4f)
                lineTo(x + 7.2f, midY)
            }
        }
    }

    val left = 2f
    val right = 13.2f
    val top = 2f
    val bottom = 13.2f

    tile(left, top)
    tile(right, top)
    tile(left, bottom)
    tile(right, bottom)

    trace(left, top, fast = true)
    trace(right, top, fast = false)
    trace(left, bottom, fast = false)
    trace(right, bottom, fast = true)
}.build()
