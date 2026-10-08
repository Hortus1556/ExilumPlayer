package com.hortus1556.exilum.ui.screens.downloads.screen

import com.hortus1556.exilum.ui.components.FileTypeMarks
import com.hortus1556.exilum.ui.liquid.liquidPageContainer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.hortus1556.exilum.ui.components.AppTopAppBar
import com.hortus1556.exilum.ui.components.appContentWindowInsets
import com.hortus1556.exilum.ui.components.rememberAppBarScrollBehavior
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.hortus1556.exilum.R
import com.hortus1556.exilum.model.Item
import com.hortus1556.exilum.model.Module
import com.hortus1556.exilum.model.ResultItem
import com.hortus1556.exilum.ui.components.BackButton
import com.hortus1556.exilum.ui.components.groupedListCard
import com.hortus1556.exilum.ui.components.MessageBox
import com.hortus1556.exilum.ui.components.ProgressbarIndicator
import com.hortus1556.exilum.ui.screens.downloads.DownloadSearchState
import com.hortus1556.exilum.ui.screens.downloads.DownloadsViewModel
import com.hortus1556.exilum.ui.screens.downloads.SearchResultType
import com.hortus1556.exilum.ui.screens.downloads.SearchType
import com.hortus1556.exilum.ui.screens.downloads.components.DownloadListItem
import com.hortus1556.exilum.ui.theme.AppTheme
import kotlinx.coroutines.flow.flowOf
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun DownloadResultScreen(
    searchType: SearchType,
    query: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onModuleClick: (Int) -> Unit
) {
    val viewModel = koinViewModel<DownloadsViewModel>()
    val state by viewModel.searchState.collectAsStateWithLifecycle()
    val pagingItems = viewModel.pagingData.collectAsLazyPagingItems()

    LaunchedEffect(Unit) {
        if (searchType == SearchType.ARTIST) {
            viewModel.searchArtist(query)
        } else {
            viewModel.searchFileOrTitle(query)
        }
    }

    DownloadScreenContent(
        modifier = modifier,
        state = state,
        pagingItems = pagingItems,
        onBack = onBack,
        onModuleClick = onModuleClick,
        onArtistClick = viewModel::getArtistById,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DownloadScreenContent(
    state: DownloadSearchState,
    pagingItems: LazyPagingItems<ResultItem>,
    onBack: () -> Unit,
    onModuleClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onArtistClick: (Int) -> Unit
) {
    val noResultsText = when (state.activeType) {
        SearchResultType.ARTISTS -> stringResource(R.string.message_no_artists)
        else -> stringResource(R.string.message_no_modules)
    }

    val appBarScroll = rememberAppBarScrollBehavior()
    Scaffold(
        containerColor = liquidPageContainer(),
        modifier = modifier.nestedScroll(appBarScroll.nestedScrollConnection),
        contentWindowInsets = appContentWindowInsets(),
        topBar = {
            AppTopAppBar(
                scrollBehavior = appBarScroll,
                title = {
                    Text(
                        text = state.title.ifBlank { stringResource(R.string.results) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = { BackButton(onBack = onBack) },
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when {
                pagingItems.loadState.refresh is LoadState.Loading -> ProgressbarIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )

                // Wowsie!
                pagingItems.loadState.refresh is LoadState.Error ||
                    (pagingItems.itemCount == 0 && state.activeType != SearchResultType.NONE) -> {
                    val refreshError = pagingItems.loadState.refresh as? LoadState.Error
                    MessageBox(
                        text = refreshError?.error?.message ?: noResultsText,
                        actions = {
                            // Only where something went wrong: asking again for
                            // a search that found nothing finds nothing again.
                            if (refreshError != null) {
                                IconButton(onClick = { pagingItems.retry() }) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = stringResource(R.string.retry)
                                    )
                                }
                            }
                            TextButton(onClick = onBack) {
                                Text(text = stringResource(R.string.desc_back_button))
                            }
                        }
                    )
                }

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // One card for what was found, as the browser's lists are.
                    val count = pagingItems.itemCount
                    items(
                        count = count,
                        key = pagingItems.itemKey { it.id }
                    ) { idx ->
                        when (val item = pagingItems[idx]) {
                            is Module -> DownloadListItem(
                                modifier = Modifier.groupedListCard(idx, count, dividerStart = FileTypeMarks.RowTextStart),
                                text = item.songtitle,
                                supportingText = item.artist,
                                leadingText = item.format,
                                trailingText = stringResource(R.string.size_kb, item.sizeKb),
                                onClick = { onModuleClick(item.id) }
                            )

                            is Item -> DownloadListItem(
                                modifier = Modifier.groupedListCard(idx, count, dividerStart = 16.dp),
                                text = item.alias,
                                onClick = { onArtistClick(item.id) }
                            )
                        }
                    }
                    if (pagingItems.loadState.append is LoadState.Loading) {
                        item {
                            LoadingIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            )
                        }
                    }
                    // A page that failed to load used to end the list without
                    // a word. Paging does not ask for it again until it is
                    // told to, however far the list is scrolled, so the
                    // results looked complete and the rest could only be had
                    // by searching again. The row says what happened, and a
                    // tap on it asks once more.
                    val appendError = pagingItems.loadState.append as? LoadState.Error
                    if (appendError != null) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = pagingItems::retry)
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = appendError.error.message
                                        ?: appendError.error.toString(),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.retry)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun PreviewModules() {
    val items = flowOf(PagingData.from<ResultItem>(Array(10) { Module(id = it) }.toList()))
    AppTheme {
        DownloadScreenContent(
            state = DownloadSearchState(
                title = "Results: foo",
                activeType = SearchResultType.MODULES
            ),
            pagingItems = items.collectAsLazyPagingItems(),
            onBack = {},
            onModuleClick = {},
            onArtistClick = {},
        )
    }
}

@Preview
@Composable
private fun PreviewArtists() {
    val items = flowOf(PagingData.from<ResultItem>(Array(10) { Item(id = it) }.toList()))
    AppTheme {
        DownloadScreenContent(
            state = DownloadSearchState(
                title = "Artists: foo",
                activeType = SearchResultType.ARTISTS
            ),
            pagingItems = items.collectAsLazyPagingItems(),
            onBack = {},
            onModuleClick = {},
            onArtistClick = {},
        )
    }
}
