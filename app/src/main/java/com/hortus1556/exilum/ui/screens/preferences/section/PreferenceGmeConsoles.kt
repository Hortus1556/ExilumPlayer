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
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsSwitch
import com.hortus1556.exilum.R
import com.hortus1556.exilum.ui.screens.preferences.components.settingsShapes
import com.hortus1556.exilum.ui.screens.preferences.components.settingsRowSpacing
import com.hortus1556.exilum.db.AppPreferences
import com.hortus1556.exilum.ui.screens.preferences.components.PreferenceItem
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsSlider
import com.hortus1556.exilum.ui.screens.preferences.components.SingleChoiceAlertDialog
import com.hortus1556.exilum.renderer.Player
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/*
 * One group per console, under Engine with the libraries.
 *
 * Per console and not per library, because GME is not what anybody is
 * listening to - a Game Boy is. The three machines have almost nothing in
 * common past a volume: an NES mixes every voice into a single output and has
 * no stereo of its own to widen, so it gets the voices spread by hand instead;
 * a Game Boy pans its four in hardware; the SNES does its panning, its echo
 * and its sample reading inside one DSP. Offering the same four controls three
 * times would mean offering two of them where they mean nothing.
 *
 * All of it is kept per console natively, so a setting made for one machine is
 * still there when that machine comes round again.
 */

/** The five voices of an NES APU, in the order the emulator reports them. */
private val nesVoices = persistentListOf(
    R.string.nes_voice_square_1,
    R.string.nes_voice_square_2,
    R.string.nes_voice_triangle,
    R.string.nes_voice_noise,
    R.string.nes_voice_dmc,
)

/**
 * And the voices an NSF's expansion chip adds after them.
 *
 * Which chip it is, and so what its voices are called, is the file's own
 * business - a VRC6 brings three, a Namco 163 eight - so here, where no file
 * is being played, they are numbered. The playing file's own overrides name
 * them as the chip itself does.
 */
private val nesExpansionVoices =
    List(Player.GME_NES_EXPANSION_VOICES) { it + Player.GME_NES_VOICES }

// Sinc first, as the default; then the hardware's own table; then none.
private val snesInterpOptions = persistentListOf(
    PreferenceItem(
        key = Player.GME_INTERP_SINC.toString(),
        title = R.string.pref_snes_interp_sinc,
        description = R.string.pref_snes_interp_sinc_desc
    ),
    PreferenceItem(
        key = Player.GME_INTERP_GAUSSIAN.toString(),
        title = R.string.pref_snes_interp_gaussian,
        description = R.string.pref_snes_interp_gaussian_desc
    ),
    PreferenceItem(
        key = Player.GME_INTERP_NONE.toString(),
        title = R.string.pref_snes_interp_none,
        description = R.string.pref_snes_interp_none_desc
    ),
)

@Composable
private fun consolePrefs(): AppPreferences = if (LocalView.current.isInEditMode) {
    AppPreferences(LocalContext.current)
} else {
    koinInject()
}

