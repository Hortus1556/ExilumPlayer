package com.hortus1556.exilum.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Some of the sides of [source], asked of it when they are wanted - which,
 * for a list's content padding or Modifier.padding, is while it is laid out.
 *
 * A Scaffold hands its content one PaddingValues and changes what it says as
 * its bars are measured, so that it can be read in layout. Taking it apart
 * where the screen is composed - a top here, a bottom there, put together
 * again as new PaddingValues - reads it in composition instead: the whole
 * body of the screen is then composed again for every frame a bar changes
 * height, a collapsing title for the length of its collapse, and a frame
 * behind it. This picks sides without reading any.
 */
@Stable
class PaddingSides(
    private val source: PaddingValues,
    private val top: Boolean = true,
    private val bottom: Boolean = true,
    private val start: Boolean = true,
    private val end: Boolean = true,
    /** Added under the bottom, whether or not the bottom itself is taken. */
    private val extraBottom: Dp = 0.dp,
) : PaddingValues {

    override fun calculateTopPadding(): Dp = if (top) source.calculateTopPadding() else 0.dp

    override fun calculateBottomPadding(): Dp = (if (bottom) source.calculateBottomPadding() else 0.dp) + extraBottom

    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp {
        val taken = if (layoutDirection == LayoutDirection.Ltr) start else end
        return if (taken) source.calculateLeftPadding(layoutDirection) else 0.dp
    }

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp {
        val taken = if (layoutDirection == LayoutDirection.Ltr) end else start
        return if (taken) source.calculateRightPadding(layoutDirection) else 0.dp
    }

    // The same sides of the same padding are the same padding: one made anew
    // each time a screen is composed does not look like a change to what it
    // is handed to.
    override fun equals(other: Any?): Boolean = other is PaddingSides && other.source == source &&
        other.top == top && other.bottom == bottom && other.start == start && other.end == end &&
        other.extraBottom == extraBottom

    override fun hashCode(): Int {
        var result = source.hashCode()
        result = 31 * result + (if (top) 1 else 0) + (if (bottom) 2 else 0) + (if (start) 4 else 0) + (if (end) 8 else 0)
        return 31 * result + extraBottom.hashCode()
    }
}
