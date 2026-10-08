package com.hortus1556.exilum.ui.oneui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The colors One UI is drawn in, beyond what a Material [ColorScheme] has
 * names for. Neutral grays and one blue: One UI keeps color for what can be
 * acted on and for values, and leaves everything else black, white and gray.
 */
@Immutable
data class OneUiColors(
    val isDark: Boolean,
    /** Behind everything - the gray the cards sit on. */
    val background: Color,
    /** Cards: every grouped list is one. */
    val card: Color,
    /** Pop-ups, a step lighter than a card in the dark. */
    val dialog: Color,
    val text: Color,
    val textSecondary: Color,
    /** The small headings over a card. */
    val subheader: Color,
    /** The one color: switches, sliders, buttons, selected values. */
    val accent: Color,
    /** Hairlines between the rows of a card. */
    val divider: Color,
    /** The wash drawn over something while it is held down. */
    val highlight: Color,
    val switchTrackOff: Color,
    val switchThumbOff: Color,
    val sliderTrack: Color,
    /** Floating bars, laid over their blurred backdrop. */
    val bar: Color,
    /** Floating bars where the platform cannot blur (before Android 12). */
    val barOpaque: Color,
    val barBorder: Color,
    /** The capsule behind the selected tab. */
    val barSelection: Color,
    /** The round buttons that float over the top of a page. */
    val floatingButton: Color,
)

internal val OneUiLight = OneUiColors(
    isDark = false,
    background = Color(0xFFF2F2F2),
    card = Color(0xFFFCFCFC),
    dialog = Color(0xFFFCFCFC),
    text = Color(0xFF191919),
    textSecondary = Color(0xFF737373),
    subheader = Color(0xFF7A7A7A),
    accent = Color(0xFF0381FE),
    divider = Color(0xFFE2E2E2),
    highlight = Color(0x14000000),
    switchTrackOff = Color(0xFFCBCBCB),
    switchThumbOff = Color(0xFFFFFFFF),
    sliderTrack = Color(0xFFDADADA),
    bar = Color(0xC4FCFCFC),
    barOpaque = Color(0xF7FCFCFC),
    barBorder = Color(0x12000000),
    barSelection = Color(0x12000000),
    floatingButton = Color(0xF2FCFCFC),
)

internal val OneUiDark = OneUiColors(
    isDark = true,
    background = Color(0xFF000000),
    card = Color(0xFF171717),
    dialog = Color(0xFF252525),
    text = Color(0xFFFAFAFA),
    textSecondary = Color(0xFF8C8C8C),
    subheader = Color(0xFF8F8F8F),
    accent = Color(0xFF3E91FF),
    divider = Color(0xFF2E2E2E),
    highlight = Color(0x1FFFFFFF),
    switchTrackOff = Color(0xFF4D4D4D),
    switchThumbOff = Color(0xFFDADADA),
    sliderTrack = Color(0xFF3A3A3A),
    bar = Color(0xC21E1E1E),
    barOpaque = Color(0xF71C1C1C),
    barBorder = Color(0x17FFFFFF),
    barSelection = Color(0x24FFFFFF),
    floatingButton = Color(0xF2242424),
)

val LocalOneUiColors = staticCompositionLocalOf { OneUiDark }

/** One UI's measurements, shared so every card, row and bar agrees. */
object OneUiDimens {
    /** Cards, pop-ups and sheets. */
    val CardCorner = 26.dp

    /** From the edge of the screen to the edge of a card. */
    val CardMargin = 12.dp

    /** From the edge of a card to the text in it. */
    val RowPadding = 24.dp

    /** Between one card and the next. */
    val GroupGap = 12.dp

    /** The toolbar row an expanded app bar collapses into. */
    val ToolbarHeight = 60.dp

    /** The floating tab bar. */
    val TabBarHeight = 64.dp

    /** From the bottom of the screen, and between the floating bars. */
    val BarMargin = 12.dp
}

