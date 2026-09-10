package com.mynote.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NoteEntity::class, CategoryEntity::class, NoteRevisionEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun categoryDao(): CategoryDao
    abstract fun noteRevisionDao(): NoteRevisionDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `note_revisions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`noteId` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`categoryId` INTEGER, " +
                        "`pinned` INTEGER NOT NULL, " +
                        "`color` INTEGER, " +
                        "`savedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_revisions_noteId` ON `note_revisions` (`noteId`)")
            }
        }
    }
}
