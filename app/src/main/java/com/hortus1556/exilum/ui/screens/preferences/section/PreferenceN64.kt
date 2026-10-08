package com.hortus1556.exilum.ui.screens.preferences.section

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hortus1556.exilum.R
import com.hortus1556.exilum.db.AppPreferences
import com.hortus1556.exilum.renderer.Player
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsMenuLink
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsSlider
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsSwitch
import com.hortus1556.exilum.ui.screens.preferences.components.groupHeaderShapes
import com.hortus1556.exilum.ui.screens.preferences.components.groupRowShapes
import com.hortus1556.exilum.ui.screens.preferences.components.settingsRowSpacing
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** The group's rows: how the sound code is run, and the two sides. */
private const val USF_ROWS = 2

/**
 * Collapsible group for the Nintendo 64 - USF and miniusf, played by
 * lazyusf2. Sits with the other consoles inside the Engine section.
 *
 * The console has no sound chip: each game's own code mixes its music into
 * one stereo pair on the console's signal processor. What there is to set is
 * how that code is run - the emulator's own version of it, or the processor
 * itself - and how far apart the two sides are. Pitch and tempo work as for
 * every other format, from the player.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ColumnScope.Nintendo64Group(
    colors: ListItemColors
) {
    val scope = rememberCoroutineScope()
    val prefs = if (LocalView.current.isInEditMode) {
        AppPreferences(LocalContext.current)
    } else {
        koinInject<AppPreferences>()
    }

    val hle by prefs.getUsfHleFlow()
        .collectAsStateWithLifecycle(initialValue = Player.DEFAULT_USF_HLE)
    val stereo by prefs.getUsfStereoFlow()
        .collectAsStateWithLifecycle(initialValue = Player.DEFAULT_USF_STEREO)

    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "n64_chevron"
    )

    SettingsMenuLink(
        title = { Text(text = stringResource(R.string.pref_usf_row)) },
        subtitle = { Text(text = stringResource(R.string.pref_usf_row_desc)) },
        action = {
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.rotate(chevronRotation)
            )
        },
        colors = colors,
        shapes = groupHeaderShapes(expanded, USF_ROWS),
        onClick = { expanded = !expanded }
    )

    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column(verticalArrangement = settingsRowSpacing()) {
            SettingsSwitch(
                state = hle,
                title = { Text(text = stringResource(R.string.pref_usf_hle)) },
                subtitle = { Text(text = stringResource(R.string.pref_usf_hle_desc)) },
                colors = colors,
                shapes = groupRowShapes(0, USF_ROWS),
                onCheckedChange = { scope.launch { prefs.setUsfHle(it) } },
            )
            SettingsSlider(
                title = { Text(text = stringResource(R.string.pref_gsf_stereo)) },
                subtitle = { Text(text = stringResource(R.string.pref_gsf_stereo_desc)) },
                action = { Text(text = stringResource(R.string.value_percent, stereo)) },
                colors = colors,
                steps = 0,
                valueRange = 0f..200f,
                value = stereo.toFloat(),
                onValueChange = { scope.launch { prefs.setUsfStereo(it.toInt()) } },
                shapes = groupRowShapes(1, USF_ROWS),
            )
        }
    }
}
