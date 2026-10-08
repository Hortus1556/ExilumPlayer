package com.hortus1556.exilum.ui.screens.preferences.components

import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.liquid.LiquidSwitch
import com.hortus1556.exilum.ui.liquid.LiquidRow
import com.hortus1556.exilum.ui.liquid.LiquidGroupShape
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import com.alorma.compose.settings.ui.core.LocalSettingsGroupEnabled
import com.alorma.compose.settings.ui.expressive.SettingsTileDefaults
import com.hortus1556.exilum.ui.oneui.OneUiGroupShape
import com.hortus1556.exilum.ui.oneui.OneUiRow
import com.hortus1556.exilum.ui.oneui.OneUiSwitch
import com.hortus1556.exilum.ui.theme.isOneUi
import com.alorma.compose.settings.ui.expressive.SettingsMenuLink as ExpressiveMenuLink
import com.alorma.compose.settings.ui.expressive.SettingsSwitch as ExpressiveSwitch

/**
 * The shape of row [index] of [count] in a settings group: a segment of
 * Material's segmented list, or One UI's place in a card (which also tells
 * the row whether it needs a hairline above it).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun settingsShapes(index: Int, count: Int): ListItemShapes = when {
    isOneUi -> OneUiGroupShape.of(index, count).let { ListItemShapes(it, it, it, it, it, it) }
    isLiquid -> LiquidGroupShape.of(index, count).let { ListItemShapes(it, it, it, it, it, it) }
    else -> ListItemDefaults.segmentedShapes(index, count)
}

/**
 * The header row of a group that folds open: a card of its own while
 * folded, and under One UI the top of one card with its [rows] while open.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun groupHeaderShapes(expanded: Boolean, rows: Int): ListItemShapes =
    if ((isOneUi || isLiquid) && expanded) settingsShapes(0, rows + 1) else settingsShapes(0, 1)

/**
 * Row [index] of the [rows] inside an open group: under One UI they carry on
 * the card its header starts, under Material they are a group of their own.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun groupRowShapes(index: Int, rows: Int): ListItemShapes =
    if (isOneUi || isLiquid) settingsShapes(index + 1, rows + 1) else settingsShapes(index, rows)

/** The card position [settingsShapes] put in [shapes], or a card of its own. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun ListItemShapes.groupShape(): OneUiGroupShape =
    shape as? OneUiGroupShape ?: OneUiGroupShape.Single

/** The section position [settingsShapes] put in [shapes], or a section of its own. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun ListItemShapes.liquidShape(): LiquidGroupShape =
    shape as? LiquidGroupShape ?: LiquidGroupShape.Single

/**
 * A settings row that opens something, in the style the app is drawn in.
 *
 * [value] is the current setting, when the row picks one: shown at the end
 * of the row under Material, and under the title in the accent under One UI.
 * [action] is anything else for the end of the row - a chevron.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsMenuLink(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalSettingsGroupEnabled.current,
    icon: (@Composable () -> Unit)? = null,
    subtitle: (@Composable () -> Unit)? = null,
    value: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    colors: ListItemColors = SettingsTileDefaults.colors(),
    shapes: ListItemShapes = SettingsTileDefaults.shapes(),
    onClick: () -> Unit,
) {
    if (isOneUi) {
        OneUiRow(
            title = title,
            modifier = modifier,
            shape = shapes.groupShape(),
            summary = subtitle,
            value = value,
            icon = icon,
            trailing = action,
            enabled = enabled,
            interaction = Modifier.clickable(enabled = enabled, onClick = onClick),
        )
    } else if (isLiquid) {
        // iOS: the value in gray at the end, and a chevron on whatever opens
        // something rather than doing something in place.
        LiquidRow(
            title = title,
            modifier = modifier,
            shape = shapes.liquidShape(),
            subtitle = subtitle,
            value = value,
            icon = icon,
            trailing = action,
            chevron = action == null,
            enabled = enabled,
            interaction = Modifier.clickable(enabled = enabled, onClick = onClick),
        )
    } else {
        ExpressiveMenuLink(
            title = title,
            modifier = modifier,
            enabled = enabled,
            icon = icon,
            subtitle = subtitle,
            action = value ?: action,
            colors = colors,
            shapes = shapes,
            onClick = onClick,
        )
    }
}

/** A settings row with a switch, in the style the app is drawn in. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsSwitch(
    state: Boolean,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalSettingsGroupEnabled.current,
    icon: @Composable (() -> Unit)? = null,
    subtitle: @Composable (() -> Unit)? = null,
    colors: ListItemColors = SettingsTileDefaults.colors(),
    shapes: ListItemShapes = SettingsTileDefaults.shapes(),
    onCheckedChange: (Boolean) -> Unit,
) {
    if (isLiquid) {
        val source = remember { MutableInteractionSource() }
        LiquidRow(
            title = title,
            modifier = modifier,
            shape = shapes.liquidShape(),
            subtitle = subtitle,
            icon = icon,
            trailing = {
                LiquidSwitch(
                    checked = state,
                    onCheckedChange = onCheckedChange,
                    enabled = enabled,
                    interactionSource = source,
                )
            },
            enabled = enabled,
        )
        return
    }
    if (isOneUi) {
        val haptic = LocalHapticFeedback.current
        val source = remember { MutableInteractionSource() }
        // The whole row is the switch: a tap anywhere on it flips it, and
        // the one in the corner only shows which way it went.
        OneUiRow(
            title = title,
            modifier = modifier,
            shape = shapes.groupShape(),
            summary = subtitle,
            icon = icon,
            trailing = {
                OneUiSwitch(
                    checked = state,
                    onCheckedChange = null,
                    enabled = enabled,
                    interactionSource = source,
                )
            },
            enabled = enabled,
            interaction = Modifier.toggleable(
                value = state,
                interactionSource = source,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = {
                    haptic.performHapticFeedback(
                        if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff
                    )
                    onCheckedChange(it)
                },
            ),
        )
    } else {
        ExpressiveSwitch(
            state = state,
            title = title,
            modifier = modifier,
            enabled = enabled,
            icon = icon,
            subtitle = subtitle,
            colors = colors,
            shapes = shapes,
            onCheckedChange = onCheckedChange,
        )
    }
}
