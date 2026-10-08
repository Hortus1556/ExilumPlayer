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
import androidx.compose.material.icons.filled.RestartAlt
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
import com.hortus1556.exilum.db.ChorusSettings
import com.hortus1556.exilum.db.EqualizerSettings
import com.hortus1556.exilum.db.ReverbSettings
import com.hortus1556.exilum.db.SurroundSettings
import com.hortus1556.exilum.renderer.Player
import com.hortus1556.exilum.ui.screens.preferences.components.PreferenceItem
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsMenuLink
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsSlider
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsSwitch
import com.hortus1556.exilum.ui.screens.preferences.components.SingleChoiceAlertDialog
import com.hortus1556.exilum.ui.screens.preferences.components.groupHeaderShapes
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsBandSliders
import com.hortus1556.exilum.ui.screens.preferences.components.groupRowShapes
import com.hortus1556.exilum.ui.screens.preferences.components.settingsRowSpacing
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/** The reverb's rooms, smallest first, in the order the native side numbers them. */
internal val reverbTypeOptions = persistentListOf(
    PreferenceItem(Player.REVERB_SMALL_ROOM.toString(), R.string.fx_reverb_small_room, R.string.fx_reverb_small_room_desc),
    PreferenceItem(Player.REVERB_MEDIUM_ROOM.toString(), R.string.fx_reverb_medium_room, R.string.fx_reverb_medium_room_desc),
    PreferenceItem(Player.REVERB_LARGE_ROOM.toString(), R.string.fx_reverb_large_room, R.string.fx_reverb_large_room_desc),
    PreferenceItem(Player.REVERB_HALL.toString(), R.string.fx_reverb_hall, R.string.fx_reverb_hall_desc),
    PreferenceItem(Player.REVERB_CONCERT_HALL.toString(), R.string.fx_reverb_concert_hall, R.string.fx_reverb_concert_hall_desc),
    PreferenceItem(Player.REVERB_ARENA.toString(), R.string.fx_reverb_arena, R.string.fx_reverb_arena_desc),
    PreferenceItem(Player.REVERB_PLATE.toString(), R.string.fx_reverb_plate, R.string.fx_reverb_plate_desc),
    PreferenceItem(Player.REVERB_BATHROOM.toString(), R.string.fx_reverb_bathroom, R.string.fx_reverb_bathroom_desc),
    PreferenceItem(Player.REVERB_CAVE.toString(), R.string.fx_reverb_cave, R.string.fx_reverb_cave_desc),
)

/** The equalizer's bands, by the frequency each is centred on. */
private val eqBandNames = persistentListOf(
    R.string.eq_band_31hz, R.string.eq_band_63hz, R.string.eq_band_125hz, R.string.eq_band_250hz,
    R.string.eq_band_500hz, R.string.eq_band_1khz, R.string.eq_band_2khz, R.string.eq_band_4khz,
    R.string.eq_band_8khz, R.string.eq_band_16khz,
)

@Composable
private fun effectPrefs(): AppPreferences = if (LocalView.current.isInEditMode) {
    AppPreferences(LocalContext.current)
} else {
    koinInject<AppPreferences>()
}

/** A gain as the equalizer's rows show it: signed, with a real minus sign. */
private fun dbLabel(db: Int): String = when {
    db > 0 -> "+$db"
    db < 0 -> "−${-db}"
    else -> "0"
}

