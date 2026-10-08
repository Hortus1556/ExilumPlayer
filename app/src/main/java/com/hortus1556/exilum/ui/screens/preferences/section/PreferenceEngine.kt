package com.hortus1556.exilum.ui.screens.preferences.section

import kotlinx.coroutines.flow.map
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
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsSlider
import com.hortus1556.exilum.ui.screens.preferences.components.SingleChoiceAlertDialog
import com.hortus1556.exilum.ui.theme.AppTheme
import com.hortus1556.exilum.renderer.Player
import com.hortus1556.exilum.renderer.ResamplerMode
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

// Best first, the two machines after it, and no interpolation last.
private val interpOptions = persistentListOf(
    PreferenceItem(
        key = ResamplerMode.SINC.id.toString(),
        title = R.string.pref_interp_item_sinc,
        description = R.string.pref_interp_item_sinc_desc
    ),
    PreferenceItem(
        key = ResamplerMode.CUBIC.id.toString(),
        title = R.string.pref_interp_item_spline,
        description = R.string.pref_interp_item_spline_desc
    ),
    PreferenceItem(
        key = ResamplerMode.AMIGA_A500.id.toString(),
        title = R.string.pref_interp_item_amiga_a500,
        description = R.string.pref_interp_item_amiga_a500_desc
    ),
    PreferenceItem(
        key = ResamplerMode.AMIGA_A1200.id.toString(),
        title = R.string.pref_interp_item_amiga_a1200,
        description = R.string.pref_interp_item_amiga_a1200_desc
    ),
    PreferenceItem(
        key = ResamplerMode.OFF.id.toString(),
        title = R.string.pref_interp_item_off,
        description = R.string.pref_interp_item_off_desc
    ),
)

/**
 * The Engine section: one collapsible group per decoding library.
 *
 * One section rather than one per library, because they are all the engine -
 * a reader looking for how a file is decoded should find every library that
 * might decode it in the same place, folded away until it is wanted. Each
 * group is still its own file; only the heading is shared.
 *
 * Every control in the libopenmpt group maps directly to a native libopenmpt
 * render parameter or ctl — there is no libxmp-compatibility cruft.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PreferenceEngine(
    colors: ListItemColors
) {
    val scope = rememberCoroutineScope()
    val prefs = if (LocalView.current.isInEditMode) {
        AppPreferences(LocalContext.current)
    } else {
        koinInject<AppPreferences>()
    }

    val stereoSeparation by prefs.getStereoSeparationFlow()
        .collectAsStateWithLifecycle(initialValue = Player.DEFAULT_STEREO_SEPARATION)
    // Through the modes themselves, so a choice made when there were others
    // - linear, which is gone - shows as the one it now plays as.
    val interp by prefs.getInterpolationTypeFlow()
        .map { ResamplerMode.fromId(it).id }
        .collectAsStateWithLifecycle(initialValue = Player.DEFAULT_INTERPOLATION)

    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "libopenmpt_chevron"
    )

    var isInterpShowing by remember { mutableStateOf(false) }
    if (isInterpShowing) {
        SingleChoiceAlertDialog(
            selectedItemKey = interp.toString(),
            items = interpOptions,
            onItemSelected = { key ->
                key?.toIntOrNull()?.let { scope.launch { prefs.setInterpolationType(it) } }
                isInterpShowing = false
            },
        )
    }

    PreferenceSection(
        title = {
            Text(
                text = stringResource(R.string.pref_title_engine)
            )
        },
        verticalArrangement = settingsRowSpacing(),
        content = {
            SettingsMenuLink(
                title = { Text(text = stringResource(R.string.pref_libopenmpt_row)) },
                subtitle = { Text(text = stringResource(R.string.pref_libopenmpt_row_desc)) },
                action = {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.rotate(chevronRotation)
                    )
                },
                colors = colors,
                shapes = groupHeaderShapes(expanded, 2),
                onClick = { expanded = !expanded }
            )

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(verticalArrangement = settingsRowSpacing()) {
                    // Sample rate, buffer size, sample format, volume ramping
                    // and dithering used to be here. None of them was a
                    // choice worth offering: the stream is opened at whatever
                    // rate the device runs at, which is the one rate that
                    // needs no resampling anywhere; the buffer is sized by
                    // Oboe from the device's own burst; and ramping and
                    // dithering are libopenmpt defaults that are right.
                    // A mono switch used to be here, and a master volume.
                    // Mono is what the separation below reads as at 0, and how
                    // loud a file plays is the sound check's to decide.
                    SettingsSlider(
                        title = { Text(text = stringResource(R.string.pref_stereo_separation)) },
                        subtitle = { Text(text = stringResource(R.string.pref_stereo_separation_desc)) },
                        action = { Text(text = stringResource(R.string.value_percent, stereoSeparation)) },
                        colors = colors,
                        steps = 0,
                        valueRange = 0f..200f,
                        value = stereoSeparation.toFloat(),
                        onValueChange = { scope.launch { prefs.setStereoSeparation(it.toInt()) } },
                        shapes = groupRowShapes(0, 2),
                    )
                    SettingsMenuLink(
                        title = { Text(text = stringResource(R.string.pref_interpolation_type)) },
                        subtitle = { Text(text = stringResource(R.string.pref_interpolation_type_desc)) },
                        value = {
                            val text = when (interp) {
                                ResamplerMode.OFF.id -> stringResource(R.string.interp_off)
                                ResamplerMode.CUBIC.id -> stringResource(R.string.interp_spline)
                                ResamplerMode.SINC.id -> stringResource(R.string.interp_sinc)
                                ResamplerMode.AMIGA_A500.id -> stringResource(R.string.interp_openmpt_amiga_a500)
                                ResamplerMode.AMIGA_A1200.id -> stringResource(R.string.interp_openmpt_amiga_a1200)
                                else -> stringResource(R.string.unknown)
                            }
                            Text(text = text)
                        },
                        colors = colors,
                        shapes = groupRowShapes(1, 2),
                        onClick = { isInterpShowing = true }
                    )
                }
            }

            HivelyGroup(colors)
            AdPlugGroup(colors)
            // Per console rather than per library: see PreferenceGmeConsoles.
            NesGroup(colors)
            GameBoyGroup(colors)
            SnesGroup(colors)
            GameBoyAdvanceGroup(colors)
            NintendoDsGroup(colors)
            Nintendo64Group(colors)
            PlayStationGroup(colors)
            GameCubeGroup(colors)
            RecordedMusicGroup(colors)
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
            Column {
                PreferenceEngine(colors)
            }
        }
    }
}
