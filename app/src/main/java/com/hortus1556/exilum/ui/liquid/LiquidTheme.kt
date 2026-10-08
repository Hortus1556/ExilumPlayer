package com.hortus1556.exilum.ui.liquid

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * iOS's colors - the semantic system colors UIKit resolves for light and
 * dark - plus the one tint every control takes, which here is the device's
 * own accent where it has one, as an app's tint is its own on iOS.
 */
@Stable
data class LiquidColors(
    val isDark: Boolean,
    /** systemGroupedBackground: behind grouped lists, and every page. */
    val background: Color,
    /** secondarySystemGroupedBackground: the cells of a grouped list. */
    val cell: Color,
    /** tertiarySystemGroupedBackground. */
    val cellRaised: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    /** systemFill and the lighter fills, for tracks and gray capsules. */
    val fill: Color,
    val secondaryFill: Color,
    val tertiaryFill: Color,
    /** systemGray4: a cell held down. */
    val cellHighlight: Color,
    val tint: Color,
    val green: Color,
    val red: Color,
    val orange: Color,
    val yellow: Color,
    val purple: Color,
    val pink: Color,
    val indigo: Color,
    val teal: Color,
    val blue: Color,
    val gray: Color,
    /**
     * How much the glass frosts what is behind it, from the settings: 0
     * clear, 1 all but solid - as state, read by what draws glass and by
     * nothing else. It was a plain value, so a new opacity made these colours
     * new; and they are provided statically, which rebuilds everything under
     * them when they change - the whole app, on every step of the slider.
     */
    val glass: State<Float> = DefaultGlass,
) {
    val glassOpacity: Float get() = glass.value
}

/** The opacity colours have until the settings say otherwise. */
private val DefaultGlass: State<Float> = mutableFloatStateOf(0.4f)

internal val LiquidLight = LiquidColors(
    isDark = false,
    background = Color(0xFFF2F2F7),
    cell = Color(0xFFFFFFFF),
    cellRaised = Color(0xFFF2F2F7),
    label = Color(0xFF000000),
    secondaryLabel = Color(0x993C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    separator = Color(0x4A3C3C43),
    fill = Color(0x33787880),
    secondaryFill = Color(0x29787880),
    tertiaryFill = Color(0x1F767680),
    cellHighlight = Color(0xFFD1D1D6),
    tint = Color(0xFF007AFF),
    green = Color(0xFF34C759),
    red = Color(0xFFFF3B30),
    orange = Color(0xFFFF9500),
    yellow = Color(0xFFFFCC00),
    purple = Color(0xFFAF52DE),
    pink = Color(0xFFFF2D55),
    indigo = Color(0xFF5856D6),
    teal = Color(0xFF30B0C7),
    blue = Color(0xFF007AFF),
    gray = Color(0xFF8E8E93),
)

internal val LiquidDark = LiquidColors(
    isDark = true,
    background = Color(0xFF000000),
    cell = Color(0xFF1C1C1E),
    cellRaised = Color(0xFF2C2C2E),
    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    separator = Color(0xA6545458),
    fill = Color(0x5C787880),
    secondaryFill = Color(0x52787880),
    tertiaryFill = Color(0x3D767680),
    cellHighlight = Color(0xFF3A3A3C),
    tint = Color(0xFF0A84FF),
    green = Color(0xFF30D158),
    red = Color(0xFFFF453A),
    orange = Color(0xFFFF9F0A),
    yellow = Color(0xFFFFD60A),
    purple = Color(0xFFBF5AF2),
    pink = Color(0xFFFF375F),
    indigo = Color(0xFF5E5CE6),
    teal = Color(0xFF40C8E0),
    blue = Color(0xFF0A84FF),
    gray = Color(0xFF8E8E93),
)

val LocalLiquidColors = staticCompositionLocalOf { LiquidDark }

/** iOS's measurements. */
object LiquidDimens {
    /** Grouped list sections, which iOS 26 rounds further than before. */
    val SectionCorner = 26.dp

    /** From the edge of the screen to a grouped section. */
    val SectionMargin = 16.dp

    /** From the edge of a cell to its text. */
    val CellPadding = 16.dp

    /** A cell's height with one line; more with a subtitle. */
    val CellHeight = 52.dp

    /** The rounded-square icon of a settings row. */
    val RowIcon = 30.dp

    /** The inline navigation bar. */
    val BarHeight = 52.dp

    /** The floating tab bar. */
    val TabBarHeight = 62.dp

    /** A glass button in a bar. */
    val BarButton = 44.dp

    /** Alerts and sheets. */
    val AlertCorner = 34.dp
}

/**
 * iOS's text styles, at their default Dynamic Type sizes, with San
 * Francisco's own tracking - tight at body sizes, open at the large title.
 * The typeface is the system's: San Francisco is Apple's to ship.
 */
object LiquidType {
    val LargeTitle = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.37.sp)
    val Title1 = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.36.sp)
    val Title2 = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.26).sp)
    val Title3 = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.45).sp)
    val Headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.43).sp)
    val Body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.43).sp)
    val Callout = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = (-0.31).sp)
    val Subheadline = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.23).sp)
    val Footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = (-0.08).sp)
    val Caption1 = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val Caption2 = TextStyle(fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.06.sp)
    val TabLabel = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp)
}

