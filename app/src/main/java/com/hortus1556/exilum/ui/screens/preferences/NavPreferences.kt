package com.hortus1556.exilum.ui.screens.preferences

import androidx.compose.runtime.remember
import com.hortus1556.exilum.ui.theme.isLiquid
import com.hortus1556.exilum.ui.components.pagePredictivePopTransitionSpec
import com.hortus1556.exilum.ui.components.pagePopTransitionSpec
import com.hortus1556.exilum.ui.components.pagePushTransitionSpec
import com.hortus1556.exilum.ui.liquid.rememberLiquidPageDecorator
import com.hortus1556.exilum.ui.liquid.LiquidNavDirection
import com.hortus1556.exilum.ui.components.popTransitionSpec
import com.hortus1556.exilum.ui.components.predictivePopTransitionSpec
import com.hortus1556.exilum.ui.components.pushTransitionSpec
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.hortus1556.exilum.data.ModuleRepository
import org.koin.compose.koinInject
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.Serializable
import com.hortus1556.exilum.ui.popOrKeepRoot
import com.hortus1556.exilum.ui.pushOnce

private sealed interface NavKeyPreferences : NavKey {
    @Serializable
    data object Preferences : NavKeyPreferences

    @Serializable
    data object About : NavKeyPreferences

    @Serializable
    data object Formats : NavKeyPreferences

    /** One section of the settings as a page of its own - One UI's layout. */
    @Serializable
    data class Category(val name: String) : NavKeyPreferences
}

@Composable
fun NavPreferences(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val backStack = rememberNavBackStack(NavKeyPreferences.Preferences)

    BackHandler(enabled = backStack.size > 1) {
        backStack.popOrKeepRoot()
    }

    // Under Liquid Glass the pages move themselves, so each can hold its
    // bar still while it slides, as iOS's navigation bar stays put.
    val direction = remember { LiquidNavDirection() }
    val pageDecorator = rememberLiquidPageDecorator<NavKey>(direction)
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.popOrKeepRoot() },
        transitionSpec = pagePushTransitionSpec(direction),
        popTransitionSpec = pagePopTransitionSpec(direction),
        predictivePopTransitionSpec = pagePredictivePopTransitionSpec(direction),
        entryDecorators = listOfNotNull(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
            pageDecorator.takeIf { isLiquid },
        ),
        entryProvider = entryProvider {
            entry<NavKeyPreferences.Preferences> {
                PreferencesScreen(
                    modifier = modifier,
                    snackbarHostState = snackbarHostState,
                    onBack = onBack,
                    onFormats = { backStack.pushOnce(NavKeyPreferences.Formats) },
                    onCategory = { backStack.pushOnce(NavKeyPreferences.Category(it.name)) },
                    onAbout = { backStack.pushOnce(NavKeyPreferences.About) }
                )
            }
            entry<NavKeyPreferences.Category> { key ->
                PreferencesScreen(
                    modifier = modifier,
                    snackbarHostState = snackbarHostState,
                    category = PreferenceCategory.valueOf(key.name),
                    onBack = backStack::popOrKeepRoot,
                    onFormats = { backStack.pushOnce(NavKeyPreferences.Formats) },
                    onAbout = { backStack.pushOnce(NavKeyPreferences.About) }
                )
            }
            entry<NavKeyPreferences.About> {
                PreferencesAbout(
                    modifier = modifier,
                    onBack = backStack::popOrKeepRoot
                )
            }
            entry<NavKeyPreferences.Formats> {
                val modules = koinInject<ModuleRepository>()
                val groups = remember(modules) { modules.formatGroups().toPersistentList() }
                PreferencesFormats(
                    modifier = modifier,
                    groups = groups,
                    onBack = backStack::popOrKeepRoot,
                )
            }
        }
    )
}
