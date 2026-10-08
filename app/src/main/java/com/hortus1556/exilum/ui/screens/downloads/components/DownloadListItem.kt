package com.hortus1556.exilum.ui.screens.downloads.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.hortus1556.exilum.R
import com.hortus1556.exilum.ui.components.FileTypeMarks
import com.hortus1556.exilum.ui.components.FileTypeSlot
import com.hortus1556.exilum.ui.liquid.LocalLiquidColors
import com.hortus1556.exilum.ui.theme.AppTheme
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.util.fromHtml

/**
 * A row of what a search found, or of what was downloaded before: a module
 * by its title, over who made it, with its format before and its size after;
 * or an artist, by name alone.
 *
 * A Row in every style, as the browser's rows are (see BrowserListItem) and
 * to the same measurements, so that the lists of the app are one kind of
 * list. It was Material's list item whatever the style: under Liquid Glass a
 * gray Material row with a square label on it, among pages of inset cards
 * and artwork. There the format is the kind of file's own icon, on the
 * colours the title is drawn in everywhere else.
 */
@Composable
internal fun DownloadListItem(
    text: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    trailingText: String? = null,
    leadingText: String? = null,
    onClick: () -> Unit,
) {
    // Parsed when the text changes rather than on every recomposition of the
    // row: Html.fromHtml is a whole parser to run for a title and a name.
    val title = remember(text) { text.fromHtml() }
    val supporting = remember(supportingText) { supportingText?.fromHtml() }
    val liquid = isLiquid
    val colors = LocalLiquidColors.current
    val scheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = if (liquid) 56.dp else 62.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingText != null) {
            // The format, as the archive names it: its logo, or the name itself.
            FileTypeSlot(remember(leadingText) { FileTypeMarks.of(leadingText) })
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (leadingText != null) 12.dp else 0.dp)
                .padding(vertical = if (liquid) 12.dp else 9.dp),
            verticalArrangement = Arrangement.spacedBy(if (liquid) 1.dp else 0.dp),
        ) {
            Text(
                text = title.ifBlank { stringResource(R.string.untitled) },
                style = typography.bodyLarge,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = typography.labelSmall,
                color = if (liquid) colors.secondaryLabel else scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Preview
@Composable
private fun ArtistListItem_Preview() {
    AppTheme {
        Surface {
            DownloadListItem(text = "Artist Alias", onClick = {})
        }
    }
}

@Preview
@Composable
private fun ModuleListItem_Preview() {
    AppTheme {
        Surface {
            DownloadListItem(
                text = stringResource(R.string.app_name),
                supportingText = stringResource(R.string.app_name),
                leadingText = "669",
                trailingText = stringResource(R.string.size_kb, 99999),
                onClick = {},
            )
        }
    }
}
