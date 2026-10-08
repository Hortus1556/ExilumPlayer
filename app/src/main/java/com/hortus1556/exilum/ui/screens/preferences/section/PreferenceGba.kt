package com.hortus1556.exilum.ui.screens.preferences.section

import kotlinx.collections.immutable.persistentListOf
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
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsMenuLink
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsSlider
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsSwitch
import com.hortus1556.exilum.ui.screens.preferences.components.groupHeaderShapes
import com.hortus1556.exilum.ui.screens.preferences.components.groupRowShapes
import com.hortus1556.exilum.ui.screens.preferences.components.settingsRowSpacing
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** The Game Boy Advance's six channels, in the order the emulator reports them. */
internal val gsfChannelNames = persistentListOf(
    R.string.gsf_channel_square_1,
    R.string.gsf_channel_square_2,
    R.string.gsf_channel_wave,
    R.string.gsf_channel_noise,
    R.string.gsf_channel_direct_a,
    R.string.gsf_channel_direct_b,
)

/** The group's rows: the two sides, the filter, and a pan for each channel. */
private val gsfRows = 2 + gsfChannelNames.size

/**
 * Collapsible group for the Game Boy Advance - GSF and minigsf, played by
 * mGBA. Sits with the other consoles inside the Engine section.
 *
 * The console mixes everything itself, so what there is to set is what
 * happens to its stereo pair after: how far apart the two sides are, whether
 * the grit of the games' own low-rate mixing is softened, and where each of
 * its six channels sits. Pitch and tempo work as for every other format,
 * from the player.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ColumnScope.GameBoyAdvanceGroup(
    colors: ListItemColors
) {
    val scope = rememberCoroutineScope()
    val prefs = if (LocalView.current.isInEditMode) {
        AppPreferences(LocalContext.current)
    } else {
        koinInject<AppPreferences>()
    }

    val stereo by prefs.getGsfStereoFlow()
        .collectAsStateWithLifecycle(initialValue = Player.DEFAULT_GSF_STEREO)
    val lowpass by prefs.getGsfLowpassFlow()
        .collectAsStateWithLifecycle(initialValue = Player.DEFAULT_GSF_LOWPASS)

    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "gba_chevron"
    )

    SettingsMenuLink(
        title = { Text(text = stringResource(R.string.pref_gsf_row)) },
        subtitle = { Text(text = stringResource(R.string.pref_gsf_row_desc)) },
        action = {
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.rotate(chevronRotation)
            )
        },
        colors = colors,
        shapes = groupHeaderShapes(expanded, gsfRows),
        onClick = { expanded = !expanded }
    )

    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column(verticalArrangement = settingsRowSpacing()) {
            SettingsSlider(
                title = { Text(text = stringResource(R.string.pref_gsf_stereo)) },
                subtitle = { Text(text = stringResource(R.string.pref_gsf_stereo_desc)) },
                action = { Text(text = stringResource(R.string.value_percent, stereo)) },
                colors = colors,
                steps = 0,
                valueRange = 0f..200f,
                value = stereo.toFloat(),
                onValueChange = { scope.launch { prefs.setGsfStereo(it.toInt()) } },
                shapes = groupRowShapes(0, gsfRows),
            )
            SettingsSwitch(
                state = lowpass,
                title = { Text(text = stringResource(R.string.pref_gsf_lowpass)) },
                subtitle = { Text(text = stringResource(R.string.pref_gsf_lowpass_desc)) },
                colors = colors,
                shapes = groupRowShapes(1, gsfRows),
                onCheckedChange = { scope.launch { prefs.setGsfLowpass(it) } },
            )
            // One slider per channel, from where its game puts it: the
            // console routes each to a side, or both, on its own, and this
            // moves it from there.
            gsfChannelNames.forEachIndexed { channel, name ->
                // Its flow held on to, as the Nintendo DS's are: see there.
                val pan by remember(prefs, channel) { prefs.getGsfPanFlow(channel) }
                    .collectAsStateWithLifecycle(initialValue = Player.DEFAULT_GSF_PAN)
                val hint: (@Composable () -> Unit)? = if (channel == 0) {
                    { Text(text = stringResource(R.string.pref_gsf_pan_desc)) }
                } else {
                    null
                }
                SettingsSlider(
                    title = { Text(text = stringResource(name)) },
                    subtitle = hint,
                    action = { Text(text = panLabel(pan)) },
                    colors = colors,
                    steps = 0,
                    centered = true,
                    startLabel = stringResource(R.string.pan_left_short),
                    endLabel = stringResource(R.string.pan_right_short),
                    valueRange = -100f..100f,
                    value = pan.toFloat(),
                    onValueChange = { scope.launch { prefs.setGsfPan(channel, it.toInt()) } },
                    shapes = groupRowShapes(2 + channel, gsfRows),
                )
            }
        }
    }
}
