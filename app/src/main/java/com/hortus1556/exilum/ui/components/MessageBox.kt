package com.hortus1556.exilum.ui.components

import androidx.compose.ui.text.font.FontWeight
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.liquid.LocalLiquidColors
import com.hortus1556.exilum.ui.liquid.LiquidType
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.tooling.preview.datasource.*
import androidx.compose.ui.unit.*
import com.hortus1556.exilum.R
import com.hortus1556.exilum.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MessageBox(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    title: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    if (isLiquid) {
        LiquidMessage(text = text, modifier = modifier, icon = icon, title = title, actions = actions)
        return
    }
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
        content = {
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.small,
                tonalElevation = 3.dp,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    icon?.let {
                        Icon(
                            imageVector = it,
                            contentDescription = stringResource(R.string.desc_message_box_icon),
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .padding(bottom = 16.dp)
                                .size(24.dp),
                        )
                    }

                    title?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    }

                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    actions?.let { item ->
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            content = { item() },
                        )
                    }
                }
            }
        }
    )
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            content = {
                MessageBox(
                    text = LoremIpsum(50).values.first(),
                    actions = {
                        TextButton(onClick = {}) { Text(text = stringResource(R.string.confirm)) }
                        TextButton(onClick = {}) { Text(text = stringResource(R.string.confirm)) }
                    }
                )
            }
        )
    }
}

/**
 * iOS's empty state: no card, just a large gray symbol, the title in bold
 * and a line or two under it in gray, centred on the page, with any action
 * under that.
 */
@Composable
private fun LiquidMessage(
    text: String,
    modifier: Modifier,
    icon: ImageVector?,
    title: String?,
    actions: (@Composable RowScope.() -> Unit)?,
) {
    val colors = LocalLiquidColors.current
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = stringResource(R.string.desc_message_box_icon),
                    tint = colors.secondaryLabel,
                    modifier = Modifier
                        .padding(bottom = 14.dp)
                        .size(52.dp),
                )
            }
            title?.let {
                Text(
                    text = it,
                    style = LiquidType.Title2.copy(fontWeight = FontWeight.Bold),
                    color = colors.label,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Text(
                text = text,
                style = LiquidType.Body,
                color = colors.secondaryLabel,
                textAlign = TextAlign.Center,
            )
            actions?.let { item ->
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.Center, content = { item() })
            }
        }
    }
}