/**
 * The frame every effect's group has: a header row that opens and closes it,
 * saying what the effect is doing, and its [rows] rows under it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColumnScope.EffectGroup(
    title: String,
    summary: String,
    rows: Int,
    colors: ListItemColors,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "effect_chevron"
    )
    SettingsMenuLink(
        title = { Text(text = title) },
        subtitle = { Text(text = summary) },
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
        Column(verticalArrangement = settingsRowSpacing()) { content() }
    }
}

/** A dry-to-wet slider, the same in every effect. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MixSlider(
    value: Int,
    description: Int,
    enabled: Boolean,
    colors: ListItemColors,
    index: Int,
    rows: Int,
    onChange: (Int) -> Unit,
) {
    SettingsSlider(
        title = { Text(text = stringResource(R.string.pref_fx_mix)) },
        subtitle = { Text(text = stringResource(description)) },
        action = { Text(text = stringResource(R.string.value_percent, value)) },
        colors = colors,
        enabled = enabled,
        steps = 0,
        valueRange = 0f..100f,
        value = value.toFloat(),
        startLabel = stringResource(R.string.fx_dry),
        endLabel = stringResource(R.string.fx_wet),
        onValueChange = { onChange(it.roundToInt()) },
        shapes = groupRowShapes(index, rows),
    )
}

private const val REVERB_ROWS = 3

/**
 * The reverb: a room around the music, whatever engine plays it - see
 * Player.setReverb. On, the room it is, and how much of it is heard.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ColumnScope.ReverbGroup(colors: ListItemColors) {
    val scope = rememberCoroutineScope()
    val prefs = effectPrefs()
    // The flow held on to, here and in the three groups after: this function
    // reads what it gives, so it ran again with every value a slider wrote,
    // and asking for the flow again each time was a new one to collect.
    val reverb by remember(prefs) { prefs.getReverbFlow() }
        .collectAsStateWithLifecycle(initialValue = ReverbSettings())

    var isTypeShowing by remember { mutableStateOf(false) }
    if (isTypeShowing) {
        SingleChoiceAlertDialog(
            selectedItemKey = reverb.type.toString(),
            items = reverbTypeOptions,
            onItemSelected = { key ->
                key?.toIntOrNull()?.let { scope.launch { prefs.setReverbType(it) } }
                isTypeShowing = false
            },
        )
    }
    val typeName = stringResource(
        (reverbTypeOptions.firstOrNull { it.key == reverb.type.toString() } ?: reverbTypeOptions[Player.DEFAULT_REVERB_TYPE]).title
    )
    val summary = if (reverb.enabled) {
        stringResource(R.string.pref_fx_reverb_summary, typeName, reverb.mix)
    } else {
        stringResource(R.string.pref_fx_off)
    }

    EffectGroup(stringResource(R.string.pref_fx_reverb_row), summary, REVERB_ROWS, colors) {
        SettingsSwitch(
            state = reverb.enabled,
            title = { Text(text = stringResource(R.string.pref_fx_reverb)) },
            subtitle = { Text(text = stringResource(R.string.pref_fx_reverb_desc)) },
            colors = colors,
            shapes = groupRowShapes(0, REVERB_ROWS),
            onCheckedChange = { scope.launch { prefs.setReverbOn(it) } },
        )
        SettingsMenuLink(
            title = { Text(text = stringResource(R.string.pref_fx_reverb_type)) },
            value = { Text(text = typeName) },
            enabled = reverb.enabled,
            colors = colors,
            shapes = groupRowShapes(1, REVERB_ROWS),
            onClick = { isTypeShowing = true },
        )
        MixSlider(reverb.mix, R.string.pref_fx_reverb_mix_desc, reverb.enabled, colors, 2, REVERB_ROWS) {
            scope.launch { prefs.setReverbMix(it) }
        }
    }
}

private const val CHORUS_ROWS = 4

/**
 * The chorus: the music doubled with a copy whose delay sways - see
 * Player.setChorus. On, how fast it sways, how far apart its two sides
 * sway, and how much of it is heard.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ColumnScope.ChorusGroup(colors: ListItemColors) {
    val scope = rememberCoroutineScope()
    val prefs = effectPrefs()
    val chorus by remember(prefs) { prefs.getChorusFlow() }
        .collectAsStateWithLifecycle(initialValue = ChorusSettings())
    val summary = if (chorus.enabled) {
        stringResource(R.string.pref_fx_chorus_summary, chorus.rate / 10f, chorus.mix)
    } else {
        stringResource(R.string.pref_fx_off)
    }

    EffectGroup(stringResource(R.string.pref_fx_chorus_row), summary, CHORUS_ROWS, colors) {
        SettingsSwitch(
            state = chorus.enabled,
            title = { Text(text = stringResource(R.string.pref_fx_chorus)) },
            subtitle = { Text(text = stringResource(R.string.pref_fx_chorus_desc)) },
            colors = colors,
            shapes = groupRowShapes(0, CHORUS_ROWS),
            onCheckedChange = { scope.launch { prefs.setChorusOn(it) } },
        )
        SettingsSlider(
            title = { Text(text = stringResource(R.string.pref_fx_chorus_rate)) },
            subtitle = { Text(text = stringResource(R.string.pref_fx_chorus_rate_desc)) },
            action = { Text(text = stringResource(R.string.value_hz_fraction, chorus.rate / 10f)) },
            colors = colors,
            enabled = chorus.enabled,
            steps = 0,
            valueRange = Player.CHORUS_RATE_MIN.toFloat()..Player.CHORUS_RATE_MAX.toFloat(),
            value = chorus.rate.toFloat(),
            snapPoints = listOf(
                Player.CHORUS_RATE_MIN.toFloat(), Player.DEFAULT_CHORUS_RATE.toFloat(), Player.CHORUS_RATE_MAX.toFloat(),
            ),
            startLabel = stringResource(R.string.fx_slow),
            endLabel = stringResource(R.string.fx_fast),
            onValueChange = { scope.launch { prefs.setChorusRate(it.roundToInt()) } },
            shapes = groupRowShapes(1, CHORUS_ROWS),
        )
        SettingsSlider(
            title = { Text(text = stringResource(R.string.pref_fx_chorus_spread)) },
            subtitle = { Text(text = stringResource(R.string.pref_fx_chorus_spread_desc)) },
            action = { Text(text = stringResource(R.string.value_percent, chorus.spread)) },
            colors = colors,
            enabled = chorus.enabled,
            steps = 0,
            valueRange = 0f..100f,
            value = chorus.spread.toFloat(),
            startLabel = stringResource(R.string.fx_narrow),
            endLabel = stringResource(R.string.fx_wide),
            onValueChange = { scope.launch { prefs.setChorusSpread(it.roundToInt()) } },
            shapes = groupRowShapes(2, CHORUS_ROWS),
        )
        MixSlider(chorus.mix, R.string.pref_fx_chorus_mix_desc, chorus.enabled, colors, 3, CHORUS_ROWS) {
            scope.launch { prefs.setChorusMix(it) }
        }
    }
}

private const val SURROUND_ROWS = 2

/** The virtual surround: ModPlug Player's, around everything played - see Player.setSurround. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ColumnScope.SurroundGroup(colors: ListItemColors) {
    val scope = rememberCoroutineScope()
    val prefs = effectPrefs()
    val surround by remember(prefs) { prefs.getSurroundFlow() }
        .collectAsStateWithLifecycle(initialValue = SurroundSettings())
    val summary = if (surround.enabled) {
        stringResource(R.string.value_percent, surround.mix)
    } else {
        stringResource(R.string.pref_fx_off)
    }

    EffectGroup(stringResource(R.string.pref_fx_surround_row), summary, SURROUND_ROWS, colors) {
        SettingsSwitch(
            state = surround.enabled,
            title = { Text(text = stringResource(R.string.pref_fx_surround)) },
            subtitle = { Text(text = stringResource(R.string.pref_fx_surround_desc)) },
            colors = colors,
            shapes = groupRowShapes(0, SURROUND_ROWS),
            onCheckedChange = { scope.launch { prefs.setSurroundOn(it) } },
        )
        MixSlider(surround.mix, R.string.pref_fx_surround_mix_desc, surround.enabled, colors, 1, SURROUND_ROWS) {
            scope.launch { prefs.setSurroundMix(it) }
        }
    }
}

/** The switch, a slider for each band, and a way back to flat. */
private const val EQ_ROWS = 3

