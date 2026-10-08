package com.hortus1556.exilum.ui.screens.player.components

import com.hortus1556.exilum.ui.liquid.LiquidGlassButton
import androidx.compose.runtime.CompositionLocalProvider
import com.hortus1556.exilum.ui.liquid.LiquidMenu
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.rememberCoroutineScope
import com.hortus1556.exilum.ui.liquid.LiquidMenuSeparator
import com.hortus1556.exilum.ui.liquid.LiquidMenuPane
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import com.hortus1556.exilum.ui.liquid.nowPlayingGlass
import com.hortus1556.exilum.ui.liquid.frost
import com.hortus1556.exilum.ui.liquid.liquidGel
import com.hortus1556.exilum.ui.liquid.liquidButtonGlass
import com.hortus1556.exilum.ui.liquid.LiquidPressHighlight
import com.hortus1556.exilum.ui.liquid.LocalLiquidBackdrop
import com.hortus1556.exilum.ui.liquid.LocalLiquidColors
import com.hortus1556.exilum.ui.liquid.ContinuousShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateColorAsState
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.liquid.liquidPress
import com.hortus1556.exilum.ui.liquid.liquidGlassPlain
import com.hortus1556.exilum.ui.liquid.LiquidType
import com.hortus1556.exilum.ui.liquid.CapsuleShape
import androidx.compose.foundation.shape.CircleShape
import com.hortus1556.exilum.ui.oneui.LocalOneUiColors
import com.hortus1556.exilum.ui.theme.isOneUi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.media3.common.Player
import com.hortus1556.exilum.R
import com.hortus1556.exilum.player.DEFAULT_TEMPO_PERCENT
import com.hortus1556.exilum.player.MAX_PITCH_SEMITONES
import com.hortus1556.exilum.player.MAX_TEMPO_PERCENT
import com.hortus1556.exilum.player.MIN_TEMPO_PERCENT
import com.hortus1556.exilum.player.TEMPO_STEP_PERCENT
import com.hortus1556.exilum.ui.theme.AppTheme

@Composable
internal fun ChipList(
    modifier: Modifier = Modifier,
    isShuffle: Boolean,
    isShuffleRoot: Boolean,
    repeatMode: Int,
    globalPitch: Int,
    onGlobalPitch: (Int) -> Unit,
    globalTempo: Int,
    onGlobalTempo: (Int) -> Unit,
    isSubSongs: Boolean,
    onShuffle: () -> Unit,
    onLoop: () -> Unit,
    onAddToPlaylist: () -> Unit = {},
    onPlaySubSongs: () -> Unit,
    onShowDurations: () -> Unit,
    onAudioInfo: () -> Unit,
    onOverrides: () -> Unit = {},
    /**
     * Whether the two subsong controls belong in this list.
     *
     * False when they have been given the row that pos/pat/row used to have -
     * a format with no patterns has that row going spare and nothing else to
     * put in it. They live in one place or the other, never both. False too
     * when the file has one subsong or none, where there is nothing to list
     * and nothing to play one after another.
     */
    withSubsongs: Boolean = true,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(
            space = 8.dp,
            alignment = Alignment.CenterHorizontally
        ),
        // Liquid Glass lines the chips up with the title and the bar above.
        contentPadding = PaddingValues(horizontal = if (isLiquid) 16.dp else 6.dp),
        content = {
            item {
                // Off, on, and on over the whole library - the infinity sign,
                // since that one never runs out.
                val root = isShuffle && isShuffleRoot
                PlayerFilterChip(
                    selected = isShuffle,
                    onClick = onShuffle,
                    label = {
                        val shuffleText = when {
                            root -> "Shuffle \u221E"
                            isShuffle -> "Shuffle On"
                            else -> "Shuffle Off"
                        }
                        Text(text = shuffleText)
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (root) Icons.Default.AllInclusive else Icons.Default.Shuffle,
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize)
                        )
                    }
                )
            }
            item {
                PlayerFilterChip(
                    selected = repeatMode != 0,
                    onClick = onLoop,
                    label = {
                        val loopText = when (repeatMode) {
                            Player.REPEAT_MODE_ONE -> "Repeating One"
                            Player.REPEAT_MODE_ALL -> "Repeating All"
                            Player.REPEAT_MODE_OFF -> "Repeat Off"
                            else -> throw IllegalArgumentException("Invalid Repeat Mode")
                        }
                        Text(text = loopText)
                    },
                    leadingIcon = {
                        val loopIcon = when (repeatMode) {
                            Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne
                            Player.REPEAT_MODE_ALL -> Icons.Default.RepeatOn
                            Player.REPEAT_MODE_OFF -> Icons.Default.Repeat
                            else -> throw IllegalArgumentException("Invalid Repeat Mode")
                        }

                        Icon(
                            imageVector = loopIcon,
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize)
                        )
                    }
                )
            }
            item {
                PlayerAssistChip(
                    onClick = onAddToPlaylist,
                    label = { Text(text = stringResource(R.string.chip_add_to_playlist)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize)
                        )
                    }
                )
            }
            item {
                GlobalPitchTempoChip(
                    semitones = globalPitch,
                    tempoPercent = globalTempo,
                    onPitch = onGlobalPitch,
                    onTempo = onGlobalTempo,
                )
            }
            if (withSubsongs) {
                item { SubsongsChip(isSubSongs = isSubSongs, onClick = onPlaySubSongs) }
                item { ShowSubsongsChip(onClick = onShowDurations) }
            }
            item {
                PlayerAssistChip(
                    onClick = onOverrides,
                    label = { Text(text = stringResource(R.string.chip_overrides)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.SettingsSuggest,
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize)
                        )
                    }
                )
            }
            item {
                PlayerAssistChip(
                    onClick = onAudioInfo,
                    label = { Text("Audio Info (Oboe)") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.BugReport,
                            null,
                            Modifier.size(AssistChipDefaults.IconSize)
                        )
                    }
                )
            }
        }
    )
}

