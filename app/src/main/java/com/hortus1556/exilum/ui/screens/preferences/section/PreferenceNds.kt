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
import androidx.compose.runtime.remember
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
import com.hortus1556.exilum.ui.screens.preferences.components.PreferenceItem
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsMenuLink
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsSlider
import com.hortus1556.exilum.ui.screens.preferences.components.SingleChoiceAlertDialog
import com.hortus1556.exilum.ui.screens.preferences.components.groupHeaderShapes
import com.hortus1556.exilum.ui.screens.preferences.components.groupRowShapes
import com.hortus1556.exilum.ui.screens.preferences.components.settingsRowSpacing
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * How a Nintendo DS voice's recording is read between the samples it has, the
 * console's own way first and the smoothest last. The overrides sheet offers
 * the same list for one file.
 */
internal val twoSfInterpOptions = persistentListOf(
    PreferenceItem(
        key = Player.TWOSF_INTERP_NONE.toString(),
        title = R.string.pref_twosf_interp_none,
        description = R.string.pref_twosf_interp_none_desc,
    ),
    PreferenceItem(
        key = Player.TWOSF_INTERP_BLEP.toString(),
        title = R.string.pref_twosf_interp_blep,
        description = R.string.pref_twosf_interp_blep_desc,
    ),
    PreferenceItem(
        key = Player.TWOSF_INTERP_LINEAR.toString(),
        title = R.string.pref_twosf_interp_linear,
        description = R.string.pref_twosf_interp_linear_desc,
    ),
    PreferenceItem(
        key = Player.TWOSF_INTERP_BLAM.toString(),
        title = R.string.pref_twosf_interp_blam,
        description = R.string.pref_twosf_interp_blam_desc,
    ),
    PreferenceItem(
        key = Player.TWOSF_INTERP_CUBIC.toString(),
        title = R.string.pref_twosf_interp_cubic,
        description = R.string.pref_twosf_interp_cubic_desc,
    ),
    PreferenceItem(
        key = Player.TWOSF_INTERP_SINC.toString(),
        title = R.string.pref_twosf_interp_sinc,
        description = R.string.pref_twosf_interp_sinc_desc,
    ),
)

/** The group's rows: the interpolation, the two sides, and a pan for each voice. */
private const val TWOSF_ROWS = 2 + Player.TWOSF_CHANNELS

/**
 * Collapsible group for the Nintendo DS - 2SF and mini2sf, played by vio2sf.
 * Sits with the other consoles inside the Engine section.
 *
 * The console mixes its sixteen voices itself, and what there is to set is
 * inside that mix: how a voice's recording is read between its samples, and
 * where each voice sits. How far apart the two sides are is done to the
 * stereo pair after. Pitch and tempo work as for every other format, from
 * the player.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ColumnScope.NintendoDsGroup(
    colors: ListItemColors
) {
    val scope = rememberCoroutineScope()
    val prefs = if (LocalView.current.isInEditMode) {
        AppPreferences(LocalContext.current)
    } else {
        koinInject<AppPreferences>()
    }

    val interp by prefs.getTwoSfInterpolationFlow()
        .collectAsStateWithLifecycle(initialValue = Player.DEFAULT_TWOSF_INTERPOLATION)
    val stereo by prefs.getTwoSfStereoFlow()
        .collectAsStateWithLifecycle(initialValue = Player.DEFAULT_TWOSF_STEREO)

    var isInterpShowing by remember { mutableStateOf(false) }
    if (isInterpShowing) {
        SingleChoiceAlertDialog(
            selectedItemKey = interp.toString(),
            items = twoSfInterpOptions,
            onItemSelected = { key ->
                key?.toIntOrNull()?.let { scope.launch { prefs.setTwoSfInterpolation(it) } }
                isInterpShowing = false
            },
        )
    }

    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "nds_chevron"
    )

    SettingsMenuLink(
        title = { Text(text = stringResource(R.string.pref_twosf_row)) },
        subtitle = { Text(text = stringResource(R.string.pref_twosf_row_desc)) },
        action = {
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.rotate(chevronRotation)
            )
        },
        colors = colors,
        shapes = groupHeaderShapes(expanded, TWOSF_ROWS),
        onClick = { expanded = !expanded }
    )

    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column(verticalArrangement = settingsRowSpacing()) {
            SettingsMenuLink(
                title = { Text(text = stringResource(R.string.pref_twosf_interp)) },
                subtitle = { Text(text = stringResource(R.string.pref_twosf_interp_desc)) },
                value = {
                    val chosen = twoSfInterpOptions.firstOrNull { it.key == interp.toString() }
                    Text(text = stringResource(chosen?.title ?: R.string.pref_twosf_interp_cubic))
                },
                colors = colors,
                shapes = groupRowShapes(0, TWOSF_ROWS),
                onClick = { isInterpShowing = true },
            )
            SettingsSlider(
                title = { Text(text = stringResource(R.string.pref_gsf_stereo)) },
                subtitle = { Text(text = stringResource(R.string.pref_gsf_stereo_desc)) },
                action = { Text(text = stringResource(R.string.value_percent, stereo)) },
                colors = colors,
                steps = 0,
                valueRange = 0f..200f,
                value = stereo.toFloat(),
                onValueChange = { scope.launch { prefs.setTwoSfStereo(it.toInt()) } },
                shapes = groupRowShapes(1, TWOSF_ROWS),
            )
            // One slider per voice, from where its game puts it: the console
            // pans each of them itself, and this moves it from there.
            repeat(Player.TWOSF_CHANNELS) { channel ->
                // Its flow held on to. Asked for again each time this list
                // was gone through - every time a slider in it wrote its
                // value - it was a new flow, and all sixteen were let go and
                // picked up again ten times a second under a finger.
                val pan by remember(prefs, channel) { prefs.getTwoSfPanFlow(channel) }
                    .collectAsStateWithLifecycle(initialValue = Player.DEFAULT_TWOSF_PAN)
                val hint: (@Composable () -> Unit)? = if (channel == 0) {
                    { Text(text = stringResource(R.string.pref_twosf_pan_desc)) }
                } else {
                    null
                }
                SettingsSlider(
                    title = { Text(text = stringResource(R.string.twosf_channel, channel + 1)) },
                    subtitle = hint,
                    action = { Text(text = panLabel(pan)) },
                    colors = colors,
                    steps = 0,
                    centered = true,
                    startLabel = stringResource(R.string.pan_left_short),
                    endLabel = stringResource(R.string.pan_right_short),
                    valueRange = -100f..100f,
                    value = pan.toFloat(),
                    onValueChange = { scope.launch { prefs.setTwoSfPan(channel, it.toInt()) } },
                    shapes = groupRowShapes(2 + channel, TWOSF_ROWS),
                )
            }
        }
    }
}
