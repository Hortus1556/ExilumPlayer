package com.hortus1556.exilum.ui.screens.player.components

import com.hortus1556.exilum.ui.screens.preferences.section.twoSfInterpOptions
import com.hortus1556.exilum.ui.screens.preferences.section.gsfChannelNames
import com.hortus1556.exilum.ui.screens.preferences.section.reverbTypeOptions
import com.hortus1556.exilum.ui.liquid.rememberLiquidRowLight
import com.hortus1556.exilum.ui.liquid.LocalLiquidOverlayHost
import com.hortus1556.exilum.ui.liquid.LiquidBottomSheet
import androidx.compose.runtime.Composable
import com.hortus1556.exilum.ui.liquid.LiquidMenu
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.hortus1556.exilum.ui.liquid.LiquidType
import com.hortus1556.exilum.ui.liquid.LiquidMenuSeparator
import com.hortus1556.exilum.ui.liquid.LiquidMenuPane
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import com.hortus1556.exilum.ui.liquid.OwnWindowAnimation
import androidx.compose.material3.BottomSheetDefaults
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.liquid.sheet
import com.hortus1556.exilum.ui.liquid.LocalLiquidColors
import com.hortus1556.exilum.ui.liquid.LiquidSheetShape
import com.hortus1556.exilum.ui.liquid.LiquidSheetGrabber
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOn
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hortus1556.exilum.R
import com.hortus1556.exilum.data.FileOverrides
import com.hortus1556.exilum.data.LoopPreset
import com.hortus1556.exilum.data.OverrideKeys
import com.hortus1556.exilum.data.TimeDisplay
import com.hortus1556.exilum.data.defaultSubsongLabel
import com.hortus1556.exilum.data.loopPresetCode
import com.hortus1556.exilum.data.subsongKey
import com.hortus1556.exilum.db.AppPreferences
import com.hortus1556.exilum.player.PlayerUiState
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsSlider
import com.hortus1556.exilum.renderer.Player
import com.hortus1556.exilum.renderer.ResamplerMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject

/** The library a file is played by, which decides the settings it can override. */
internal enum class OverrideCore { OPENMPT, HIVELY, ADPLUG, GME, VGM, GSF, TWOSF, USF, PSF, DSP, AUDIO, NONE }

/** From ModVars.renderingEngine, which every decoder fills with its own name. */
internal fun overrideCoreOf(renderingEngine: String): OverrideCore = when {
    renderingEngine.startsWith("libopenmpt") -> OverrideCore.OPENMPT
    renderingEngine.startsWith("Hively") -> OverrideCore.HIVELY
    renderingEngine.startsWith("AdPlug") -> OverrideCore.ADPLUG
    renderingEngine.startsWith("Game Music Emu") -> OverrideCore.GME
    renderingEngine.startsWith("VGM") -> OverrideCore.VGM
    renderingEngine.startsWith("mGBA") -> OverrideCore.GSF
    renderingEngine.startsWith("vio2sf") -> OverrideCore.TWOSF
    renderingEngine.startsWith("lazyusf2") -> OverrideCore.USF
    renderingEngine.startsWith("aopsf") -> OverrideCore.PSF
    renderingEngine.startsWith("GameCube streams") -> OverrideCore.DSP
    renderingEngine.startsWith("FFmpeg") -> OverrideCore.AUDIO
    else -> OverrideCore.NONE
}

/** The GME console behind a file, by its extension - the three that have settings of their own. */
internal fun gmeConsoleOf(extension: String): Int = when (extension.lowercase()) {
    "nsf", "nsfe" -> Player.GME_NES
    "gbs" -> Player.GME_GAME_BOY
    "spc" -> Player.GME_SNES
    else -> -1
}

/** How far each nudge button moves a loop point, in milliseconds. */
private val LOOP_NUDGES = listOf(-100, -10, 10, 100)

