package com.hortus1556.exilum.ui.screens.player.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.hortus1556.exilum.ui.theme.AppTheme

/**
 * Height-less [androidx.compose.material3.BottomAppBar]
 */
@Composable
fun PlayerBottomAppBar(
    modifier: Modifier = Modifier,
    containerColor: Color = BottomAppBarDefaults.containerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = BottomAppBarDefaults.ContainerElevation,
    contentPadding: PaddingValues = PaddingValues(vertical = 8.dp),
    windowInsets: WindowInsets = BottomAppBarDefaults.windowInsets,
    shape: Shape = RectangleShape,
    shadowElevation: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        shape = shape,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(windowInsets)
                .padding(contentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        PlayerBottomAppBar {}
    }
}
