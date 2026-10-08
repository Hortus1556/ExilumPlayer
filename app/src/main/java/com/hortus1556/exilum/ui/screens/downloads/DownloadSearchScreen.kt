package com.hortus1556.exilum.ui.screens.downloads

import com.hortus1556.exilum.ui.liquid.liquidPageContainer
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.text.input.rememberTextFieldState
import com.hortus1556.exilum.ui.liquid.LocalLiquidColors
import com.hortus1556.exilum.ui.liquid.LiquidType
import com.hortus1556.exilum.ui.liquid.LiquidSegmentedControl
import com.hortus1556.exilum.ui.liquid.LiquidSearchField
import com.hortus1556.exilum.ui.liquid.LiquidFillButton
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.theme.isOneUi
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.hortus1556.exilum.ui.components.AppTopAppBar
import com.hortus1556.exilum.ui.components.appContentWindowInsets
import com.hortus1556.exilum.ui.components.rememberAppBarScrollBehavior
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.hortus1556.exilum.R
import com.hortus1556.exilum.core.Constants
import com.hortus1556.exilum.ui.components.BackButton
import com.hortus1556.exilum.ui.theme.AppTheme
import com.hortus1556.exilum.util.annotatedLinkString
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DownloadSearchScreen(
    hasApiKey: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSearch: (String, SearchType) -> Unit,
    onRandom: () -> Unit,
    modifier: Modifier = Modifier,
    onHistory: () -> Unit
) {
    val resource = LocalResources.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(SearchType.TITLE) }
    var hasInteracted by rememberSaveable { mutableStateOf(false) }

    // The field no longer takes the focus by itself. That was for a page that
    // was opened to be typed into; this one is a tab, composed at launch with
    // every other section, so half a second into the app the keyboard came up
    // over the file browser - and again on every way back from the results.

    val appBarScroll = rememberAppBarScrollBehavior()
    // iOS: no floating button or credits bar - the search is a button on the
    // page, and the credits are the page's footnote.
    val liquid = isLiquid
    val submit: () -> Unit = {
        scope.launch {
            if (!hasApiKey) {
                snackbarHostState.showSnackbar(message = resource.getString(R.string.snack_no_api))
            } else if (query.length < 3) {
                snackbarHostState.showSnackbar(message = resource.getString(R.string.snack_query_too_short))
            } else {
                onSearch(query, type)
                focusManager.clearFocus()
            }
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
                title = { Text(text = stringResource(R.string.title_download)) },
                // A tab of its own under One UI and iOS, with nothing to go back to.
                navigationIcon = { if (!isOneUi && !isLiquid) BackButton(onBack = onBack) }
            )
        },
        floatingActionButton = {
            if (!liquid) ExtendedFloatingActionButton(
                shape = MaterialTheme.shapes.small,
                onClick = {
                    scope.launch {
                        if (!hasApiKey) {
                            snackbarHostState.showSnackbar(
                                message = resource.getString(R.string.snack_no_api)
                            )
                        } else if (query.length < 3) {
                            snackbarHostState.showSnackbar(
                                message = resource.getString(R.string.snack_query_too_short)
                            )
                        } else {
                            onSearch(query, type)
                            focusManager.clearFocus()
                        }
                    }
                },
                text = { Text(text = stringResource(R.string.search)) },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.desc_search_button)
                    )
                }
            )
        },
        bottomBar = {
            if (!liquid) ShortNavigationBar(
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding(),
                content = {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                        content = {
                            Text(
                                text = annotatedLinkString(
                                    text = stringResource(R.string.search_api_credits),
                                    url = Constants.TMA_BASE_URL,
                                ),
                            )
                        }
                    )
                }
            )
        }
    ) { padding ->
        if (liquid) {
            LiquidSearchPage(
                padding = padding,
                query = query,
                onQuery = {
                    query = it
                    hasInteracted = true
                },
                type = type,
                onType = { type = it },
                hasApiKey = hasApiKey,
                onSubmit = submit,
                onRandom = onRandom,
                onHistory = onHistory,
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
            content = {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    enabled = hasApiKey,
                    value = query,
                    onValueChange = {
                        query = it
                        hasInteracted = true
                    },
                    isError = hasInteracted && query.length < 3,
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.search)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (query.length >= 3) {
                                onSearch(query, type)
                                focusManager.clearFocus()
                            } else {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = resource.getString(R.string.snack_query_too_short)
                                    )
                                }
                            }
                        }
                    ),
                )

                Spacer(modifier = Modifier.height(24.dp))

                ButtonGroup(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    expandedRatio = .20f,
                    overflowIndicator = {},
                    content = {
                        toggleableItem(
                            checked = type == SearchType.TITLE,
                            enabled = hasApiKey,
                            label = resource.getString(R.string.search_selection_title),
                            onCheckedChange = { type = SearchType.TITLE },
                            weight = 1f,
                        )
                        toggleableItem(
                            checked = type == SearchType.ARTIST,
                            enabled = hasApiKey,
                            label = resource.getString(R.string.search_selection_artist),
                            onCheckedChange = { type = SearchType.ARTIST },
                            weight = 1f,
                        )
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                ButtonGroup(
                    overflowIndicator = {},
                    content = {
                        customItem(
                            buttonGroupContent = {
                                OutlinedButton(
                                    onClick = onRandom,
                                    shape = MaterialTheme.shapes.small,
                                    enabled = hasApiKey,
                                    modifier = Modifier.weight(1f),
                                    content = {
                                        Icon(
                                            imageVector = Icons.Default.Shuffle,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(text = stringResource(R.string.random))
                                    }
                                )
                            },
                            menuContent = {}
                        )
                        customItem(
                            buttonGroupContent = {
                                OutlinedButton(
                                    onClick = onHistory,
                                    shape = MaterialTheme.shapes.small,
                                    content = {
                                        Icon(
                                            imageVector = Icons.Default.History,
                                            contentDescription = null,
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(text = stringResource(R.string.history))
                                    }
                                )
                            },
                            menuContent = {}
                        )
                    }
                )
            }
        )
    }
}