/**
 * Everything that can be set for the playing file alone: a volume boost in
 * place of the master volume together with the settings of the library that
 * plays it, the precision timer, a length for a file that cannot say how long
 * it is, and loop points and their presets. All of it is kept per file - see
 * FileOverrides - and in tracker.conf.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun OverridesSheet(
    state: PlayerUiState,
    /** The file's overrides as they stand - asked for inside the sheet, see below. */
    overrides: () -> FileOverrides,
    names: Map<String, String>,
    positionMs: () -> Int,
    onDismiss: () -> Unit,
    onTimeDisplay: (Int) -> Unit,
    onDuration: (Int?) -> Unit,
    onSetting: (String, Int?) -> Unit,
    onTryLoop: (Int, Int) -> Unit,
    onStopLoop: () -> Unit,
    onSaveLoop: (Int, Int) -> Unit,
    onPlayPreset: (LoopPreset) -> Unit,
    onRenamePreset: (LoopPreset) -> Unit,
    onDeletePreset: (LoopPreset) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = ListItemDefaults.segmentedColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
    val uri = state.currentModule?.filePath.orEmpty()
    val core = overrideCoreOf(state.modVars.renderingEngine)
    val precise = state.timeDisplay
    val prefs = if (LocalView.current.isInEditMode) AppPreferences(LocalContext.current) else koinInject<AppPreferences>()

    // The same content either way: on real glass in the app's own
    // window under Liquid Glass, in Material's sheet otherwise.
    val body: @Composable ColumnScope.() -> Unit = {
        // Read here, in the sheet's own content, and not by whoever shows the
        // sheet: every write a slider makes comes straight back as a new set
        // of these, and it is only this list that has anything to redo.
        val current = overrides()
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.overrides_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = state.modVars.modName.ifEmpty { state.currentModule?.name.orEmpty() },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // How the file sounds: the playing library's own settings for it.
            // A volume boost used to sit above them, in place of the master
            // volume; the sound check decides how loud a file plays now.
            item { SectionTitle(R.string.overrides_section_sound) }
            if (core != OverrideCore.NONE && core != OverrideCore.VGM) {
                item {
                    CoreSettings(
                        core = core,
                        console = gmeConsoleOf(state.currentModule?.fileExtension.orEmpty()),
                        overrides = current,
                        voiceNames = state.modVars.instruments.asList(),
                        onSetting = onSetting,
                    )
                }
            }

            // And what is done to the sound after the library has made it,
            // whichever library that is.
            item { SectionTitle(R.string.overrides_section_effects) }
            item { EffectSettings(overrides = current, onSetting = onSetting) }

            item { SectionTitle(R.string.overrides_section_timer) }
            item {
                PrecisionTimer(mode = state.timeDisplay, onMode = onTimeDisplay)
            }

            if (!state.modVars.durationKnown) {
                item { SectionTitle(R.string.overrides_section_length) }
                item {
                    LengthEditor(
                        length = current.durations[state.currentSequence],
                        fileLength = state.modVars.sequenceDuration(state.currentSequence),
                        timeDisplay = precise,
                        positionMs = positionMs,
                        onLength = onDuration,
                    )
                }
            }

            item { SectionTitle(R.string.overrides_section_loops) }
            item {
                LoopEditor(
                    state = state,
                    timeDisplay = precise,
                    positionMs = positionMs,
                    onTry = onTryLoop,
                    onStop = onStopLoop,
                    onSave = onSaveLoop,
                )
            }
            items(current.loops, key = { it.id }) { preset ->
                val code = loopPresetCode(preset.id)
                LoopPresetRow(
                    name = names[subsongKey(uri, code)] ?: defaultSubsongLabel(code),
                    preset = preset,
                    active = state.loopRegion?.presetId == preset.id,
                    timeDisplay = precise,
                    onPlay = { onPlayPreset(preset) },
                    onRename = { onRenamePreset(preset) },
                    onDelete = { onDeletePreset(preset) },
                )
            }
        }
    }
    val overlays = LocalLiquidOverlayHost.current
    if (overlays != null) {
        LiquidBottomSheet(
            host = overlays,
            onDismissRequest = onDismiss,
            dragToDismiss = true,
            content = body,
        )
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            // iOS: the sheet's own corners and gray, and its grabber.
            dragHandle = if (isLiquid) {
                { LiquidSheetGrabber() }
            } else {
                { BottomSheetDefaults.DragHandle() }
            },
            shape = if (isLiquid) LiquidSheetShape else BottomSheetDefaults.ExpandedShape,
            containerColor = if (isLiquid) LocalLiquidColors.current.sheet else BottomSheetDefaults.ContainerColor,
        ) {
            OwnWindowAnimation()
            body()
        }
    }
}

