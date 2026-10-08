package com.hortus1556.exilum

import android.app.Application
import android.os.Build
import java.io.File
import android.util.Log
import com.hortus1556.exilum.core.CrashHandler
import com.hortus1556.exilum.data.TrackerConfigStore
import com.hortus1556.exilum.core.FileLoggingTree
import com.hortus1556.exilum.db.AppPreferences
import com.hortus1556.exilum.di.appModule
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import timber.log.Timber

class App : Application() {
    private val appScope = MainScope()

    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority == Log.DEBUG) return
            Log.println(priority, "Mod Player", message)
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Sound Check's analysis process (see LoudnessService) is the same
        // application started again, and wants none of this: the database,
        // the preferences and tracker.conf belong to the main process, and a
        // second writer to tracker.conf would corrupt it.
        if (currentProcessName().endsWith(":loudness")) {
            Timber.plant(ReleaseTree())
            return
        }

        startKoin {
            androidContext(this@App)
            modules(appModule)
        }

        Timber.plant(if (BuildConfig.DEBUG) Timber.DebugTree() else ReleaseTree())

        val fileLoggingTree = get<FileLoggingTree>()
        Timber.plant(fileLoggingTree)
        appScope.launch {
            get<AppPreferences>().getFileLoggingFlow().collect { enabled ->
                fileLoggingTree.enabled = enabled
            }
        }

        // Restores playlists and favourites from tracker.conf in the user's
        // music folder, then keeps that file in step with every later change -
        // see TrackerConfigStore for why the file outranks the database.
        get<TrackerConfigStore>().start(appScope)

        CrashHandler.initialize(this@App)
    }

    private fun currentProcessName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().trim('\u0000', ' ') }.getOrDefault("")
        }

    override fun onTerminate() {
        super.onTerminate()
        appScope.cancel()
    }
}
