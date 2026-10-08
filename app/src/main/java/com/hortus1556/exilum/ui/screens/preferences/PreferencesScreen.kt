package com.hortus1556.exilum.ui.screens.preferences

import com.hortus1556.exilum.ui.liquid.liquidPageContainer
import androidx.compose.runtime.CompositionLocalProvider
import com.hortus1556.exilum.ui.liquid.scrollPadding
import com.hortus1556.exilum.ui.liquid.pageSides
import com.hortus1556.exilum.ui.liquid.liquidPage
import com.hortus1556.exilum.ui.liquid.rememberLiquidPageBackdrop
import com.hortus1556.exilum.ui.liquid.LocalLiquidBackdrop
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.liquid.LocalLiquidColors
import com.hortus1556.exilum.ui.liquid.LiquidRow
import com.hortus1556.exilum.ui.liquid.LiquidIconSquare
import com.hortus1556.exilum.ui.liquid.LiquidGroupShape
import com.hortus1556.exilum.ui.liquid.LiquidDimens
import com.hortus1556.exilum.ui.components.withoutBottom
import androidx.annotation.StringRes
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.hortus1556.exilum.R
import com.hortus1556.exilum.core.FileLoggingTree
import com.hortus1556.exilum.db.AppPreferences
import com.hortus1556.exilum.ui.components.AppAlertDialog
import com.hortus1556.exilum.ui.components.AppTopAppBar
import com.hortus1556.exilum.ui.components.BackButton
import com.hortus1556.exilum.ui.components.appContentWindowInsets
import com.hortus1556.exilum.ui.components.rememberAppBarScrollBehavior
import com.hortus1556.exilum.ui.oneui.OneUiCircleIcon
import com.hortus1556.exilum.ui.oneui.OneUiDimens
import com.hortus1556.exilum.ui.oneui.OneUiGroupShape
import com.hortus1556.exilum.ui.oneui.OneUiRow
import com.hortus1556.exilum.ui.screens.preferences.components.LocalShowSectionTitle
import com.hortus1556.exilum.ui.screens.preferences.components.SettingsMenuLink
import com.hortus1556.exilum.ui.screens.preferences.components.settingsShapes
import com.hortus1556.exilum.ui.screens.preferences.section.PreferenceBrowser
import com.hortus1556.exilum.ui.screens.preferences.section.PreferenceEngine
import com.hortus1556.exilum.ui.screens.preferences.section.PreferenceInfo
import com.hortus1556.exilum.ui.screens.preferences.section.PreferenceInterface
import com.hortus1556.exilum.ui.screens.preferences.section.PreferenceOboe
import com.hortus1556.exilum.ui.screens.preferences.section.PreferencePlayer
import com.hortus1556.exilum.ui.theme.AppTheme
import com.hortus1556.exilum.ui.theme.isOneUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * The sections of the settings, as One UI lists them: each a page of its
 * own, reached from a list with a colored round icon per entry. Material
 * Expressive shows them all on one page instead.
 */
enum class PreferenceCategory(
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
    val icon: ImageVector,
    val color: Color,
) {
    Interface(R.string.pref_title_interface, R.string.pref_category_interface_desc, Icons.Default.Palette, Color(0xFF8C62F2)),
    Player(R.string.pref_title_player, R.string.pref_category_player_desc, Icons.Default.PlayArrow, Color(0xFF33B658)),
    Browser(R.string.pref_title_browser, R.string.pref_category_browser_desc, Icons.Default.Folder, Color(0xFFF5A623)),
    Engine(R.string.pref_title_engine, R.string.pref_category_engine_desc, Icons.Default.Memory, Color(0xFF3E91FF)),
    Oboe(R.string.pref_title_oboe, R.string.pref_category_oboe_desc, Icons.Default.GraphicEq, Color(0xFFF0506E)),
    About(R.string.pref_title_about, R.string.pref_category_about_desc, Icons.Default.Info, Color(0xFF8E8E93)),
}

/** How One UI groups the categories into cards. */
private val categoryCards = listOf(
    listOf(PreferenceCategory.Interface, PreferenceCategory.Player, PreferenceCategory.Browser),
    listOf(PreferenceCategory.Engine, PreferenceCategory.Oboe),
    listOf(PreferenceCategory.About),
)