/**
 * A collapsible console group: the header row, and whatever it folds away.
 *
 * Shared by the three, because the scaffolding is the whole of what they have
 * in common - what goes inside is different for every machine.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColumnScope.ConsoleGroup(
    title: Int,
    subtitle: Int,
    label: String,
    colors: ListItemColors,
    rows: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = label
    )

    SettingsMenuLink(
        title = { Text(text = stringResource(title)) },
        subtitle = { Text(text = stringResource(subtitle)) },
        action = {
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.rotate(chevronRotation)
            )
        },
        colors = colors,
        shapes = groupHeaderShapes(expanded, rows),
        onClick = { expanded = !expanded }
    )

    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column(verticalArrangement = settingsRowSpacing(), content = content)
    }
}

/** Reads as L 40, C, R 40 - which side, and how far. */
internal fun panLabel(pan: Int): String = when {
    pan < 0 -> "L ${-pan}"
    pan > 0 -> "R $pan"
    else -> "C"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ColumnScope.NesGroup(colors: ListItemColors) {
    val scope = rememberCoroutineScope()
    val prefs = consolePrefs()

    ConsoleGroup(
        title = R.string.pref_nes_row,
        subtitle = R.string.pref_nes_row_desc,
        label = "nes_chevron",
        colors = colors,
        rows = nesVoices.size + nesExpansionVoices.size,
    ) {
        val rows = nesVoices.size + nesExpansionVoices.size

        // One slider per voice, centred by default, because an NES has no
        // panning of its own to adjust: every voice arrives in the middle and
        // this is the only thing that will ever move it.
        nesVoices.forEachIndexed { voice, name ->
            // Its flow held on to. Asked for again each time this list was
            // gone through - every time a slider in it wrote its value - it
            // was a new flow, and all thirteen were let go and picked up
            // again ten times a second under a finger.
            val pan by remember(prefs, voice) { prefs.getGmePanFlow(Player.GME_NES, voice) }
                .collectAsStateWithLifecycle(initialValue = Player.DEFAULT_GME_PAN)
            // Explained once, on the first of the five, rather than five times
            // over. Typed out rather than written inline, so the lambda is a
            // lambda and not the branch of an if.
            val hint: (@Composable () -> Unit)? = if (voice == 0) {
                { Text(text = stringResource(R.string.pref_nes_pan_desc)) }
            } else {
                null
            }
            SettingsSlider(
                title = { Text(text = stringResource(name)) },
                subtitle = hint,
                action = { Text(text = panLabel(pan)) },
                colors = colors,
                // Drawn out from the middle, with the sides named under the
                // ends. Free to move, and pulled onto the middle and the
                // halfway points - the values a finger would otherwise have to
                // be lucky to hit.
                steps = 0,
                centered = true,
                startLabel = stringResource(R.string.pan_left_short),
                endLabel = stringResource(R.string.pan_right_short),
                valueRange = -100f..100f,
                value = pan.toFloat(),
                onValueChange = {
                    scope.launch { prefs.setGmePan(Player.GME_NES, voice, it.toInt()) }
                },
                shapes = groupRowShapes(voice, rows),
            )
        }

        // And the expansion chip's, numbered: see nesExpansionVoices.
        nesExpansionVoices.forEachIndexed { index, voice ->
            val pan by remember(prefs, voice) { prefs.getGmePanFlow(Player.GME_NES, voice) }
                .collectAsStateWithLifecycle(initialValue = Player.DEFAULT_GME_PAN)
            val hint: (@Composable () -> Unit)? = if (index == 0) {
                { Text(text = stringResource(R.string.pref_nes_expansion_pan_desc)) }
            } else {
                null
            }
            SettingsSlider(
                title = { Text(text = stringResource(R.string.nes_voice_expansion, index + 1)) },
                subtitle = hint,
                action = { Text(text = panLabel(pan)) },
                colors = colors,
                steps = 0,
                centered = true,
                startLabel = stringResource(R.string.pan_left_short),
                endLabel = stringResource(R.string.pan_right_short),
                valueRange = -100f..100f,
                value = pan.toFloat(),
                onValueChange = {
                    scope.launch { prefs.setGmePan(Player.GME_NES, voice, it.toInt()) }
                },
                shapes = groupRowShapes(voice, rows),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ColumnScope.GameBoyGroup(colors: ListItemColors) {
    val scope = rememberCoroutineScope()
    val prefs = consolePrefs()
    val stereo by prefs.getGmeStereoFlow(Player.GME_GAME_BOY)
        .collectAsStateWithLifecycle(initialValue = Player.DEFAULT_GME_STEREO)

    ConsoleGroup(
        title = R.string.pref_gb_row,
        subtitle = R.string.pref_gb_row_desc,
        label = "gameboy_chevron",
        colors = colors,
        rows = 1,
    ) {
        SettingsSlider(
            title = { Text(text = stringResource(R.string.pref_console_stereo)) },
            subtitle = { Text(text = stringResource(R.string.pref_gb_stereo_desc)) },
            action = { Text(text = stringResource(R.string.value_percent, stereo)) },
            colors = colors,
            steps = 0,
            valueRange = 0f..200f,
            value = stereo.toFloat(),
            onValueChange = { scope.launch { prefs.setGmeStereo(Player.GME_GAME_BOY, it.toInt()) } },
            shapes = groupRowShapes(0, 1),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ColumnScope.SnesGroup(colors: ListItemColors) {
    val scope = rememberCoroutineScope()
    val prefs = consolePrefs()
    val echo by prefs.getGmeEchoFlow()
        .collectAsStateWithLifecycle(initialValue = Player.DEFAULT_GME_ECHO)
    val interp by prefs.getGmeInterpolationFlow()
        .collectAsStateWithLifecycle(initialValue = Player.DEFAULT_GME_INTERPOLATION)

    var isInterpShowing by remember { mutableStateOf(false) }
    if (isInterpShowing) {
        SingleChoiceAlertDialog(
            selectedItemKey = interp.toString(),
            items = snesInterpOptions,
            onItemSelected = { key ->
                key?.toIntOrNull()?.let { scope.launch { prefs.setGmeInterpolation(it) } }
                isInterpShowing = false
            },
        )
    }

    ConsoleGroup(
        title = R.string.pref_snes_row,
        subtitle = R.string.pref_snes_row_desc,
        label = "snes_chevron",
        colors = colors,
        rows = 2,
    ) {
        SettingsSwitch(
            state = echo,
            title = { Text(text = stringResource(R.string.pref_snes_echo)) },
            subtitle = { Text(text = stringResource(R.string.pref_snes_echo_desc)) },
            colors = colors,
            shapes = groupRowShapes(0, 2),
            onCheckedChange = { scope.launch { prefs.setGmeEcho(it) } },
        )
        SettingsMenuLink(
            title = { Text(text = stringResource(R.string.pref_snes_interp)) },
            subtitle = { Text(text = stringResource(R.string.pref_snes_interp_desc)) },
            value = {
                val text = when (interp) {
                    Player.GME_INTERP_NONE -> stringResource(R.string.pref_snes_interp_none)
                    Player.GME_INTERP_GAUSSIAN -> stringResource(R.string.pref_snes_interp_gaussian)
                    else -> stringResource(R.string.pref_snes_interp_sinc)
                }
                Text(text = text)
            },
            colors = colors,
            shapes = groupRowShapes(1, 2),
            onClick = { isInterpShowing = true }
        )
    }
}