@Composable
private fun SectionTitle(text: Int) {
    Text(
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        text = stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** The tick box, and which precise unit it switches the elapsed time to. */
@Composable
private fun PrecisionTimer(mode: Int, onMode: (Int) -> Unit) {
    val precise = mode != TimeDisplay.CLOCK
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onMode(if (precise) TimeDisplay.CLOCK else TimeDisplay.MILLISECONDS) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = precise,
                onCheckedChange = { onMode(if (it) TimeDisplay.MILLISECONDS else TimeDisplay.CLOCK) },
            )
            Column {
                Text(text = stringResource(R.string.overrides_timer))
                Text(
                    text = stringResource(R.string.overrides_timer_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (precise) {
            Row(
                modifier = Modifier.padding(start = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = mode == TimeDisplay.MILLISECONDS,
                    onClick = { onMode(TimeDisplay.MILLISECONDS) },
                    label = { Text(text = stringResource(R.string.overrides_timer_ms)) },
                )
                FilterChip(
                    selected = mode == TimeDisplay.BYTES,
                    onClick = { onMode(TimeDisplay.BYTES) },
                    label = { Text(text = stringResource(R.string.overrides_timer_bytes)) },
                )
            }
        }
    }
}

/** A length for a track that has none of its own: typed in seconds, or taken from where playback is. */
@Composable
private fun LengthEditor(
    length: Int?,
    fileLength: Int,
    timeDisplay: Int,
    positionMs: () -> Int,
    onLength: (Int?) -> Unit,
) {
    var seconds by rememberSaveable(length) {
        mutableStateOf(length?.let { "%.3f".format(it / 1000.0) } ?: "")
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(
                R.string.overrides_length_now,
                TimeDisplay.format((length ?: fileLength).toLong(), timeDisplay, precise = true),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.overrides_length_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = seconds,
                onValueChange = { seconds = it },
                singleLine = true,
                label = { Text(text = stringResource(R.string.overrides_length_seconds)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            FilledTonalButton(
                onClick = {
                    seconds.replace(',', '.').toDoubleOrNull()
                        ?.takeIf { it > 0.0 }
                        ?.let { onLength((it * 1000.0).toInt()) }
                },
            ) { Text(text = stringResource(R.string.overrides_apply)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onLength(positionMs().takeIf { it > 0 }) }) {
                Text(text = stringResource(R.string.overrides_length_from_position))
            }
            TextButton(enabled = length != null, onClick = { onLength(null) }) {
                Text(text = stringResource(R.string.overrides_clear))
            }
        }
    }
}

/** The two loop points, set from where playback is and moved by a few milliseconds at a time. */
@Composable
private fun LoopEditor(
    state: PlayerUiState,
    timeDisplay: Int,
    positionMs: () -> Int,
    onTry: (Int, Int) -> Unit,
    onStop: () -> Unit,
    onSave: (Int, Int) -> Unit,
) {
    var startMs by rememberSaveable { mutableIntStateOf(state.loopRegion?.startMs ?: 0) }
    var endMs by rememberSaveable { mutableIntStateOf(state.loopRegion?.endMs ?: 0) }
    val looping = state.loopRegion != null

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.overrides_loops_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LoopPointRow(
            label = "A",
            valueMs = startMs,
            timeDisplay = timeDisplay,
            onSet = { startMs = positionMs() },
            onNudge = { startMs = (startMs + it).coerceAtLeast(0) },
        )
        LoopPointRow(
            label = "B",
            valueMs = endMs,
            timeDisplay = timeDisplay,
            onSet = { endMs = positionMs() },
            onNudge = { endMs = (endMs + it).coerceAtLeast(0) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (looping) {
                OutlinedButton(onClick = onStop) { Text(text = stringResource(R.string.overrides_loop_stop)) }
            }
            FilledTonalButton(enabled = endMs > startMs, onClick = { onTry(startMs, endMs) }) {
                Text(text = stringResource(R.string.overrides_loop_try))
            }
            Button(enabled = endMs > startMs, onClick = { onSave(startMs, endMs) }) {
                Text(text = stringResource(R.string.overrides_loop_save))
            }
        }
    }
}

@Composable
private fun LoopPointRow(
    label: String,
    valueMs: Int,
    timeDisplay: Int,
    onSet: () -> Unit,
    onNudge: (Int) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(24.dp),
            )
            Text(
                modifier = Modifier.weight(1f),
                text = TimeDisplay.format(valueMs.toLong(), timeDisplay, precise = true),
                style = MaterialTheme.typography.bodyLarge,
            )
            FilledTonalButton(onClick = onSet) { Text(text = stringResource(R.string.overrides_loop_set)) }
        }
        Row(
            modifier = Modifier.padding(start = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LOOP_NUDGES.forEach { step ->
                TextButton(onClick = { onNudge(step) }) {
                    Text(text = if (step > 0) "+$step ms" else "$step ms")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoopPresetRow(
    name: String,
    preset: LoopPreset,
    active: Boolean,
    timeDisplay: Int,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val range = TimeDisplay.format(preset.startMs.toLong(), timeDisplay, precise = true) +
        " → " + TimeDisplay.format(preset.endMs.toLong(), timeDisplay, precise = true)
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        onClick = onPlay,
        content = { Text(text = name) },
        supportingContent = {
            Text(
                text = if (preset.subsong >= 0) "$range · ${defaultSubsongLabel(preset.subsong)}" else range
            )
        },
        leadingContent = {
            Icon(
                imageVector = if (active) Icons.Default.RepeatOn else Icons.Default.Repeat,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = onRename) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.desc_rename_subsong))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.overrides_loop_delete))
                }
            }
        },
    )
}

/**
 * The playing library's settings, each following the settings screen until it
 * is given a value of its own.
 *
 * Not its volume: Hively's and AdPlug's volume and a console's master volume
 * are what the boost above already does, and two controls for one thing only
 * leave a question of which to use.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CoreSettings(
    core: OverrideCore,
    console: Int,
    overrides: FileOverrides,
    /** What the playing file calls its voices, where it says - see nesVoiceLabel. */
    voiceNames: List<String>,
    onSetting: (String, Int?) -> Unit,
) {
    val prefs = if (LocalView.current.isInEditMode) AppPreferences(LocalContext.current) else koinInject<AppPreferences>()
    val own = overrides.settings

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (core) {
            OverrideCore.OPENMPT -> {
                OverrideSlider(
                    title = R.string.pref_stereo_separation, key = OverrideKeys.OPENMPT_STEREO,
                    global = prefs.getStereoSeparationFlow(), default = Player.DEFAULT_STEREO_SEPARATION,
                    own = own, range = 0..200, unit = "%", onSetting = onSetting,
                )
                OverrideChoice(
                    title = R.string.pref_interpolation_type, key = OverrideKeys.OPENMPT_INTERPOLATION,
                    global = prefs.getInterpolationTypeFlow().map { ResamplerMode.fromId(it).id },
                    default = Player.DEFAULT_INTERPOLATION,
                    own = own, onSetting = onSetting,
                    options = listOf(
                        ResamplerMode.SINC.id to R.string.interp_sinc,
                        ResamplerMode.CUBIC.id to R.string.interp_spline,
                        ResamplerMode.AMIGA_A500.id to R.string.interp_openmpt_amiga_a500,
                        ResamplerMode.AMIGA_A1200.id to R.string.interp_openmpt_amiga_a1200,
                        ResamplerMode.OFF.id to R.string.interp_off,
                    ),
                )
            }

            OverrideCore.HIVELY -> {
                OverrideSlider(
                    title = R.string.pref_hively_stereo, key = OverrideKeys.HVL_STEREO,
                    global = prefs.getHivelyStereoFlow(), default = Player.DEFAULT_HIVELY_STEREO,
                    own = own, range = 0..200, unit = "%", onSetting = onSetting,
                )
            }

            OverrideCore.GSF -> {
                OverrideSlider(
                    title = R.string.pref_gsf_stereo, key = OverrideKeys.GSF_STEREO,
                    global = prefs.getGsfStereoFlow(), default = Player.DEFAULT_GSF_STEREO,
                    own = own, range = 0..200, unit = "%", onSetting = onSetting,
                )
                OverrideChoice(
                    title = R.string.pref_gsf_lowpass, key = OverrideKeys.GSF_LOWPASS,
                    global = prefs.getGsfLowpassFlow().map { if (it) 1 else 0 },
                    default = if (Player.DEFAULT_GSF_LOWPASS) 1 else 0,
                    own = own, onSetting = onSetting,
                    options = listOf(1 to R.string.overrides_on, 0 to R.string.overrides_off),
                )
                gsfChannelNames.forEachIndexed { channel, name ->
                    OverrideSlider(
                        title = name, key = OverrideKeys.gsfPan(channel),
                        global = prefs.getGsfPanFlow(channel), default = Player.DEFAULT_GSF_PAN,
                        own = own, range = -100..100, unit = "", onSetting = onSetting, pan = true,
                    )
                }
            }

            OverrideCore.TWOSF -> {
                OverrideChoice(
                    title = R.string.pref_twosf_interp, key = OverrideKeys.TWOSF_INTERPOLATION,
                    global = prefs.getTwoSfInterpolationFlow(), default = Player.DEFAULT_TWOSF_INTERPOLATION,
                    own = own, onSetting = onSetting,
                    options = twoSfInterpOptions.map { it.key.toInt() to it.title },
                )
                OverrideSlider(
                    title = R.string.pref_gsf_stereo, key = OverrideKeys.TWOSF_STEREO,
                    global = prefs.getTwoSfStereoFlow(), default = Player.DEFAULT_TWOSF_STEREO,
                    own = own, range = 0..200, unit = "%", onSetting = onSetting,
                )
                for (channel in 0 until Player.TWOSF_CHANNELS) {
                    OverrideSlider(
                        titleText = stringResource(R.string.twosf_channel, channel + 1),
                        key = OverrideKeys.twoSfPan(channel),
                        global = prefs.getTwoSfPanFlow(channel), default = Player.DEFAULT_TWOSF_PAN,
                        own = own, range = -100..100, unit = "", onSetting = onSetting, pan = true,
                    )
                }
            }

            OverrideCore.USF -> {
                OverrideChoice(
                    title = R.string.pref_usf_hle, key = OverrideKeys.USF_HLE,
                    global = prefs.getUsfHleFlow().map { if (it) 1 else 0 },
                    default = if (Player.DEFAULT_USF_HLE) 1 else 0,
                    own = own, onSetting = onSetting,
                    options = listOf(1 to R.string.overrides_on, 0 to R.string.overrides_off),
                )
                OverrideSlider(
                    title = R.string.pref_gsf_stereo, key = OverrideKeys.USF_STEREO,
                    global = prefs.getUsfStereoFlow(), default = Player.DEFAULT_USF_STEREO,
                    own = own, range = 0..200, unit = "%", onSetting = onSetting,
                )
            }

            OverrideCore.PSF -> {
                OverrideSlider(
                    title = R.string.pref_gsf_stereo, key = OverrideKeys.PSF_STEREO,
                    global = prefs.getPsfStereoFlow(), default = Player.DEFAULT_PSF_STEREO,
                    own = own, range = 0..200, unit = "%", onSetting = onSetting,
                )
                OverrideChoice(
                    title = R.string.pref_psf_reverb, key = OverrideKeys.PSF_REVERB,
                    global = prefs.getPsfReverbFlow().map { if (it) 1 else 0 },
                    default = if (Player.DEFAULT_PSF_REVERB) 1 else 0,
                    own = own, onSetting = onSetting,
                    options = listOf(1 to R.string.overrides_on, 0 to R.string.overrides_off),
                )
            }

            OverrideCore.DSP -> {
                OverrideSlider(
                    title = R.string.pref_gsf_stereo, key = OverrideKeys.DSP_STEREO,
                    global = prefs.getDspStereoFlow(), default = Player.DEFAULT_DSP_STEREO,
                    own = own, range = 0..200, unit = "%", onSetting = onSetting,
                )
            }

            OverrideCore.AUDIO -> {
                OverrideSlider(
                    title = R.string.pref_gsf_stereo, key = OverrideKeys.AUDIO_STEREO,
                    global = prefs.getAudioStereoFlow(), default = Player.DEFAULT_AUDIO_STEREO,
                    own = own, range = 0..200, unit = "%", onSetting = onSetting,
                )
            }

            OverrideCore.ADPLUG -> {
                OverrideChoice(
                    title = R.string.pref_adplug_core, key = OverrideKeys.ADPLUG_CORE,
                    global = prefs.getAdPlugCoreFlow(), default = Player.DEFAULT_ADPLUG_CORE,
                    own = own, onSetting = onSetting,
                    options = listOf(
                        Player.ADPLUG_CORE_NUKED to R.string.pref_adplug_core_nuked,
                        Player.ADPLUG_CORE_DOSBOX to R.string.pref_adplug_core_dosbox,
                        Player.ADPLUG_CORE_MAME to R.string.pref_adplug_core_mame,
                        Player.ADPLUG_CORE_KEN to R.string.pref_adplug_core_ken,
                    ),
                )
            }

            OverrideCore.GME -> if (console >= 0) {
                if (console == Player.GME_NES) {
                    // Every voice this file has, named as its own chip names
                    // them: the APU's five, and after them whatever the
                    // expansion chip brought - three for a VRC6, eight for a
                    // Namco 163. The settings screen, with no file in front
                    // of it, can only number those.
                    for (voice in 0 until nesVoiceCount(voiceNames)) {
                        OverrideSlider(
                            titleText = nesVoiceLabel(voiceNames, voice),
                            key = OverrideKeys.gmePan(console, voice),
                            global = prefs.getGmePanFlow(console, voice), default = Player.DEFAULT_GME_PAN,
                            own = own, range = -100..100, unit = "", onSetting = onSetting, pan = true,
                        )
                    }
                } else if (console == Player.GME_GAME_BOY) {
                    OverrideSlider(
                        title = R.string.pref_console_stereo, key = OverrideKeys.gmeStereo(console),
                        global = prefs.getGmeStereoFlow(console), default = Player.DEFAULT_GME_STEREO,
                        own = own, range = 0..200, unit = "%", onSetting = onSetting,
                    )
                }
                if (console == Player.GME_SNES) {
                    OverrideChoice(
                        title = R.string.pref_snes_echo, key = OverrideKeys.GME_SNES_ECHO,
                        global = prefs.getGmeEchoFlow().map { if (it) 1 else 0 },
                        default = if (Player.DEFAULT_GME_ECHO) 1 else 0,
                        own = own, onSetting = onSetting,
                        options = listOf(1 to R.string.overrides_on, 0 to R.string.overrides_off),
                    )
                    OverrideChoice(
                        title = R.string.pref_snes_interp, key = OverrideKeys.GME_SNES_INTERPOLATION,
                        global = prefs.getGmeInterpolationFlow(), default = Player.DEFAULT_GME_INTERPOLATION,
                        own = own, onSetting = onSetting,
                        options = listOf(
                            Player.GME_INTERP_SINC to R.string.pref_snes_interp_sinc,
                            Player.GME_INTERP_GAUSSIAN to R.string.pref_snes_interp_gaussian,
                            Player.GME_INTERP_NONE to R.string.pref_snes_interp_none,
                        ),
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.overrides_core_none),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            else -> Unit
        }
    }
}

/**
 * How many voices the playing NSF has: the APU's five, plus whatever its
 * expansion chip added, up to what the mixer can keep apart.
 */
private fun nesVoiceCount(voiceNames: List<String>): Int {
    return voiceNames.size.coerceIn(
        Player.GME_NES_VOICES,
        Player.GME_NES_VOICES + Player.GME_NES_EXPANSION_VOICES,
    )
}

/**
 * What to call voice [voice] of the playing NSF. The decoder hands the
 * emulator's own voice names over in the instrument list - "Square 1",
 * "Noise", "Namco 1" - so an expansion chip's voices are named by the chip
 * that brought them.
 */
@Composable
private fun nesVoiceLabel(voiceNames: List<String>, voice: Int): String {
    val own = voiceNames.getOrNull(voice).orEmpty()
    if (own.isNotBlank()) return own
    return when (voice) {
        0 -> stringResource(R.string.nes_voice_square_1)
        1 -> stringResource(R.string.nes_voice_square_2)
        2 -> stringResource(R.string.nes_voice_triangle)
        3 -> stringResource(R.string.nes_voice_noise)
        4 -> stringResource(R.string.nes_voice_dmc)
        else -> stringResource(R.string.nes_voice_expansion, voice - Player.GME_NES_VOICES + 1)
    }
}

/**
 * The output effects for this file alone: the reverb, the chorus and the
 * virtual surround, each on or off and set as the settings screen sets them.
 * Not the equalizer - that is for the ears and the speakers, not for a tune.
 */
@Composable
private fun EffectSettings(
    overrides: FileOverrides,
    onSetting: (String, Int?) -> Unit,
) {
    val prefs = if (LocalView.current.isInEditMode) AppPreferences(LocalContext.current) else koinInject<AppPreferences>()
    val own = overrides.settings
    val onOff = listOf(1 to R.string.overrides_on, 0 to R.string.overrides_off)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OverrideChoice(
            title = R.string.pref_fx_reverb_row, key = OverrideKeys.FX_REVERB,
            global = prefs.getReverbFlow().map { if (it.enabled) 1 else 0 }, default = 0,
            own = own, onSetting = onSetting, options = onOff,
        )
        OverrideChoice(
            title = R.string.overrides_fx_reverb_room, key = OverrideKeys.FX_REVERB_TYPE,
            global = prefs.getReverbFlow().map { it.type }, default = Player.DEFAULT_REVERB_TYPE,
            own = own, onSetting = onSetting,
            options = reverbTypeOptions.map { it.key.toInt() to it.title },
        )
        OverrideSlider(
            title = R.string.overrides_fx_reverb_mix, key = OverrideKeys.FX_REVERB_MIX,
            global = prefs.getReverbFlow().map { it.mix }, default = Player.DEFAULT_REVERB_MIX,
            own = own, range = 0..100, unit = "%", onSetting = onSetting,
        )

        OverrideChoice(
            title = R.string.pref_fx_chorus_row, key = OverrideKeys.FX_CHORUS,
            global = prefs.getChorusFlow().map { if (it.enabled) 1 else 0 }, default = 0,
            own = own, onSetting = onSetting, options = onOff,
        )
        OverrideSlider(
            title = R.string.overrides_fx_chorus_rate, key = OverrideKeys.FX_CHORUS_RATE,
            global = prefs.getChorusFlow().map { it.rate }, default = Player.DEFAULT_CHORUS_RATE,
            own = own, range = Player.CHORUS_RATE_MIN..Player.CHORUS_RATE_MAX, unit = "",
            onSetting = onSetting, readout = ::chorusRateLabel,
        )
        OverrideSlider(
            title = R.string.overrides_fx_chorus_spread, key = OverrideKeys.FX_CHORUS_SPREAD,
            global = prefs.getChorusFlow().map { it.spread }, default = Player.DEFAULT_CHORUS_SPREAD,
            own = own, range = 0..100, unit = "%", onSetting = onSetting,
        )
        OverrideSlider(
            title = R.string.overrides_fx_chorus_mix, key = OverrideKeys.FX_CHORUS_MIX,
            global = prefs.getChorusFlow().map { it.mix }, default = Player.DEFAULT_CHORUS_MIX,
            own = own, range = 0..100, unit = "%", onSetting = onSetting,
        )

        OverrideChoice(
            title = R.string.pref_fx_surround_row, key = OverrideKeys.FX_SURROUND,
            global = prefs.getSurroundFlow().map { if (it.enabled) 1 else 0 }, default = 0,
            own = own, onSetting = onSetting, options = onOff,
        )
        OverrideSlider(
            title = R.string.overrides_fx_surround_mix, key = OverrideKeys.FX_SURROUND_MIX,
            global = prefs.getSurroundFlow().map { it.mix }, default = Player.DEFAULT_SURROUND_MIX,
            own = own, range = 0..100, unit = "%", onSetting = onSetting,
        )
    }
}

