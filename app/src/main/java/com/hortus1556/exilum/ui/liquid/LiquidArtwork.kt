package com.hortus1556.exilum.ui.liquid

import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hortus1556.exilum.ui.oneui.trackLook

/**
 * Artwork as Music's lists lead their rows with it. Modules have none, so it
 * is the colors [key] - a file's path - is drawn in everywhere else, Now
 * Playing included, on a small rounded square with [icon] on top.
 */
@Composable
fun LiquidArtwork(
    key: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    icon: ImageVector = Icons.Default.MusicNote,
) {
    val colors = LocalLiquidColors.current
    val look = remember(key, colors.isDark) { trackLook(key, colors.isDark) }
    val shape = remember(size) { ContinuousShape(size * 0.2f) }
    Box(
        modifier = modifier
            .size(size)
            // The rounded square is filled, not clipped to. A clip to a
            // continuous corner is a clip to a path - a mask the GPU builds
            // for every row on screen, every frame a list scrolls - where a
            // filled path is simply drawn. The glyph inside needs no clip.
            .drawWithCache {
                val outline = shape.createOutline(this.size, layoutDirection, this)
                val brush = Brush.linearGradient(look.palette)
                onDrawBehind { drawOutline(outline, brush) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(size / 2))
    }
}
