package com.hortus1556.exilum.ui.screens.playlists.components

import com.hortus1556.exilum.ui.theme.isOneUi
import com.hortus1556.exilum.ui.theme.isLiquid
import androidx.compose.ui.unit.dp
import com.hortus1556.exilum.ui.components.listItemShapeOnCard
import com.hortus1556.exilum.ui.components.listItemColorsOnCard
import com.hortus1556.exilum.ui.components.groupedListCard
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.*
import androidx.compose.ui.res.stringResource
import com.hortus1556.exilum.R
import com.hortus1556.exilum.db.entity.ModuleEntity
import com.hortus1556.exilum.ui.theme.AppTheme
import kotlin.math.abs
import kotlinx.coroutines.launch
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableCollectionItemScope

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ReorderableCollectionItemScope.PlaylistEntryItem(
    index: Int,
    /** How many entries the list has, so the rows draw as one card across it. */
    count: Int,
    file: ModuleEntity,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    /** The subsong's name when this entry is one subsong, which then leads; null for a whole file. */
    subsongName: String? = null,
    onRename: () -> Unit = {},
    /**
     * The row was let go after being dragged by its handle. The order is
     * saved here, once, and not for every row the drag passed on the way.
     */
    onDropped: () -> Unit = {},
) {
    val hapticFeedback = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var componentWidth by remember { mutableFloatStateOf(0f) }
    var swipeOffset by remember { mutableFloatStateOf(0f) }

    val isSwiping by remember { derivedStateOf { swipeOffset < -2f } }

    LaunchedEffect(isSwiping) {
        if (isSwiping) hapticFeedback.performHapticFeedback(HapticFeedbackType.VirtualKey)
    }

    Box(
        modifier = Modifier
            .onSizeChanged { componentWidth = it.width.toFloat() }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    swipeOffset = (swipeOffset + delta).coerceIn(-componentWidth, 0f)
                },
                onDragStopped = {
                    scope.launch {
                        if (componentWidth > 0f && abs(swipeOffset) / componentWidth >= 0.4f) {
                            animate(
                                initialValue = swipeOffset,
                                targetValue = -componentWidth,
                            ) { v, _ -> swipeOffset = v }
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                            onRemove()
                        } else {
                            animate(
                                initialValue = swipeOffset,
                                targetValue = 0f,
                                animationSpec = spring(),
                            ) { v, _ -> swipeOffset = v }
                        }
                    }
                }
            )
    ) {
        // What a swipe uncovers, and only then: the rows are see-through now
        // that they draw as one card, so this would otherwise show through them.
        if (isSwiping) {
            val grouped = isOneUi || isLiquid
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(horizontal = if (grouped) 16.dp else 0.dp)
                    .background(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = if (grouped) MaterialTheme.shapes.large else MaterialTheme.shapes.small,
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Box(modifier = Modifier.offset { IntOffset(swipeOffset.toInt(), 0) }) {
            ListItem(
                // Part of the list's own card, as every other list here is.
                modifier = Modifier.groupedListCard(index, count, dividerStart = 36.dp),
                onClick = onPlay,
                colors = listItemColorsOnCard(),
                shapes = ListItemDefaults.shapes(
                    shape = listItemShapeOnCard(MaterialTheme.shapes.small),
                    focusedShape = listItemShapeOnCard(MaterialTheme.shapes.small),
                    pressedShape = listItemShapeOnCard(MaterialTheme.shapes.small),
                ),
                content = {
                    Text(
                        text = subsongName ?: file.name,
                        maxLines = 1,
                    )
                },
                supportingContent = {
                    val type = file.moduleType.ifBlank { file.fileExtension.uppercase() }
                    Text(
                        text = if (subsongName != null) "${file.name} \u00B7 $type" else type,
                        maxLines = 1,
                    )
                },
                leadingContent = {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                trailingContent = {
                    Row {
                        if (subsongName != null) {
                            IconButton(
                                onClick = onRename,
                                content = {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = stringResource(R.string.desc_rename_subsong),
                                    )
                                }
                            )
                        }
                        IconButton(
                            modifier = Modifier.draggableHandle(
                                onDragStarted = {
                                    hapticFeedback.performHapticFeedback(
                                        HapticFeedbackType.GestureThresholdActivate
                                    )
                                },
                                onDragStopped = {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    onDropped()
                                },
                            ),
                            onClick = {},
                            content = {
                                Icon(
                                    imageVector = Icons.Rounded.DragHandle,
                                    contentDescription = null,
                                )
                            }
                        )
                    }
                }
            )
        }
    }
}

@Preview
@Composable
private fun Preview() {
    val fakeScope = remember {
        object : ReorderableCollectionItemScope {
            override fun Modifier.draggableHandle(
                enabled: Boolean,
                interactionSource: MutableInteractionSource?,
                onDragStarted: (startedPosition: Offset) -> Unit,
                onDragStopped: () -> Unit,
                dragGestureDetector: DragGestureDetector
            ): Modifier = this

            override fun Modifier.longPressDraggableHandle(
                enabled: Boolean,
                interactionSource: MutableInteractionSource?,
                onDragStarted: (startedPosition: Offset) -> Unit,
                onDragStopped: () -> Unit
            ): Modifier = this
        }
    }

    AppTheme {
        with(fakeScope) {
            PlaylistEntryItem(
                index = 1,
                count = 3,
                file = ModuleEntity(
                    filePath = "content://preview/1",
                    filename = "aegis_-_beneath_the_fallen_stars.it",
                    fileExtension = "it",
                    fileSize = 1_820_792L,
                    moduleName = "Beneath the Fallen Stars",
                    moduleType = "Impulse Tracker",
                ),
                onPlay = {},
                onRemove = {},
            )
        }
    }
}
