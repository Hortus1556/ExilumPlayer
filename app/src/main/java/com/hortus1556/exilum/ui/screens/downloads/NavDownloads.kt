package com.hortus1556.exilum.ui.screens.downloads

import androidx.compose.runtime.remember
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.components.pagePredictivePopTransitionSpec
import com.hortus1556.exilum.ui.components.pagePopTransitionSpec
import com.hortus1556.exilum.ui.components.pagePushTransitionSpec
import com.hortus1556.exilum.ui.liquid.rememberLiquidPageDecorator
import com.hortus1556.exilum.ui.liquid.LiquidNavDirection
import com.hortus1556.exilum.ui.components.popTransitionSpec
import com.hortus1556.exilum.ui.components.predictivePopTransitionSpec
import com.hortus1556.exilum.ui.components.pushTransitionSpec
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.hortus1556.exilum.BuildConfig
import com.hortus1556.exilum.db.entity.ModuleEntity
import com.hortus1556.exilum.player.PlayerViewModel
import com.hortus1556.exilum.ui.screens.downloads.screen.DownloadHistoryScreen
import com.hortus1556.exilum.ui.screens.downloads.screen.DownloadModuleScreen
import com.hortus1556.exilum.ui.screens.downloads.screen.DownloadResultScreen
import com.hortus1556.exilum.util.findDownloadedModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import com.hortus1556.exilum.ui.popOrKeepRoot
import com.hortus1556.exilum.ui.pushOnce

private sealed interface NavKeyDownload : NavKey {
    @Serializable data object Search : NavKeyDownload

    @Serializable data object History : NavKeyDownload

    @Serializable data class SearchResult(val query: String, val type: SearchType) : NavKeyDownload

    @Serializable data class Module(val moduleId: Int) : NavKeyDownload
}

@Composable
fun NavDownloads(
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToPlayer: () -> Unit
) {
    val viewModel: PlayerViewModel = koinViewModel(
        viewModelStoreOwner = LocalActivity.current as ComponentActivity
    )

    val backStack = rememberNavBackStack(NavKeyDownload.Search)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val hasApiKey = remember { BuildConfig.API_KEY.isNotBlank() }

    BackHandler(enabled = backStack.size > 1) {
        backStack.popOrKeepRoot()
    }

    // Under Liquid Glass the pages move themselves, so each can hold its
    // bar still while it slides, as iOS's navigation bar stays put.
    val direction = remember { LiquidNavDirection() }
    val pageDecorator = rememberLiquidPageDecorator<NavKey>(direction)
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.popOrKeepRoot() },
        transitionSpec = pagePushTransitionSpec(direction),
        popTransitionSpec = pagePopTransitionSpec(direction),
        predictivePopTransitionSpec = pagePredictivePopTransitionSpec(direction),
        entryDecorators = listOfNotNull(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
            pageDecorator.takeIf { isLiquid },
        ),
        entryProvider = entryProvider {
            entry<NavKeyDownload.Search> {
                DownloadSearchScreen(
                    modifier = modifier,
                    hasApiKey = hasApiKey,
                    snackbarHostState = snackbarHostState,
                    onBack = onBack,
                    onSearch = { query, type ->
                        backStack.pushOnce(NavKeyDownload.SearchResult(query, type))
                    },
                    onRandom = { backStack.pushOnce(NavKeyDownload.Module(-1)) },
                    onHistory = { backStack.pushOnce(NavKeyDownload.History) },
                )
            }

            entry<NavKeyDownload.History> {
                DownloadHistoryScreen(
                    modifier = modifier,
                    onBack = { backStack.popOrKeepRoot() },
                    onModuleClick = { backStack.pushOnce(NavKeyDownload.Module(it)) },
                )
            }

            entry<NavKeyDownload.SearchResult> { it ->
                DownloadResultScreen(
                    modifier = modifier,
                    searchType = it.type,
                    query = it.query,
                    onBack = backStack::popOrKeepRoot,
                    onModuleClick = { backStack.pushOnce(NavKeyDownload.Module(it)) },
                )
            }

            entry<NavKeyDownload.Module> {
                DownloadModuleScreen(
                    modifier = modifier,
                    moduleId = it.moduleId,
                    snackbarHostState = snackbarHostState,
                    onBack = { backStack.popOrKeepRoot() },
                    onPlay = { module ->
                        scope.launch(Dispatchers.IO) {
                            val rootUriStr = viewModel.getLastDirectoryUri() ?: return@launch
                            val rootUri = rootUriStr.toUri()
                            val filename = module.url.substringAfterLast('#')

                            // Walk the download dir to find the file URI
                            val fileUri = context.findDownloadedModule(
                                rootUri = rootUri,
                                artist = module.artist,
                                filename = filename
                            ) ?: return@launch

                            val moduleFile = ModuleEntity(
                                filePath = fileUri.toString(),
                                filename = module.songtitle.ifBlank { filename },
                                fileSize = module.bytes.toLong(),
                                fileExtension = filename.substringAfterLast('.', ""),
                            )

                            // Does not open the player: see FileBrowserScreen.
                            withContext(Dispatchers.Main) { viewModel.play(moduleFile) }
                        }
                    },
                )
            }
        }
    )
}
