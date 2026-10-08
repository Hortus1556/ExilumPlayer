package com.hortus1556.exilum.ui.screens.favourites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hortus1556.exilum.data.FavouritesRepository
import com.hortus1556.exilum.data.SubsongNamesRepository
import com.hortus1556.exilum.db.entity.FavouriteEntity
import com.hortus1556.exilum.db.entity.ModuleEntity
import com.hortus1556.exilum.player.PlayerViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class FavouritesUiState(
    val favourites: ImmutableList<FavouriteEntity> = persistentListOf(),
    /** Subsong names, keyed by subsongKey - see SubsongNamesRepository. */
    val names: Map<String, String> = emptyMap(),
    val isLoading: Boolean = true,
)

class FavouritesViewModel(
    private val repository: FavouritesRepository,
    private val subsongNames: SubsongNamesRepository,
) : ViewModel() {

    val state: StateFlow<FavouritesUiState>
        field = MutableStateFlow(FavouritesUiState())

    init {
        combine(repository.favourites, subsongNames.names) { list, names ->
            FavouritesUiState(
                favourites = list.toImmutableList(),
                names = names,
                isLoading = false,
            )
        }.onEach { state.value = it }.launchIn(viewModelScope)
    }

    fun remove(item: FavouriteEntity) {
        viewModelScope.launch { repository.remove(item.uri, item.subsong) }
    }

    /** Names the subsong [item] saved; blank takes the name away. */
    fun renameSubsong(item: FavouriteEntity, name: String) {
        viewModelScope.launch { subsongNames.rename(item.uri, item.subsong, name) }
    }

    /** Plays the whole favourites list, starting at [index]. */
    fun playAll(player: PlayerViewModel, index: Int, shuffle: Boolean? = null) {
        viewModelScope.launch {
            val modules: List<ModuleEntity> = repository.getFavouriteModules()
            if (modules.isEmpty()) return@launch
            player.playAll(
                files = modules.toImmutableList(),
                startAt = index.coerceIn(0, modules.lastIndex),
                // The listener's own setting unless this was the shuffle button;
                // a plain play used to switch shuffle off.
                isShuffle = shuffle ?: player.state.value.isShuffle,
                // The shuffle button shuffles the favourites, not the library.
                keepRoot = shuffle != true,
            )
        }
    }
}
