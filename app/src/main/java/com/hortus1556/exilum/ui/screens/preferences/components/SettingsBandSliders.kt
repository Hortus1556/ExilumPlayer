package com.hortus1556.exilum.ui.screens.preferences.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alorma.compose.settings.ui.core.LocalSettingsGroupEnabled
import com.alorma.compose.settings.ui.expressive.SettingsTileDefaults
import com.hortus1556.exilum.ui.liquid.LiquidRow
import com.hortus1556.exilum.ui.liquid.LiquidSlider
import com.hortus1556.exilum.ui.liquid.LiquidType
import com.hortus1556.exilum.ui.liquid.LocalLiquidColors
import com.hortus1556.exilum.ui.oneui.LocalOneUiColors
import com.hortus1556.exilum.ui.oneui.OneUiGroupShape
import com.hortus1556.exilum.ui.oneui.OneUiRow
import com.hortus1556.exilum.ui.oneui.OneUiSlider
import com.hortus1556.exilum.ui.oneui.OneUiType
import com.hortus1556.exilum.ui.theme.isLiquid

/** How tall the sliders stand. */
private val BandHeight = 168.dp

/** One UI's small print under a band: it has no style of its own this small. */
private val OneUiBandLabel = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)

/**
 * A row of sliders stood on end, side by side in one tile: an equalizer's
 * bands, low to high from the start, each up for more and down for less.
 * They are the style's own sliders, as [SettingsSlider] has them, turned a
 * quarter turn.
 *
 * @param labels what is written under each, short enough for ten abreast.
 * @param names what each is called in full, for whoever has it read out.
 * @param readout what a value is written as over its slider.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsBandSliders(
    values: List<Float>,
    onValueChange: (band: Int, value: Float) -> Unit,
    labels: List<String>,
    names: List<String>,
    readout: (Float) -> String,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalSettingsGroupEnabled.current,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    subtitle: (@Composable () -> Unit)? = null,
    shapes: ListItemShapes = SettingsTileDefaults.shapes(),
    centered: Boolean = false,
) {
    val liquid = isLiquid
    val bands: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        ) {
            values.forEachIndexed { band, value ->
                BandSlider(
                    value = value,
                    onValueChange = { onValueChange(band, it) },
                    label = labels.getOrElse(band) { "" },
                    name = names.getOrElse(band) { "" },
                    readout = readout,
                    valueRange = valueRange,
                    enabled = enabled,
                    centered = centered,
                    liquid = liquid,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    if (liquid) {
        LiquidRow(
            title = title,
            modifier = modifier,
            shape = shapes.liquidShape(),
            subtitle = subtitle,
            enabled = enabled,
            below = bands,
        )
    } else {
        OneUiRow(
            title = title,
            modifier = modifier,
            shape = shapes.shape as? OneUiGroupShape ?: OneUiGroupShape.Single,
            summary = subtitle,
            enabled = enabled,
            below = bands,
        )
    }
}

/** One of them: what it is at, the slider on end, and what it is. */
@Composable
private fun BandSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    name: String,
    readout: (Float) -> String,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    centered: Boolean,
    liquid: Boolean,
    modifier: Modifier,
) {
    val points = remember(valueRange) { defaultSnapPoints(valueRange, 0) }
    val hold = rememberSliderHold(value, onValueChange, valueRange, points)
    val said = readout(hold.shown)
    val dim = if (enabled) 1f else 0.4f
    Column(
        // One thing to whoever has the screen read out: the band by its
        // name, and what it is at by what is written over it.
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = name
            stateDescription = said
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val standing = Modifier
            .height(BandHeight)
            .onEnd()
        if (liquid) {
            val colors = LocalLiquidColors.current
            BandText(said, LiquidType.Caption1, colors.label, dim)
            LiquidSlider(
                value = hold.shown,
                onValueChange = hold.onDrag,
                modifier = standing,
                enabled = enabled,
                valueRange = valueRange,
                onValueChangeFinished = hold.onRelease,
                centered = centered,
                standing = true,
            )
            BandText(label, LiquidType.Caption2, colors.secondaryLabel, dim)
        } else {
            val colors = LocalOneUiColors.current
            BandText(said, OneUiBandLabel, colors.accent, dim)
            OneUiSlider(
                value = hold.shown,
                onValueChange = hold.onDrag,
                modifier = standing,
                enabled = enabled,
                valueRange = valueRange,
                onValueChangeFinished = hold.onRelease,
                centered = centered,
            )
            BandText(label, OneUiBandLabel, colors.textSecondary, dim)
        }
    }
}

@Composable
private fun BandText(text: String, style: TextStyle, color: androidx.compose.ui.graphics.Color, dim: Float) {
    Text(
        text = text,
        modifier = Modifier
            .alpha(dim)
            .clearAndSetSemantics { },
        style = style,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
    )
}

/**
 * Stands a slider on end: turned a quarter turn about its corner, so that
 * its start is at the bottom and its end at the top, and measured as it will
 * stand - as long as there is height for, and as wide as it is thick. A
 * finger on it is read in its own turned frame, so one going up the screen
 * goes along it.
 */
private fun Modifier.onEnd(): Modifier = this
    .graphicsLayer {
        rotationZ = -90f
        transformOrigin = TransformOrigin(0f, 0f)
    }
    .layout { measurable, constraints ->
        val placeable = measurable.measure(
            Constraints(
                minWidth = constraints.minHeight,
                maxWidth = constraints.maxHeight,
                minHeight = constraints.minWidth,
                maxHeight = constraints.maxWidth,
            )
        )
        layout(placeable.height, placeable.width) {
            placeable.place(-placeable.width, 0)
        }
    }
