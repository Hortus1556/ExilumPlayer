package com.hortus1556.exilum.ui.screens.preferences.section

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsMenuLink
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsSwitch
import com.hortus1556.exilum.R
import com.hortus1556.exilum.ui.screens.preferences.components.settingsShapes
import com.hortus1556.exilum.ui.screens.preferences.components.settingsRowSpacing
import com.hortus1556.exilum.db.AppPreferences
import com.hortus1556.exilum.ui.screens.preferences.components.PreferenceSection
import com.hortus1556.exilum.ui.theme.AppTheme
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PreferenceInfo(
    colors: ListItemColors,
    onFormats: () -> Unit,
    onAbout: () -> Unit,
    onExportLogs: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val prefs = if (LocalView.current.isInEditMode) {
        AppPreferences(LocalContext.current)
    } else {
        koinInject<AppPreferences>()
    }
    val fileLogging by prefs.getFileLoggingFlow().collectAsStateWithLifecycle(false)

    PreferenceSection(
        title = {
            Text(
                text = stringResource(R.string.pref_title_about)
            )
        },
        verticalArrangement = settingsRowSpacing(),
        content = {
            SettingsMenuLink(
                title = { Text(text = stringResource(R.string.pref_formats_mpt)) },
                subtitle = { Text(text = stringResource(R.string.pref_formats_mpt_desc)) },
                colors = colors,
                shapes = settingsShapes(0, 4),
                onClick = onFormats
            )
            SettingsMenuLink(
                title = { Text(text = stringResource(R.string.pref_about)) },
                subtitle = { Text(text = stringResource(R.string.pref_about_desc)) },
                colors = colors,
                shapes = settingsShapes(1, 4),
                onClick = onAbout
            )
            SettingsSwitch(
                title = { Text(text = stringResource(R.string.pref_logging)) },
                subtitle = { Text(text = stringResource(R.string.pref_logging_desc)) },
                colors = colors,
                shapes = settingsShapes(2, 4),
                state = fileLogging,
                onCheckedChange = { scope.launch { prefs.setFileLogging(it) } }
            )
            SettingsMenuLink(
                title = { Text(text = stringResource(R.string.pref_export_logs)) },
                subtitle = { Text(text = stringResource(R.string.pref_export_logs_desc)) },
                colors = colors,
                shapes = settingsShapes(3, 4),
                enabled = fileLogging,
                onClick = onExportLogs,
            )
        }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface {
            val colors = ListItemDefaults.segmentedColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            )
            PreferenceInfo(
                colors = colors,
                onFormats = {},
                onAbout = {},
                onExportLogs = {},
            )
        }
    }
}
