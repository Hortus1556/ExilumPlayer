package com.hortus1556.exilum.ui.screens.preferences.section

import com.hortus1556.exilum.ui.screens.preferences.components.groupRowShapes
import com.hortus1556.exilum.ui.screens.preferences.components.groupHeaderShapes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsMenuLink
import com.hortus1556.exilum.R
import com.hortus1556.exilum.ui.screens.preferences.components.settingsShapes
import com.hortus1556.exilum.ui.screens.preferences.components.settingsRowSpacing
import com.hortus1556.exilum.db.AppPreferences
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsSlider
import com.hortus1556.exilum.renderer.Player
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Collapsible group for the Hively replayer - the one that plays .ahx, .thx
 * and .hvl. Sits under libopenmpt's inside the Engine section.
 *
 * Deliberately short. This replayer has no resampler, no dithering and no
 * ramping to configure: it reads a synthesised wavetable at a fixed step and
 * mixes four to sixteen voices with Amiga panning, and the only two things
 * about that worth a control are how wide the panning is spread and how loud
 * the result comes out. Everything in libopenmpt's group applies to trackers
 * and reaches none of these tunes.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ColumnScope.HivelyGroup(
    colors: ListItemColors
) {
    val scope = rememberCoroutineScope()
    val prefs = if (LocalView.current.isInEditMode) {
        AppPreferences(LocalContext.current)
    } else {
        koinInject<AppPreferences>()
    }

    val stereo by prefs.getHivelyStereoFlow()
        .collectAsStateWithLifecycle(initialValue = Player.DEFAULT_HIVELY_STEREO)

    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "hively_chevron"
    )

    SettingsMenuLink(
        title = { Text(text = stringResource(R.string.pref_hively_row)) },
        subtitle = { Text(text = stringResource(R.string.pref_hively_row_desc)) },
        action = {
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.rotate(chevronRotation)
            )
        },
        colors = colors,
        shapes = groupHeaderShapes(expanded, 1),
        onClick = { expanded = !expanded }
    )

    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column(verticalArrangement = settingsRowSpacing()) {
            SettingsSlider(
                title = { Text(text = stringResource(R.string.pref_hively_stereo)) },
                subtitle = { Text(text = stringResource(R.string.pref_hively_stereo_desc)) },
                action = { Text(text = stringResource(R.string.value_percent, stereo)) },
                colors = colors,
                steps = 0,
                valueRange = 0f..200f,
                value = stereo.toFloat(),
                onValueChange = { scope.launch { prefs.setHivelyStereo(it.toInt()) } },
                shapes = groupRowShapes(0, 1),
            )
        }
    }
}
