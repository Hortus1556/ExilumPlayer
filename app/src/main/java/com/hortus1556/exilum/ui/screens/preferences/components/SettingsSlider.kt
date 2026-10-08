package com.hortus1556.exilum.ui.screens.preferences.components

import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.liquid.LocalLiquidColors
import com.hortus1556.exilum.ui.liquid.LiquidType
import com.hortus1556.exilum.ui.liquid.LiquidSlider
import com.hortus1556.exilum.ui.liquid.LiquidRow
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.*
import com.alorma.compose.settings.ui.core.LocalSettingsGroupEnabled
import com.alorma.compose.settings.ui.expressive.SettingsTileDefaults
import com.hortus1556.exilum.R
import com.hortus1556.exilum.ui.oneui.LocalOneUiColors
import com.hortus1556.exilum.ui.oneui.OneUiGroupShape
import com.hortus1556.exilum.ui.oneui.OneUiRow
import com.hortus1556.exilum.ui.oneui.OneUiSlider
import com.hortus1556.exilum.ui.oneui.OneUiType
import com.hortus1556.exilum.ui.theme.AppTheme
import com.hortus1556.exilum.ui.theme.isOneUi
import kotlin.math.abs
import kotlin.math.ceil

/** How close to a snap point a drag has to come to land on it, as a share of the range. */
private const val SNAP_FRACTION = 0.02f

/** Snap points go on every multiple of this - 0, 50, 100, 150, 200. */
private const val SNAP_EVERY = 50f

/**
 * The round values a slider pulls towards: the ends and every multiple of
 * [SNAP_EVERY] between. They are where the defaults sit - 100 for a volume or
 * a spread, 0 for a pan - and the values hardest to land on exactly with a
 * finger. None for a slider with steps, which lands on its own values already.
 */
internal fun defaultSnapPoints(range: ClosedFloatingPointRange<Float>, steps: Int): List<Float> {
    if (steps > 0) return emptyList()
    return buildList {
        add(range.start)
        var point = ceil(range.start / SNAP_EVERY) * SNAP_EVERY
        while (point <= range.endInclusive) {
            add(point)
            point += SNAP_EVERY
        }
        add(range.endInclusive)
    }.distinct()
}

