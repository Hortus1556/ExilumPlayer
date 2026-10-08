package com.hortus1556.exilum.core

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.hortus1556.exilum.BuildConfig
import com.hortus1556.exilum.util.writeToDownloads
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val LOG_CAT_COUNT = 500
        private const val CRASH_FILE_HISTORY_COUNT = 2
        private const val CRASH_FOLDER = "ExilumPlayer"

        // What a report's name starts with, for writing one and for finding
        // the old ones again. Written out twice the two had drifted apart:
        // reports were saved as "xmp_crash_..." and looked for as "crash_...".
        private const val CRASH_FILE_PREFIX = "xmp_crash_"

        val timestamp: String
            get() = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())

        @Suppress("SameParameterValue")
        private fun logcatCommand(count: Int): String =
            "logcat -d -t $count --pid=${android.os.Process.myPid()}"

        fun initialize(context: Context) {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(
                CrashHandler(context.applicationContext, defaultHandler)
            )
        }
    }

    private val recentLogcat: String
        get() = try {
            val process = Runtime.getRuntime().exec(logcatCommand(LOG_CAT_COUNT))
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "Failed to retrieve logcat: ${e.message}"
        }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // The report is a courtesy and the crash is not: whatever writing it
        // runs into, the system's handler is still handed the crash. Only an
        // Exception used to be caught, so a crash that was an OutOfMemoryError
        // and ran out again while its report was being put together escaped
        // from here unreported, and nothing ended the process - a dead worker
        // thread with its work left half done, or a frozen app.
        try {
            saveCrashToFile(throwable)
        } catch (_: Throwable) {
            // Nothing left to report it with.
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrashToFile(throwable: Throwable) {
        val stackTrace = StringWriter().apply {
            throwable.printStackTrace(PrintWriter(this))
        }.toString()

        val time = timestamp
        val fileName = "$CRASH_FILE_PREFIX$time.txt"

        val crashReport = buildString {
            appendLine("Timestamp: $time")
            appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine()
            appendLine("---------- Device Information ----------")
            appendLine("${Build.MANUFACTURER} - ${Build.BRAND} - ${Build.MODEL}")
            appendLine("Android Version: ${Build.VERSION.RELEASE}")
            appendLine()
            appendLine("---------- Cause ----------")
            appendLine("Exception: ${throwable.javaClass.name}")
            appendLine("Message: ${throwable.message}")
            appendLine()
            appendLine("---------- Stack Trace ----------")
            appendLine(stackTrace)
            appendLine()
            appendLine("---------- Logcat ----------")
            appendLine(recentLogcat)
        }

        writeToDownloads(fileName, crashReport)
        cleanupOldCrashFiles()
    }

    private fun writeToDownloads(fileName: String, content: String) =
        context.writeToDownloads(fileName, content)

    private fun cleanupOldCrashFiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Query MediaStore for crash files and delete the oldest beyond the limit
            val resolver = context.contentResolver
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.DATE_MODIFIED
            )
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
            // From the start of the name: LIKE matches the whole of it, so a
            // pattern that left the prefix off matched no report at all and
            // the folder gained one, with its 500 lines of logcat, per crash.
            val selectionArgs = arrayOf("%$CRASH_FOLDER%", "$CRASH_FILE_PREFIX%.txt")
            val sortOrder = "${MediaStore.Downloads.DATE_MODIFIED} DESC"

            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                var count = 0
                while (cursor.moveToNext()) {
                    count++
                    if (count > CRASH_FILE_HISTORY_COUNT) {
                        val id = cursor.getLong(idCol)
                        val deleteUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                            .buildUpon().appendPath(id.toString()).build()
                        resolver.delete(deleteUri, null, null)
                    }
                }
            }
        } else {
            // Android 9 and below uses direct file cleanup
            val downloadsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                CRASH_FOLDER
            )
            // The reports only. The folder is also where logs are exported to,
            // and counting everything in it had a crash delete the logs the
            // user had saved to send along with it.
            downloadsDir.listFiles()
                ?.filter { it.name.startsWith(CRASH_FILE_PREFIX) }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(CRASH_FILE_HISTORY_COUNT)
                ?.forEach { it.delete() }
        }
    }
}
