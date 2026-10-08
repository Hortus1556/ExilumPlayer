package com.hortus1556.exilum.ui.screens.favourites

import com.hortus1556.exilum.ui.components.FileTypeMarks
import com.hortus1556.exilum.ui.components.FileTypeSlot
import com.hortus1556.exilum.ui.liquid.liquidPageContainer
import androidx.compose.runtime.CompositionLocalProvider
import com.hortus1556.exilum.ui.liquid.scrollPadding
import com.hortus1556.exilum.ui.liquid.pageSides
import com.hortus1556.exilum.ui.liquid.liquidPage
import com.hortus1556.exilum.ui.liquid.rememberLiquidPageBackdrop
import com.hortus1556.exilum.ui.liquid.LocalLiquidBackdrop
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.theme.isOneUi
import com.hortus1556.exilum.ui.components.withoutBottom
import com.hortus1556.exilum.ui.components.listItemColorsOnCard
import com.hortus1556.exilum.ui.components.groupedListCard
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.hortus1556.exilum.ui.components.AppTopAppBar
import com.hortus1556.exilum.ui.components.appContentWindowInsets
import com.hortus1556.exilum.ui.components.rememberAppBarScrollBehavior
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hortus1556.exilum.R
import com.hortus1556.exilum.data.defaultSubsongLabel
import com.hortus1556.exilum.data.subsongKey
import com.hortus1556.exilum.db.entity.FavouriteEntity
import com.hortus1556.exilum.player.PlayerViewModel
import com.hortus1556.exilum.ui.components.BackButton
import com.hortus1556.exilum.ui.components.MessageBox
import com.hortus1556.exilum.ui.components.ProgressbarIndicator
import com.hortus1556.exilum.ui.components.RenameSubsongDialog
import com.hortus1556.exilum.ui.theme.AppTheme
import org.koin.compose.viewmodel.koinViewModel

/**
 * Section host, mirroring NavPlaylists: resolves the view models and leaves
 * the screen itself stateless. The mini player belongs to the tab bar now, so
 * this no longer watches playback state at all.
 */
@Composable
fun NavFavourites(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onNavigateToPlayer: () -> Unit,
) {
    val viewModel = koinViewModel<FavouritesViewModel>()
    val playerViewModel = koinViewModel<PlayerViewModel>(
        viewModelStoreOwner = LocalActivity.current as ComponentActivity
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    FavouritesScreen(
        modifier = modifier,
        state = state,
        onBack = onBack,
        // Neither of these opens the player: see FileBrowserScreen.
        onPlay = { index -> viewModel.playAll(playerViewModel, index) },
        onShuffle = { viewModel.playAll(playerViewModel, 0, shuffle = true) },
        onRemove = viewModel::remove,
        onRename = viewModel::renameSubsong,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavouritesScreen(
    state: FavouritesUiState,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit,
    onRemove: (FavouriteEntity) -> Unit,
    modifier: Modifier = Modifier,
    onRename: (FavouriteEntity, String) -> Unit = { _, _ -> },
) {
    var renaming by remember { mutableStateOf<FavouriteEntity?>(null) }
    renaming?.let { item ->
        RenameSubsongDialog(
            initialName = state.names[subsongKey(item.uri, item.subsong)].orEmpty(),
            placeholder = defaultSubsongLabel(item.subsong),
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                onRename(item, name)
            },
        )
    }

    val appBarScroll = rememberAppBarScrollBehavior()
    // Under Liquid Glass the page is what its glass bends.
    val pageBackdrop = rememberLiquidPageBackdrop()
    CompositionLocalProvider(LocalLiquidBackdrop provides pageBackdrop) {
    Scaffold(
        containerColor = liquidPageContainer(),
        modifier = modifier.nestedScroll(appBarScroll.nestedScrollConnection),
        contentWindowInsets = appContentWindowInsets(),
        topBar = {
            AppTopAppBar(
                scrollBehavior = appBarScroll,
                title = { Text(text = stringResource(R.string.nav_favourites)) },
                // A tab of its own under One UI and iOS, with nothing to go back to.
                navigationIcon = { if (!isOneUi && !isLiquid) BackButton(onBack = onBack) },
                actions = {
                    if (state.favourites.isNotEmpty()) {
                        IconButton(
                            onClick = onShuffle,
                            content = {
                                Icon(
                                    imageVector = Icons.Default.Shuffle,
                                    contentDescription = stringResource(R.string.desc_shuffle_favourites),
                                )
                            }
                        )
                    }
                },
            )
        },
        content = { padding ->
            Crossfade(targetState = state.isLoading, label = "favourites") { loading ->
                when {
                    loading -> ProgressbarIndicator()

                    state.favourites.isEmpty() -> MessageBox(
                        text = stringResource(R.string.message_no_favourites),
                    )

                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding.pageSides())
                            .liquidPage(pageBackdrop),
                        contentPadding = padding.scrollPadding(),
                        content = {
                            itemsIndexed(
                                items = state.favourites,
                                key = { _, item -> subsongKey(item.uri, item.subsong) },
                            ) { index, item ->
                                // A saved subsong leads with its own name, and
                                // the file it is in goes underneath.
                                val isSubsong = item.subsong >= 0
                                val subsongName = if (isSubsong) {
                                    state.names[subsongKey(item.uri, item.subsong)]
                                        ?: defaultSubsongLabel(item.subsong)
                                } else {
                                    null
                                }
                                ListItem(
                                    modifier = Modifier
                                        // The list item puts 16 points after its leading slot, not 12.
                                        .groupedListCard(index, state.favourites.size, dividerStart = FileTypeMarks.RowTextStart + 4.dp)
                                        .clickable { onPlay(index) },
                                    colors = listItemColorsOnCard(),
                                    headlineContent = {
                                        Text(
                                            text = subsongName ?: item.name,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            text = if (isSubsong) {
                                                "${item.name} \u00B7 ${item.extension}"
                                            } else {
                                                item.extension
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    leadingContent = {
                                        FileTypeSlot(remember(item.extension) { FileTypeMarks.of(item.extension) })
                                    },
                                    trailingContent = {
                                        Row {
                                            if (isSubsong) {
                                                IconButton(
                                                    onClick = { renaming = item },
                                                    content = {
                                                        Icon(
                                                            imageVector = Icons.Default.Edit,
                                                            contentDescription = stringResource(
                                                                R.string.desc_rename_subsong
                                                            ),
                                                        )
                                                    }
                                                )
                                            }
                                            IconButton(
                                                onClick = { onRemove(item) },
                                                content = {
                                                    Icon(
                                                        imageVector = Icons.Default.StarBorder,
                                                        contentDescription = stringResource(
                                                            R.string.desc_remove_favourite
                                                        ),
                                                    )
                                                }
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    )
                }
            }
        },
    )
    }
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        FavouritesScreen(
            state = FavouritesUiState(isLoading = false),
            onBack = {},
            onPlay = {},
            onShuffle = {},
            onRemove = {},
        )
    }
}
