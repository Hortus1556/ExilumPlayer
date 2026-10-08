package com.hortus1556.exilum.ui.screens.browser.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.hortus1556.exilum.ui.liquid.LiquidMenuSeparator
import com.hortus1556.exilum.ui.liquid.LiquidMenuItem
import com.hortus1556.exilum.ui.liquid.LiquidMenu
import androidx.compose.ui.unit.dp
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.liquid.ContinuousShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import com.hortus1556.exilum.R
import com.hortus1556.exilum.ui.components.BackButton
import com.hortus1556.exilum.ui.screens.browser.BrowserSortOrder
import com.hortus1556.exilum.ui.theme.AppTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BrowserInputField(
    textFieldState: TextFieldState,
    searchBarState: SearchBarState,
    colors: TextFieldColors,
    sortOrder: BrowserSortOrder,
    onSortOrder: (BrowserSortOrder) -> Unit,
    onFolderPick: () -> Unit,
    onShuffleAll: () -> Unit,
    onFilter: (String) -> Unit
) {
    val scope = rememberCoroutineScope()

    SearchBarDefaults.InputField(
        modifier = Modifier.fillMaxWidth(),
        textFieldState = textFieldState,
        searchBarState = searchBarState,
        colors = colors,
        onSearch = { scope.launch { searchBarState.animateToCollapsed() } },
        placeholder = { Text(text = stringResource(R.string.search_placeholder)) },
        leadingIcon = {
            if (searchBarState.currentValue == SearchBarValue.Expanded) {
                BackButton {
                    scope.launch { searchBarState.animateToCollapsed() }
                    textFieldState.clearText()
                    onFilter("")
                }
            } else {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = stringResource(R.string.desc_search)
                )
            }
        },
        trailingIcon = {
            if (textFieldState.text.isNotEmpty()) {
                IconButton(
                    onClick = {
                        textFieldState.clearText()
                        onFilter("")
                    },
                    content = {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = stringResource(R.string.desc_search_clear)
                        )
                    }
                )
            } else {
                Row {
                    IconButton(
                        onClick = onShuffleAll,
                        content = {
                            Icon(
                                imageVector = Icons.Default.Casino,
                                contentDescription = stringResource(R.string.desc_shuffle_all_root)
                            )
                        }
                    )
                    IconButton(
                        onClick = onFolderPick,
                        content = {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = stringResource(R.string.desc_folder_pick)
                            )
                        }
                    )
                    SortMenu(sortOrder = sortOrder, onSortOrder = onSortOrder)
                }
            }
        },
    )
}

@Composable
internal fun SortMenu(
    sortOrder: BrowserSortOrder,
    onSortOrder: (BrowserSortOrder) -> Unit
) {
    var showSortMenu by remember { mutableStateOf(false) }
    if (isLiquid) {
        LiquidSortMenu(
            sortOrder = sortOrder,
            open = showSortMenu,
            onOpen = { showSortMenu = it },
            onSortOrder = onSortOrder,
        )
        return
    }
    Box {
        IconButton(
            onClick = { showSortMenu = true },
            content = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Sort,
                    contentDescription = stringResource(R.string.desk_sort_button)
                )
            }
        )
        DropdownMenu(
            expanded = showSortMenu,
            onDismissRequest = { showSortMenu = false },
            // iOS 26's menus are a rounded pane.
            shape = if (isLiquid) ContinuousShape(22.dp) else MaterialTheme.shapes.small,
            content = {
                SortItem(R.string.name, Icons.Default.SortByAlpha, sortOrder == BrowserSortOrder.NAME) {
                    onSortOrder(BrowserSortOrder.NAME)
                    showSortMenu = false
                }
                SortItem(R.string.type, Icons.Default.Extension, sortOrder == BrowserSortOrder.TYPE) {
                    onSortOrder(BrowserSortOrder.TYPE)
                    showSortMenu = false
                }
                SortItem(R.string.size, Icons.Default.DataArray, sortOrder == BrowserSortOrder.SIZE) {
                    onSortOrder(BrowserSortOrder.SIZE)
                    showSortMenu = false
                }
            }
        )
    }
}

