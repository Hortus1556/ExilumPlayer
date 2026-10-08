package com.hortus1556.exilum.ui.screens.preferences.section

import com.hortus1556.exilum.ui.liquid.LocalLiquidColors
import com.hortus1556.exilum.ui.liquid.LiquidRefraction
import kotlinx.collections.immutable.toPersistentList
import kotlin.math.roundToInt
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsSlider
import com.hortus1556.exilum.db.DEFAULT_GLASS_OPACITY
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsMenuLink
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsSwitch
import com.hortus1556.exilum.R
import com.hortus1556.exilum.ui.screens.preferences.components.settingsShapes
import com.hortus1556.exilum.ui.screens.preferences.components.settingsRowSpacing
import com.hortus1556.exilum.db.AppPreferences
import com.hortus1556.exilum.ui.screens.preferences.components.PreferenceItem
import com.hortus1556.exilum.ui.screens.preferences.components.PreferenceSection
import com.hortus1556.exilum.ui.screens.preferences.components.SingleChoiceAlertDialog
import com.hortus1556.exilum.ui.theme.AppTheme
import com.hortus1556.exilum.ui.theme.UiStyle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

// Every style there is, so the list cannot fall behind them - Liquid Glass
// first, as the one the app is drawn in unless told otherwise.
private val uiStyleItems = UiStyle.entries.sortedByDescending { it == UiStyle.LiquidGlass }.map { style ->
    PreferenceItem(key = style.name, title = style.title, description = style.description)
}.toPersistentList()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PreferenceInterface(
    colors: ListItemColors
) {
    val scope = rememberCoroutineScope()
    val prefs = if (LocalView.current.isInEditMode) {
        AppPreferences(LocalContext.current)
    } else {
        koinInject<AppPreferences>()
    }

    val uiStyle by remember(prefs) {
        prefs.getUiStyleFlow().map {
            UiStyle.fromStored(it)
        }
    }.collectAsStateWithLifecycle(UiStyle.LiquidGlass)
    // Starting from what the app is drawn in now, which is the setting: a
    // switch that started on and was told off a moment later slid across,
    // lifting as though it had been pressed, every time the page was opened
    // in light mode.
    val darkMode by prefs.getDarkModeFlow().collectAsStateWithLifecycle(initialValue = LocalLiquidColors.current.isDark)
    val refraction by prefs.getLiquidRefractionFlow().collectAsStateWithLifecycle(initialValue = LiquidRefraction.enabled)
    val glassOpacity by prefs.getLiquidGlassOpacityFlow().collectAsStateWithLifecycle(initialValue = DEFAULT_GLASS_OPACITY)
    val liquid = uiStyle == UiStyle.LiquidGlass

    var isUiStyleShowing by remember { mutableStateOf(false) }
    if (isUiStyleShowing) {
        SingleChoiceAlertDialog(
            selectedItemKey = uiStyle.name,
            items = uiStyleItems,
            onItemSelected = { key ->
                key?.let {
                    scope.launch { prefs.setUiStyle(UiStyle.valueOf(it).stored) }
                }
                isUiStyleShowing = false
            },
        )
    }

    PreferenceSection(
        title = {
            Text(
                text = stringResource(R.string.pref_title_interface)
            )
        },
        verticalArrangement = settingsRowSpacing(),
        content = {
            SettingsMenuLink(
                title = { Text(text = stringResource(R.string.pref_scheme_style)) },
                subtitle = { Text(text = stringResource(R.string.pref_scheme_style_desc)) },
                value = { Text(text = stringResource(uiStyle.title)) },
                colors = colors,
                shapes = settingsShapes(0, if (liquid) 4 else 2),
                onClick = { isUiStyleShowing = true }
            )
            SettingsSwitch(
                title = { Text(text = stringResource(R.string.pref_dark_mode)) },
                subtitle = { Text(text = stringResource(R.string.pref_dark_mode_desc)) },
                state = darkMode,
                onCheckedChange = { scope.launch { prefs.setDarkMode(it) } },
                colors = colors,
                shapes = settingsShapes(1, if (liquid) 4 else 2),
            )
            // Only Liquid Glass has glass to make clearer or frostier.
            if (liquid) {
                SettingsSlider(
                    value = glassOpacity,
                    onValueChange = { scope.launch { prefs.setLiquidGlassOpacity(it) } },
                    title = { Text(text = stringResource(R.string.pref_glass_opacity)) },
                    subtitle = { Text(text = stringResource(R.string.pref_glass_opacity_desc)) },
                    action = { Text(text = "${(glassOpacity * 100).roundToInt()}%") },
                    valueRange = 0f..1f,
                    colors = colors,
                    shapes = settingsShapes(2, 4),
                    startLabel = stringResource(R.string.pref_glass_opacity_clear),
                    endLabel = stringResource(R.string.pref_glass_opacity_frosted),
                )
                // The bending itself, which a device before Android 13
                // cannot do: there the glass is a blur, and this says so.
                val supported = LiquidRefraction.supported
                SettingsSwitch(
                    title = { Text(text = stringResource(R.string.pref_liquid_refraction)) },
                    subtitle = {
                        Text(
                            text = stringResource(
                                if (supported) R.string.pref_liquid_refraction_desc else R.string.pref_liquid_refraction_unsupported
                            )
                        )
                    },
                    state = supported && refraction,
                    enabled = supported,
                    onCheckedChange = { scope.launch { prefs.setLiquidRefraction(it) } },
                    colors = colors,
                    shapes = settingsShapes(3, 4),
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Preview
@Composable
private fun Preview() {
    AppTheme {
        val colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
        Surface {
            PreferenceInterface(colors)
        }
    }
}
