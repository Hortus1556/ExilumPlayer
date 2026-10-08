package com.hortus1556.exilum.ui.screens.preferences

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hortus1556.exilum.R
import com.hortus1556.exilum.data.FormatGroup
import com.hortus1556.exilum.ui.components.AppTopAppBar
import com.hortus1556.exilum.ui.components.BackButton
import com.hortus1556.exilum.ui.components.appContentWindowInsets
import com.hortus1556.exilum.ui.components.rememberAppBarScrollBehavior
import com.hortus1556.exilum.ui.liquid.LiquidDimens
import com.hortus1556.exilum.ui.liquid.LiquidType
import com.hortus1556.exilum.ui.liquid.LocalLiquidBackdrop
import com.hortus1556.exilum.ui.liquid.liquidPage
import com.hortus1556.exilum.ui.liquid.liquidPageContainer
import com.hortus1556.exilum.ui.liquid.pageSides
import com.hortus1556.exilum.ui.liquid.rememberLiquidPageBackdrop
import com.hortus1556.exilum.ui.liquid.scrollPadding
import com.hortus1556.exilum.ui.oneui.OneUiDimens
import com.hortus1556.exilum.ui.screens.preferences.components.InfoSectionHeader
import com.hortus1556.exilum.ui.screens.preferences.components.infoCard
import com.hortus1556.exilum.ui.screens.preferences.components.infoSecondaryColor
import com.hortus1556.exilum.ui.screens.preferences.components.infoTagColor
import com.hortus1556.exilum.ui.theme.AppTheme
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.theme.isOneUi
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * What the app plays, by the library that plays it: a section per decoder,
 * in the order a file is offered to them, each with what it is for, its
 * licence and its extensions.
 */
@Composable
internal fun PreferencesFormats(
    groups: ImmutableList<FormatGroup>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = remember(groups) { groups.flatMap { it.extensions }.distinct().size }
    val appBarScroll = rememberAppBarScrollBehavior()
    // Under Liquid Glass the page is what its glass bends.
    val pageBackdrop = rememberLiquidPageBackdrop()
    CompositionLocalProvider(LocalLiquidBackdrop provides pageBackdrop) {
        Scaffold(
            containerColor = liquidPageContainer(),
            modifier = modifier.nestedScroll(appBarScroll.nestedScrollConnection),
            contentWindowInsets = appContentWindowInsets(),
            topBar = {
                AppTopAppBar(
                    scrollBehavior = appBarScroll,
                    title = { Text(text = stringResource(R.string.pref_formats_mpt)) },
                    subtitle = { Text(text = pluralStringResource(R.plurals.formats_total, total, total)) },
                    navigationIcon = { BackButton(onBack = onBack) },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding.pageSides())
                    .liquidPage(pageBackdrop),
                contentPadding = padding.scrollPadding(),
            ) {
                item(key = "intro") { FormatsIntro() }
                groups.forEach { group ->
                    val engine = NativeEngine.of(group.id)
                    item(key = "title:" + group.id) {
                        InfoSectionHeader(text = engine?.title ?: group.id)
                    }
                    item(key = group.id) {
                        EngineCard(engine = engine, extensions = group.extensions)
                    }
                }
                item(key = "end") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** How a file finds its decoder, which is what decides a shared extension. */
@Composable
private fun FormatsIntro() {
    val margin = when {
        isLiquid -> LiquidDimens.SectionMargin + 16.dp
        isOneUi -> OneUiDimens.CardMargin + 16.dp
        else -> 32.dp
    }
    Text(
        text = stringResource(R.string.formats_intro),
        style = if (isLiquid) LiquidType.Footnote else MaterialTheme.typography.bodySmall,
        color = infoSecondaryColor(),
        modifier = Modifier.padding(start = margin, end = margin, top = 8.dp, bottom = 4.dp),
    )
}

/** A decoder's card: what it plays, under what licence, and every extension. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EngineCard(engine: NativeEngine?, extensions: ImmutableList<String>) {
    val secondary = infoSecondaryColor()
    Column(
        modifier = Modifier
            .infoCard()
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (engine != null) {
            Text(
                text = stringResource(engine.description),
                style = if (isLiquid) LiquidType.Subheadline else MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = listOfNotNull(
                engine?.licence,
                pluralStringResource(R.plurals.formats_total, extensions.size, extensions.size),
            ).joinToString(" · "),
            style = if (isLiquid) LiquidType.Footnote else MaterialTheme.typography.labelMedium,
            color = secondary,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val tag = infoTagColor()
            extensions.forEach { extension ->
                Text(
                    text = extension.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(tag)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        PreferencesFormats(
            groups = persistentListOf(
                FormatGroup("libopenmpt", persistentListOf("it", "mod", "s3m", "xm")),
                FormatGroup("hively", persistentListOf("ahx", "hvl", "thx")),
            ),
            onBack = {},
        )
    }
}
