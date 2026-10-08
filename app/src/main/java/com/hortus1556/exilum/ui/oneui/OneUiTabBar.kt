package com.hortus1556.exilum.ui.oneui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** One entry of the [OneUiFloatingTabBar]. */
class OneUiTab(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

/**
 * One UI 8.5's tab bar: a pill floating clear of the edges of the screen,
 * frosted over the content scrolling underneath it, with a capsule behind
 * the selected tab.
 *
 * [position] is where the selection is, in tabs, fractions included: it
 * follows the pages as they are swiped rather than jumping when they land.
 */
@Composable
fun OneUiFloatingTabBar(
    tabs: List<OneUiTab>,
    selected: Int,
    position: () -> Float,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOneUiColors.current
    val haptic = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val pill = CircleShape

    Row(
        modifier = modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .height(OneUiDimens.TabBarHeight)
            .shadow(
                elevation = 10.dp,
                shape = pill,
                ambientColor = Color.Black.copy(alpha = 0.10f),
                spotColor = Color.Black.copy(alpha = 0.16f),
            )
            .frostedGlass(backdrop, pill, tint = colors.bar, fallback = colors.barOpaque)
            .border(width = 0.5.dp, color = colors.barBorder, shape = pill)
            .padding(4.dp)
            // The capsule, drawn where the selection is right now.
            .drawBehind {
                if (tabs.isEmpty()) return@drawBehind
                val slot = size.width / tabs.size
                val at = position().coerceIn(0f, (tabs.size - 1).toFloat())
                val x = if (rtl) size.width - slot * (at + 1) else slot * at
                drawRoundRect(
                    color = colors.barSelection,
                    topLeft = Offset(x, 0f),
                    size = Size(slot, size.height),
                    cornerRadius = CornerRadius(size.height / 2f),
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = index == selected
            val source = remember { MutableInteractionSource() }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pressScale(source, pressedScale = 0.9f)
                    .clickable(
                        interactionSource = source,
                        indication = null,
                        role = Role.Tab,
                        onClickLabel = tab.label,
                    ) {
                        if (!isSelected) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(index)
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val tint = if (isSelected) colors.text else colors.textSecondary
                Icon(
                    imageVector = if (isSelected) tab.selectedIcon else tab.icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Box(modifier = Modifier.padding(horizontal = 2.dp)) {
                    Text(
                        text = tab.label,
                        color = tint,
                        style = OneUiType.TabLabel.copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