/** Text styles One UI uses that Material has no slot for. */
object OneUiType {
    /** The big title of an expanded app bar. */
    val ExpandedTitle = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Normal)

    /** The same title once the app bar has collapsed into its toolbar. */
    val CollapsedTitle = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)

    val ExpandedSubtitle = TextStyle(fontSize = 14.sp, lineHeight = 19.sp)

    /** Row titles. */
    val Title = TextStyle(fontSize = 17.sp, lineHeight = 22.sp)

    /** Row summaries, and the values shown under a title in the accent color. */
    val Summary = TextStyle(fontSize = 14.sp, lineHeight = 19.sp)

    /** The small heading over a card. */
    val Subheader = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)

    val DialogTitle = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)

    val DialogText = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)

    val Button = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)

    val TabLabel = TextStyle(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
}

/**
 * Material's slots mapped onto One UI's colors, so every stock component -
 * sheets, menus, text fields, progress bars - comes out right without being
 * told about One UI at all.
 */
internal fun oneUiColorScheme(c: OneUiColors): ColorScheme {
    val base = if (c.isDark) darkColorScheme() else lightColorScheme()
    val raised = if (c.isDark) Color(0xFF252525) else c.card
    val raisedMore = if (c.isDark) Color(0xFF2C2C2C) else Color(0xFFEAEAEA)
    val neutral = if (c.isDark) Color(0xFF2C2C2C) else Color(0xFFE4E4E4)
    val error = if (c.isDark) Color(0xFFFF5A4F) else Color(0xFFE0352B)
    return base.copy(
        primary = c.accent,
        onPrimary = Color.White,
        primaryContainer = c.accent.copy(alpha = 0.16f).compositeOver(c.card),
        onPrimaryContainer = c.accent,
        inversePrimary = if (c.isDark) Color(0xFF0381FE) else Color(0xFF7AB6FF),
        secondary = c.textSecondary,
        onSecondary = c.card,
        secondaryContainer = neutral,
        onSecondaryContainer = c.text,
        tertiary = c.accent,
        onTertiary = Color.White,
        tertiaryContainer = c.accent.copy(alpha = 0.16f).compositeOver(c.card),
        onTertiaryContainer = c.accent,
        background = c.background,
        onBackground = c.text,
        surface = c.background,
        onSurface = c.text,
        surfaceVariant = neutral,
        onSurfaceVariant = c.textSecondary,
        surfaceTint = Color.Transparent,
        inverseSurface = Color(0xFF3F3F3F),
        inverseOnSurface = Color(0xFFFAFAFA),
        error = error,
        onError = Color.White,
        errorContainer = error.copy(alpha = 0.16f).compositeOver(c.card),
        onErrorContainer = error,
        outline = if (c.isDark) Color(0xFF5A5A5A) else Color(0xFFBDBDBD),
        outlineVariant = c.divider,
        scrim = Color.Black,
        surfaceBright = c.card,
        surfaceDim = c.background,
        surfaceContainerLowest = c.card,
        surfaceContainerLow = c.card,
        surfaceContainer = c.card,
        surfaceContainerHigh = raised,
        surfaceContainerHighest = raisedMore,
    )
}

/**
 * Material's type scale nudged to One UI's: 17sp titles, no letter spacing
 * anywhere, heavier buttons. The typeface is left to the system, which on a
 * Galaxy phone is Samsung's own.
 */
internal fun oneUiTypography(base: Typography = Typography()): Typography = base.copy(
    titleLarge = base.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleMedium = base.titleMedium.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    titleSmall = base.titleSmall.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 23.sp, letterSpacing = 0.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 19.sp, letterSpacing = 0.sp),
    bodySmall = base.bodySmall.copy(fontSize = 13.sp, lineHeight = 17.sp, letterSpacing = 0.sp),
    labelLarge = base.labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    labelMedium = base.labelMedium.copy(fontSize = 13.sp, lineHeight = 17.sp, letterSpacing = 0.sp),
    labelSmall = base.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
)

/** Round everywhere: nothing in One UI has a sharp corner. */
internal val OneUiShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(OneUiDimens.CardCorner),
    extraLarge = RoundedCornerShape(OneUiDimens.CardCorner),
)

/** Material's ripple, toned down to One UI's gray wash, for the stock components that always ripple. */
@OptIn(ExperimentalMaterial3Api::class)
internal fun oneUiRippleConfiguration(colors: OneUiColors) = RippleConfiguration(
    color = colors.text,
    rippleAlpha = RippleAlpha(
        draggedAlpha = 0.08f,
        focusedAlpha = 0.08f,
        hoveredAlpha = 0.04f,
        pressedAlpha = 0.10f,
    ),
)
