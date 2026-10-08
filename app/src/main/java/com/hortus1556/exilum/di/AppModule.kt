package com.hortus1556.exilum.di

import androidx.room.Room
import com.hortus1556.exilum.core.Constants
import com.hortus1556.exilum.core.FileLoggingTree
import com.hortus1556.exilum.data.DownloadHistoryRepository
import com.hortus1556.exilum.data.ModArchiveService
import com.hortus1556.exilum.data.ModuleRepository
import com.hortus1556.exilum.data.PlaylistRepository
import com.hortus1556.exilum.data.StorageAccessEvents
import com.hortus1556.exilum.db.AppDatabase
import com.hortus1556.exilum.db.MIGRATION_8_9
import com.hortus1556.exilum.db.MIGRATION_10_11
import com.hortus1556.exilum.data.SubsongNamesRepository
import com.hortus1556.exilum.db.MIGRATION_11_12
import com.hortus1556.exilum.db.MIGRATION_12_13
import com.hortus1556.exilum.data.FileOverridesRepository
import com.hortus1556.exilum.data.LyricsRepository
import com.hortus1556.exilum.db.MIGRATION_9_10
import com.hortus1556.exilum.db.AppPreferences
import com.hortus1556.exilum.player.ModPlayer
import com.hortus1556.exilum.player.PlayerEngine
import com.hortus1556.exilum.player.PlayerViewModel
import com.hortus1556.exilum.player.soundcheck.LoudnessAnalyzer
import com.hortus1556.exilum.data.SoundCheckStore
import com.hortus1556.exilum.ui.screens.browser.FileBrowserViewModel
import com.hortus1556.exilum.ui.screens.downloads.DownloadsViewModel
import com.hortus1556.exilum.ui.screens.playlists.PlaylistsViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.xml.xml
import com.hortus1556.exilum.data.FavouritesRepository
import com.hortus1556.exilum.data.TrackerConfigStore
import com.hortus1556.exilum.ui.screens.favourites.FavouritesViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    // Core
    single { AppPreferences(androidContext()) }
    single { FileLoggingTree(androidContext()) }
    single { StorageAccessEvents() }

    // ViewModels
    viewModel { FileBrowserViewModel(androidContext(), get(), get(), get(), get(), get()) }
    viewModel { DownloadsViewModel(androidContext(), get(), get(), get(), get()) }
    viewModel { PlayerViewModel(androidContext(), get(), get(), get(), get(), get(), get(), get()) }
    // Not handed a PlayerViewModel: a view model asked for here is a new one
    // each time, which no screen shows and nothing clears. The screen passes
    // the activity's - see PlaylistsViewModel.playPlaylist.
    viewModel { PlaylistsViewModel(androidContext(), get(), get(), get()) }
    viewModel { FavouritesViewModel(get(), get()) }

    // Database
    single {
        Room.databaseBuilder(
            androidContext(),
            AppDatabase::class.java,
            Constants.ROOM_DATABASE_NAME,
        ).addMigrations(MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
            .fallbackToDestructiveMigration(true)
            .build()
    }
    single { get<AppDatabase>().moduleMetadataDao() }
    single { get<AppDatabase>().downloadHistoryDao() }
    single { get<AppDatabase>().playlistDao() }
    single { get<AppDatabase>().favouriteDao() }
    single { get<AppDatabase>().subsongNameDao() }
    single { get<AppDatabase>().fileOverridesDao() }
    single { ModuleRepository(androidContext(), get(), get(), get()) }
    single { DownloadHistoryRepository(get()) }
    single { PlaylistRepository(get()) }
    single { FavouritesRepository(get()) }
    single { SubsongNamesRepository(get()) }
    single { FileOverridesRepository(get()) }
    single { LyricsRepository(androidContext(), get()) }
    single { TrackerConfigStore(androidContext(), get(), get(), get(), get(), get()) }

    // Downloads
    single {
        HttpClient(engineFactory = Android) {
            install(ContentNegotiation) { xml() }
            defaultRequest {
                url(Constants.HTTP_BASE_URL)
                accept(ContentType.Application.Xml)
            }
        }
    }
    single { ModArchiveService(get()) }

    // Media Player
    single { SoundCheckStore(androidContext()) }
    single { LoudnessAnalyzer(androidContext(), get(), get()) }
    single { PlayerEngine(androidContext(), get(), get(), get(), get()) }
    single { ModPlayer(androidContext(), get(), get(), get()) }
}
