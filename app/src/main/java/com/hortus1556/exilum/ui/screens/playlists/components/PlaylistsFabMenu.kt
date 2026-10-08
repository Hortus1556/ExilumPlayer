package com.hortus1556.exilum.ui.screens.playlists.components

import com.hortus1556.exilum.ui.liquid.LocalLiquidBars
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.liquid.LiquidMenuAction
import com.hortus1556.exilum.ui.liquid.LiquidFabMenu
import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.*
import com.hortus1556.exilum.R
import com.hortus1556.exilum.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlaylistsFabMenu(
    modifier: Modifier = Modifier,
    expanded: Boolean,
    onExpand: () -> Unit,
    onImportZxtune: () -> Unit,
    onNewPlaylist: () -> Unit
) {
    if (isLiquid) {
        // Folds into the tab bar's folded row with it, as the browser's does.
        val bars = LocalLiquidBars.current
        LiquidFabMenu(
            modifier = modifier,
            fold = { bars?.fold?.value ?: 0f },
            expanded = expanded,
            onExpand = onExpand,
            icon = Icons.AutoMirrored.Filled.PlaylistPlay,
            actions = listOf(
                LiquidMenuAction(stringResource(R.string.fab_import_zxtune), Icons.Default.Download, onImportZxtune),
                LiquidMenuAction(stringResource(R.string.fab_new_playlist), Icons.Default.Add, onNewPlaylist),
            ),
        )
        return
    }
    FloatingActionButtonMenu(
        modifier = modifier.offset(x = 16.dp, y = 16.dp),
        expanded = expanded,
        button = {
            FloatingActionButton(
                onClick = onExpand,
                shape = MaterialTheme.shapes.small,
                content = {
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Default.PlaylistRemove
                        } else {
                            Icons.AutoMirrored.Filled.PlaylistPlay
                        },
                        contentDescription = null,
                    )
                }
            )
        },
        content = {
            FloatingActionButtonMenuItem(
                onClick = onImportZxtune,
                text = { Text(text = stringResource(R.string.fab_import_zxtune)) },
                icon = { Icon(imageVector = Icons.Default.Download, contentDescription = null) }
            )
            FloatingActionButtonMenuItem(
                onClick = onNewPlaylist,
                text = { Text(text = stringResource(R.string.fab_new_playlist)) },
                icon = { Icon(imageVector = Icons.Default.Add, contentDescription = null) }
            )
        },
    )
}

private class PlaylistsPreviewParameter : PreviewParameterProvider<Boolean> {
    override val values = sequenceOf(false, true)
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Preview
@Composable
private fun Preview(
    @PreviewParameter(PlaylistsPreviewParameter::class) expanded: Boolean
) {
    AppTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            floatingActionButton = {
                PlaylistsFabMenu(
                    expanded = expanded,
                    onExpand = {},
                    onImportZxtune = {},
                    onNewPlaylist = {},
                )
            },
            content = {}
        )
    }
}
