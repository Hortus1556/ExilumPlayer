package com.hortus1556.exilum.data

import kotlinx.coroutines.delay
import android.content.Context
import android.net.Uri
import com.hortus1556.exilum.core.Constants
import com.hortus1556.exilum.db.AppPreferences
import com.hortus1556.exilum.db.dao.ModuleDao
import com.hortus1556.exilum.db.entity.ModuleEntity
import com.hortus1556.exilum.util.DirectoryEntry
import com.hortus1556.exilum.util.isHidden
import com.hortus1556.exilum.util.queryDirectoryEntries
import com.hortus1556.exilum.util.resolveDocId
import com.hortus1556.exilum.renderer.Player
import com.hortus1556.exilum.renderer.model.ModInfo
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

class ModuleRepository(
    private val context: Context,
    private val dao: ModuleDao,
    private val prefs: AppPreferences,
    private val access: StorageAccessEvents,
) {

    /**
     * Outlives any single screen/ViewModel so an in-flight shallow scan or
     * validation pass for a folder isn't cancelled by fast navigation away from it.
     *
     * With somewhere for what goes wrong to land: a scan talks to a documents
     * provider and to SQLite, either of which can throw what nobody foresaw,
     * and thrown here with nothing to catch it that was the end of the app.
     */
    private val repoScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, failure ->
            Timber.e(failure, "A scan of the library failed")
        }
    )

    /**
     * How many files are tested at once. A test reads the whole file and hands
     * it to each decoder until one takes it: it is work for a core, not a
     * wait, and at eight at a time a folder being opened took every core the
     * phone has from the list being scrolled and from the music playing.
     */
    private val validationDispatcher = Dispatchers.IO.limitedParallelism(VALIDATION_THREADS)

    /**
     * The files being tested now, by path. A folder opened again while its
     * first pass is still running - left and come back to, or reached by the
     * walk of the whole library - does not start them all again.
     */
    private val validating: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * How many folders are being listed for the screen this moment. The walk
     * of the whole library stands aside while there are any: both go through
     * the one documents provider, and a folder being opened waited its turn
     * behind three thousand that nobody was looking at.
     */
    private val listingForScreen = MutableStateFlow(0)

    private val formatsCheck = Mutex()
    private var formatsChecked = false

    /** Every extension a decoder opens, lower case. */
    private val claimedExtensions: Set<String> by lazy {
        Player.getFormatGroups().flatMapTo(HashSet()) { row -> row.drop(1).map { it.lowercase() } }
    }

    /**
     * Gives every file once turned away another look when the app has learned
     * formats since it last tested them.
     *
     * A file is tested once and the answer kept, which is right until a new
     * decoder arrives: every .fur in the library had been tested before
     * Furnace was added, was on record as not a module, and was skipped by
     * every scan after that - rescans included. The decoders' own list of
     * formats is what changes when one is added, so the library remembers the
     * list it was tested against and sends the rejects back when it differs.
     * Once per process, before the first scan.
     *
     * A decoder can also learn to open files it turned away without its list
     * changing - the .rk files CustomMade has always named, and only lately
     * finds the module in - so the list is kept with [DECODER_REVISION], and a
     * new revision sends the rejects back the same way.
     */
    private suspend fun retestAfterDecoderChanges() = formatsCheck.withLock {
        if (formatsChecked) return@withLock
        val formats = Player.getFormats().sorted().joinToString(",") + ";r$DECODER_REVISION"
        if (prefs.getTestedFormats() != formats) {
            dao.markRejectedUntested()
            prefs.setTestedFormats(formats)
        }
        formatsChecked = true
    }

    /**
     * The extensions each decoder opens, in the order a file is offered to
     * them - the first to recognise its content plays it.
     */
    fun formatGroups(): List<FormatGroup> = Player.getFormatGroups().mapNotNull { row ->
        val id = row.firstOrNull() ?: return@mapNotNull null
        FormatGroup(id = id, extensions = row.drop(1).toPersistentList())
    }

    fun getChildren(parentPath: String): Flow<List<ModuleEntity>> = dao.getChildren(parentPath)

    /**
     * Everything below [rootPath] whose name matches [query] - directories
     * included, so a search can land the user in a folder as well as on a file.
     *
     * One query for the whole subtree (see ModuleDao.getSubtree on why by
     * parent links), where it was one for every folder below the root - three
     * thousand of them for each letter typed, in a library of that many
     * folders, and every row of the library read to keep a handful.
     */
    suspend fun searchDescendants(rootPath: String, query: String): List<ModuleEntity> {
        if (query.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            // SQLite's LIKE folds case for ASCII letters and no others. A
            // search in ASCII - nearly every one - is matched there, and only
            // the matches read; any other is matched here, as it always was.
            if (query.all { it.code < 0x80 }) {
                dao.searchSubtree(rootPath, "%" + query.likeEscaped() + "%")
            } else {
                dao.getSubtree(rootPath).filter { child ->
                    val name = child.filename
                    if (child.isDirectory) {
                        name.contains(query, ignoreCase = true)
                    } else {
                        (child.isValidModule || !child.isTested) &&
                            (name.contains(query, ignoreCase = true) || child.moduleName.contains(query, ignoreCase = true))
                    }
                }
            }
        }
    }

    /**
     * Every module below [rootPath], folder by folder: a folder's own files by
     * name, then each folder in it by name, all the way down. Safe to call
     * from the main thread.
     */
    suspend fun getDescendantModules(rootPath: String): List<ModuleEntity> = withContext(Dispatchers.IO) {
        val byParent = dao.getSubtree(rootPath).groupBy { it.parentPath }
        val result = ArrayList<ModuleEntity>()
        val stack = ArrayDeque<String>()
        stack.addLast(rootPath)
        while (stack.isNotEmpty()) {
            val children = byParent[stack.removeLast()] ?: continue
            children.filter { it.isValidModule && !it.isDirectory }
                .sortedByKey { it.filename.lowercase() }
                .forEach { result.add(it) }
            // Pushed last first, so that the first by name is the next taken.
            children.filter { it.isDirectory }
                .sortedByKey { it.filename.lowercase() }
                .asReversed()
                .forEach { stack.addLast(it.filePath) }
        }
        result
    }

    /**
     * Fast, non-recursive listing of [uri]'s direct children. Writes bare rows
     * immediately from a single cheap [queryDirectoryEntries] call — no native
     * validation on this path — so [getChildren] emits near-instantly regardless
     * of folder size or whether this directory has ever been visited before.
     *
     * New or changed files are then validated off this path (see [validateFiles]):
     * results are written as they come, so the reactive [getChildren] flow
     * progressively reveals real titles / drops invalid files without the caller
     * ever blocking on it. Safe to call every time a directory is opened.
     *
     * @return the listing itself, done once the folder's rows are as the
     *   folder is - or it could not be read; the tests go on after it.
     */
    fun indexDirectoryInstant(uri: Uri): Job = repoScope.launch {
        listingForScreen.update { it + 1 }
        try {
            listForScreen(uri)
        } finally {
            listingForScreen.update { it - 1 }
        }
    }

    private suspend fun listForScreen(uri: Uri) {
        retestAfterDecoderChanges()
        val parentPath = uri.toString()
        val docId = uri.resolveDocId(context)
        // A failed query (most commonly a lost SAF grant) must not be treated
        // as "this directory is empty": that would fall straight into the
        // stale-path cleanup below and delete every row this path already
        // has. Bail out and leave the existing rows exactly as they are.
        val entries = context.contentResolver.queryDirectoryEntries(uri, docId)
            ?.filterNot { it.isHidden() }
            ?: run {
                access.reportLost()
                return
            }
        val existing = dao.getChildrenOnce(parentPath).associateBy { it.filePath }
        val seenPaths = HashSet<String>(entries.size)
        val changed = ArrayList<ModuleEntity>()
        val pendingValidation = ArrayList<DirectoryEntry>()

        for (entry in entries) {
            val path = entry.childUri.toString()
            seenPaths += path
            val prior = existing[path]
            if (entry.isDirectory) {
                if (prior?.isDirectory != true) {
                    changed += ModuleEntity(
                        id = prior?.id ?: 0,
                        filename = entry.name.trim(),
                        filePath = path,
                        parentPath = parentPath,
                        isDirectory = true,
                    )
                }
                continue
            }
            val listed = prior != null && !prior.isDirectory && prior.fileSize == entry.size
            // Tested, and the same file still: nothing to do.
            if (listed && prior.isTested) continue
            // Listed already and waiting for its test - a scan cut short, a
            // reject sent back by retestAfterDecoderChanges, or a test under
            // way this moment: the row stands. Written again as a bare one it
            // went over the result the first pass was just putting down.
            if (!listed) {
                changed += ModuleEntity(
                    id = prior?.id ?: 0,
                    filename = entry.name.trim(),
                    fileExtension = entry.extension(),
                    filePath = path,
                    parentPath = parentPath,
                    fileSize = entry.size,
                    isDirectory = false,
                )
            }
            if (validating.add(path)) pendingValidation += entry
        }
        // Only what differs is written: every row of a folder was, when one
        // file in it had changed. Nothing new, removed or changed since the
        // last visit, and nothing is written at all - revisiting is then just
        // the Room flow handing back the value it already has.
        val stalePaths = existing.keys - seenPaths
        try {
            if (changed.isNotEmpty()) dao.upsertByPath(changed)
            if (stalePaths.isNotEmpty()) {
                // And what was in them, where they were folders: a folder
                // deleted or renamed left every row below it where it was,
                // found by the search, queued by Play all and by the Sound
                // Check, and failing to load each time.
                val below = stalePaths
                    .filter { existing[it]?.isDirectory == true }
                    .flatMap { folder -> dao.getSubtree(folder).map { it.filePath } }
                dao.deleteByFilePaths(stalePaths.toList() + below)
            }
        } catch (failure: Throwable) {
            for (entry in pendingValidation) validating.remove(entry.childUri.toString())
            throw failure
        }

        // On their own, so that the listing is done when the list is.
        if (pendingValidation.isNotEmpty()) repoScope.launch { validateFiles(pendingValidation, parentPath) }
    }

    /**
     * Runs native module validation for [entries] with bounded concurrency,
     * writing the results in batches as they land. Every one of [entries] is
     * in [validating] on the way in, and out of it on the way out.
     */
    private suspend fun validateFiles(entries: List<DirectoryEntry>, parentPath: String) {
        val done = ArrayList<ModuleEntity>()
        val lock = Any()
        suspend fun flush() {
            val batch = synchronized(lock) {
                if (done.isEmpty()) null else ArrayList(done).also { done.clear() }
            }
            if (batch != null) dao.upsertByPath(batch)
        }
        try {
            coroutineScope {
                val workers = entries.map { entry ->
                    launch(validationDispatcher) {
                        val row = test(entry, parentPath) ?: return@launch
                        synchronized(lock) { done += row }
                    }
                }
                // Often enough for the folder to fill in as it goes, seldom
                // enough that the list is not rebuilt for every file.
                while (workers.any { it.isActive }) {
                    delay(VALIDATION_FLUSH_MS)
                    flush()
                }
            }
            flush()
        } finally {
            for (entry in entries) validating.remove(entry.childUri.toString())
        }
    }

    /**
     * Whether [entry] is a module, as the row to keep of it - or null when
     * the file could not be read: that is no answer, and the row stays
     * untested for a later scan to try.
     */
    private fun test(entry: DirectoryEntry, parentPath: String): ModuleEntity? {
        val modInfo = ModInfo()
        val isValid = if (entry.isNotWorthOpening()) {
            false
        } else {
            val probed = try {
                Player.probeFromFd(context, entry.childUri, modInfo)
            } catch (failure: Exception) {
                // A provider's own failure opening the file: no answer either.
                Timber.w(failure, "Could not test %s", entry.name)
                null
            }
            if (probed == null) {
                access.reportLost()
                return null
            }
            probed
        }
        return ModuleEntity(
            filename = entry.name.trim(),
            fileExtension = entry.extension(),
            filePath = entry.childUri.toString(),
            parentPath = parentPath,
            fileSize = entry.size,
            isDirectory = false,
            isValidModule = isValid,
            isTested = true,
            moduleName = modInfo.name.trim(),
            moduleType = modInfo.type.trim(),
        )
    }

    /**
     * Files known not to be music without being read. A test reads the whole
     * file into memory, whatever it is: a film or an archive kept beside the
     * music was read in full, three at a time, to be told it was neither.
     * Never one whose extension a decoder opens.
     */
    private fun DirectoryEntry.isNotWorthOpening(): Boolean {
        if (size > FILE_SIZE_LIMIT) return true
        val extension = extension()
        if (extension in claimedExtensions) return false
        return extension in Constants.SKIP_EXTENSIONS || size > UNCLAIMED_SIZE_LIMIT
    }

    private fun DirectoryEntry.extension(): String = name.substringAfterLast('.', "").lowercase()

    /**
     * Full recursive walk of [uri]'s subtree, testing every file not tested
     * yet, and deleting every row it did not come across: the walk made once
     * behind the browser at each start (so search, Android Auto and "add
     * folder to playlist" have the whole library) and at a pull to refresh -
     * never what the open folder waits for, which is [indexDirectoryInstant].
     *
     * The rows it deletes are those of files and folders that have gone
     * since - nothing else ever removed them unless their own folder was
     * opened again - and those of a music folder chosen before this one,
     * which stayed in the search, in Play all and in the Sound Check's pass
     * for good.
     *
     * It must never run that deletion off a walk that didn't actually complete
     * (e.g. a lost SAF grant partway through): [indexInternal] returning false
     * means some directory failed to enumerate, in which case whatever it
     * didn't reach is indistinguishable from "no longer exists" unless this
     * bails out instead of deleting it.
     */
    suspend fun reindexDirectory(uri: Uri) = scan("reindex") {
        retestAfterDecoderChanges()
        val allPaths = dao.getAllFilePaths().toHashSet()
        val skipPaths = dao.getConfirmedPaths().toHashSet()
        val seenPaths = mutableSetOf<String>()
        val completed = indexInternal(uri, skipPaths, seenPaths)
        if (!completed) return@scan
        val removed = allPaths - seenPaths
        if (removed.isNotEmpty()) dao.deleteByFilePaths(removed.toList())
    }

    /**
     * A walk of the library with a floor under it: what it throws is written
     * down and the walk given up, the rows it had reached left as they are.
     * Its callers launch it and look away, and an exception out of it took
     * the app down with it.
     */
    private suspend fun scan(what: String, walk: suspend () -> Unit) {
        try {
            walk()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Timber.e(failure, "The library %s failed", what)
        }
    }

    /** @return false if any directory in the walk failed to enumerate (e.g. a
     * lost SAF grant) and the walk was aborted early; true if it completed. */
    private suspend fun indexInternal(
        uri: Uri,
        skipPaths: Set<String>,
        seenPaths: MutableSet<String>? = null
    ): Boolean {
        val resolver = context.contentResolver
        val rootDocId = uri.resolveDocId(context)

        // Stack of (docId, parentPath) — parentPath is the URI string used to query children later
        val stack = ArrayDeque<Pair<String, String>>()
        // Written a batch at a time rather than a row at a time: see
        // ModuleDao.upsertByPath.
        val pending = ArrayList<ModuleEntity>()
        suspend fun flush() {
            if (pending.isEmpty()) return
            dao.upsertByPath(pending.toList())
            pending.clear()
        }
        stack.addLast(rootDocId to uri.toString())

        while (stack.isNotEmpty()) {
            val (docId, parentPath) = stack.removeLast()
            // After whatever the screen is waiting for.
            listingForScreen.first { it == 0 }
            val entries = resolver.queryDirectoryEntries(uri, docId) ?: run {
                access.reportLost()
                flush()
                return false
            }

            for (entry in entries.filterNot { it.isHidden() }) {
                val path = entry.childUri.toString()
                seenPaths?.add(path)
                if (entry.isDirectory) {
                    if (path !in skipPaths) {
                        pending += ModuleEntity(
                            filename = entry.name.trim(),
                            fileExtension = "",
                            filePath = path,
                            parentPath = parentPath,
                            fileSize = 0L,
                            isDirectory = true,
                        )
                        if (pending.size >= INDEX_BATCH) flush()
                    }
                    stack.addLast(entry.docId to path)
                } else if (path !in skipPaths && validating.add(path)) {
                    // Not one the open folder's own pass is testing this
                    // moment: the two went over the same files, and each
                    // other's rows.
                    try {
                        // Unreadable is no answer: kept as an untested row, which
                        // the browser still lists and the next scan tests again.
                        pending += test(entry, parentPath) ?: ModuleEntity(
                            filename = entry.name.trim(),
                            fileExtension = entry.extension(),
                            filePath = path,
                            parentPath = parentPath,
                            fileSize = entry.size,
                            isDirectory = false,
                        )
                    } finally {
                        validating.remove(path)
                    }
                    if (pending.size >= INDEX_BATCH) flush()
                }
            }
        }
        flush()
        return true
    }
}

/** Sorted by [key], worked out once a row rather than twice at every comparison. */
internal inline fun <T> Iterable<T>.sortedByKey(key: (T) -> String): List<T> =
    map { key(it) to it }.sortedBy { it.first }.map { it.second }

/** As a literal inside a LIKE pattern whose escape is the backslash. */
private fun String.likeEscaped(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/** How often a folder's validation results are written while they come in. */
private const val VALIDATION_FLUSH_MS = 500L

/** Files tested at once by a folder's validation pass. */
private const val VALIDATION_THREADS = 3

/** Rows written per transaction by the full walk. */
private const val INDEX_BATCH = 64

/**
 * Raised whenever a decoder comes to open files it used to turn away while
 * opening the same extensions as before, so that those files are tested again.
 * 1: CustomMade finds the module inside a Ron Klaren sound module (.rk).
 */
private const val DECODER_REVISION = 1

/** Above this, a file whose extension no decoder opens is not read to be tested. */
private const val UNCLAIMED_SIZE_LIMIT = 64L * 1024 * 1024

/** And above this, no file is: the native side reads none this large either (decoder.cpp, kMaxFileBytes). */
private const val FILE_SIZE_LIMIT = 512L * 1024 * 1024
