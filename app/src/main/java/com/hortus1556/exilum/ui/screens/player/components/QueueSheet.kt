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
import com.hortus1556.exilum.data.defaultSubsongLabel
import com.hortus1556.exilum.data.subsongKey
import com.hortus1556.exilum.db.entity.ModuleEntity
import com.hortus1556.exilum.ui.theme.AppTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueSheet(
    sheetState: SheetState,
    queue: ImmutableList<ModuleEntity>,
    currentIndex: Int,
    onItemClick: (Int) -> Unit,
    onDismiss: () -> Unit,
    names: Map<String, String> = emptyMap(),
) {
    // Opened on the entry playing, not opened at the top and scrolled to it.
    //
    // The sheet is built afresh each time it is shown, so the list began at
    // its first entry every time and was then animated to the playing one:
    // with a few hundred files in the queue it raced past a hundred rows as
    // the sheet came up, building and dropping each on the way, and a touch
    // during that left it wherever it had got to.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = currentIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0)),
    )

    LaunchedEffect(currentIndex) {
        if (currentIndex !in queue.indices) return@LaunchedEffect
        // The next track is a row away and worth being seen to move to. One
        // further off than about a screenful - a shuffle, a tap far down a
        // long queue - is jumped to: scrolling there shows nothing but rows
        // going by.
        val shown = listState.layoutInfo.visibleItemsInfo
        val near = shown.isNotEmpty() &&
            currentIndex >= shown.first().index - shown.size &&
            currentIndex <= shown.last().index + shown.size
        if (near) {
            listState.animateScrollToItem(currentIndex)
        } else {
            listState.scrollToItem(currentIndex)
        }
    }

    // The same content either way: on real glass in the app's own
    // window under Liquid Glass, in Material's sheet otherwise.
    val body: @Composable ColumnScope.() -> Unit = {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
            content = {
                Text(
                    text = stringResource(R.string.title_queue_sheet, queue.size),
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
                    items = queue,
                    // No key of their own. A queue can hold one file twice, or
                    // as two of its subsongs, so the position had to be part
                    // of any key - and with the position in it a key says
                    // nothing the position does not, while costing a string
                    // for every entry the list looks ahead at.
                    itemContent = { index, file ->
                        QueueListItem(
                            isCurrentItem = index == currentIndex,
                            index = index,
                            file = file,
                            subsongName = if (file.subsong >= 0) {
                                names[subsongKey(file.filePath, file.subsong)]
                                    ?: defaultSubsongLabel(file.subsong)
                            } else {
                                null
                            },
                            onItemClick = onItemClick,
                        )
                    }
                )
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
private fun QueueListItem(
    isCurrentItem: Boolean,
    index: Int,
    file: ModuleEntity,
    subsongName: String?,
    onItemClick: (Int) -> Unit
) {
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        onClick = { onItemClick(index) },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        ),
        shapes = ListItemDefaults.shapes(
            shape = MaterialTheme.shapes.small,
            focusedShape = MaterialTheme.shapes.small,
            pressedShape = MaterialTheme.shapes.small,
        ),
        content = {
            Text(
                text = if (subsongName != null) "${file.name} · $subsongName" else file.name,
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
        leadingContent = {
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = if (isCurrentItem) {
            {
                Icon(
                    imageVector = Icons.Default.Equalizer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            null
        },
        supportingContent = {
            Text(
                text = file.type,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    var currentIndex by remember { mutableIntStateOf(3) }
    AppTheme {
        Scaffold { paddingValues ->
            Box(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
            )
            QueueSheet(
                sheetState = sheetState,
                queue = List(10) {
                    ModuleEntity(
                        filePath = "content://preview/$it",
                        filename = "Item $it",
                        fileExtension = "669",
                        fileSize = 669L,
                    )
                }.toPersistentList(),
                currentIndex = currentIndex,
                onItemClick = { currentIndex = it },
                onDismiss = {},
            )
        }
    }
}