/**
 * The sort button's menu under Liquid Glass: drawn in the app's own window,
 * hung from the button on a pane of real glass - the menus' own, lit where it
 * is touched - rather than a DropdownMenu, a window of Android's own with
 * nothing of the app behind it to bend. Each way to sort leads with the check
 * when it is the one in use and ends with its symbol, as iOS lays out a menu.
 */
@Composable
private fun LiquidSortMenu(
    sortOrder: BrowserSortOrder,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    onSortOrder: (BrowserSortOrder) -> Unit,
) {
    var anchor by remember { mutableStateOf<Rect?>(null) }
    Box(modifier = Modifier.onGloballyPositioned { anchor = it.boundsInRoot() }) {
        IconButton(onClick = { onOpen(true) }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Sort,
                contentDescription = stringResource(R.string.desk_sort_button),
            )
        }
    }
    LiquidMenu(expanded = open, anchor = anchor, onDismissRequest = { onOpen(false) }) {
        val pick: (BrowserSortOrder) -> Unit = { order ->
            onSortOrder(order)
            onOpen(false)
        }
        LiquidMenuItem(
            text = stringResource(R.string.name),
            onClick = { pick(BrowserSortOrder.NAME) },
            icon = Icons.Default.SortByAlpha,
            checked = sortOrder == BrowserSortOrder.NAME,
        )
        LiquidMenuSeparator()
        LiquidMenuItem(
            text = stringResource(R.string.type),
            onClick = { pick(BrowserSortOrder.TYPE) },
            icon = Icons.Default.Extension,
            checked = sortOrder == BrowserSortOrder.TYPE,
        )
        LiquidMenuSeparator()
        LiquidMenuItem(
            text = stringResource(R.string.size),
            onClick = { pick(BrowserSortOrder.SIZE) },
            icon = Icons.Default.DataArray,
            checked = sortOrder == BrowserSortOrder.SIZE,
        )
    }
}

/**
 * One way to sort. Material leads with the symbol and ends with a check;
 * iOS's menus lead with the check and end with the symbol.
 */
@Composable
private fun SortItem(label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    if (isLiquid) {
        DropdownMenuItem(
            text = { Text(text = stringResource(label)) },
            onClick = onClick,
            leadingIcon = {
                Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    if (selected) Icon(imageVector = Icons.Default.Check, contentDescription = null)
                }
            },
            trailingIcon = { Icon(imageVector = icon, contentDescription = null) },
        )
    } else {
        DropdownMenuItem(
            text = { Text(text = stringResource(label)) },
            onClick = onClick,
            leadingIcon = { Icon(imageVector = icon, contentDescription = null) },
            trailingIcon = {
                if (selected) Icon(imageVector = Icons.Default.Check, contentDescription = null)
            },
        )
    }
}

@Preview
@Composable
private fun Preview_Sort() {
    AppTheme {
        Surface {
            SortMenu(
                sortOrder = BrowserSortOrder.SIZE,
                onSortOrder = {}
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun Preview() {
    val searchBarState = rememberSearchBarWithGapState()
    val textFieldState = rememberTextFieldState()
    val appBarWithSearchColors = SearchBarDefaults.appBarWithSearchColors(
        scrolledSearchBarContainerColor = Color.Unspecified,
        scrolledAppBarContainerColor = Color.Unspecified,
    )
    AppTheme {
        Surface {
            BrowserInputField(
                textFieldState = textFieldState,
                searchBarState = searchBarState,
                colors = appBarWithSearchColors.searchBarColors.inputFieldColors,
                sortOrder = BrowserSortOrder.SIZE,
                onSortOrder = {},
                onFolderPick = {},
                onShuffleAll = {},
                onFilter = {},
            )
        }
    }
}
