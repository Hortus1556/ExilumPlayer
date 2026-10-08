package com.hortus1556.exilum.ui.screens.downloads.screen

import com.hortus1556.exilum.ui.liquid.liquidPageContainer
import com.hortus1556.exilum.ui.components.AppAlertDialog
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.hortus1556.exilum.ui.components.AppTopAppBar
import com.hortus1556.exilum.ui.components.appContentWindowInsets
import com.hortus1556.exilum.ui.components.rememberAppBarScrollBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hortus1556.exilum.R
import com.hortus1556.exilum.model.Module
import com.hortus1556.exilum.model.ModuleResult
import com.hortus1556.exilum.ui.components.BackButton
import com.hortus1556.exilum.ui.components.MessageBox
import com.hortus1556.exilum.ui.components.ProgressbarIndicator
import com.hortus1556.exilum.ui.screens.downloads.DownloadStatus
import com.hortus1556.exilum.ui.screens.downloads.DownloadsViewModel
import com.hortus1556.exilum.ui.screens.downloads.ModuleResultState
import com.hortus1556.exilum.ui.screens.downloads.components.ModuleDetailLayout
import com.hortus1556.exilum.ui.liquid.CapsuleShape
import com.hortus1556.exilum.ui.liquid.LiquidFillButton
import com.hortus1556.exilum.ui.liquid.LocalLiquidColors
import com.hortus1556.exilum.ui.theme.AppTheme
import com.hortus1556.exilum.ui.theme.isLiquid
import androidx.compose.ui.draw.clip
import com.hortus1556.exilum.util.shareLink
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun DownloadModuleScreen(
    moduleId: Int,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (Module) -> Unit
) {
    val viewModel = koinViewModel<DownloadsViewModel>()

    val state by viewModel.moduleResultState.collectAsStateWithLifecycle()

    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Only fetch if we don't already have a module loaded
        // or if the requested ID differs from what's currently loaded
        if (state.result == null || (moduleId >= 0 && state.result?.module?.id != moduleId)) {
            viewModel.getModuleById(moduleId)
        }
    }

    // A download that failed used to say nothing at all: the button went back
    // to Download as if it had never been pressed, whether the network was
    // gone, no folder was chosen or the file could not be made. It goes to the
    // host the sections share rather than one of this page's own, so it can
    // be read from another tab the user has moved to meanwhile.
    val downloadError = (state.downloadStatus as? DownloadStatus.Error)?.message
    LaunchedEffect(downloadError) {
        if (downloadError != null) {
            snackbarHostState.showSnackbar(message = downloadError)
            viewModel.downloadErrorShown()
        }
    }

    if (showDeleteDialog) {
        AppAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text(text = stringResource(R.string.dialog_title_delete_module)) },
            text = {
                val name = state.result?.module?.filename
                    .orEmpty()
                    .ifEmpty { stringResource(R.string.untitled) }
                Text(text = stringResource(R.string.dialog_message_delete_module, name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.result?.module?.let { viewModel.deleteModule(it) }
                        showDeleteDialog = false
                    },
                    content = { Text(text = stringResource(R.string.delete)) }
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    content = { Text(text = stringResource(R.string.cancel)) }
                )
            }
        )
    }

    DownloadModuleContent(
        modifier = modifier,
        state = state,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onShowDialog = { showDeleteDialog = it },
        onPlay = onPlay,
        onDownloadModule = viewModel::downloadModule,
        onRandomModule = viewModel::getRandomModule,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadModuleContent(
    state: ModuleResultState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onShowDialog: (Boolean) -> Unit,
    onDownloadModule: (Module) -> Unit,
    onRandomModule: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (Module) -> Unit
) {
    val resource = LocalResources.current
    val appBarScroll = rememberAppBarScrollBehavior()
    val liquid = isLiquid
    // What the page's two buttons say and whether they can be pressed: the
    // same in every style, which draws them its own way.
    val module = state.result?.module
    val isDownloading = state.downloadStatus is DownloadStatus.Loading ||
        state.downloadStatus is DownloadStatus.Progress
    val buttonLabel = when {
        state.isLoading -> stringResource(R.string.loading)

        isDownloading -> when (val s = state.downloadStatus) {
            is DownloadStatus.Progress -> "%.0f%%".format(s.percent)
            else -> stringResource(R.string.downloading)
        }

        state.moduleExists -> stringResource(R.string.play)

        module?.isSupported == false -> stringResource(R.string.unsupported)

        else -> stringResource(R.string.download)
    }
    // Nothing to download or play where no module came back: the button was live there, and did nothing.
    val canAct = module != null && !state.isLoading && !isDownloading && module.isSupported
    val canRandom = !state.isLoading && !isDownloading
    val onAct: () -> Unit = {
        if (state.moduleExists) {
            module?.let { onPlay(it) }
        } else {
            module?.let { onDownloadModule(it) }
        }
    }
    Scaffold(
        containerColor = liquidPageContainer(),
        modifier = modifier.nestedScroll(appBarScroll.nestedScrollConnection),
        contentWindowInsets = appContentWindowInsets(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopAppBar(
                scrollBehavior = appBarScroll,
                title = {
                    Text(
                        text = if (state.isRandom) {
                            stringResource(R.string.title_download_random)
                        } else {
                            stringResource(R.string.title_download_module)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton(onBack = onBack) },
                actions = {
                    if (state.moduleExists) {
                        IconButton(
                            onClick = { onShowDialog(true) },
                            content = {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null
                                )
                            }
                        )
                    }
                    state.result?.module?.infopage?.let { url ->
                        val context = LocalContext.current
                        if (url.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    val module = state.result.module
                                    fun Module.shareText() = resource.getString(
                                        R.string.module_download_share,
                                        songtitle.ifBlank { filename },
                                        artist,
                                        infopage
                                    )
                                    context.shareLink(message = module.shareText())
                                },
                                content = {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                    }
                }
            )
        },
        content = { padding ->
          Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
          ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
                content = {
                    if (state.isLoading) ProgressbarIndicator()

                    state.softError?.let {
                        MessageBox(
                            text = it,
                            actions = {
                                TextButton(
                                    onClick = onBack,
                                    content = {
                                        Text(text = stringResource(R.string.desc_back_button))
                                    }
                                )
                            }
                        )
                    }

                    state.result?.let { result ->
                        ModuleDetailLayout(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            moduleResult = result,
                        )
                    }
                }
            )
            // The page's own, at the end of what it shows, and not a bottom
            // bar: the tab bar and the mini player float over the foot of the
            // screen in both styles, and a bar down there was under them -
            // an opaque slab under Liquid Glass, and under One UI the two
            // buttons themselves, out of sight and out of reach.
            if (liquid) {
                LiquidModuleActions(
                    label = buttonLabel,
                    canAct = canAct,
                    canRandom = canRandom,
                    status = state.downloadStatus,
                    onAct = onAct,
                    onRandom = onRandomModule,
                )
            } else {
                OneUiModuleActions(
                    label = buttonLabel,
                    canAct = canAct,
                    canRandom = canRandom,
                    status = state.downloadStatus,
                    onAct = onAct,
                    onRandom = onRandomModule,
                )
            }
          }
        }
    )
}

/** The page's two buttons under One UI: Material's own, which its theme draws, with the download's progress over them. */
@Composable
private fun OneUiModuleActions(
    label: String,
    canAct: Boolean,
    canRandom: Boolean,
    status: DownloadStatus,
    onAct: () -> Unit,
    onRandom: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when (status) {
            is DownloadStatus.Progress -> LinearProgressIndicator(
                progress = { status.percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )

            is DownloadStatus.Loading -> LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
            )

            else -> Spacer(modifier = Modifier.height(4.dp)) // keep layout stable
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.small,
                enabled = canAct,
                onClick = onAct,
                content = { Text(text = label) }
            )
            OutlinedButton(
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.small,
                enabled = canRandom,
                onClick = onRandom,
                content = { Text(text = stringResource(R.string.random)) }
            )
        }
    }
}

