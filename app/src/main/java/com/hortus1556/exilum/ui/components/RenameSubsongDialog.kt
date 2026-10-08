package com.hortus1556.exilum.ui.components

import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.liquid.LiquidAlertField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.hortus1556.exilum.R

/**
 * Names a subsong. Confirming an empty name takes the name away again, so the
 * subsong goes back to what the format or the numbering calls it.
 */
@Composable
internal fun RenameSubsongDialog(
    initialName: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.dialog_title_rename_subsong)) },
        text = {
            if (isLiquid) {
                LiquidAlertField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = placeholder,
                    autoFocus = true,
                )
            } else {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text(text = placeholder) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text(text = stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.cancel)) }
        },
    )
}
