package com.hortus1556.exilum.ui.screens.playlists.components

import com.hortus1556.exilum.ui.components.AppAlertDialog
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.*
import com.hortus1556.exilum.R
import com.hortus1556.exilum.db.entity.PlaylistEntity
import com.hortus1556.exilum.ui.theme.AppTheme

@Composable
internal fun DeletePlaylistDialog(
    playlist: PlaylistEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AppAlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(imageVector = Icons.Default.DeleteForever, contentDescription = null)
        },
        title = { Text(text = stringResource(R.string.dialog_title_delete_playlist)) },
        text = {
            Text(text = stringResource(R.string.dialog_message_delete_playlist, playlist.name))
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                content = { Text(text = stringResource(R.string.delete)) }
            )
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                content = { Text(text = stringResource(R.string.cancel)) }
            )
        }
    )
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            DeletePlaylistDialog(
                playlist = PlaylistEntity(name = "Amiga Tunes"),
                onDismiss = {},
                onConfirm = {}
            )
        }
    }
}