/**
 * Play-every-subsong, as a toggle.
 *
 * Shared rather than inlined: on a format with no patterns this chip is drawn
 * from [SubsongRow] instead, in the row pos/pat/row would have had, and the
 * two places have to look the same because to the user it is the same chip.
 */
@Composable
internal fun SubsongsChip(isSubSongs: Boolean, onClick: () -> Unit) {
    PlayerFilterChip(
        selected = isSubSongs,
        onClick = onClick,
        label = { Text(text = if (isSubSongs) "Subsongs On" else "Subsongs Off") },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.AccountTree,
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize)
            )
        }
    )
}

/** Opens the subsong list. Shared for the same reason as [SubsongsChip]. */
@Composable
internal fun ShowSubsongsChip(onClick: () -> Unit) {
    PlayerAssistChip(
        onClick = onClick,
        label = { Text("Show Subsongs") },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.FormatListNumbered,
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize)
            )
        }
    )
}

/** "P+5", "P-12", "P0" - the pitch readout, marked so it is not taken for the tempo. */
private fun pitchText(semitones: Int): String = if (semitones > 0) "P+$semitones" else "P$semitones"

/**
 * Global pitch and tempo, as a chip that opens two steppers: the tempo above,
 * the pitch below.
 *
 * Steppers and not sliders: the useful values are whole semitones and steps of
 * five percent, and a slider that has to land on one of them is harder to use than
 * two buttons, on a phone especially. The pitch reads the way a musician
 * reads an interval, sign kept on purpose - which way it has gone is the
 * whole question - and the chip shows whichever of the two has moved.
 */