/**
 * A slider row.
 *
 * @param snapPoints values a drag is pulled onto once it comes within
 *   [SNAP_FRACTION] of the range, with a tick of haptic feedback as it lands.
 *   Round values by default - see [defaultSnapPoints]; empty for none.
 * @param centered draws the track filled from the middle rather than from the
 *   start, for a value either side of zero - a pan.
 * @param startLabel and [endLabel] are written under the two ends of the track.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalSettingsGroupEnabled.current,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    subtitle: @Composable (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    sliderColors: SliderColors = SliderDefaults.colors(),
    colors: ListItemColors = SettingsTileDefaults.colors(),
    shapes: ListItemShapes = SettingsTileDefaults.shapes(),
    elevation: ListItemElevation = SettingsTileDefaults.elevation(),
    snapPoints: List<Float>? = null,
    centered: Boolean = false,
    startLabel: String? = null,
    endLabel: String? = null,
    semanticProperties: SemanticsPropertyReceiver.() -> Unit = {}
) {
    val points = remember(valueRange, steps, snapPoints) {
        snapPoints ?: defaultSnapPoints(valueRange, steps)
    }
    val hold = rememberSliderHold(value, onValueChange, valueRange, points, onValueChangeFinished)
    val shown = hold.shown
    val snapped = hold.onDrag
    val release = hold.onRelease

    if (isLiquid) {
        val colors = LocalLiquidColors.current
        LiquidRow(
            title = title,
            modifier = modifier.semantics(properties = semanticProperties),
            shape = shapes.liquidShape(),
            subtitle = subtitle,
            icon = icon,
            value = action?.let {
                {
                    // Width-stable for the same reason as the Material row.
                    Box(modifier = Modifier.widthIn(min = 56.dp), contentAlignment = Alignment.CenterEnd) { it() }
                }
            },
            enabled = enabled,
            below = {
                Column(modifier = Modifier.padding(top = 2.dp)) {
                    LiquidSlider(
                        value = shown,
                        onValueChange = snapped,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                        valueRange = valueRange,
                        steps = steps,
                        onValueChangeFinished = release,
                        centered = centered,
                    )
                    if (startLabel != null || endLabel != null) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = startLabel.orEmpty(), style = LiquidType.Footnote, color = colors.secondaryLabel)
                            Text(text = endLabel.orEmpty(), style = LiquidType.Footnote, color = colors.secondaryLabel)
                        }
                    }
                }
            },
        )
        return
    }
    if (isOneUi) {
        OneUiSliderRow(
            value = shown,
            onValueChange = snapped,
            title = title,
            modifier = modifier.semantics(properties = semanticProperties),
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = release,
            icon = icon,
            subtitle = subtitle,
            action = action,
            shape = shapes.shape as? OneUiGroupShape ?: OneUiGroupShape.Single,
            centered = centered,
            startLabel = startLabel,
            endLabel = endLabel,
        )
        return
    }

    SegmentedListItem(
        onClick = {}, // no row-level click; slider handles its own interaction
        modifier = Modifier
            .fillMaxWidth()
            .semantics(properties = semanticProperties)
            .then(modifier),
        shapes = shapes,
        enabled = enabled,
        content = title,
        leadingContent = icon,
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                subtitle?.invoke()
                Slider(
                    value = shown,
                    onValueChange = snapped,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    valueRange = valueRange,
                    steps = steps,
                    onValueChangeFinished = release,
                    colors = sliderColors,
                    track = { state ->
                        if (centered) {
                            SliderDefaults.CenteredTrack(
                                sliderState = state,
                                enabled = enabled,
                                colors = sliderColors,
                            )
                        } else {
                            SliderDefaults.Track(
                                sliderState = state,
                                enabled = enabled,
                                colors = sliderColors,
                            )
                        }
                    },
                )
                if (startLabel != null || endLabel != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(text = startLabel.orEmpty(), style = MaterialTheme.typography.labelMedium)
                        Text(text = endLabel.orEmpty(), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        },
        colors = colors,
        elevation = elevation,
        // Width-stable, because the value readout lives in it and the value is
        // what the slider is for. "40 ms" becoming "400 ms" widened the
        // trailing slot, the row gave the width back out of the supporting
        // slot, and the slider itself shrank as you dragged it.
        trailingContent = action?.let {
            {
                Box(
                    modifier = Modifier.widthIn(min = 64.dp),
                    contentAlignment = Alignment.CenterEnd,
                    content = { it() },
                )
            }
        },
    )
}

/** What a settings slider shows, and what it does with a finger on it and with one lifted: see [rememberSliderHold]. */
internal class SliderHold(val shown: Float, val onDrag: (Float) -> Unit, val onRelease: () -> Unit)

/**
 * The hold a settings slider has on its value while a finger is on it: shown
 * as the finger sets it, pulled onto the [points] it comes near, and written
 * to [onValueChange] no more often than a setting can take.
 */
