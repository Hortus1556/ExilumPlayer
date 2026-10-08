package com.hortus1556.exilum.ui.screens.preferences.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.hortus1556.exilum.ui.components.groupedListCard
import com.hortus1556.exilum.ui.liquid.LiquidDimens
import com.hortus1556.exilum.ui.liquid.LiquidSectionHeader
import com.hortus1556.exilum.ui.liquid.LocalLiquidColors
import com.hortus1556.exilum.ui.oneui.LocalOneUiColors
import com.hortus1556.exilum.ui.oneui.OneUiDimens
import com.hortus1556.exilum.ui.oneui.OneUiSubheader
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.theme.isOneUi

// The pieces of the information pages - the formats and About - which are
// lazy lists of headed cards rather than settings: a heading, and cards whose
// rows are drawn as One UI's card, iOS's inset grouped section, or Material's
// segmented group.

/** A heading over a card, in the style the app is drawn in. */
@Composable
internal fun InfoSectionHeader(text: String, modifier: Modifier = Modifier) {
    when {
        isLiquid -> LiquidSectionHeader(modifier = modifier.padding(horizontal = LiquidDimens.SectionMargin)) {
            Text(text = text)
        }

        isOneUi -> OneUiSubheader(modifier = modifier.padding(horizontal = OneUiDimens.CardMargin)) {
            Text(text = text)
        }

        else -> Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = modifier.padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 8.dp),
        )
    }
}

/** Row [index] of a card of [count] rows. */
@Composable
internal fun Modifier.infoCard(index: Int = 0, count: Int = 1): Modifier =
    if (isOneUi || isLiquid) {
        groupedListCard(index, count, dividerStart = 16.dp)
    } else {
        this
            .padding(horizontal = 16.dp)
            .padding(top = if (index == 0) 0.dp else 2.dp)
            .clip(materialGroupShape(index, count))
            .background(MaterialTheme.colorScheme.surfaceContainer)
    }

/** Material's segmented group: round at the group's ends, nearly square between its rows. */
private fun materialGroupShape(index: Int, count: Int): Shape {
    val top = if (index == 0) 20.dp else 4.dp
    val bottom = if (index == count - 1) 20.dp else 4.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** The colour of the lesser text on a card. */
@Composable
internal fun infoSecondaryColor(): Color = when {
    isLiquid -> LocalLiquidColors.current.secondaryLabel
    isOneUi -> LocalOneUiColors.current.textSecondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** The fill of a small tag laid on a card. */
@Composable
internal fun infoTagColor(): Color = when {
    isLiquid -> LocalLiquidColors.current.fill
    isOneUi -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    else -> MaterialTheme.colorScheme.secondaryContainer
}
