package com.hortus1556.exilum.ui.liquid

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.ColumnScope
import com.hortus1556.exilum.ui.theme.isLiquid
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.IntrinsicSize
import kotlin.math.roundToInt
import androidx.compose.ui.layout.layout
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.Icons
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * iOS's filled buttons: [prominent] is the tint with white text - the one
 * thing the page is for - and otherwise the gray fill with the text in the
 * tint, for everything beside it. Capsules, as iOS 26 draws them, that give
 * way a little under the finger.
 */
@Composable
fun LiquidFillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    prominent: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = LocalLiquidColors.current
    val source = remember { MutableInteractionSource() }
    val content = if (prominent) Color.White else colors.tint
    Row(
        modifier = modifier
            .height(50.dp)
            .liquidPress(source, grow = 0.97f)
            .clip(CapsuleShape)
            .background(if (prominent) colors.tint else colors.tertiaryFill)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = LiquidType.Body.copy(fontWeight = FontWeight.SemiBold),
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * iOS 26's segmented control: a gray capsule with the chosen segment lifted
 * out of it on a lighter one, which slides to the segment tapped.
 */
@Composable
fun LiquidSegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalLiquidColors.current
    val haptic = LocalHapticFeedback.current
    val at by animateFloatAsState(
        targetValue = selected.toFloat(),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "segmented",
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(CapsuleShape)
            .background(colors.tertiaryFill)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(2.dp),
    ) {
        val count = options.size.coerceAtLeast(1)
        val segment = maxWidth / count
        Box(
            modifier = Modifier
                .graphicsLayer { translationX = at * segment.toPx() }
                .width(segment)
                .fillMaxHeight()
                .shadow(3.dp, CapsuleShape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.12f))
                .background(if (colors.isDark) Color(0xFF636366) else Color.White, CapsuleShape),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CapsuleShape)
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            enabled = enabled,
                            role = Role.Tab,
                        ) {
                            if (index != selected) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(index)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option,
                        style = LiquidType.Subheadline.copy(
                            fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = colors.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** One action in a [LiquidFabMenu]. */
class LiquidMenuAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * The pane iOS 26 hangs a menu on: a rounded sheet of frosted glass, its
 * corners continuous, everything in it clipped to them.
 *
 * Offered on its own so a menu that has to be positioned by something else -
 * a DropdownMenu, which knows how to sit under what opened it and how to
 * close itself - can still look like the rest of them. Give that menu no
 * container of its own and put this inside it.
 */
@Composable
fun LiquidMenuPane(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    LiquidPane(
        shape = ContinuousShape(14.dp),
        modifier = modifier
            .width(IntrinsicSize.Max)
            .widthIn(min = 200.dp, max = 300.dp),
    ) {
        androidx.compose.foundation.layout.Column(content = content)
    }
}

/** A hairline between two rows of a menu, as iOS draws it. */
@Composable
fun LiquidMenuSeparator() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.33.dp)
            .background(LocalLiquidColors.current.separator),
    )
}

/**
 * A page's floating button as iOS 26 draws one: a circle of glass in the
 * tint. Tapped, it turns into a close button and the page's menu grows out
 * of it - a rounded pane with each action's name on the left and its symbol
 * on the right, as iOS lays out a menu.
 */
@Composable
fun LiquidFabMenu(
    expanded: Boolean,
    onExpand: () -> Unit,
    icon: ImageVector,
    actions: List<LiquidMenuAction>,
    modifier: Modifier = Modifier,
    /** How folded the tab bar is: folded, the button is the folded row's height. */
    fold: () -> Float = { 0f },
) {
    val colors = LocalLiquidColors.current
    val backdrop = LocalLiquidBackdrop.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val light = remember(scope) { LiquidPressHighlight(scope) }
    val carrier = LocalLiquidGlassCarrier.current
    // Read where it is drawn, and not here: read here, all of this - the
    // button's glass with it - was built again for every frame the menu
    // took to open or close.
    val opening = animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
        label = "fab_menu",
    )
    val menuShown by remember { derivedStateOf { opening.value > 0.01f } }
    // A menu belongs to the page it was opened on: swiping to another closes it.
    val present = LocalLiquidPageMotion.current?.present ?: true
    LaunchedEffect(present) { if (!present && expanded) onExpand() }
    androidx.compose.foundation.layout.Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (menuShown) {
            // A menu like every other: the windows' pane - following the
            // glass opacity, lit where it is touched - and the menus' rows.
            // It had glass of its own, as thick whatever the setting, and
            // rows that only grayed under a finger.
            LiquidMenuPane(
                modifier = Modifier.graphicsLayer {
                    // Out of the button's own corner, and back into it.
                    val open = opening.value
                    alpha = ((open - 0.04f) / 0.96f).coerceIn(0f, 1f)
                    val scale = 0.84f + 0.16f * open
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)
                },
            ) {
                actions.forEachIndexed { index, action ->
                    if (index > 0) LiquidMenuSeparator()
                    LiquidMenuItem(text = action.label, onClick = action.onClick, icon = action.icon)
                }
            }
        }
        val source = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .layout { measurable, _ ->
                    val side = androidx.compose.ui.util.lerp(56.dp.toPx(), 52.dp.toPx(), fold().coerceIn(0f, 1f)).roundToInt()
                    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(side, side))
                    layout(side, side) { placeable.place(0, 0) }
                }
                .liquidPress(source)
                .then(
                    if (backdrop != null) {
                        Modifier
                            .then(light.gestureModifier)
                            // A floating action's glass is a piece of the bars' sheet.
                            .liquidButtonGlass(
                                backdrop, colors.buttonGlass, frost = colors.frost, tint = colors.tintGlass,
                                union = carrier?.union, carried = carrier?.presence,
                                layerBlock = { liquidGel(light) },
                            )
                            .then(light.modifier)
                    } else {
                        Modifier
                            .shadow(12.dp, CapsuleShape, ambientColor = colors.tint.copy(alpha = 0.3f), spotColor = colors.tint.copy(alpha = 0.3f))
                            .liquidGlassPlain(CapsuleShape, colors.tintGlassPlain, highlight = 0.8f)
                    }
                )
                .clip(CapsuleShape)
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onExpand),
            contentAlignment = Alignment.Center,
        ) {
            // The two glyphs cross over in place. Turning one into the other
            // by spinning it is not something iOS does to a button.
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(26.dp)
                    .graphicsLayer {
                        val open = opening.value
                        alpha = (1f - open * 1.6f).coerceIn(0f, 1f)
                        val scale = 1f - 0.22f * open
                        scaleX = scale
                        scaleY = scale
                    },
            )
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer {
                        val open = opening.value
                        alpha = ((open - 0.35f) / 0.65f).coerceIn(0f, 1f)
                        val scale = 0.78f + 0.22f * open
                        scaleX = scale
                        scaleY = scale
                    },
            )
        }
    }
}

/**
 * The dim behind an open menu, and the tap anywhere else that closes it -
 * what iOS puts under one. Nothing at all in the other styles, which bring
 * their own.
 */
@Composable
fun LiquidMenuScrim(expanded: Boolean, onDismiss: () -> Unit) {
    if (!isLiquid) return
    val shade by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 360f),
        label = "menu_scrim",
    )
    if (shade <= 0.01f) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = shade }
            .background(Color.Black.copy(alpha = 0.22f))
            .pointerInput(expanded) { detectTapGestures { if (expanded) onDismiss() } },
    )
}
