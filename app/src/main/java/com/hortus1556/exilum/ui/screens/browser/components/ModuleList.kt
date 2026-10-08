package com.hortus1556.exilum.ui.screens.browser.components

import com.hortus1556.exilum.ui.components.FileTypeMarks
import com.hortus1556.exilum.ui.components.PaddingSides
import com.hortus1556.exilum.ui.components.groupedListCard
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.hortus1556.exilum.R
import com.hortus1556.exilum.db.entity.ModuleEntity
import com.hortus1556.exilum.ui.screens.browser.FileItem
import com.hortus1556.exilum.ui.screens.browser.documentDisplayName
import com.hortus1556.exilum.ui.theme.AppTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/**
 * A folder's rows: its folders, then its files.
 *
 * Handed the two lists and not the browser's whole state: with that, every
 * row on screen was gone through again when shuffle was switched, a letter
 * was typed or a refresh began - anything the state holds, of which the rows
 * show none. [padding] is read as the list is laid out, see PaddingSides.
 */
@Composable
internal fun ModuleList(
    directories: ImmutableList<FileItem>,
    files: ImmutableList<ModuleEntity>,
    /** Whether [files] are matches from below the folder, shown by where each one is. */
    isSearching: Boolean,
    padding: PaddingValues,
    listState: LazyListState,
    onDir: (FileItem) -> Unit,
    onSelect: (ModuleEntity) -> Unit,
    onAddToPlaylist: ((ModuleEntity) -> Unit)? = null,
    onAddFolderToPlaylist: ((FileItem) -> Unit)? = null,
    /** The folder open beside this list, as its address, picked out in it; null for none. */
    open: String? = null,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingSides(padding, extraBottom = 88.dp), // FAB
        content = {
            // Folders and files are one card under One UI.
            val count = directories.size + files.size
            itemsIndexed(
                items = directories,
                key = { _, dir -> dir.uri.toString() },
                // A folder's row and a file's are built differently: kept
                // apart, a row leaving the screen is reused for one of its
                // own kind instead of being rebuilt as the other.
                contentType = { _, _ -> "directory" },
                itemContent = { index, dir ->
                    BrowserListItem(
                        modifier = Modifier.groupedListCard(index, count, dividerStart = FileTypeMarks.RowTextStart),
                        title = dir.name,
                        comment = stringResource(R.string.directory),
                        leadingIcon = Icons.Default.Folder,
                        onClick = { onDir(dir) },
                        onTrailingClick = onAddFolderToPlaylist?.let { { it(dir) } },
                        selected = open != null && dir.uri.toString() == open,
                    )
                }
            )
            itemsIndexed(
                items = files,
                key = { _, file -> file.filePath },
                contentType = { _, _ -> "file" },
                itemContent = { index, file ->
                    BrowserListItem(
                        modifier = Modifier.groupedListCard(directories.size + index, count, dividerStart = FileTypeMarks.RowTextStart),
                        title = file.name,
                        // Search results come from anywhere below the open
                        // folder, so the type is worth less here than knowing
                        // which folder the hit is actually in.
                        comment = if (isSearching) {
                            remember(file.parentPath) { documentDisplayName(file.parentPath) }
                        } else {
                            file.moduleType.ifBlank { file.fileExtension }
                        },
                        fileMark = remember(file.fileExtension, file.filename, file.moduleType) { FileTypeMarks.of(file) },
                        onClick = { onSelect(file) },
                        onTrailingClick = onAddToPlaylist?.let { { it(file) } },
                    )
                }
            )
        }
    )
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        ModuleList(
            directories = persistentListOf(),
            files = List(10) {
                ModuleEntity(
                    filePath = "content://preview/$it",
                    filename = "Item $it",
                    fileExtension = "669",
                    fileSize = it + 1L * it,
                )
            }.toImmutableList(),
            isSearching = false,
            padding = PaddingValues(),
            listState = LazyListState(),
            onDir = {},
            onSelect = {},
        )
    }
}
