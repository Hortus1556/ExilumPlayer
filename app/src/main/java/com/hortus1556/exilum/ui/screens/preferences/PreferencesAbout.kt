package com.hortus1556.exilum.ui.screens.preferences

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hortus1556.exilum.BuildConfig
import com.hortus1556.exilum.R
import com.hortus1556.exilum.ui.components.AppAlertDialog
import com.hortus1556.exilum.ui.components.AppTopAppBar
import com.hortus1556.exilum.ui.components.BackButton
import com.hortus1556.exilum.ui.components.appContentWindowInsets
import com.hortus1556.exilum.ui.components.rememberAppBarScrollBehavior
import com.hortus1556.exilum.ui.liquid.ContinuousShape
import com.hortus1556.exilum.ui.liquid.LiquidType
import com.hortus1556.exilum.ui.liquid.LocalLiquidBackdrop
import com.hortus1556.exilum.ui.liquid.liquidPage
import com.hortus1556.exilum.ui.liquid.liquidPageContainer
import com.hortus1556.exilum.ui.liquid.pageSides
import com.hortus1556.exilum.ui.liquid.rememberLiquidPageBackdrop
import com.hortus1556.exilum.ui.liquid.scrollPadding
import com.hortus1556.exilum.ui.screens.preferences.components.InfoSectionHeader
import com.hortus1556.exilum.ui.screens.preferences.components.infoCard
import com.hortus1556.exilum.ui.screens.preferences.components.infoSecondaryColor
import com.hortus1556.exilum.ui.theme.AppTheme
import com.hortus1556.exilum.ui.theme.isLiquid
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries

/** The app's own entry in the library list: its licence, which the header opens. */
private const val APP_ID = "app.exilum"

/** The native libraries, in the order the formats screen gives their decoders. */
private val ENGINE_ORDER = listOf(
    "native.libopenmpt", "native.hively", "native.furnace", "native.libsidplayfp", "native.libresidfp",
    "native.custommade", "native.mgba", "native.vio2sf", "native.lazyusf2", "native.aopsf", "native.ffmpeg",
    "native.vgmstream",
    "native.libvgm", "native.gme", "native.adplug", "native.libbinio",
)

/** The code the app carries that is not an engine of its own. */
private val CODE_ORDER = listOf(
    "native.minimp3", "native.stb_vorbis", "native.signalsmith-stretch", "native.signalsmith-linear", "code.drakmod",
)

/** Every library in the build, sorted into the About screen's sections. */
private class AboutSections(libs: Libs?) {
    private val all = libs?.libraries.orEmpty()
    private val byId = all.associateBy { it.uniqueId }

    val app: Library? = byId[APP_ID]
    val engines: List<Library> = ENGINE_ORDER.mapNotNull(byId::get)
    val code: List<Library> = CODE_ORDER.mapNotNull(byId::get)
    val android: List<Library> = all
        .filter { it.uniqueId != APP_ID && it.uniqueId !in ENGINE_ORDER && it.uniqueId !in CODE_ORDER }
        .sortedBy { it.name.lowercase() }
}

/**
 * What the app is, whose, and under what licence; then every library it is
 * built from - the audio engines, the other code it carries and the Android
 * libraries - each opening its licence.
 */
