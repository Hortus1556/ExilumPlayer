package com.hortus1556.exilum.ui.oneui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Where a row sits in its card, which is also its shape: the first row has
 * the card's top corners, the last its bottom ones, and every row but the
 * first has a hairline above it. A card is only ever a run of rows drawn this
 * way, with nothing to wrap them - so rows can go straight into any Column or
 * lazy list and still come out as one card.
 */
@Immutable
class OneUiGroupShape(val first: Boolean, val last: Boolean) : Shape {

    private val shape = RoundedCornerShape(
        topStart = if (first) OneUiDimens.CardCorner else 0.dp,
        topEnd = if (first) OneUiDimens.CardCorner else 0.dp,
        bottomStart = if (last) OneUiDimens.CardCorner else 0.dp,
        bottomEnd = if (last) OneUiDimens.CardCorner else 0.dp,
    )

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        shape.createOutline(size, layoutDirection, density)

    override fun equals(other: Any?): Boolean =
        other is OneUiGroupShape && other.first == first && other.last == last

    override fun hashCode(): Int = (if (first) 2 else 0) + (if (last) 1 else 0)

    companion object {
        fun of(index: Int, count: Int) = OneUiGroupShape(first = index == 0, last = index == count - 1)

        /** A card of one. */
        val Single = OneUiGroupShape(first = true, last = true)
    }
}

/**
 * Draws a row as its part of a card: the card's background clipped to
 * [shape], a gap above the card's first row, and a hairline above every other
 * row, inset from [dividerStart] to the card's text margin at the end.
 */
fun Modifier.oneUiCardRow(
    shape: OneUiGroupShape,
    colors: OneUiColors,
    dividerStart: Dp = OneUiDimens.RowPadding,
    gapAbove: Boolean = shape.first,
): Modifier = this
    .padding(top = if (gapAbove) OneUiDimens.GroupGap else 0.dp)
    .clip(shape)
    .background(colors.card)
    .drawWithContent {
        drawContent()
        if (!shape.first) {
            val thickness = 1.dp.toPx().coerceAtMost(2f)
            val start = dividerStart.toPx()
            val end = size.width - OneUiDimens.RowPadding.toPx()
            if (end > start) {
                drawRect(
                    color = colors.divider,
                    topLeft = Offset(start, 0f),
                    size = Size(end - start, thickness),
                )
            }
        }
    }

/**
 * One row of a One UI card: an optional round [icon], the [title], a gray
 * [summary] and the [value] under it in the accent, [trailing] at the end,
 * and [below] - a slider - across the whole width underneath.
 *
 * The caller makes it clickable or toggleable through [interaction], which is
 * applied inside the card's shape so the press wash is too.
 */
@Composable
fun OneUiRow(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    shape: OneUiGroupShape = OneUiGroupShape.Single,
    summary: (@Composable () -> Unit)? = null,
    value: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    interaction: Modifier = Modifier,
) {
    val colors = LocalOneUiColors.current
    val iconSpace = if (icon != null) 40.dp + 16.dp else 0.dp
    Column(
        modifier = modifier
            .fillMaxWidth()
            .oneUiCardRow(shape, colors, dividerStart = OneUiDimens.RowPadding + iconSpace)
            .then(interaction)
            .heightIn(min = 64.dp)
            .padding(horizontal = OneUiDimens.RowPadding, vertical = 14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier.alpha(if (enabled) 1f else 0.4f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) { icon() }
                Spacer(modifier = Modifier.width(16.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CompositionLocalProvider(LocalContentColor provides colors.text) {
                    ProvideTextStyle(OneUiType.Title) { title() }
                }
                if (summary != null) {
                    CompositionLocalProvider(LocalContentColor provides colors.textSecondary) {
                        ProvideTextStyle(OneUiType.Summary) { summary() }
                    }
                }
                if (value != null) {
                    CompositionLocalProvider(LocalContentColor provides colors.accent) {
                        ProvideTextStyle(OneUiType.Summary) { value() }
                    }
                }
            }
            if (trailing != null) {
                Spacer(modifier = Modifier.width(16.dp))
                CompositionLocalProvider(LocalContentColor provides colors.textSecondary) {
                    trailing()
                }
            }
        }
        if (below != null) {
            below()
        }
    }
}

/** The small gray heading over a card. */
@Composable
fun OneUiSubheader(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalOneUiColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = OneUiDimens.RowPadding, end = OneUiDimens.RowPadding, top = 20.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.subheader) {
            ProvideTextStyle(OneUiType.Subheader) { content() }
        }
    }
}

/**
 * The colored circle with a white glyph that One UI 9 puts in front of each
 * entry of its Settings.
 */
@Composable
fun OneUiCircleIcon(
    imageVector: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}
