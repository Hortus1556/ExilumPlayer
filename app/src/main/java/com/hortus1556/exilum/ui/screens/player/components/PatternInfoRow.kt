package com.hortus1556.exilum.ui.screens.player.components

import com.hortus1556.exilum.ui.liquid.LocalLiquidColors
import androidx.compose.ui.graphics.Color
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.liquid.LiquidType
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.hortus1556.exilum.ui.oneui.LocalOneUiColors
import com.hortus1556.exilum.ui.theme.isOneUi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.hortus1556.exilum.ui.screens.player.PlaybackVisuals
import com.hortus1556.exilum.ui.theme.AppTheme

/** The five displayed strings, combined so derivedStateOf has one value to
 * compare rather than five - see [PatternInfoRow]. */
private data class PatternDisplay(
    val pos: String,
    val pattern: String,
    val row: String,
    val speed: String,
    val bpm: String,
)

@Composable
internal fun PatternInfoRow(
    visuals: PlaybackVisuals,
    modifier: Modifier = Modifier
) {
    // These are composable-phase reads: each one that changes recomposes an
    // InfoChip and its two Text nodes. derivedStateOf collapses the tick
    // stream down to "did any of these five strings actually change", so a
    // row that repeats the same speed and BPM recomposes only the row chip.
    val display by remember(visuals) {
        derivedStateOf {
            @Suppress("UNUSED_EXPRESSION") visuals.positionTick
            @Suppress("UNUSED_EXPRESSION") visuals.infoTick
            val s = visuals.snapshot
            val numRows = s.numRows.toString()
            PatternDisplay(
                pos = s.order.toString(),
                pattern = s.pattern.toString(),
                row = "${s.row.toString().padStart(numRows.length)}/$numRows",
                speed = s.speed.toString(),
                bpm = s.bpm.toString(),
            )
        }
    }
    // One UI sets the readout on a tile of its own, a shade off the panel.
    // Liquid Glass had it on a pane of glass; it is five numbers being read,
    // and a pane around them was a frame around a label - height spent on
    // decoration in the one place there is none to spare.
    val tile = if (isLiquid) {
        Modifier.padding(horizontal = 20.dp)
    } else if (isOneUi) {
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(LocalOneUiColors.current.background)
            .padding(vertical = 2.dp)
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .then(tile)
            .fillMaxWidth()
            .padding(all = if (isLiquid) 2.dp else 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
        content = {
            InfoChip(label = "Pos", value = display.pos)
            InfoChip(label = "Pat", value = display.pattern)
            InfoChip(label = "Row", value = display.row)
            InfoChip(label = "Spd", value = display.speed)
            InfoChip(label = "BPM", value = display.bpm)
        }
    )
}

@Composable
private fun InfoChip(label: String, value: String) {
    if (isLiquid) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label.uppercase(),
                style = LiquidType.Caption2.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
                color = LocalLiquidColors.current.secondaryLabel,
            )
            Text(
                text = value,
                style = LiquidType.Subheadline.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                color = LocalLiquidColors.current.label,
            )
        }
        return
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        content = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    )
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface {
            PatternInfoRow(visuals = remember { PlaybackVisuals() })
        }
    }
}