@Composable
fun PreferencesAbout(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val libraries by produceLibraries(R.raw.aboutlibraries)
    val sections = remember(libraries) { AboutSections(libraries) }
    var opened by remember { mutableStateOf<Library?>(null) }
    opened?.let { LicenceDialog(library = it, onDismiss = { opened = null }) }

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
                    title = { Text(text = stringResource(R.string.pref_title_about)) },
                    navigationIcon = { BackButton(onBack = onBack) }
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
                item(key = "header") { AboutHeader() }

                item(key = "licence:title") { InfoSectionHeader(text = stringResource(R.string.about_section_licence)) }
                item(key = "licence") {
                    InfoRow(
                        title = stringResource(R.string.about_licence),
                        subtitle = stringResource(R.string.about_licence_desc),
                        index = 0,
                        count = 1,
                        onClick = { opened = sections.app },
                    )
                }

                librarySection(R.string.about_section_engines, "engines", sections.engines) { opened = it }
                librarySection(R.string.about_section_code, "code", sections.code) { opened = it }
                librarySection(R.string.about_section_android, "android", sections.android) { opened = it }
                item(key = "end") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** A headed card of [libraries], one row each. */
private fun LazyListScope.librarySection(
    title: Int,
    key: String,
    libraries: List<Library>,
    onOpen: (Library) -> Unit,
) {
    if (libraries.isEmpty()) return
    item(key = "$key:title") { InfoSectionHeader(text = stringResource(title)) }
    itemsIndexed(items = libraries, key = { _, library -> "$key:${library.uniqueId}" }) { index, library ->
        InfoRow(
            title = library.name,
            subtitle = listOfNotNull(
                library.developers.mapNotNull { it.name }.joinToString(", ").ifBlank { null },
                library.licenceLabel(),
            ).joinToString(" · "),
            trailing = library.artifactVersion,
            index = index,
            count = libraries.size,
            onClick = { onOpen(library) },
        )
    }
}

/** The icon, the name, the version and whose it is. */
@Composable
private fun AboutHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // The launcher icon's own layers, cut to the shape of a home screen icon.
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(ContinuousShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // An adaptive icon shows the middle two thirds of its layers.
            Image(
                painter = painterResource(R.drawable.ic_launcher_background),
                contentDescription = null,
                modifier = Modifier.requiredSize(132.dp),
            )
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.requiredSize(132.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = if (isLiquid) LiquidType.Title1 else MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = if (isLiquid) LiquidType.Subheadline else MaterialTheme.typography.bodyMedium,
            color = infoSecondaryColor(),
        )
        Text(
            text = stringResource(R.string.about_copyright),
            style = if (isLiquid) LiquidType.Subheadline else MaterialTheme.typography.bodyMedium,
            color = infoSecondaryColor(),
            textAlign = TextAlign.Center,
        )
    }
}

/** One row of a card, which opens something. */
@Composable
private fun InfoRow(
    title: String,
    subtitle: String,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    ListItem(
        modifier = Modifier
            .infoCard(index, count)
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(text = subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, color = infoSecondaryColor())
        },
        trailingContent = trailing?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = infoSecondaryColor(),
                    maxLines = 1,
                )
            }
        },
    )
}

/** A library's licence texts, and its website when it has one. */
@Composable
private fun LicenceDialog(library: Library, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val website = library.website
    val licences = remember(library) {
        library.licenses
            .filter { !it.licenseContent.isNullOrBlank() }
            .map { it.name to reflowLicence(it.licenseContent.orEmpty()) }
    }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = library.name) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (licences.isEmpty()) {
                    Text(text = library.licenceLabel() ?: stringResource(R.string.about_no_licence))
                }
                licences.forEach { (name, content) ->
                    Text(text = name, fontWeight = FontWeight.SemiBold)
                    Text(text = content, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.close)) }
        },
        dismissButton = website?.let {
            {
                TextButton(
                    onClick = {
                        runCatching { uriHandler.openUri(it) }
                        onDismiss()
                    },
                ) { Text(text = stringResource(R.string.about_website)) }
            }
        },
    )
}

/** A line that starts an item of a list: a bullet, or a number or letter with its point or bracket. */
private val LIST_ITEM = Regex("""^([*•-]|o|\(?[0-9]{1,2}[.)]|\(?[a-z][.)])\s""")

/**
 * A licence file's text, wrapped at seventy-odd columns for a terminal, as
 * paragraphs a dialog can wrap for itself: the lines of each paragraph are
 * joined, except where one starts an item of a list.
 */
private fun reflowLicence(text: String): String =
    text.replace("\r\n", "\n").trim()
        .split(Regex("\n[ \t]*\n+"))
        .joinToString("\n\n") { paragraph ->
            val lines = paragraph.lines().map { it.trim() }.filter { it.isNotEmpty() }
            buildString {
                lines.forEachIndexed { i, line ->
                    if (i > 0) append(if (LIST_ITEM.containsMatchIn(line)) '\n' else ' ')
                    append(line.replace(Regex(" {2,}"), " "))
                }
            }
        }

/**
 * The library's licences by their short names. Some POMs name a licence
 * AboutLibraries cannot read beside one it can; only the readable ones are
 * named where there are any.
 */
private fun Library.licenceLabel(): String? {
    val readable = licenses.filter { !it.licenseContent.isNullOrBlank() }.ifEmpty { licenses }
    return readable.joinToString(", ") { it.spdxId ?: it.name }.ifBlank { null }
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        PreferencesAbout(onBack = {})
    }
}