/**
 * The search page as iOS lays it out: the search field, what to search by as
 * a segmented control, the search itself as the page's one prominent button
 * with the lesser two gray beside each other under it, and the credits as the
 * footnote.
 */
@Composable
private fun LiquidSearchPage(
    padding: PaddingValues,
    query: String,
    onQuery: (String) -> Unit,
    type: SearchType,
    onType: (SearchType) -> Unit,
    hasApiKey: Boolean,
    onSubmit: () -> Unit,
    onRandom: () -> Unit,
    onHistory: () -> Unit,
) {
    val colors = LocalLiquidColors.current
    val field = rememberTextFieldState(query)
    LaunchedEffect(field) {
        snapshotFlow { field.text.toString() }.collect { if (it != query) onQuery(it) }
    }
    Column(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LiquidSearchField(
            state = field,
            placeholder = stringResource(R.string.search),
            clearDescription = stringResource(R.string.desc_search_clear),
            enabled = hasApiKey,
            onSearch = onSubmit,
        )
        LiquidSegmentedControl(
            options = listOf(
                stringResource(R.string.search_selection_title),
                stringResource(R.string.search_selection_artist),
            ),
            selected = if (type == SearchType.ARTIST) 1 else 0,
            onSelect = { onType(if (it == 1) SearchType.ARTIST else SearchType.TITLE) },
            enabled = hasApiKey,
        )
        LiquidFillButton(
            text = stringResource(R.string.search),
            icon = Icons.Default.Search,
            onClick = onSubmit,
            prominent = true,
            enabled = hasApiKey,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LiquidFillButton(
                text = stringResource(R.string.random),
                icon = Icons.Default.Shuffle,
                onClick = onRandom,
                enabled = hasApiKey,
                modifier = Modifier.weight(1f),
            )
            LiquidFillButton(
                text = stringResource(R.string.history),
                icon = Icons.Default.History,
                onClick = onHistory,
                modifier = Modifier.weight(1f),
            )
        }
        CompositionLocalProvider(LocalContentColor provides colors.secondaryLabel) {
            ProvideTextStyle(LiquidType.Footnote.copy(color = colors.secondaryLabel)) {
                Text(
                    text = annotatedLinkString(
                        text = stringResource(R.string.search_api_credits),
                        url = Constants.TMA_BASE_URL,
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        DownloadSearchScreen(
            hasApiKey = true,
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onSearch = { _, _ -> },
            onRandom = {},
            onHistory = {},
        )
    }
}
