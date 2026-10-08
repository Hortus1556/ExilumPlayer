package com.hortus1556.exilum.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp

/**
 * The width from which the app lays itself out for a big window - the player's
 * controls side by side, the browser in columns.
 *
 * Deliberately about the window and not about the device: a folded phone, an
 * unfolded one, a tablet and a split-screen window all answer the same
 * question the same way, which is the only question a layout has - how much
 * room is there.
 */
private val WIDE_WINDOW_MIN_WIDTH = 600.dp

/** True when the window is wide enough for the big layouts - see [WIDE_WINDOW_MIN_WIDTH]. */
@Composable
fun isWideWindow(): Boolean {
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return remember(containerSize, density) {
        with(density) { containerSize.width.toDp() } >= WIDE_WINDOW_MIN_WIDTH
    }
}
