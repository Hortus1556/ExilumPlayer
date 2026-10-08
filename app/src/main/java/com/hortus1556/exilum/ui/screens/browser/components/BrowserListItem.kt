package com.hortus1556.exilum.ui.screens.browser.components

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import com.hortus1556.exilum.ui.components.FileTypeMark
import com.hortus1556.exilum.ui.components.FileTypeMarks
import com.hortus1556.exilum.ui.components.FileTypeSlot
import androidx.compose.ui.unit.dp
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.liquid.LocalLiquidColors
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.tooling.preview.*
import com.hortus1556.exilum.ui.theme.AppTheme

/**
 * A row of the browser: a folder or a file, its name over a line about it,
 * and the button for more at the end.
 *
 * Built from a Row in every style, to the measurements of Material's list
 * item where the style is not Liquid Glass - the icon 16 points in, the text
 * 12 after it in the list item's own styles, 62 points tall.
 *
 * It was that list item, the expressive one, which animates its shape and
 * colours between rest, pressed and focused and brings the state and the
 * springs for that into every row it builds - which a list builds all the
 * time while it scrolls, a row for every one that comes into view. On a
 * slower phone that was the browser's stutter. It was taken out of the
 * Liquid Glass rows first and left in the others, where the same scroll went
 * on costing twice as much at its worst frames. A row here does what a cell
 * does anywhere: it shows it is held, which every clickable does already.
 */
@Composable
internal fun BrowserListItem(
    title: String,
    comment: String? = null,
    leadingIcon: ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onTrailingClick: (() -> Unit)? = null,
    /** What kind of file the row is, for a file: its logo leads the row instead of [leadingIcon]. */
    fileMark: FileTypeMark? = null,
    /** The folder open in the column beside this one, picked out in the tint. */
    selected: Boolean = false,
) {
    val liquid = isLiquid
    val colors = LocalLiquidColors.current
    val scheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(colors.tint.copy(alpha = 0.22f)) else Modifier)
            .clickable(onClick = onClick)
            .heightIn(min = if (liquid) 56.dp else 62.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            // What kind of file it is, by the logo of what it is from.
            fileMark != null -> FileTypeSlot(fileMark)
            // A folder's icon in the middle of the same slot: the two are in
            // one list, and their names start on one line.
            leadingIcon != null -> Box(modifier = Modifier.size(FileTypeMarks.Slot), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    // Files draws folders in the tint.
                    tint = if (liquid) colors.tint else scheme.onSurfaceVariant,
                    modifier = Modifier.size(if (liquid) 30.dp else 24.dp),
                )
            }
        }
        val leads = fileMark != null || leadingIcon != null
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (leads) 12.dp else 0.dp)
                .padding(vertical = if (liquid) 12.dp else 9.dp),
            verticalArrangement = Arrangement.spacedBy(if (liquid) 1.dp else 0.dp),
        ) {
            Text(
                text = title,
                style = typography.bodyLarge,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (comment != null) {
                Text(
                    text = comment,
                    style = typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        if (onTrailingClick != null) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onTrailingClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    // iOS's "more" is the horizontal ellipsis.
                    imageVector = if (liquid) Icons.Default.MoreHoriz else Icons.Default.MoreVert,
                    contentDescription = null,
                    tint = if (liquid) colors.secondaryLabel else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface {
            BrowserListItem(
                title = "Title Item",
                comment = "Comment Item",
                leadingIcon = Icons.Default.AudioFile,
                onClick = {},
                onTrailingClick = {},
            )
        }
    }
}
