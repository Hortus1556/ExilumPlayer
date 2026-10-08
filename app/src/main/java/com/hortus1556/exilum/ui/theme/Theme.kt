package com.hortus1556.exilum.ui.theme

import com.hortus1556.exilum.ui.liquid.LiquidRefraction
import androidx.compose.runtime.LaunchedEffect
import com.hortus1556.exilum.db.DEFAULT_GLASS_OPACITY
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hortus1556.exilum.db.AppPreferences
import com.hortus1556.exilum.ui.liquid.LiquidDark
import com.hortus1556.exilum.ui.liquid.LiquidIndication
import com.hortus1556.exilum.ui.liquid.LiquidLight
import com.hortus1556.exilum.ui.liquid.LiquidShapes
import com.hortus1556.exilum.ui.liquid.LocalLiquidColors
import com.hortus1556.exilum.ui.liquid.liquidColorScheme
import com.hortus1556.exilum.ui.liquid.liquidRippleConfiguration
import com.hortus1556.exilum.ui.liquid.liquidTypography
import com.hortus1556.exilum.ui.oneui.LocalOneUiColors
import com.hortus1556.exilum.ui.oneui.OneUiDark
import com.hortus1556.exilum.ui.oneui.OneUiIndication
import com.hortus1556.exilum.ui.oneui.OneUiLight
import com.hortus1556.exilum.ui.oneui.OneUiShapes
import com.hortus1556.exilum.ui.oneui.oneUiColorScheme
import com.hortus1556.exilum.ui.oneui.oneUiRippleConfiguration
import com.hortus1556.exilum.ui.oneui.oneUiTypography
import org.koin.compose.koinInject

@Composable
fun AppTheme(
    content: @Composable () -> Unit
) {
    val inEditMode = LocalView.current.isInEditMode
    val prefs = if (inEditMode) {
        AppPreferences(LocalContext.current)
    } else {
        koinInject<AppPreferences>()
    }

    // Nothing is drawn until the style is known: starting in one and
    // switching to another a frame later flashes the whole screen - One UI
    // even changes it from dark to light.
    // The same for light or dark.
    val uiStyle by remember(prefs) { prefs.getUiStyleFlow() }
        .collectAsStateWithLifecycle(initialValue = if (inEditMode) 0 else null)
    val darkMode by remember(prefs) { prefs.getDarkModeFlow() }
        .collectAsStateWithLifecycle(initialValue = if (inEditMode) true else null)
    // Kept as the state it is and handed to the colours as that, so a new
    // opacity changes what reads it and does not make new colours - see
    // LiquidColors.glass.
    val glass = remember(prefs) { prefs.getLiquidGlassOpacityFlow() }
        .collectAsStateWithLifecycle(initialValue = DEFAULT_GLASS_OPACITY)
    val refraction by remember(prefs) { prefs.getLiquidRefractionFlow() }
        .collectAsStateWithLifecycle(initialValue = true)
    LaunchedEffect(refraction) { LiquidRefraction.enabled = refraction }
    val style = uiStyle?.let(UiStyle::fromStored) ?: return
    val dark = darkMode ?: return
    val oneUi = style == UiStyle.OneUi
    val liquid = style == UiStyle.LiquidGlass

    // The device's own colors, where there are any - the palette picked from
    // the wallpaper (on a Galaxy, One UI's Color palette). Read again with the
    // configuration, which is what changes when the palette does.
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val accent = remember(context, configuration, dark) { deviceAccent(context, dark) }

    val oneUiColors = (if (dark) OneUiDark else OneUiLight).let { base ->
        accent?.let { base.copy(accent = it) } ?: base
    }
    // Liquid Glass keeps iOS's system colors and takes the device's accent as
    // the app's tint, the way an iOS app has a tint of its own.
    val liquidColors = remember(dark, accent, glass) {
        (if (dark) LiquidDark else LiquidLight).let { base ->
            accent?.let { base.copy(tint = it) } ?: base
        }.copy(glass = glass)
    }
    val oneUiScheme = remember(oneUiColors) { oneUiColorScheme(oneUiColors) }
    val oneUiTypography = remember { oneUiTypography() }
    val liquidScheme = remember(liquidColors) { liquidColorScheme(liquidColors) }
    val liquidTypography = remember { liquidTypography() }

    // One theme whatever the style, with the content in the same place under
    // it: switching style in the settings restyles the app where it stands,
    // instead of building it again from the start - back on the first tab,
    // with the settings page just left gone.
    MaterialTheme(
        colorScheme = if (oneUi) oneUiScheme else liquidScheme,
        typography = if (oneUi) oneUiTypography else liquidTypography,
        shapes = if (oneUi) OneUiShapes else LiquidShapes,
    ) {
        CompositionLocalProvider(
            LocalUiStyle provides style,
            LocalOneUiColors provides oneUiColors,
            LocalLiquidColors provides liquidColors,
            // One UI: a gray wash that fades in and out, not a ripple, for
            // everything clickable that does not pick its own feedback, and
            // Material's own components, which always ripple, toned down to
            // the same gray. Liquid Glass: iOS's cell highlight, and the
            // ripple all but gone.
            LocalIndication provides if (oneUi) OneUiIndication else LiquidIndication,
            LocalRippleConfiguration provides if (oneUi) {
                oneUiRippleConfiguration(oneUiColors)
            } else {
                liquidRippleConfiguration(liquidColors)
            },
        ) {
            SystemBars(light = !dark, background = MaterialTheme.colorScheme.background)
            content()
        }
    }
}

/**
 * The accent of the device's own palette, from Android 12 on; null before,
 * where each style's own colors are used instead. It is a mid tone of the
 * palette's main color - as saturated as Samsung's own blue, which the pale
 * tones a Material scheme uses in the dark are not - and serves as One UI's
 * accent and as Liquid Glass's tint.
 */
private fun deviceAccent(context: Context, dark: Boolean): Color? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    return Color(
        context.getColor(
            if (dark) android.R.color.system_accent1_400 else android.R.color.system_accent1_500
        )
    )
}

/**
 * Dark status and navigation bar icons over a light theme, light ones over a
 * dark one; and the window behind the app in the theme's own background, so
 * what shows through between two screens mid-transition is not the wrong
 * shade.
 */
@Composable
private fun SystemBars(light: Boolean, background: Color) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val argb = background.toArgb()
    // A screen that is dark whatever the theme asks for light icons over it.
    val icons = light && darkScreens.intValue == 0
    SideEffect {
        val window = view.activity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = icons
            isAppearanceLightNavigationBars = icons
        }
        window.setBackgroundDrawable(ColorDrawable(argb))
    }
}

/** How many screens that stay dark in a light theme are showing. */
private val darkScreens = mutableIntStateOf(0)

/**
 * For a screen that is dark whatever the theme is - Liquid Glass's Now
 * Playing: while it is showing, the system bars' icons are light.
 */
@Composable
fun DarkScreenSystemBars() {
    DisposableEffect(Unit) {
        darkScreens.intValue++
        onDispose { darkScreens.intValue-- }
    }
}

private fun View.activity(): Activity? {
    var context = context
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