/**
 * The page's two buttons as iOS draws them - the one thing the page is for
 * in the tint, the other gray beside it - with the download's progress as a
 * line over them while there is one.
 */
@Composable
private fun LiquidModuleActions(
    label: String,
    canAct: Boolean,
    canRandom: Boolean,
    status: DownloadStatus,
    onAct: () -> Unit,
    onRandom: () -> Unit,
) {
    val colors = LocalLiquidColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (status) {
            is DownloadStatus.Progress -> LinearProgressIndicator(
                progress = { status.percent / 100f },
                modifier = Modifier.fillMaxWidth().clip(CapsuleShape),
                color = colors.tint,
                trackColor = colors.tertiaryFill,
            )

            is DownloadStatus.Loading -> LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().clip(CapsuleShape),
                color = colors.tint,
                trackColor = colors.tertiaryFill,
            )

            else -> Unit
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LiquidFillButton(
                text = label,
                onClick = onAct,
                prominent = true,
                enabled = canAct,
                modifier = Modifier.weight(1f),
            )
            LiquidFillButton(
                text = stringResource(R.string.random),
                icon = Icons.Default.Shuffle,
                onClick = onRandom,
                enabled = canRandom,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private class DownloadModulePreviewParameter : PreviewParameterProvider<ModuleResultState> {
    private val sampleModule = ModuleResult(
        module = Module(
            filename = "alpharapii.mod",
            songtitle = "alpharapii",
            format = "MOD",
            bytes = 45678,
            infopage = "website"
        )
    )

    override val values = sequenceOf(
        ModuleResultState(isLoading = true),
        ModuleResultState(isRandom = true, result = sampleModule),
        ModuleResultState(result = sampleModule),
        ModuleResultState(result = sampleModule, moduleExists = true),
        ModuleResultState(result = sampleModule, downloadStatus = DownloadStatus.Loading),
        ModuleResultState(result = sampleModule, downloadStatus = DownloadStatus.Progress(66f)),
        ModuleResultState(result = sampleModule, downloadStatus = DownloadStatus.Success),
        ModuleResultState(softError = "Could not fetch module."),
    )
}

@Preview
@Composable
private fun Preview(
    @PreviewParameter(DownloadModulePreviewParameter::class) state: ModuleResultState
) {
    AppTheme {
        DownloadModuleContent(
            state = state,
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onShowDialog = {},
            onDownloadModule = {},
            onRandomModule = {},
            onPlay = {},
        )
    }
}
