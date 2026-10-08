package com.hortus1556.exilum.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import com.hortus1556.exilum.R
import com.hortus1556.exilum.ui.theme.isLiquid

@Composable
fun BackButton(onBack: () -> Unit) {
    // iOS goes back with a chevron, not an arrow.
    val liquid = isLiquid
    IconButton(
        onClick = onBack,
        content = {
            Icon(
                imageVector = if (liquid) Icons.Filled.ArrowBackIosNew else Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.desc_back_button),
                modifier = if (liquid) Modifier.size(20.dp) else Modifier,
            )
        }
    )
}