@Composable
private fun GlobalPitchTempoChip(
    semitones: Int,
    tempoPercent: Int,
    onPitch: (Int) -> Unit,
    onTempo: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val changed = semitones != 0 || tempoPercent != DEFAULT_TEMPO_PERCENT
    // Where the chip is on screen, for the menu to hang off: under Liquid
    // Glass the menu is drawn in the app's own window rather than a popup of
    // its own, and has to be told where to go. See LiquidOverlay.
    var anchor by remember { mutableStateOf<Rect?>(null) }

    Box(modifier = Modifier.onGloballyPositioned { anchor = it.boundsInRoot() }) {
        PlayerFilterChip(
            selected = changed,
            onClick = { open = true },
            label = {
                val title = stringResource(R.string.chip_global_pitch_tempo)
                val parts = buildList {
                    if (semitones != 0) add(pitchText(semitones))
                    if (tempoPercent != DEFAULT_TEMPO_PERCENT) add("$tempoPercent%")
                }
                // Laid over the widest thing the chip can say, so a new value
                // never changes its width. The row is centred, so a chip that
                // grew or shrank moved - and the menu anchored under it moved
                // with it, at every tap on its buttons. Tabular figures, so
                // that holds on a system font whose digits differ in width.
                val style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
                Box(contentAlignment = Alignment.Center) {
                    val widest = "$MAX_TEMPO_PERCENT%"
                    for (reserve in listOf(
                        title,
                        pitchText(-MAX_PITCH_SEMITONES) + " \u00B7 " + widest,
                        pitchText(MAX_PITCH_SEMITONES) + " \u00B7 " + widest,
                    )) {
                        Text(
                            text = reserve,
                            style = style,
                            modifier = Modifier.alpha(0f).clearAndSetSemantics {},
                        )
                    }
                    Text(
                        text = if (parts.isEmpty()) title else parts.joinToString(" \u00B7 "),
                        style = style,
                    )
                }
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize)
                )
            }
        )

        val liquid = isLiquid
        val steppers: @Composable ColumnScope.() -> Unit = {
            Stepper(
                text = "$tempoPercent%",
                onDown = { onTempo(tempoPercent - TEMPO_STEP_PERCENT) },
                onUp = { onTempo(tempoPercent + TEMPO_STEP_PERCENT) },
                downEnabled = tempoPercent > MIN_TEMPO_PERCENT,
                upEnabled = tempoPercent < MAX_TEMPO_PERCENT,
                downDescription = stringResource(R.string.desc_tempo_down, TEMPO_STEP_PERCENT),
                upDescription = stringResource(R.string.desc_tempo_up, TEMPO_STEP_PERCENT),
            )
            if (liquid) LiquidMenuSeparator()
            Stepper(
                text = pitchText(semitones),
                onDown = { onPitch(semitones - 1) },
                onUp = { onPitch(semitones + 1) },
                downEnabled = semitones > -MAX_PITCH_SEMITONES,
                upEnabled = semitones < MAX_PITCH_SEMITONES,
                downDescription = stringResource(R.string.desc_pitch_down),
                upDescription = stringResource(R.string.desc_pitch_up),
            )
        }
        if (liquid) {
            LiquidMenu(
                expanded = open,
                anchor = anchor,
                onDismissRequest = { open = false },
                content = steppers,
            )
        } else {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Column(content = steppers)
            }
        }
    }
}

/** A minus button, a readout and a plus button - one row of [GlobalPitchTempoChip]. */
@Composable
private fun Stepper(
    text: String,
    onDown: () -> Unit,
    onUp: () -> Unit,
    downEnabled: Boolean,
    upEnabled: Boolean,
    downDescription: String,
    upDescription: String,
) {
    val readout: @Composable () -> Unit = {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.Center,
            // Fixed width, so stepping through the range does not shuffle the
            // buttons sideways under the finger holding one down.
            modifier = Modifier.width(64.dp),
        )
    }
    if (isLiquid) {
        // Across the whole menu, as iOS lays out a stepper in one: a bubble
        // of glass at either end and the value in the middle. The row used
        // to be only as wide as its three parts, at the start of a menu
        // wider than that - the value sat left of the middle.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiquidGlassButton(
                icon = Icons.Default.Remove,
                contentDescription = downDescription,
                onClick = onDown,
                size = 40.dp,
                glyph = 20.dp,
                enabled = downEnabled,
            )
            readout()
            LiquidGlassButton(
                icon = Icons.Default.Add,
                contentDescription = upDescription,
                onClick = onUp,
                size = 40.dp,
                glyph = 20.dp,
                enabled = upEnabled,
            )
        }
        return
    }
    Row(
        modifier = Modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onDown,
            enabled = downEnabled,
            content = {
                Icon(imageVector = Icons.Default.Remove, contentDescription = downDescription)
            }
        )
        readout()
        IconButton(
            onClick = onUp,
            enabled = upEnabled,
            content = {
                Icon(imageVector = Icons.Default.Add, contentDescription = upDescription)
            }
        )
    }
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Box(modifier = Modifier.fillMaxWidth()) {
            ChipList(
                isShuffle = false,
                isShuffleRoot = false,
                repeatMode = 1,
                globalPitch = 0,
                onGlobalPitch = {},
                globalTempo = 100,
                onGlobalTempo = {},
                isSubSongs = false,
                onShuffle = {},
                onLoop = {},
                onPlaySubSongs = {},
                onShowDurations = {},
                onAudioInfo = {},
            )
        }
    }
}