/** The bands as they are written under their sliders: the frequency alone, short enough for ten abreast. */
private val eqBandLabels = persistentListOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

/** A band's place on its slider as decibels: the whole number it is kept as. */
private fun bandReadout(db: Float): String = dbLabel(db.roundToInt())

/**
 * The ten-band equalizer - see Player.setEqualizer. On, each band up or
 * down from where it is on a slider of its own, the ten on end side by side
 * as an equalizer's are, and all of them back to the middle at once.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ColumnScope.EqualizerGroup(colors: ListItemColors) {
    val scope = rememberCoroutineScope()
    val prefs = effectPrefs()
    val eq by remember(prefs) { prefs.getEqualizerFlow() }
        .collectAsStateWithLifecycle(initialValue = EqualizerSettings())
    val flat = eq.bands.all { it == 0 }
    val summary = stringResource(
        when {
            !eq.enabled -> R.string.pref_fx_off
            flat -> R.string.pref_fx_flat
            else -> R.string.pref_fx_on
        }
    )

    EffectGroup(stringResource(R.string.pref_fx_eq_row), summary, EQ_ROWS, colors) {
        SettingsSwitch(
            state = eq.enabled,
            title = { Text(text = stringResource(R.string.pref_fx_eq)) },
            subtitle = { Text(text = stringResource(R.string.pref_fx_eq_desc)) },
            colors = colors,
            shapes = groupRowShapes(0, EQ_ROWS),
            onCheckedChange = { scope.launch { prefs.setEqOn(it) } },
        )
        SettingsBandSliders(
            title = { Text(text = stringResource(R.string.pref_fx_eq_bands)) },
            subtitle = { Text(text = stringResource(R.string.pref_fx_eq_band_desc)) },
            values = List(Player.EQ_BANDS) { band -> eq.bands.getOrElse(band) { 0 }.toFloat() },
            labels = eqBandLabels,
            names = eqBandNames.map { stringResource(it) },
            readout = ::bandReadout,
            enabled = eq.enabled,
            centered = true,
            valueRange = -Player.EQ_MAX_DB.toFloat()..Player.EQ_MAX_DB.toFloat(),
            onValueChange = { band, db -> scope.launch { prefs.setEqBand(band, db.roundToInt()) } },
            shapes = groupRowShapes(1, EQ_ROWS),
        )
        SettingsMenuLink(
            title = { Text(text = stringResource(R.string.pref_fx_eq_reset)) },
            action = { Icon(imageVector = Icons.Default.RestartAlt, contentDescription = null) },
            enabled = eq.enabled && !flat,
            colors = colors,
            shapes = groupRowShapes(EQ_ROWS - 1, EQ_ROWS),
            onClick = { scope.launch { prefs.resetEqBands() } },
        )
    }
}
