package com.mynote.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
            .addMigrations(
                AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6
            )
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

    /** 按 Room v2 的精确 schema 手工建库（含 notes/categories/note_revisions），再走 v2→v3。 */
    private fun createV2Database() {
        val v2 = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        v2.execSQL(
            "CREATE TABLE IF NOT EXISTS `notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `content` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `categoryId` INTEGER, `pinned` INTEGER NOT NULL, `color` INTEGER)"
        )
        v2.execSQL(
            "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `color` INTEGER NOT NULL)"
        )
        v2.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_name` ON `categories` (`name`)")
        v2.execSQL(
            "CREATE TABLE IF NOT EXISTS `note_revisions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `noteId` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, `content` TEXT NOT NULL, `categoryId` INTEGER, " +
                "`pinned` INTEGER NOT NULL, `color` INTEGER, `savedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        v2.execSQL("CREATE INDEX IF NOT EXISTS `index_note_revisions_noteId` ON `note_revisions` (`noteId`)")
        v2.execSQL(
            "INSERT INTO notes (title, content, createdAt, updatedAt, categoryId, pinned, color) " +
                "VALUES ('老标题', '老内容', 111, 222, NULL, 0, NULL)"
        )
        v2.execSQL(
            "INSERT INTO note_revisions (noteId, title, content, categoryId, pinned, color, savedAt) " +
                "VALUES (1, '老标题', '老内容', NULL, 0, NULL, 222)"
        )
        v2.version = 2
        v2.close()
    }

    @Test
    fun migrate2To3KeepsNotesAndCreatesAiTables() = runTest {
        createV2Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6
            )
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals("老标题", db.noteDao().getById(1)?.title)
            assertEquals(1, db.noteRevisionDao().countByNote(1))

            val sessionId = db.aiSessionDao().insert(
                AiSessionEntity(
                    noteId = 1, serviceId = "deepseek", title = "新会话",
                    remoteChatId = null, createdAt = 1, updatedAt = 1
                )
            )
            val messageId = db.aiMessageDao().insert(
                AiMessageEntity(
                    sessionId = sessionId, role = AiMessageEntity.ROLE_USER,
                    content = "问题", status = AiMessageEntity.STATUS_DONE, createdAt = 1
                )
            )
            assertTrue(sessionId > 0 && messageId > 0)
            assertEquals(1, db.aiMessageDao().getBySession(sessionId).size)

            db.noteDao().delete(db.noteDao().getById(1)!!)
            assertEquals(0, db.aiSessionDao().observeByNote(1).first().size)
            assertEquals(0, db.aiMessageDao().getBySession(sessionId).size)
        } finally {
            db.close()
        }
    }

    /** 按 Room v3 的精确 schema 手工建库（notes 无 deletedAt 列），再走 v3→v4。 */
    private fun createV3Database() {
        val v3 = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        v3.execSQL(
            "CREATE TABLE IF NOT EXISTS `notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `content` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `categoryId` INTEGER, `pinned` INTEGER NOT NULL, `color` INTEGER)"
        )
        v3.execSQL(
            "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `color` INTEGER NOT NULL)"
        )
        v3.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_name` ON `categories` (`name`)")
        v3.execSQL(
            "CREATE TABLE IF NOT EXISTS `note_revisions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `noteId` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, `content` TEXT NOT NULL, `categoryId` INTEGER, " +
                "`pinned` INTEGER NOT NULL, `color` INTEGER, `savedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        v3.execSQL("CREATE INDEX IF NOT EXISTS `index_note_revisions_noteId` ON `note_revisions` (`noteId`)")
        v3.execSQL(
            "CREATE TABLE IF NOT EXISTS `ai_sessions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `noteId` INTEGER NOT NULL, " +
                "`serviceId` TEXT NOT NULL, `title` TEXT NOT NULL, `remoteChatId` TEXT, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        v3.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_sessions_noteId` ON `ai_sessions` (`noteId`)")
        v3.execSQL(
            "CREATE TABLE IF NOT EXISTS `ai_messages` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, " +
                "`role` TEXT NOT NULL, `content` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`sessionId`) REFERENCES `ai_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        v3.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_messages_sessionId` ON `ai_messages` (`sessionId`)")
        v3.execSQL(
            "INSERT INTO notes (title, content, createdAt, updatedAt, categoryId, pinned, color) " +
                "VALUES ('老标题', '老内容', 111, 222, NULL, 0, NULL)"
        )
        v3.version = 3
        v3.close()
    }

    @Test
    fun migrate3To4AddsDeletedAtColumnKeepsData() = runTest {
        createV3Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
        try {
            val note = db.noteDao().getById(1)
            assertEquals("老标题", note?.title)
            // 迁移后 deletedAt 默认为 null（未删除）
            assertEquals(null, note?.deletedAt)

            // 新列可正常写软删除值并读回
            db.noteDao().update(note!!.copy(deletedAt = 555L))
            assertEquals(555L, db.noteDao().getById(1)?.deletedAt)
            assertEquals(0, db.noteDao().observeAll().first().size)
            assertEquals(1, db.noteDao().observeDeleted().first().size)
        } finally {
            db.close()
        }
    }

    /** 按 Room v4 的精确 schema 手工建库（notes 含 deletedAt 列、无索引），再走 v4→v5。 */
    private fun createV4Database() {
        val v4 = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        v4.execSQL(
            "CREATE TABLE IF NOT EXISTS `notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `content` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `categoryId` INTEGER, `pinned` INTEGER NOT NULL, " +
                "`color` INTEGER, `deletedAt` INTEGER)"
        )
        v4.execSQL(
            "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `color` INTEGER NOT NULL)"
        )
        v4.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_name` ON `categories` (`name`)")
        v4.execSQL(
            "CREATE TABLE IF NOT EXISTS `note_revisions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `noteId` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, `content` TEXT NOT NULL, `categoryId` INTEGER, " +
                "`pinned` INTEGER NOT NULL, `color` INTEGER, `savedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        v4.execSQL("CREATE INDEX IF NOT EXISTS `index_note_revisions_noteId` ON `note_revisions` (`noteId`)")
        v4.execSQL(
            "CREATE TABLE IF NOT EXISTS `ai_sessions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `noteId` INTEGER NOT NULL, " +
                "`serviceId` TEXT NOT NULL, `title` TEXT NOT NULL, `remoteChatId` TEXT, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        v4.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_sessions_noteId` ON `ai_sessions` (`noteId`)")
        v4.execSQL(
            "CREATE TABLE IF NOT EXISTS `ai_messages` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, " +
                "`role` TEXT NOT NULL, `content` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`sessionId`) REFERENCES `ai_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        v4.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_messages_sessionId` ON `ai_messages` (`sessionId`)")
        v4.execSQL(
            "INSERT INTO notes (title, content, createdAt, updatedAt, categoryId, pinned, color, deletedAt) " +
                "VALUES ('老标题', '老内容', 111, 222, NULL, 0, NULL, 333)"
        )
        v4.version = 4
        v4.close()
    }

    @Test
    fun migrate4To5AddsIndexesKeepsData() = runTest {
        createV4Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
        try {
            val note = db.noteDao().getById(1)
            assertEquals("老标题", note?.title)
            assertEquals(333L, note?.deletedAt)

            // 三个索引已建立（软删过滤 / 分类过滤 / 置顶+更新时间默认排序）
            val indexNames = mutableListOf<String>()
            db.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='notes'"
            ).use { cursor ->
                while (cursor.moveToNext()) indexNames += cursor.getString(0)
            }
            assertTrue(indexNames.contains("index_notes_deletedAt"))
            assertTrue(indexNames.contains("index_notes_categoryId"))
            assertTrue(indexNames.contains("index_notes_pinned_updatedAt"))

            // 软删除语义保持：已删笔记从活跃列表消失
            assertEquals(0, db.noteDao().observeAll().first().size)
            assertEquals(1, db.noteDao().observeDeleted().first().size)
        } finally {
            db.close()
        }
    }

    /** 按 Room v5 的精确 schema 手工建库（notes 含 deletedAt 列与三索引、无 noteDate），再走 v5→v6。 */
    private fun createV5Database() {
        val v5 = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        v5.execSQL(
            "CREATE TABLE IF NOT EXISTS `notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `content` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `categoryId` INTEGER, `pinned` INTEGER NOT NULL, " +
                "`color` INTEGER, `deletedAt` INTEGER)"
        )
        v5.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_deletedAt` ON `notes` (`deletedAt`)")
        v5.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_categoryId` ON `notes` (`categoryId`)")
        v5.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_pinned_updatedAt` ON `notes` (`pinned`, `updatedAt`)")
        v5.execSQL(
            "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `color` INTEGER NOT NULL)"
        )
        v5.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_name` ON `categories` (`name`)")
        v5.execSQL(
            "CREATE TABLE IF NOT EXISTS `note_revisions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `noteId` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, `content` TEXT NOT NULL, `categoryId` INTEGER, " +
                "`pinned` INTEGER NOT NULL, `color` INTEGER, `savedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        v5.execSQL("CREATE INDEX IF NOT EXISTS `index_note_revisions_noteId` ON `note_revisions` (`noteId`)")
        v5.execSQL(
            "CREATE TABLE IF NOT EXISTS `ai_sessions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `noteId` INTEGER NOT NULL, " +
                "`serviceId` TEXT NOT NULL, `title` TEXT NOT NULL, `remoteChatId` TEXT, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        v5.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_sessions_noteId` ON `ai_sessions` (`noteId`)")
        v5.execSQL(
            "CREATE TABLE IF NOT EXISTS `ai_messages` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, " +
                "`role` TEXT NOT NULL, `content` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`sessionId`) REFERENCES `ai_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        v5.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_messages_sessionId` ON `ai_messages` (`sessionId`)")
        v5.execSQL(
            "INSERT INTO notes (title, content, createdAt, updatedAt, categoryId, pinned, color, deletedAt) " +
                "VALUES ('老标题', '老内容', 111, 222, NULL, 0, NULL, NULL)"
        )
        v5.version = 5
        v5.close()
    }

    @Test
    fun migrate5To6AddsNoteDateColumnKeepsData() = runTest {
        createV5Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
        try {
            val note = db.noteDao().getById(1)
            assertEquals("老标题", note?.title)
            // 迁移后 noteDate 默认为 null（未标记，不出现在日历）
            assertEquals(null, note?.noteDate)

            // 新索引已建立（日历按日期范围查询）
            val indexNames = mutableListOf<String>()
            db.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='notes'"
            ).use { cursor ->
                while (cursor.moveToNext()) indexNames += cursor.getString(0)
            }
            assertTrue(indexNames.contains("index_notes_noteDate"))

            // 未标记日期的旧笔记不进日历
            assertEquals(0, db.noteDao().observeByDateRange(0L, 1000L).first().size)
            assertEquals(0, db.noteDao().observeDateMarks(0L, 1000L).first().size)

            // 新列可正常写值并参与日历范围查询
            db.noteDao().update(note!!.copy(noteDate = 999L))
            assertEquals(999L, db.noteDao().getById(1)?.noteDate)
            assertEquals(1, db.noteDao().observeByDateRange(0L, 1000L).first().size)
            assertEquals(1, db.noteDao().observeDateMarks(0L, 1000L).first().size)
        } finally {
            db.close()
        }
    }
}
