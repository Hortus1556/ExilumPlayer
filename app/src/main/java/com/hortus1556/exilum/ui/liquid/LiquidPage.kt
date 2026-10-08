package com.hortus1556.exilum.ui.liquid

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.hortus1556.exilum.ui.theme.isLiquid

// A page drawn the iOS 26 way: its content runs up under the top bar, and
// the glass over it - the bar's buttons, a floating action - bends that
// content as it scrolls underneath.

/**
 * What a page's glass bends: its content, over the page's background.
 * Provide it as [LocalLiquidBackdrop] around the page's Scaffold and put
 * [liquidPage] on the scrolling content. Null unless the style is Liquid
 * Glass.
 */
@Composable
fun rememberLiquidPageBackdrop(): LayerBackdrop? {
    val liquid = isLiquid
    val background = MaterialTheme.colorScheme.background
    val backdrop = rememberLayerBackdrop {
        drawRect(background)
        drawContent()
    }
    return if (liquid) backdrop else null
}

/** Captures the content this is on for [backdrop]; nothing where there is none. */
fun Modifier.liquidPage(backdrop: LayerBackdrop?): Modifier =
    if (backdrop != null) layerBackdrop(backdrop) else this

/**
 * A Scaffold's padding for the sides of a scrolling page. Under Liquid
 * Glass the page is not pushed below the top bar - it runs up under it -
 * elsewhere it is, as before.
 */
@Composable
fun PaddingValues.pageSides(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        top = if (isLiquid) 0.dp else calculateTopPadding(),
        end = calculateEndPadding(direction),
    )
}

/**
 * The padding inside a scrolling page: clear of the bars at the bottom and,
 * under Liquid Glass, of the top bar too - so the first row starts below it
 * and the rest can scroll up under it.
 */
@Composable
fun PaddingValues.scrollPadding(): PaddingValues = PaddingValues(
    top = if (isLiquid) calculateTopPadding() else 0.dp,
    bottom = calculateBottomPadding(),
)

/**
 * A page Scaffold's colour. Under Liquid Glass pages are see-through: the
 * background is laid once under them all, so a page moving past another
 * never paints over the other's bar - which stays still while the pages
 * move - and wipes its buttons away mid-pop. Where pages are stacked, the
 * stack puts the background under each page itself (see
 * rememberLiquidPageDecorator).
 */
@Composable
fun liquidPageContainer(): androidx.compose.ui.graphics.Color =
    if (isLiquid) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background
