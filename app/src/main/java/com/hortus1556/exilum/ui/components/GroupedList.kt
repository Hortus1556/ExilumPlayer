package com.hortus1556.exilum.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hortus1556.exilum.ui.liquid.LiquidDimens
import com.hortus1556.exilum.ui.liquid.LiquidGroupShape
import com.hortus1556.exilum.ui.liquid.LocalLiquidColors
import com.hortus1556.exilum.ui.liquid.liquidCardRow
import com.hortus1556.exilum.ui.oneui.LocalOneUiColors
import com.hortus1556.exilum.ui.oneui.OneUiDimens
import com.hortus1556.exilum.ui.oneui.OneUiGroupShape
import com.hortus1556.exilum.ui.oneui.oneUiCardRow
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.theme.isOneUi

/**
 * Row [index] of [count] in a lazy list, drawn as its part of one card
 * across the list - One UI's card, or iOS's inset grouped section - and left
 * alone under Material. [dividerStart] lines the separators up with the
 * text, past a leading icon.
 */
@Composable
fun Modifier.groupedListCard(index: Int, count: Int, dividerStart: Dp = 56.dp): Modifier = when {
    // Kept for as long as what it is made of stays the same. Made anew at
    // each recomposition it drew the same but compared unequal - the drawing
    // in it is a lambda - so a row handed it was never skipped: every row on
    // screen ran again each time the list did, one changed row or none.
    isOneUi -> {
        val shape = OneUiGroupShape.of(index, count)
        val colors = LocalOneUiColors.current
        this.then(
            remember(shape, colors, dividerStart) {
                Modifier.padding(horizontal = OneUiDimens.CardMargin).oneUiCardRow(shape, colors, dividerStart)
            }
        )
    }
    isLiquid -> {
        val shape = LiquidGroupShape.of(index, count)
        val colors = LocalLiquidColors.current
        this.then(
            remember(shape, colors, dividerStart) {
                Modifier.padding(horizontal = LiquidDimens.SectionMargin).liquidCardRow(shape, colors, dividerStart)
            }
        )
    }
    else -> this
}

/**
 * List item colors for a row laid on a card: see-through where the card is
 * drawn under it, Material's own otherwise.
 */
@Composable
fun listItemColorsOnCard(): ListItemColors =
    if (isOneUi || isLiquid) ListItemDefaults.colors(containerColor = Color.Transparent) else ListItemDefaults.colors()

/**
 * The shape a list item clips its press feedback to: square where the card
 * does the rounding, [material] otherwise.
 */
@Composable
fun listItemShapeOnCard(material: Shape): Shape = if (isOneUi || isLiquid) RectangleShape else material