/**
 * Material's slots on iOS's colors, so stock components - sheets, menus,
 * text fields - come out right without being told.
 */
internal fun liquidColorScheme(c: LiquidColors): ColorScheme {
    val base = if (c.isDark) darkColorScheme() else lightColorScheme()
    val raised = if (c.isDark) Color(0xFF2C2C2E) else Color(0xFFFFFFFF)
    return base.copy(
        primary = c.tint,
        onPrimary = Color.White,
        primaryContainer = c.tint.copy(alpha = 0.15f),
        onPrimaryContainer = c.tint,
        inversePrimary = c.tint,
        secondary = c.secondaryLabel,
        onSecondary = c.cell,
        secondaryContainer = c.tertiaryFill,
        onSecondaryContainer = c.label,
        tertiary = c.tint,
        onTertiary = Color.White,
        tertiaryContainer = c.tint.copy(alpha = 0.15f),
        onTertiaryContainer = c.tint,
        background = c.background,
        onBackground = c.label,
        surface = c.background,
        onSurface = c.label,
        surfaceVariant = c.tertiaryFill,
        onSurfaceVariant = c.secondaryLabel,
        surfaceTint = Color.Transparent,
        inverseSurface = if (c.isDark) Color(0xFFE5E5EA) else Color(0xFF2C2C2E),
        inverseOnSurface = if (c.isDark) Color.Black else Color.White,
        error = c.red,
        onError = Color.White,
        errorContainer = c.red.copy(alpha = 0.15f),
        onErrorContainer = c.red,
        outline = c.separator,
        outlineVariant = c.separator,
        scrim = Color.Black,
        surfaceBright = c.cell,
        surfaceDim = c.background,
        surfaceContainerLowest = c.cell,
        surfaceContainerLow = c.cell,
        surfaceContainer = c.cell,
        surfaceContainerHigh = raised,
        surfaceContainerHighest = c.cellRaised,
    )
}

/** Material's type scale on iOS's text styles. */
internal fun liquidTypography(base: Typography = Typography()): Typography = base.copy(
    headlineLarge = LiquidType.LargeTitle,
    headlineMedium = LiquidType.Title1,
    headlineSmall = LiquidType.Title2,
    titleLarge = LiquidType.Headline,
    titleMedium = LiquidType.Headline,
    titleSmall = LiquidType.Subheadline.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = LiquidType.Body,
    bodyMedium = LiquidType.Subheadline,
    bodySmall = LiquidType.Footnote,
    labelLarge = LiquidType.Body,
    labelMedium = LiquidType.Footnote,
    labelSmall = LiquidType.Caption1,
)

/** Everything round, and round the Apple way. */
internal val LiquidShapes = Shapes(
    extraSmall = ContinuousShape(10.dp),
    small = ContinuousShape(14.dp),
    medium = ContinuousShape(20.dp),
    large = ContinuousShape(LiquidDimens.SectionCorner),
    extraLarge = ContinuousShape(LiquidDimens.AlertCorner),
)

/** iOS does not ripple: Material's own components get only the faintest wash. */
@OptIn(ExperimentalMaterial3Api::class)
internal fun liquidRippleConfiguration(c: LiquidColors) = RippleConfiguration(
    color = c.label,
    rippleAlpha = RippleAlpha(draggedAlpha = 0.06f, focusedAlpha = 0.06f, hoveredAlpha = 0.03f, pressedAlpha = 0.07f),
)

/**
 * A cell held down on iOS: it turns systemGray4 at once and eases back when
 * let go - no ripple, no delay.
 */
internal object LiquidIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = CellHighlightNode(interactionSource)
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = -2
}

private class CellHighlightNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val strength = Animatable(0f)
    private var held = 0

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        held++
                        coroutineScope.launch { strength.snapTo(1f) }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        held = (held - 1).coerceAtLeast(0)
                        if (held == 0) coroutineScope.launch { strength.animateTo(0f, tween(durationMillis = 350)) }
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val alpha = strength.value
        if (alpha > 0f) drawRect(currentValueOf(LocalLiquidColors).cellHighlight, alpha = alpha)
        drawContent()
    }
}
