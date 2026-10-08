package com.hortus1556.exilum.ui.oneui

import com.hortus1556.exilum.ui.liquid.OwnWindowAnimation
import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** How far the screen behind a pop-up is dimmed. */
private const val DIM_AMOUNT = 0.32f

/** How much the screen behind a pop-up is blurred, where the device can. */
private const val BLUR_BEHIND_PX = 48

/**
 * One UI's pop-up. Down at the bottom of the screen where the thumb is, not
 * in the middle; round-cornered and slid up into place; the title on the
 * left in bold; and the buttons as words in the accent color splitting the
 * width between them, a hairline apart. What is behind is dimmed and, on a
 * device that can, blurred.
 *
 * Icons are left out: One UI's pop-ups do not have them.
 */
@Composable
fun OneUiDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    val colors = LocalOneUiColors.current
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
            securePolicy = properties.securePolicy,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        OwnWindowAnimation()
        val view = LocalView.current
        SideEffect {
            val window = com.hortus1556.exilum.ui.liquid.dialogWindow(view) ?: return@SideEffect
            window.setDimAmount(DIM_AMOUNT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply { blurBehindRadius = BLUR_BEHIND_PX }
            }
        }

        val shown = remember { MutableTransitionState(false) }.apply { targetState = true }

        // The whole window is the dialog's, so a tap outside the card has to
        // be caught here rather than by the platform.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(properties.dismissOnClickOutside) {
                    detectTapGestures {
                        if (properties.dismissOnClickOutside) onDismissRequest()
                    }
                }
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(OneUiDimens.CardMargin),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(
                visibleState = shown,
                enter = slideInVertically(
                    animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
                    initialOffsetY = { it / 3 },
                ) + fadeIn(tween(durationMillis = 150)) + scaleIn(
                    initialScale = 0.96f,
                    animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
                ),
            ) {
                Column(
                    modifier = modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .shadow(elevation = 12.dp, shape = RoundedCornerShape(OneUiDimens.CardCorner))
                        .clip(RoundedCornerShape(OneUiDimens.CardCorner))
                        .background(colors.dialog)
                        // Taps on the card are the card's, not a tap outside.
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(top = 24.dp, bottom = 8.dp),
                ) {
                    if (title != null) {
                        Box(modifier = Modifier.padding(horizontal = OneUiDimens.RowPadding)) {
                            CompositionLocalProvider(LocalContentColor provides colors.text) {
                                ProvideTextStyle(OneUiType.DialogTitle) { title() }
                            }
                        }
                        Spacer(modifier = Modifier.height(if (text != null) 14.dp else 8.dp))
                    }
                    if (text != null) {
                        Box(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .padding(horizontal = OneUiDimens.RowPadding),
                        ) {
                            CompositionLocalProvider(LocalContentColor provides colors.text) {
                                ProvideTextStyle(OneUiType.DialogText) { text() }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CompositionLocalProvider(LocalContentColor provides colors.accent) {
                            ProvideTextStyle(OneUiType.Button) {
                                if (dismissButton != null) {
                                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                        dismissButton()
                                    }
                                    Box(
                                        modifier = Modifier
                                            .padding(vertical = 14.dp)
                                            .width(1.dp)
                                            .fillMaxHeight()
                                            .background(colors.divider),
                                    )
                                }
                                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    confirmButton()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
