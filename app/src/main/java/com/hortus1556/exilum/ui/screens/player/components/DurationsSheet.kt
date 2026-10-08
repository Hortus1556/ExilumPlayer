package com.hortus1556.exilum.ui.screens.player.components

import com.hortus1556.exilum.ui.liquid.LocalLiquidOverlayHost
import com.hortus1556.exilum.ui.liquid.LiquidBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.ColumnScope
import com.hortus1556.exilum.ui.liquid.OwnWindowAnimation
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.liquid.sheet
import com.hortus1556.exilum.ui.liquid.LocalLiquidColors
import com.hortus1556.exilum.ui.liquid.LiquidSheetShape
import com.hortus1556.exilum.ui.liquid.LiquidSheetGrabber
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.SheetValue.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.hortus1556.exilum.R
import com.hortus1556.exilum.R.string.title_subsong_sheet
import com.hortus1556.exilum.data.LoopPreset
import com.hortus1556.exilum.data.TimeDisplay
import com.hortus1556.exilum.data.defaultSubsongLabel
import com.hortus1556.exilum.data.loopPresetCode
import com.hortus1556.exilum.data.subsongKey
import com.hortus1556.exilum.ui.theme.AppTheme
import com.hortus1556.exilum.renderer.model.Sequence
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DurationsSheet(
    sheetState: SheetState,
    seqData: ImmutableList<Sequence>,
    currentSequence: Int,
    onDismiss: () -> Unit,
    onItemClick: (Int) -> Unit,
    uri: String = "",
    names: Map<String, String> = emptyMap(),
    onRename: (Int) -> Unit = {},
    presets: List<LoopPreset> = emptyList(),
    activePresetId: Int? = null,
    onPreset: (LoopPreset) -> Unit = {},
) {
    val listState = rememberLazyListState()

    // The same content either way: on real glass in the app's own
    // window under Liquid Glass, in Material's sheet otherwise.
    val body: @Composable ColumnScope.() -> Unit = {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
            content = {
                Text(
                    text = stringResource(title_subsong_sheet, seqData.size),
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .align(Alignment.CenterEnd),
                    onClick = onDismiss,
                    content = {
                        Icon(imageVector = Icons.Default.Close, contentDescription = null)
                    }
                )
            }
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = {
                itemsIndexed(
                    items = seqData,
                    key = { idx, _ -> idx },
                    itemContent = { idx, sequence ->
                        DurationItem(
                            isCurrentItem = idx == currentSequence && activePresetId == null,
                            index = idx,
                            duration = sequence.duration,
                            name = sequence.name,
                            givenName = names[subsongKey(uri, idx)],
                            onItemClick = onItemClick,
                            onRename = onRename,
                        )
                    }
                )
                // The loop presets saved for this file, which play like
                // subsongs of their own.
                items(items = presets, key = { "loop" + it.id }) { preset ->
                    val code = loopPresetCode(preset.id)
                    val label = names[subsongKey(uri, code)] ?: defaultSubsongLabel(code)
                    val span = TimeDisplay.format(preset.startMs.toLong(), TimeDisplay.CLOCK, precise = true) +
                        " \u2192 " + TimeDisplay.format(preset.endMs.toLong(), TimeDisplay.CLOCK, precise = true)
                    ListItem(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onPreset(preset) },
                        shapes = ListItemDefaults.shapes(
                            shape = MaterialTheme.shapes.small,
                            focusedShape = MaterialTheme.shapes.small,
                            pressedShape = MaterialTheme.shapes.small,
                        ),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        content = { Text(text = "$span - $label", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = {
                            RadioButton(selected = activePresetId == preset.id, onClick = null)
                        },
                        trailingContent = {
                            IconButton(
                                onClick = { onRename(code) },
                                content = {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = stringResource(R.string.desc_rename_subsong),
                                    )
                                }
                            )
                        },
                    )
                }
            }
        )
    }
    val overlays = LocalLiquidOverlayHost.current
    if (overlays != null) {
        LiquidBottomSheet(
            host = overlays,
            onDismissRequest = onDismiss,
            dragToDismiss = false,
            content = body,
        )
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            sheetGesturesEnabled = false,
            // iOS: the sheet's own corners and gray, and its grabber.
            dragHandle = if (isLiquid) {
                { LiquidSheetGrabber() }
            } else {
                null
            },
            shape = if (isLiquid) LiquidSheetShape else MaterialTheme.shapes.small,
            containerColor = if (isLiquid) LocalLiquidColors.current.sheet else BottomSheetDefaults.ContainerColor,
        ) {
            OwnWindowAnimation()
            body()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DurationItem(
    isCurrentItem: Boolean,
    index: Int,
    duration: Int,
    name: String,
    givenName: String?,
    onItemClick: (Int) -> Unit,
    onRename: (Int) -> Unit,
) {
    val minutes = duration / 60000
    val seconds = (duration / 1000) % 60
    // The format's own name where there is one - an NSF names its tracks, and
    // "Sub Song 7" is strictly worse than what the file already says. Where it
    // has none, whatever the listener called it, and only then the numbering.
    val label = name.ifBlank { givenName ?: defaultSubsongLabel(index) }
    val timeText = "%d:%02d - %s".format(minutes, seconds, label)

    ListItem(
        modifier = Modifier.fillMaxWidth(),
        onClick = { onItemClick(index) },
        shapes = ListItemDefaults.shapes(
            shape = MaterialTheme.shapes.small,
            focusedShape = MaterialTheme.shapes.small,
            pressedShape = MaterialTheme.shapes.small,
        ),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        ),
        content = {
            Text(
                text = timeText,
                style = if (isCurrentItem) {
                    MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { RadioButton(selected = isCurrentItem, onClick = null) },
        // Named by hand only where the format gives no name to keep.
        trailingContent = if (name.isBlank()) {
            {
                IconButton(
                    onClick = { onRename(index) },
                    content = {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.desc_rename_subsong),
                        )
                    }
                )
            }
        } else {
            null
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun Preview() {
    val sheetState = rememberBottomSheetState(
        initialValue = Expanded,
        enabledValues = setOf(Hidden, Expanded),
    )
    var currentSequence by remember { mutableIntStateOf(5) }
    AppTheme {
        Scaffold { paddingValues ->
            Box(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
            )

            DurationsSheet(
                sheetState = sheetState,
                seqData = listOf(
                    Sequence(0, 183_000), // 3:03 - Main Song
                    Sequence(0, 94_000), // 1:34
                    Sequence(0, 211_000), // 3:31
                    Sequence(0, 57_000), // 0:57
                    Sequence(0, 142_000), // 2:22
                    Sequence(0, 78_000), // 1:18
                    Sequence(0, 305_000), // 5:05
                    Sequence(0, 33_000), // 0:33
                    Sequence(0, 167_000), // 2:47
                    Sequence(0, 120_000), // 2:00
                ).toPersistentList(),
                currentSequence = currentSequence,
                onItemClick = { currentSequence = it },
                onDismiss = {},
            )
        }
    }
}
