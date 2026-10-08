package com.hortus1556.exilum.ui.liquid

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Where a cell sits in its grouped section, which is also its shape: the
 * first has the section's top corners, the last its bottom ones, and every
 * cell but the first a hairline above it - so cells go straight into any
 * Column or lazy list and still come out as one inset grouped section.
 */
@Immutable
class LiquidGroupShape(val first: Boolean, val last: Boolean) : Shape {
    private val shape = ContinuousShape(
        radius = LiquidDimens.SectionCorner,
        topStart = first,
        topEnd = first,
        bottomEnd = last,
        bottomStart = last,
    )

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        shape.createOutline(size, layoutDirection, density)

    override fun equals(other: Any?): Boolean =
        other is LiquidGroupShape && other.first == first && other.last == last

    override fun hashCode(): Int = (if (first) 2 else 0) + (if (last) 1 else 0)

    companion object {
        fun of(index: Int, count: Int) = LiquidGroupShape(first = index == 0, last = index == count - 1)
        val Single = LiquidGroupShape(first = true, last = true)
    }
}

/**
 * Draws a cell as its part of an inset grouped section: the section's white
 * (or, in the dark, gray) clipped to [shape], a gap above the section's first
 * cell, and a hairline separator above every other cell from [dividerStart]
 * to the edge - iOS runs them right to the trailing edge.
 */
fun Modifier.liquidCardRow(
    shape: LiquidGroupShape,
    colors: LiquidColors,
    dividerStart: Dp = LiquidDimens.CellPadding,
    gapAbove: Boolean = shape.first,
): Modifier = this
    .padding(top = if (gapAbove) 20.dp else 0.dp)
    .clip(shape)
    .background(colors.cell)
    .drawWithContent {
        drawContent()
        if (!shape.first) {
            val start = dividerStart.toPx()
            // A hairline: a third of a point on iOS, never less than a pixel.
            val thickness = (0.33.dp.toPx()).coerceAtLeast(1f)
            drawRect(colors.separator, Offset(start, 0f), Size(size.width - start, thickness))
        }
    }

/** The disclosure chevron at the end of a cell that opens something. */
@Composable
fun LiquidChevron(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
        contentDescription = null,
        tint = LocalLiquidColors.current.tertiaryLabel,
        modifier = modifier.size(13.dp),
    )
}

/**
 * One cell of an inset grouped list, the way iOS lays one out: an optional
 * rounded-square [icon], the [title] with a smaller gray [subtitle] under it,
 * then at the end the [value] in gray, [trailing] (a switch), and a
 * [chevron] when it opens something; [below] (a slider) across the whole
 * width underneath.
 *
 * The caller makes it clickable or toggleable through [interaction], applied
 * inside the section's shape so the highlight is too.
 */
@Composable
fun LiquidRow(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    shape: LiquidGroupShape = LiquidGroupShape.Single,
    subtitle: (@Composable () -> Unit)? = null,
    value: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    enabled: Boolean = true,
    titleColor: Color = LocalLiquidColors.current.label,
    interaction: Modifier = Modifier,
) {
    val colors = LocalLiquidColors.current
    val iconSpace = if (icon != null) LiquidDimens.RowIcon + 14.dp else 0.dp
    Column(
        modifier = modifier
            .fillMaxWidth()
            .liquidCardRow(shape, colors, dividerStart = LiquidDimens.CellPadding + iconSpace)
            .then(interaction)
            .heightIn(min = LiquidDimens.CellHeight)
            .padding(horizontal = LiquidDimens.CellPadding, vertical = 11.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier.alpha(if (enabled) 1f else 0.4f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Box(modifier = Modifier.size(LiquidDimens.RowIcon), contentAlignment = Alignment.Center) { icon() }
                Spacer(modifier = Modifier.width(14.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                CompositionLocalProvider(LocalContentColor provides titleColor) {
                    ProvideTextStyle(LiquidType.Body) { title() }
                }
                if (subtitle != null) {
                    CompositionLocalProvider(LocalContentColor provides colors.secondaryLabel) {
                        ProvideTextStyle(LiquidType.Footnote) { subtitle() }
                    }
                }
            }
            if (value != null) {
                Spacer(modifier = Modifier.width(12.dp))
                // Hard against the end of the row, as iOS sets a value, with
                // the title taking whatever it leaves.
                Box(modifier = Modifier.widthIn(max = 180.dp), contentAlignment = Alignment.CenterEnd) {
                    CompositionLocalProvider(LocalContentColor provides colors.secondaryLabel) {
                        ProvideTextStyle(LiquidType.Body) { value() }
                    }
                }
            }
            if (trailing != null) {
                Spacer(modifier = Modifier.width(12.dp))
                CompositionLocalProvider(LocalContentColor provides colors.secondaryLabel) { trailing() }
            }
            if (chevron) {
                Spacer(modifier = Modifier.width(10.dp))
                LiquidChevron()
            }
        }
        if (below != null) below()
    }
}

/**
 * The heading over a section: iOS 26 dropped the small capitals for plain
 * sentence case, a size up.
 */
@Composable
fun LiquidSectionHeader(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalLiquidColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            // The section under it opens with its own gap; the heading gives
            // most of that back, so it sits just above its section.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val giveBack = 13.dp.roundToPx()
                layout(placeable.width, (placeable.height - giveBack).coerceAtLeast(0)) {
                    placeable.place(0, 0)
                }
            }
            .padding(start = LiquidDimens.CellPadding, end = LiquidDimens.CellPadding, top = 28.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.secondaryLabel) {
            ProvideTextStyle(LiquidType.Subheadline.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) { content() }
        }
    }
}

/**
 * A choice in a list, iOS's way: the option and a line saying what it does,
 * and a checkmark in the tint on the one - or the ones - chosen, rather than
 * a radio button or a box. [divider] draws the hairline above it.
 */
@Composable
fun LiquidCheckRow(
    title: String,
    description: String?,
    checked: Boolean,
    onClick: () -> Unit,
    divider: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalLiquidColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                if (divider) {
                    drawRect(colors.separator, Offset.Zero, Size(size.width, 0.33.dp.toPx().coerceAtLeast(1f)))
                }
            }
            .clip(ContinuousShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            androidx.compose.material3.Text(text = title, style = LiquidType.Body, color = colors.label)
            if (!description.isNullOrEmpty()) {
                androidx.compose.material3.Text(text = description, style = LiquidType.Footnote, color = colors.secondaryLabel)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = colors.tint,
            modifier = Modifier
                .size(20.dp)
                .alpha(if (checked) 1f else 0f),
        )
    }
}
