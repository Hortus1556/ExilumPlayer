package com.hortus1556.exilum.ui.screens.preferences.components

import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.liquid.LiquidSectionHeader
import com.hortus1556.exilum.ui.liquid.LiquidDimens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.alorma.compose.settings.ui.core.LocalSettingsGroupEnabled
import com.alorma.compose.settings.ui.expressive.SettingsGroup
import com.hortus1556.exilum.R
import com.hortus1556.exilum.ui.oneui.OneUiDimens
import com.hortus1556.exilum.ui.oneui.OneUiSubheader
import com.hortus1556.exilum.ui.theme.AppTheme
import com.hortus1556.exilum.ui.theme.isOneUi

/**
 * Whether a [PreferenceSection] shows its title. Off on a page that is the
 * section, whose app bar already says what it is.
 */
internal val LocalShowSectionTitle = staticCompositionLocalOf { true }

/**
 * A titled group of settings. Under One UI: a small gray heading over its
 * rows, which draw themselves as cards (see settingsShapes).
 */
@Composable
internal fun PreferenceSection(
    title: @Composable () -> Unit,
    enabled: Boolean = true,
    paddingValues: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    if (isLiquid) {
        CompositionLocalProvider(LocalSettingsGroupEnabled provides enabled) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LiquidDimens.SectionMargin)
                    .padding(bottom = 4.dp),
            ) {
                if (LocalShowSectionTitle.current) {
                    LiquidSectionHeader(content = title)
                }
                content()
            }
        }
        return
    }
    if (isOneUi) {
        CompositionLocalProvider(LocalSettingsGroupEnabled provides enabled) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = OneUiDimens.CardMargin)
                    .padding(bottom = 8.dp),
            ) {
                if (LocalShowSectionTitle.current) {
                    OneUiSubheader(content = title)
                }
                content()
            }
        }
        return
    }
    SettingsGroup(
        contentPadding = paddingValues,
        verticalArrangement = verticalArrangement,
        enabled = enabled,
        title = if (LocalShowSectionTitle.current) {
            { ProvideTextStyle(MaterialTheme.typography.headlineSmall, title) }
        } else {
            null
        },
        content = content
    )
}

/**
 * The gap between the rows of a group: Material's segments stand a little
 * apart, One UI's rows are one card.
 */
@Composable
internal fun settingsRowSpacing(): Arrangement.Vertical =
    Arrangement.spacedBy(if (isOneUi || isLiquid) 0.dp else 4.dp)

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PreferenceSection(
                    title = { Text(text = stringResource(R.string.confirm)) },
                    enabled = true,
                    content = {}
                )
                PreferenceSection(
                    title = { Text(text = stringResource(R.string.confirm)) },
                    enabled = false,
                    content = {}
                )
            }
        }
    }
}