/**
 * The settings. With a [category] only that section, as a page of its own
 * (One UI's layout); without one, every section on one page under Material
 * Expressive, or the list of categories under One UI.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun PreferencesScreen(
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onFormats: () -> Unit,
    modifier: Modifier = Modifier,
    category: PreferenceCategory? = null,
    onCategory: (PreferenceCategory) -> Unit = {},
    onAbout: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val resource = LocalResources.current
    val prefs = if (LocalView.current.isInEditMode) {
        AppPreferences(LocalContext.current)
    } else {
        koinInject<AppPreferences>()
    }
    val fileLoggingTree = if (LocalView.current.isInEditMode) {
        FileLoggingTree(LocalContext.current)
    } else {
        koinInject<FileLoggingTree>()
    }
    val oneUi = isOneUi
    val liquid = isLiquid
    // One UI and iOS both split the settings into a list of categories, each a
    // page of its own.
    val categorized = oneUi || liquid

    val exportLogs: () -> Unit = {
        scope.launch(Dispatchers.IO) {
            val success = fileLoggingTree.exportToDownloads()
            val msg = resource.getString(
                if (success) {
                    R.string.snack_export_logs_ok
                } else {
                    R.string.snack_export_logs_fail
                }
            )
            withContext(Dispatchers.Main) {
                snackbarHostState.showSnackbar(msg)
            }
        }
    }

    var isShowingResetDialog by remember { mutableStateOf(false) }
    if (isShowingResetDialog) {
        AppAlertDialog(
            onDismissRequest = { isShowingResetDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = null
                )
            },
            title = { Text(text = stringResource(R.string.dialog_title_pref_reset)) },
            text = { Text(text = stringResource(R.string.dialog_message_pref_reset)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val text = resource.getString(R.string.snack_pref_reset_ok)
                            prefs.resetAll()
                            snackbarHostState.showSnackbar(text)
                        }
                        isShowingResetDialog = false
                    },
                    content = { Text(text = stringResource(R.string.confirm)) }
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { isShowingResetDialog = false },
                    content = { Text(text = stringResource(R.string.cancel)) }
                )
            }
        )
    }

    val scrollBehavior = rememberAppBarScrollBehavior()

    // Under Liquid Glass the page is what its glass bends.
    val pageBackdrop = rememberLiquidPageBackdrop()
    CompositionLocalProvider(LocalLiquidBackdrop provides pageBackdrop) {
    Scaffold(
        containerColor = liquidPageContainer(),
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = appContentWindowInsets(),
        topBar = {
            AppTopAppBar(
                title = {
                    Text(text = stringResource(category?.title ?: R.string.title_preferences))
                },
                // The top of the settings is a tab of its own under One UI,
                // with nothing to go back to.
                navigationIcon = {
                    if (!categorized || category != null) BackButton(onBack = onBack)
                },
                scrollBehavior = scrollBehavior,
            )
        },
        content = { paddingValues ->
            val scrollState = rememberScrollState()
            val colors = ListItemDefaults.segmentedColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            )

            Column(
                modifier = Modifier
                    .padding(paddingValues.pageSides())
                    .fillMaxSize()
                    .liquidPage(pageBackdrop)
                    .verticalScroll(scrollState)
                    .padding(paddingValues.scrollPadding()),
                content = {
                    when {
                        category != null -> CompositionLocalProvider(LocalShowSectionTitle provides false) {
                            PreferenceCategoryContent(
                                category = category,
                                colors = colors,
                                onFormats = onFormats,
                                onAbout = onAbout,
                                onExportLogs = exportLogs,
                            )
                        }

                        liquid -> LiquidCategories(
                            onCategory = onCategory,
                            onReset = { isShowingResetDialog = true },
                        )

                        oneUi -> OneUiCategories(
                            onCategory = onCategory,
                            onReset = { isShowingResetDialog = true },
                        )

                        else -> {
                            PreferenceInterface(colors)
                            PreferenceBrowser(colors)
                            PreferencePlayer(colors)
                            PreferenceInfo(
                                colors = colors,
                                onFormats = onFormats,
                                onAbout = onAbout,
                                onExportLogs = exportLogs,
                            )
                            PreferenceEngine(colors)
                            PreferenceOboe(colors)
                            // Last, on its own: the one thing on this screen that
                            // undoes everything above it.
                            SettingsMenuLink(
                                modifier = Modifier.padding(16.dp),
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.RestartAlt,
                                        contentDescription = null
                                    )
                                },
                                title = { Text(text = stringResource(R.string.dialog_title_pref_reset)) },
                                subtitle = { Text(text = stringResource(R.string.pref_reset_desc)) },
                                colors = colors,
                                shapes = settingsShapes(0, 1),
                                onClick = { isShowingResetDialog = true }
                            )
                        }
                    }
                    if (categorized) Spacer(modifier = Modifier.height(16.dp))
                }
            )
        }
    )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PreferenceCategoryContent(
    category: PreferenceCategory,
    colors: ListItemColors,
    onFormats: () -> Unit,
    onAbout: () -> Unit,
    onExportLogs: () -> Unit,
) {
    when (category) {
        PreferenceCategory.Interface -> PreferenceInterface(colors)
        PreferenceCategory.Player -> PreferencePlayer(colors)
        PreferenceCategory.Browser -> PreferenceBrowser(colors)
        PreferenceCategory.Engine -> PreferenceEngine(colors)
        PreferenceCategory.Oboe -> PreferenceOboe(colors)
        PreferenceCategory.About -> PreferenceInfo(
            colors = colors,
            onFormats = onFormats,
            onAbout = onAbout,
            onExportLogs = onExportLogs,
        )
    }
}

/**
 * The top of iOS's Settings: the categories in inset grouped sections, each
 * behind its rounded-square icon with a chevron to say it opens a page, and
 * reset on its own at the end in red, as iOS writes a destructive action.
 */
