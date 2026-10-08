package com.hortus1556.exilum.ui.theme

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.hortus1556.exilum.R

/**
 * The overall design language the app is drawn in - not a color or a palette
 * style, a whole different visual system, picked under Settings > Interface.
 *
 * Material Expressive 3 was the third of these and is gone: what the app is
 * drawn in now is one of Samsung's design language or Apple's, and Apple's is
 * what it starts in.
 */
enum class UiStyle(
    @param:StringRes val title: Int,
    @param:StringRes val description: Int,
    /**
     * What the setting holds. Their old places, from when Material Expressive
     * was the first of the three, so nobody's choice moves out from under them.
     */
    val stored: Int,
) {
    OneUi(
        R.string.pref_ui_style_item_one_ui,
        R.string.pref_ui_style_item_one_ui_desc,
        stored = 1,
    ),
    LiquidGlass(
        R.string.pref_ui_style_item_liquid_glass,
        R.string.pref_ui_style_item_liquid_glass_desc,
        stored = 2,
    ),
    ;

    companion object {
        /** What the setting holds, as a style; 0 was Material Expressive 3. */
        fun fromStored(value: Int): UiStyle = entries.firstOrNull { it.stored == value } ?: LiquidGlass
    }
}

/** The style [AppTheme] is drawing in, for components that look different under each. */
val LocalUiStyle = staticCompositionLocalOf { UiStyle.LiquidGlass }

/** Whether the app is being drawn as One UI. */
val isOneUi: Boolean
    @Composable
    @ReadOnlyComposable
    get() = LocalUiStyle.current == UiStyle.OneUi

/** Whether the app is being drawn as Apple's Liquid Glass. */
val isLiquid: Boolean
    @Composable
    @ReadOnlyComposable
    get() = LocalUiStyle.current == UiStyle.LiquidGlass