/** The chorus's rate as its slider shows it: kept in tenths of a hertz. */
private fun chorusRateLabel(tenths: Int): String = "%.1f Hz".format(tenths / 10f)

/** Says whether a setting is this file's own, and offers to put it back. */
@Composable
private fun OwnValueNote(isOwn: Boolean, onReset: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(if (isOwn) R.string.overrides_this_file else R.string.overrides_from_settings),
            style = MaterialTheme.typography.bodySmall,
            color = if (isOwn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isOwn) {
            IconButton(onClick = onReset) {
                Icon(Icons.Default.RestartAlt, contentDescription = stringResource(R.string.overrides_reset))
            }
        }
    }
}

/**
 * One of the playing library's settings as a slider.
 *
 * Only cuts what it is handed down to what its own row shows, and the row is
 * built from that. Every write a slider makes comes back as a new set of the
 * file's overrides, up to ten times a second while one is dragged, and each
 * row was handed all of them - and, with them, a flow of the settings
 * screen's value made afresh by the call. So every row was built again for a
 * change to one, and every row dropped the flow it was listening to and began
 * on another: eighteen of them on a Nintendo DS file, each a fresh read of
 * the stored settings. A row whose own value did not change is now left
 * alone, and keeps the flow it started with.
 */
@Composable
private fun OverrideSlider(
    key: String,
    title: Int = 0,
    /** In place of [title], where what a thing is called comes from the file. */
    titleText: String? = null,
    global: Flow<Int>,
    default: Int,
    own: Map<String, Int>,
    range: IntRange,
    unit: String,
    onSetting: (String, Int?) -> Unit,
    pan: Boolean = false,
    /** What the value is shown as, where it is not the number and its [unit]. A function by name, so that the row is left alone when nothing of its own changes. */
    readout: ((Int) -> String)? = null,
) {
    OverrideSliderRow(
        key = key,
        title = titleText ?: stringResource(title),
        // The first one handed over for this setting, kept: the ones after it
        // are the same question asked again.
        global = remember(key) { global },
        default = default,
        ownValue = own[key],
        min = range.first,
        max = range.last,
        unit = unit,
        onSetting = onSetting,
        pan = pan,
        readout = readout,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OverrideSliderRow(
    key: String,
    title: String,
    global: Flow<Int>,
    default: Int,
    /** This file's own value for the setting, or null where it follows the settings screen. */
    ownValue: Int?,
    min: Int,
    max: Int,
    unit: String,
    onSetting: (String, Int?) -> Unit,
    pan: Boolean,
    readout: ((Int) -> String)?,
) {
    val globalValue by global.collectAsStateWithLifecycle(initialValue = default)
    val value = ownValue ?: globalValue
    val shown = when {
        readout != null -> readout(value)
        !pan -> "$value$unit"
        value < 0 -> "L ${-value}"
        value > 0 -> "R $value"
        else -> "C"
    }
    SettingsSlider(
        title = { Text(text = title) },
        subtitle = { OwnValueNote(isOwn = ownValue != null, onReset = { onSetting(key, null) }) },
        action = { Text(text = shown) },
        value = value.toFloat(),
        valueRange = min.toFloat()..max.toFloat(),
        onValueChange = { onSetting(key, it.toInt()) },
        centered = pan,
        startLabel = if (pan) stringResource(R.string.pan_left_short) else null,
        endLabel = if (pan) stringResource(R.string.pan_right_short) else null,
        shapes = ListItemDefaults.segmentedShapes(0, 1),
    )
}

/** One of the playing library's settings as a choice from a menu - cut down for its row as [OverrideSlider] is. */
@Composable
private fun OverrideChoice(
    title: Int,
    key: String,
    global: Flow<Int>,
    default: Int,
    own: Map<String, Int>,
    options: List<Pair<Int, Int>>,
    onSetting: (String, Int?) -> Unit,
) {
    OverrideChoiceRow(
        title = title,
        key = key,
        global = remember(key) { global },
        default = default,
        ownValue = own[key],
        // Kept like the flow: the list is written out again at every call.
        options = remember(key) { options },
        onSetting = onSetting,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OverrideChoiceRow(
    title: Int,
    key: String,
    global: Flow<Int>,
    default: Int,
    ownValue: Int?,
    options: List<Pair<Int, Int>>,
    onSetting: (String, Int?) -> Unit,
) {
    val globalValue by global.collectAsStateWithLifecycle(initialValue = default)
    val value = ownValue ?: globalValue
    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<Rect?>(null) }
    Box(modifier = Modifier.onGloballyPositioned { anchor = it.boundsInRoot() }) {
        ListItem(
            modifier = Modifier.fillMaxWidth(),
            onClick = { open = true },
            content = { Text(text = stringResource(title)) },
            supportingContent = { OwnValueNote(isOwn = ownValue != null, onReset = { onSetting(key, null) }) },
            trailingContent = {
                val label = options.firstOrNull { it.first == value }?.second
                Text(text = label?.let { stringResource(it) } ?: value.toString())
            },
        )
        val liquid = isLiquid
        val items: @Composable ColumnScope.() -> Unit = {
            options.forEachIndexed { index, (optionValue, optionLabel) ->
                if (liquid && index > 0) LiquidMenuSeparator()
                LiquidMenuRow(
                    text = stringResource(optionLabel),
                    selected = optionValue == value,
                    plain = !liquid,
                ) {
                    open = false
                    onSetting(key, optionValue)
                }
            }
        }
        if (liquid) {
            LiquidMenu(
                expanded = open,
                anchor = anchor,
                onDismissRequest = { open = false },
                content = items,
            )
        } else {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Column(content = items)
            }
        }
    }
}

/**
 * One line of a menu: what it says, and a tick where it is the one in use.
 *
 * [plain] falls back to Material's own row, for the styles that are not
 * Liquid Glass and whose menus are Material's.
 */
@Composable
private fun LiquidMenuRow(
    text: String,
    selected: Boolean,
    plain: Boolean,
    onClick: () -> Unit,
) {
    if (plain) {
        DropdownMenuItem(text = { Text(text = text) }, onClick = onClick)
        return
    }
    val colors = LocalLiquidColors.current
    val source = remember { MutableInteractionSource() }
    val light = rememberLiquidRowLight()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Color.Transparent)
            .then(light.modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .then(light.gestureModifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = LiquidType.Body,
            color = colors.label,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = colors.tint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