@Composable
private fun LiquidCategories(
    onCategory: (PreferenceCategory) -> Unit,
    onReset: () -> Unit,
) {
    val colors = LocalLiquidColors.current
    Column(modifier = Modifier.padding(horizontal = LiquidDimens.SectionMargin)) {
        categoryCards.forEach { card ->
            card.forEachIndexed { index, category ->
                LiquidRow(
                    title = { Text(text = stringResource(category.title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    icon = { LiquidIconSquare(imageVector = category.icon, color = category.color) },
                    shape = LiquidGroupShape.of(index, card.size),
                    chevron = true,
                    interaction = Modifier.clickable { onCategory(category) },
                )
            }
        }
        LiquidRow(
            title = { Text(text = stringResource(R.string.dialog_title_pref_reset)) },
            titleColor = colors.red,
            interaction = Modifier.clickable(onClick = onReset),
        )
        Spacer(modifier = Modifier.height(20.dp))
    }
}

/**
 * The top of One UI's settings: the categories in cards, each behind its
 * round colored icon, and reset on its own at the end.
 */
@Composable
private fun OneUiCategories(
    onCategory: (PreferenceCategory) -> Unit,
    onReset: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = OneUiDimens.CardMargin)) {
        categoryCards.forEach { card ->
            card.forEachIndexed { index, category ->
                OneUiRow(
                    title = { Text(text = stringResource(category.title)) },
                    summary = {
                        Text(
                            text = stringResource(category.summary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    icon = { OneUiCircleIcon(imageVector = category.icon, color = category.color) },
                    shape = OneUiGroupShape.of(index, card.size),
                    interaction = Modifier.clickable { onCategory(category) },
                )
            }
        }
        OneUiRow(
            title = {
                Text(
                    text = stringResource(R.string.dialog_title_pref_reset),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            summary = { Text(text = stringResource(R.string.pref_reset_desc)) },
            icon = { OneUiCircleIcon(imageVector = Icons.Default.RestartAlt, color = MaterialTheme.colorScheme.error) },
            interaction = Modifier.clickable(onClick = onReset),
        )
        // Keeps the list from ending flush against whatever floats below it.
        Spacer(modifier = Modifier.height(OneUiDimens.GroupGap))
    }
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        PreferencesScreen(
            snackbarHostState = SnackbarHostState(),
            onBack = {},
            onAbout = {},
            onFormats = {},
        )
    }
}