/**
 * A toggle chip in the style the app is drawn in. Material's is outlined and
 * turns tertiary when on; One UI's is a filled gray pill with no outline that
 * takes the accent when on.
 */
@Composable
private fun PlayerFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    if (isLiquid) {
        LiquidChip(selected = selected, onClick = onClick, label = label, leadingIcon = leadingIcon)
    } else if (isOneUi) {
        val colors = LocalOneUiColors.current
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = label,
            leadingIcon = leadingIcon,
            shape = CircleShape,
            border = null,
            colors = FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                labelColor = colors.text,
                iconColor = colors.textSecondary,
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = colors.accent,
                selectedLeadingIconColor = colors.accent,
            ),
        )
    } else {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = label,
            leadingIcon = leadingIcon,
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer
            ),
        )
    }
}

/** An action chip in the style the app is drawn in - see [PlayerFilterChip]. */
@Composable
private fun PlayerAssistChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    if (isLiquid) {
        LiquidChip(selected = false, onClick = onClick, label = label, leadingIcon = leadingIcon)
    } else if (isOneUi) {
        val colors = LocalOneUiColors.current
        AssistChip(
            onClick = onClick,
            label = label,
            leadingIcon = leadingIcon,
            shape = CircleShape,
            border = null,
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                labelColor = colors.text,
                leadingIconContentColor = colors.textSecondary,
            ),
        )
    } else {
        AssistChip(onClick = onClick, label = label, leadingIcon = leadingIcon)
    }
}

/**
 * A chip as iOS 26 draws a button over Now Playing: a capsule of glass with a
 * white label, turning solid white with the label dark when it is on - the
 * way Music marks shuffle and repeat. It swells a little under the finger.
 */
@Composable
private fun LiquidChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    leadingIcon: @Composable (() -> Unit)?,
) {
    val source = remember { MutableInteractionSource() }
    val colors = LocalLiquidColors.current
    val backdrop = LocalLiquidBackdrop.current
    val scope = rememberCoroutineScope()
    val light = remember(scope) { LiquidPressHighlight(scope) }
    val fill by animateColorAsState(
        targetValue = if (selected) colors.label.copy(alpha = 0.92f) else colors.nowPlayingGlass,
        label = "liquid_chip_fill",
    )
    val content by animateColorAsState(
        // Selected is the label's own colour turned over: white on black in
        // the light, black on white in the dark.
        targetValue = if (selected) {
            (if (colors.isDark) Color.Black else Color.White).copy(alpha = 0.9f)
        } else {
            colors.label
        },
        label = "liquid_chip_content",
    )
    Row(
        modifier = Modifier
            .height(36.dp)
            .then(
                if (backdrop != null) {
                    // Real glass over Now Playing's colours, giving under the finger.
                    Modifier
                        .then(light.gestureModifier)
                        .liquidButtonGlass(backdrop, fill, frost = colors.frost, layerBlock = { liquidGel(light) })
                        .then(light.modifier)
                } else {
                    Modifier
                        .liquidPress(source)
                        .liquidGlassPlain(CapsuleShape, fill, highlight = if (selected) 0.35f else 0.8f)
                }
            )
            .clip(CapsuleShape)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides content) {
            ProvideTextStyle(LiquidType.Subheadline.copy(fontWeight = FontWeight.SemiBold)) {
                leadingIcon?.invoke()
                label()
            }
        }
    }
}
