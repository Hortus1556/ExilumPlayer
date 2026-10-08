package com.hortus1556.exilum.ui.screens.playlists.components

import com.hortus1556.exilum.ui.liquid.LiquidMenuSeparator
import com.hortus1556.exilum.ui.liquid.LiquidMenuItem
import com.hortus1556.exilum.ui.liquid.LiquidMenu
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.liquid.LocalLiquidColors
import com.hortus1556.exilum.ui.liquid.ContinuousShape
import com.hortus1556.exilum.ui.liquid.LiquidArtwork
import androidx.compose.ui.Modifier
import com.hortus1556.exilum.ui.components.listItemColorsOnCard
import com.hortus1556.exilum.ui.components.listItemShapeOnCard
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.hortus1556.exilum.R
import com.hortus1556.exilum.db.entity.PlaylistEntity
import com.hortus1556.exilum.ui.theme.AppTheme
import com.hortus1556.exilum.util.toReadableDate

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlaylistListItem(
    item: PlaylistEntity,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val liquid = isLiquid

    val shape = listItemShapeOnCard(MaterialTheme.shapes.small)
    ListItem(
        onClick = onSelect,
        modifier = modifier,
        shapes = ListItemDefaults.shapes(
            shape = shape,
            focusedShape = shape,
            pressedShape = shape,
        ),
        colors = listItemColorsOnCard(),
        content = { Text(text = item.name) },
        supportingContent = {
            Column {
                if (item.comment.isNotBlank()) Text(text = item.comment)
                Text(
                    text = "Created: ${item.createdAt.toReadableDate()}",
                    fontSize = 10.sp,
                )
            }
        },
        leadingContent = {
            // Music leads a playlist with its cover.
            if (liquid) {
                LiquidArtwork(key = item.name, icon = Icons.AutoMirrored.Filled.QueueMusic)
            } else {
                Icon(imageVector = Icons.AutoMirrored.Filled.List, contentDescription = null)
            }
        },
        trailingContent = {
            var anchor by remember { mutableStateOf<Rect?>(null) }
            Box(modifier = Modifier.onGloballyPositioned { anchor = it.boundsInRoot() }) {
                IconButton(onClick = { expanded = true }) {
                    Icon(
                        // iOS's "more" is the horizontal ellipsis.
                        imageVector = if (liquid) Icons.Default.MoreHoriz else Icons.Default.MoreVert,
                        contentDescription = null,
                        tint = if (liquid) LocalLiquidColors.current.secondaryLabel else LocalContentColor.current,
                    )
                }
                // Under Liquid Glass, iOS's menu on glass in the app's own
                // window; Material's popup otherwise.
                if (liquid) {
                    LiquidMenu(
                        expanded = expanded,
                        anchor = anchor,
                        onDismissRequest = { expanded = false },
                    ) {
                        LiquidMenuItem(
                            text = stringResource(R.string.rename),
                            icon = Icons.Default.Edit,
                            onClick = {
                                expanded = false
                                onRename()
                            },
                        )
                        LiquidMenuSeparator()
                        LiquidMenuItem(
                            text = stringResource(R.string.delete),
                            icon = Icons.Default.Delete,
                            destructive = true,
                            onClick = {
                                expanded = false
                                onDelete()
                            },
                        )
                    }
                } else DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.delete)) },
                        onClick = {
                            expanded = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.rename)) },
                        onClick = {
                            expanded = false
                            onRename()
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                            )
                        },
                    )
                }
            }
        }
    )
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        PlaylistListItem(
            item = PlaylistEntity(
                name = "Name Name Name",
                comment = "Comment Comment"
            ),
            onSelect = {},
            onDelete = {},
            onRename = {},
        )
    }
}
