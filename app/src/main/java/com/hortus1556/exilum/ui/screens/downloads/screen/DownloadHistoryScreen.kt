package com.hortus1556.exilum.ui.screens.downloads.screen

import com.hortus1556.exilum.ui.components.FileTypeMarks
import com.hortus1556.exilum.ui.liquid.liquidPageContainer
import com.hortus1556.exilum.ui.components.AppAlertDialog
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.hortus1556.exilum.ui.components.AppTopAppBar
import com.hortus1556.exilum.ui.components.appContentWindowInsets
import com.hortus1556.exilum.ui.components.rememberAppBarScrollBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hortus1556.exilum.R
import com.hortus1556.exilum.model.Artist
import com.hortus1556.exilum.model.ArtistInfo
import com.hortus1556.exilum.model.Module
import com.hortus1556.exilum.ui.components.BackButton
import com.hortus1556.exilum.ui.components.groupedListCard
import androidx.compose.ui.unit.dp
import com.hortus1556.exilum.ui.components.MessageBox
import com.hortus1556.exilum.ui.screens.downloads.DownloadsViewModel
import com.hortus1556.exilum.ui.screens.downloads.components.DownloadListItem
import com.hortus1556.exilum.ui.theme.AppTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun DownloadHistoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onModuleClick: (Int) -> Unit
) {
    val viewModel = koinViewModel<DownloadsViewModel>()
    val history by viewModel.historyState.collectAsStateWithLifecycle()

    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AppAlertDialog(
            onDismissRequest = { showClearDialog = false },
            icon = { Icon(imageVector = Icons.Default.DeleteForever, contentDescription = null) },
            title = { Text(text = stringResource(R.string.dialog_title_clear_history)) },
            text = { Text(text = stringResource(R.string.dialog_message_clear_history)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearHistory()
                        showClearDialog = false
                    },
                    content = { Text(text = stringResource(R.string.clear)) }
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearDialog = false },
                    content = { Text(text = stringResource(R.string.cancel)) }
                )
            }
        )
    }

    DownloadHistoryContent(
        modifier = modifier,
        history = history,
        onBack = onBack,
        onModuleClick = onModuleClick,
        onShowDialog = { showClearDialog = it },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadHistoryContent(
    history: ImmutableList<Module>,
    onBack: () -> Unit,
    onModuleClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onShowDialog: (Boolean) -> Unit
) {
    val appBarScroll = rememberAppBarScrollBehavior()
    Scaffold(
        containerColor = liquidPageContainer(),
        modifier = modifier.nestedScroll(appBarScroll.nestedScrollConnection),
        contentWindowInsets = appContentWindowInsets(),
        topBar = {
            AppTopAppBar(
                scrollBehavior = appBarScroll,
                title = { Text(text = stringResource(R.string.title_history)) },
                navigationIcon = { BackButton(onBack = onBack) },
                actions = {
                    if (history.isNotEmpty()) {
                        IconButton(
                            onClick = { onShowDialog(true) },
                            content = {
                                Icon(
                                    imageVector = Icons.Default.ClearAll,
                                    contentDescription = stringResource(R.string.desc_clear_history)
                                )
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.Center,
            content = {
                if (history.isEmpty()) {
                    MessageBox(
                        text = stringResource(R.string.message_no_history),
                        actions = {
                            TextButton(
                                onClick = onBack,
                                content = {
                                    Text(text = stringResource(R.string.desc_back_button))
                                }
                            )
                        }
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        content = {
                            itemsIndexed(
                                items = history,
                                itemContent = { index, module ->
                                    DownloadListItem(
                                        modifier = Modifier.groupedListCard(index, history.size, dividerStart = FileTypeMarks.RowTextStart),
                                        text = module.songtitle,
                                        supportingText = module.artist,
                                        leadingText = module.format,
                                        onClick = { onModuleClick(module.id) }
                                    )
                                }
                            )
                        }
                    )
                }
            }
        )
    }
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        DownloadHistoryContent(
            history = Array(10) {
                Module(
                    format = "MOD",
                    songtitle = "Song Title $it",
                    artistInfo = ArtistInfo(artist = listOf(Artist(alias = "Song Artist $it"))),
                    bytes = (it + 1) * 1234,
                )
            }.toPersistentList(),
            onBack = {},
            onModuleClick = {},
            onShowDialog = {},
        )
    }
}
