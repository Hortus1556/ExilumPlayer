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
import com.hortus1556.exilum.ui.screens.preferences.components.PreferenceItem
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsSlider
import com.hortus1556.exilum.ui.screens.preferences.components.SingleChoiceAlertDialog
import kotlinx.collections.immutable.persistentListOf
import com.hortus1556.exilum.renderer.Player
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Collapsible group for AdPlug - the AdLib formats, played on an emulated
 * OPL3. Sits under libopenmpt's and Hively's inside the Engine section.
 *
 * The emulator is a choice: Nuked OPL3, DOSBox's, MAME's and Ken Silverman's
 * are all in AdPlug. Nuked keeps running beside whichever is heard, because
 * the channel view reads each voice's output from it and the others cannot be
 * asked for that - see ScopeOpl in decoder-adplug.cpp.
 *
 * What is worth a control is the one thing the format cannot ask for. An
 * OPL2 has no panning at all, so an OPL2 tune arrives with every voice
 * stacked in the middle.
 */
/** The OPL cores, in the order the native side numbers them. */
private val adplugCoreOptions = persistentListOf(
    PreferenceItem(
        key = Player.ADPLUG_CORE_NUKED.toString(),
        title = R.string.pref_adplug_core_nuked,
        description = R.string.pref_adplug_core_nuked_desc,
    ),
    PreferenceItem(
        key = Player.ADPLUG_CORE_DOSBOX.toString(),
        title = R.string.pref_adplug_core_dosbox,
        description = R.string.pref_adplug_core_dosbox_desc,
    ),
    PreferenceItem(
        key = Player.ADPLUG_CORE_MAME.toString(),
        title = R.string.pref_adplug_core_mame,
        description = R.string.pref_adplug_core_mame_desc,
    ),
    PreferenceItem(
        key = Player.ADPLUG_CORE_KEN.toString(),
        title = R.string.pref_adplug_core_ken,
        description = R.string.pref_adplug_core_ken_desc,
    ),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ColumnScope.AdPlugGroup(
    colors: ListItemColors
) {
    val scope = rememberCoroutineScope()
    val prefs = if (LocalView.current.isInEditMode) {
        AppPreferences(LocalContext.current)
    } else {
        koinInject<AppPreferences>()
    }

    val core by prefs.getAdPlugCoreFlow()
        .collectAsStateWithLifecycle(initialValue = Player.DEFAULT_ADPLUG_CORE)

    var isCoreShowing by remember { mutableStateOf(false) }
    if (isCoreShowing) {
        SingleChoiceAlertDialog(
            selectedItemKey = core.toString(),
            items = adplugCoreOptions,
            onItemSelected = { key ->
                key?.toIntOrNull()?.let { scope.launch { prefs.setAdPlugCore(it) } }
                isCoreShowing = false
            },
        )
    }

    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "adplug_chevron"
    )

    SettingsMenuLink(
        title = { Text(text = stringResource(R.string.pref_adplug_row)) },
        subtitle = { Text(text = stringResource(R.string.pref_adplug_row_desc)) },
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
            SettingsMenuLink(
                title = { Text(text = stringResource(R.string.pref_adplug_core)) },
                subtitle = { Text(text = stringResource(R.string.pref_adplug_core_desc)) },
                value = {
                    val option = adplugCoreOptions.firstOrNull { it.key == core.toString() }
                        ?: adplugCoreOptions.first()
                    Text(text = stringResource(option.title))
                },
                colors = colors,
                shapes = groupRowShapes(0, 1),
                onClick = { isCoreShowing = true },
            )
        }
    }
}
