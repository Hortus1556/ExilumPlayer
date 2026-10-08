package com.hortus1556.exilum.ui.screens.preferences.components

import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.liquid.LiquidCheckRow
import com.hortus1556.exilum.ui.components.AppAlertDialog
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.Preview
import com.hortus1556.exilum.R
import com.hortus1556.exilum.ui.theme.AppTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

internal data class PreferenceItem(
    val key: String,
    @param:StringRes val title: Int,
    @param:StringRes val description: Int
)

/**
 * A list of choices, of which picking one is the answer: no title - the row
 * that opened it says what it is - and no buttons, as iOS lists its options.
 * Tapping outside or going back leaves the setting as it was.
 */
@Composable
internal fun SingleChoiceAlertDialog(
    selectedItemKey: String?,
    onItemSelected: (String?) -> Unit,
    items: ImmutableList<PreferenceItem>
) {
    AppAlertDialog(
        onDismissRequest = { onItemSelected(selectedItemKey) },
        text = {
            val liquid = isLiquid
            // Scrolled when the list is longer than the dialog has room for.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                items.forEachIndexed { index, sampleItem ->
                    val isSelected = sampleItem.key == selectedItemKey
                    if (liquid) {
                        LiquidCheckRow(
                            title = stringResource(sampleItem.title),
                            description = stringResource(sampleItem.description),
                            checked = isSelected,
                            onClick = { onItemSelected(sampleItem.key) },
                            divider = index > 0,
                        )
                    } else {
                        LabelRadioButton(
                            item = sampleItem,
                            isSelected = isSelected,
                            onClick = { onItemSelected(sampleItem.key) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LabelRadioButton(
    item: PreferenceItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        onClick = onClick,
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        content = { Text(text = stringResource(item.title)) },
        supportingContent = { Text(text = stringResource(item.description)) },
        trailingContent = { RadioButton(selected = isSelected, onClick = null) },
    )
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            SingleChoiceAlertDialog(
                selectedItemKey = "2",
                onItemSelected = {},
                items = persistentListOf(
                    PreferenceItem("1", R.string.app_name, R.string.app_name),
                    PreferenceItem("2", R.string.app_name, R.string.app_name),
                    PreferenceItem("3", R.string.app_name, R.string.app_name),
                    PreferenceItem("4", R.string.app_name, R.string.app_name),
                ),
            )
        }
    }
}
