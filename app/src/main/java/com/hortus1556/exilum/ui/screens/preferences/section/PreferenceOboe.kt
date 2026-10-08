package com.hortus1556.exilum.ui.screens.preferences.section

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsMenuLink
import com.hortus1556.exilum.R
import com.hortus1556.exilum.ui.screens.preferences.components.settingsShapes
import com.hortus1556.exilum.ui.screens.preferences.components.settingsRowSpacing
import com.hortus1556.exilum.db.AppPreferences
import com.hortus1556.exilum.ui.screens.preferences.components.PreferenceItem
import com.hortus1556.exilum.ui.screens.preferences.components.PreferenceSection
import com.hortus1556.exilum.ui.screens.preferences.components.SingleChoiceAlertDialog
import com.hortus1556.exilum.ui.theme.AppTheme
import com.hortus1556.exilum.renderer.Player
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private fun Int.toAudioApiKey() = when (this) {
    Player.OBOE_AUDIO_API_AAUDIO -> "aaudio"
    Player.OBOE_AUDIO_API_OPENSLES -> "opensles"
    else -> "unspecified"
}

private fun String.toAudioApiInt() = when (this) {
    "aaudio" -> Player.OBOE_AUDIO_API_AAUDIO
    "opensles" -> Player.OBOE_AUDIO_API_OPENSLES
    else -> Player.OBOE_AUDIO_API_UNSPECIFIED
}

private val apiOptions = persistentListOf(
    PreferenceItem(
        key = "unspecified",
        title = R.string.pref_audio_api_item_unspecified,
        description = R.string.pref_audio_api_item_unspecified_desc
    ),
    PreferenceItem(
        key = "aaudio",
        title = R.string.pref_audio_api_item_aaudio,
        description = R.string.pref_audio_api_item_aaudio_desc
    ),
    PreferenceItem(
        key = "opensles",
        title = R.string.pref_audio_api_item_opensles,
        description = R.string.pref_audio_api_item_opensles_desc
    ),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PreferenceOboe(
    colors: ListItemColors
) {
    val scope = rememberCoroutineScope()
    val prefs = if (LocalView.current.isInEditMode) {
        AppPreferences(LocalContext.current)
    } else {
        koinInject<AppPreferences>()
    }

    val audioApi by prefs.getOboeAudioApiFlow()
        .collectAsStateWithLifecycle(initialValue = Player.OBOE_AUDIO_API_UNSPECIFIED)

    var isAudioApiShowing by remember { mutableStateOf(false) }
    if (isAudioApiShowing) {
        SingleChoiceAlertDialog(
            selectedItemKey = audioApi.toAudioApiKey(),
            items = apiOptions,
            onItemSelected = { key ->
                isAudioApiShowing = false
                key?.let { scope.launch { prefs.setOboeAudioApi(it.toAudioApiInt()) } }
            },
        )
    }

    PreferenceSection(
        title = {
            Text(
                text = stringResource(R.string.pref_title_oboe),
            )
        },
        verticalArrangement = settingsRowSpacing(),
        content = {
            SettingsMenuLink(
                onClick = { isAudioApiShowing = true },
                title = { Text(text = stringResource(R.string.pref_audio_api)) },
                subtitle = { Text(text = stringResource(R.string.pref_audio_api_desc)) },
                value = {
                    val text = apiOptions.find { it.key == audioApi.toAudioApiKey() }!!.title
                    Text(text = stringResource(text))
                },
                colors = colors,
                shapes = settingsShapes(0, 1),
            )
            // The effects, on everything played: see PreferenceEffects.
            ReverbGroup(colors)
            ChorusGroup(colors)
            SurroundGroup(colors)
            EqualizerGroup(colors)
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
            PreferenceOboe(colors = colors)
        }
    }
}
