package com.mynote.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseMigrationTest {

    private val dbName = "migration-test.db"
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun teardown() {
        context.deleteDatabase(dbName)
    }

    /** 按 Room v1 的精确 schema 手工建库，再走 MIGRATION_1_2 打开。 */
    private fun createV1Database() {
        val v1 = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        v1.execSQL(
            "CREATE TABLE IF NOT EXISTS `notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `content` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `categoryId` INTEGER, `pinned` INTEGER NOT NULL, `color` INTEGER)"
        )
        v1.execSQL(
            "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `color` INTEGER NOT NULL)"
        )
        v1.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_name` ON `categories` (`name`)")
        v1.execSQL(
            "INSERT INTO notes (title, content, createdAt, updatedAt, categoryId, pinned, color) " +
                "VALUES ('老标题', '老内容', 111, 222, NULL, 0, NULL)"
        )
        v1.version = 1
        v1.close()
    }

    @Test
    fun migrate1To2KeepsNotesAndCreatesRevisionTable() = runTest {
        createV1Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val note = db.noteDao().getById(1)
            assertEquals("老标题", note?.title)
            assertEquals("老内容", note?.content)

            val revisionId = db.noteRevisionDao().insert(
                NoteRevisionEntity(
                    noteId = 1, title = "老标题", content = "老内容",
                    categoryId = null, pinned = false, color = null, savedAt = 333
                )
            )
            assertTrue(revisionId > 0)
            assertEquals(1, db.noteRevisionDao().countByNote(1))

            db.noteDao().delete(note!!)
            assertEquals(0, db.noteRevisionDao().countByNote(1))
        } finally {
            db.close()
        }
    }
}
