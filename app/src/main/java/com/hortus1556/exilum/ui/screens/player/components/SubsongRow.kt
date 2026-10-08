package com.hortus1556.exilum.ui.screens.player.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.hortus1556.exilum.ui.theme.AppTheme

/**
 * The subsong controls, in the row [PatternInfoRow] would have had.
 *
 * Only ever drawn for a format with no patterns. Pos, pat and row are a
 * tracker's own vocabulary - a position in an order list, the pattern it names
 * and the row inside it - and a chip log or a CPU dump has none of those, so
 * that row was five labels reading zero. What those formats do have instead is
 * a file holding a dozen tunes at once, which is worth a row of its own and
 * was previously buried in a chip list behind a fold.
 *
 * The chips themselves come from [ChipList] so that they are the same chips
 * and not a second pair that looks like them; they are taken out of the list
 * when they are drawn here.
 */
@Composable
internal fun SubsongRow(
    isSubSongs: Boolean,
    onPlaySubSongs: () -> Unit,
    onShowDurations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = {
            SubsongsChip(isSubSongs = isSubSongs, onClick = onPlaySubSongs)
            ShowSubsongsChip(onClick = onShowDurations)
        }
    )
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface {
            SubsongRow(isSubSongs = true, onPlaySubSongs = {}, onShowDurations = {})
        }
    }
}