@Composable
internal fun rememberSliderHold(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    points: List<Float>,
    onValueChangeFinished: (() -> Unit)? = null,
): SliderHold {
    val haptic = LocalHapticFeedback.current
    // What the finger has set, shown at once. The setting comes back through
    // a write to disk and the preferences flow, and a knob that waited for
    // that trailed behind the finger in jumps. The stored value takes over
    // again once it has caught up, or shortly after letting go.
    var held by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(value) {
        val now = held ?: return@LaunchedEffect
        if (abs(now - value) <= (valueRange.endInclusive - valueRange.start) * 1e-4f) held = null
    }
    // And the setting is written as the finger goes, but no more than every
    // WRITE_INTERVAL_MS, the latest each time and always the last: every
    // write is a trip to disk and back, and some settings - the glass - are
    // redrawn wherever they are used when they change.
    val scope = rememberCoroutineScope()
    val change by rememberUpdatedState(onValueChange)
    val pending = remember { floatArrayOf(Float.NaN) }
    var writer by remember { mutableStateOf<Job?>(null) }
    val write: (Float) -> Unit = { next ->
        pending[0] = next
        if (writer?.isActive != true) {
            writer = scope.launch {
                while (true) {
                    val sent = pending[0]
                    change(sent)
                    delay(WRITE_INTERVAL_MS)
                    if (pending[0] == sent) break
                }
            }
        }
    }
    val finished by rememberUpdatedState(onValueChangeFinished)
    val release: () -> Unit = {
        finished?.invoke()
        scope.launch {
            delay(RELEASE_HOLD_MS)
            // Whatever the store says by now is what stands.
            if (held != null && writer?.isActive != true) held = null
        }
    }
    val shown = held ?: value
    // Only for noticing when a drag lands on a point, never read while
    // composing - so it is a plain holder, not state.
    val landedOn = remember { FloatArray(1) { Float.NaN } }
    val snapped: (Float) -> Unit = { raw ->
        val reach = (valueRange.endInclusive - valueRange.start) * SNAP_FRACTION
        val point = points.minByOrNull { abs(it - raw) }?.takeIf { abs(it - raw) <= reach }
        if (point != null && point != landedOn[0]) {
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
        landedOn[0] = point ?: Float.NaN
        val next = point ?: raw
        held = next
        write(next)
    }
    return SliderHold(shown, snapped, release)
}

/**
 * The One UI slider row: the title with its value at the end in the accent,
 * the description under it, then the slider across the whole card and the
 * labels of its two ends.
 */
@Composable
private fun OneUiSliderRow(
    value: Float,
    onValueChange: (Float) -> Unit,
    title: @Composable () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChangeFinished: (() -> Unit)?,
    icon: @Composable (() -> Unit)?,
    subtitle: @Composable (() -> Unit)?,
    action: (@Composable () -> Unit)?,
    shape: OneUiGroupShape,
    centered: Boolean,
    startLabel: String?,
    endLabel: String?,
) {
    val colors = LocalOneUiColors.current
    OneUiRow(
        title = title,
        modifier = modifier,
        shape = shape,
        summary = subtitle,
        icon = icon,
        trailing = action?.let {
            {
                // Width-stable for the same reason as the Material row's.
                Box(modifier = Modifier.widthIn(min = 56.dp), contentAlignment = Alignment.CenterEnd) {
                    CompositionLocalProvider(LocalContentColor provides colors.accent) {
                        ProvideTextStyle(OneUiType.Summary) { it() }
                    }
                }
            }
        },
        enabled = enabled,
        below = {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                OneUiSlider(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    valueRange = valueRange,
                    steps = steps,
                    onValueChangeFinished = onValueChangeFinished,
                    centered = centered,
                )
                if (startLabel != null || endLabel != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(text = startLabel.orEmpty(), style = OneUiType.Summary, color = colors.textSecondary)
                        Text(text = endLabel.orEmpty(), style = OneUiType.Summary, color = colors.textSecondary)
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SettingsSlider(
                    value = 0.5f,
                    onValueChange = {},
                    title = { Text(text = stringResource(R.string.confirm)) },
                    subtitle = { Text(text = stringResource(R.string.confirm)) },
                    enabled = true,
                )

                SettingsSlider(
                    value = 0.5f,
                    onValueChange = {},
                    title = { Text(text = stringResource(R.string.confirm)) },
                    subtitle = { Text(text = stringResource(R.string.confirm)) },
                    enabled = false,
                )
            }
        }
    }
}

/** At most this often the setting is written while a slider is dragged. */
private const val WRITE_INTERVAL_MS = 100L

/** How long a released slider keeps showing where it was let go, at most. */
private const val RELEASE_HOLD_MS = 600L
