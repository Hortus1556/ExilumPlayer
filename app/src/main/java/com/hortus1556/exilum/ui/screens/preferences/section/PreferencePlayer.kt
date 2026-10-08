package com.hortus1556.exilum.ui.screens.preferences.section

import com.hortus1556.exilum.db.DEFAULT_SOUND_CHECK
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsSwitch
import com.hortus1556.exilum.R
import com.hortus1556.exilum.ui.screens.preferences.components.settingsShapes
import com.hortus1556.exilum.ui.screens.preferences.components.settingsRowSpacing
import com.hortus1556.exilum.db.AppPreferences
import com.hortus1556.exilum.player.soundcheck.LoudnessAnalyzer
import com.hortus1556.exilum.ui.screens.preferences.components.PreferenceSection
import com.hortus1556.exilum.ui.theme.AppTheme
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PreferencePlayer(
    colors: ListItemColors
) {
    val scope = rememberCoroutineScope()
    val prefs = if (LocalView.current.isInEditMode) {
        AppPreferences(LocalContext.current)
    } else {
        koinInject<AppPreferences>()
    }

    // The two flows held on to: this function reads the library's analysis
    // below, so it runs again for every file analysed, and asking for them
    // again each time was two new flows to collect.
    val autoResume by remember(prefs) { prefs.getAutoResumeFlow() }
        .collectAsStateWithLifecycle(initialValue = false)
    val soundCheck by remember(prefs) { prefs.getSoundCheckFlow() }
        .collectAsStateWithLifecycle(initialValue = DEFAULT_SOUND_CHECK)
    val analyzer = if (LocalView.current.isInEditMode) null else koinInject<LoudnessAnalyzer>()
    val library = analyzer?.progress?.collectAsStateWithLifecycle()?.value

    PreferenceSection(
        title = {
            Text(
                text = stringResource(R.string.pref_title_player)
            )
        },
        verticalArrangement = settingsRowSpacing(),
        content = {
            SettingsSwitch(
                title = { Text(text = stringResource(R.string.pref_auto_resume)) },
                subtitle = { Text(text = stringResource(R.string.pref_auto_resume_desc)) },
                state = autoResume,
                onCheckedChange = { scope.launch { prefs.setAutoResume(it) } },
                colors = colors,
                shapes = settingsShapes(0, 2),
            )
            SettingsSwitch(
                title = { Text(text = stringResource(R.string.pref_sound_check)) },
                subtitle = {
                    Column {
                        Text(text = stringResource(R.string.pref_sound_check_desc))
                        // The library's analysis, while it has not got through it.
                        if (soundCheck && library != null && library.total > 0 && library.done < library.total) {
                            Text(
                                text = stringResource(R.string.pref_sound_check_progress, library.done, library.total),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                state = soundCheck,
                onCheckedChange = { scope.launch { prefs.setSoundCheck(it) } },
                colors = colors,
                shapes = settingsShapes(1, 2),
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
            PreferencePlayer(colors = colors)
        }
    }
}
