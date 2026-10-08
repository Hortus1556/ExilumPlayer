package com.hortus1556.exilum.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hortus1556.exilum.R
import com.hortus1556.exilum.db.entity.PlaylistEntity
import kotlinx.collections.immutable.ImmutableList

/**
 * Picks the playlist something is added to. Shared by the browser, for files
 * and folders, and the player, for what is playing.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlaylistPickerDialog(
    playlists: ImmutableList<PlaylistEntity>,
    onDismiss: () -> Unit,
    onPick: (PlaylistEntity) -> Unit
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.dialog_title_pick_playlist)) },
        text = {
            if (playlists.isEmpty()) {
                Text(text = stringResource(R.string.dialog_message_empty_playlist))
            } else {
                LazyColumn {
                    itemsIndexed(playlists) { idx, playlist ->
                        ListItem(
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent
                            ),
                            shapes = ListItemDefaults.shapes(
                                shape = MaterialTheme.shapes.small,
                                focusedShape = MaterialTheme.shapes.small,
                                pressedShape = MaterialTheme.shapes.small,
                            ),
                            onClick = { onPick(playlist) },
                            content = { Text(text = playlist.name) },
                            supportingContent = if (playlist.comment.isNotBlank()) {
                                { Text(text = playlist.comment) }
                            } else {
                                null
                            }
                        )
                        if (idx < playlists.size - 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.cancel)) }
        },
    )
}
