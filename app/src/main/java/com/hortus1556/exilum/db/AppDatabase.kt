package com.hortus1556.exilum.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.hortus1556.exilum.db.dao.DownloadHistoryDao
import com.hortus1556.exilum.db.dao.FavouriteDao
import com.hortus1556.exilum.db.dao.FileOverridesDao
import com.hortus1556.exilum.db.dao.ModuleDao
import com.hortus1556.exilum.db.dao.PlaylistDao
import com.hortus1556.exilum.db.dao.SubsongNameDao
import com.hortus1556.exilum.db.entity.DownloadHistoryEntity
import com.hortus1556.exilum.db.entity.FavouriteEntity
import com.hortus1556.exilum.db.entity.FileOverridesEntity
import com.hortus1556.exilum.db.entity.ModuleEntity
import com.hortus1556.exilum.db.entity.PlaylistEntity
import com.hortus1556.exilum.db.entity.PlaylistEntryEntity
import com.hortus1556.exilum.db.entity.SubsongNameEntity

@Database(
    entities = [
        ModuleEntity::class,
        DownloadHistoryEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        FavouriteEntity::class,
        SubsongNameEntity::class,
        FileOverridesEntity::class,
    ],
    version = 13,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun moduleMetadataDao(): ModuleDao
    abstract fun downloadHistoryDao(): DownloadHistoryDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun favouriteDao(): FavouriteDao
    abstract fun subsongNameDao(): SubsongNameDao
    abstract fun fileOverridesDao(): FileOverridesDao
}

/** Adds the per-file overrides: boost, lengths, loop presets, core settings. See FileOverridesEntity. */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `file_overrides` (" +
                "`uri` TEXT NOT NULL, " +
                "`data` TEXT NOT NULL, " +
                "PRIMARY KEY(`uri`))"
        )
    }
}

/**
 * Remembers subsongs: which one a favourite or a playlist entry plays, and
 * what they are called.
 *
 * Nothing is dropped. Every existing favourite and entry becomes -1, the file
 * as a whole, which is what they always were. Favourites have to be rebuilt,
 * because the subsong joins their primary key and SQLite cannot change one in
 * place; the modules table gains the column only so a queue entry can carry
 * it - see ModuleEntity.subsong.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `modules` ADD COLUMN `subsong` INTEGER NOT NULL DEFAULT -1")
        connection.execSQL(
            "ALTER TABLE `playlist_entries` ADD COLUMN `subsong` INTEGER NOT NULL DEFAULT -1"
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `favourites_new` (" +
                "`uri` TEXT NOT NULL, " +
                "`subsong` INTEGER NOT NULL DEFAULT -1, " +
                "`name` TEXT NOT NULL, " +
                "`extension` TEXT NOT NULL, " +
                "`addedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`uri`, `subsong`))"
        )
        connection.execSQL(
            "INSERT INTO `favourites_new` (`uri`, `subsong`, `name`, `extension`, `addedAt`) " +
                "SELECT `uri`, -1, `name`, `extension`, `addedAt` FROM `favourites`"
        )
        connection.execSQL("DROP TABLE `favourites`")
        connection.execSQL("ALTER TABLE `favourites_new` RENAME TO `favourites`")
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `subsong_names` (" +
                "`uri` TEXT NOT NULL, " +
                "`subsong` INTEGER NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "PRIMARY KEY(`uri`, `subsong`))"
        )
    }
}

/**
 * Drops the indexed files again, for the same reason as [MIGRATION_9_10]: the
 * set of decoders grew.
 *
 * Every .rad, .hsc, .cmf, .dro and the forty-odd other AdLib formats already
 * walked past was recorded as not-a-module by a build with no AdPlug in it,
 * and a row that says no is never asked again.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("DELETE FROM modules WHERE isDirectory = 0")
    }
}

/**
 * Drops the indexed files so they are probed again.
 *
 * The index is a cache of "did a decoder accept this", and it keeps a row for
 * every file whose size has not changed - which is the right call while the
 * set of decoders is fixed, and exactly wrong the moment it grows. Every SPC,
 * NSF and VGM already seen was recorded as not-a-module by a build that only
 * had libopenmpt, and would never have been offered to anything else.
 *
 * Only the files go. Directories stay, so the tree does not have to be walked
 * again from nothing, and playlists and favourites are untouched - they keep
 * their own rows and refer to files by path.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("DELETE FROM modules WHERE isDirectory = 0")
    }
}

/**
 * Adds the favourites table without touching anything else.
 *
 * Written out rather than left to the destructive fallback: favourites arrive
 * together with tracker.conf, so anyone upgrading has no config file to be
 * restored from yet, and dropping the database here would take their existing
 * playlists with it.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `favourites` (" +
                "`uri` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`extension` TEXT NOT NULL, " +
                "`addedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`uri`))"
        )
    }
}
