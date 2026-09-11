# 笔记历史修改记录 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为笔记增加保存点历史快照（时间线 + diff 高亮 + 恢复），每篇上限 50 条，40 条起提醒。

**Architecture:** Room 新增 `note_revisions` 表存完整文本快照（v1→v2 手写迁移）；`NoteRepository.saveNote` 在同一事务里写笔记 + 快照 + 裁剪；diff 为纯函数在打开详情时计算；历史页独立全屏路由。

**Tech Stack:** Kotlin + Compose(Material3) + Room(KSP, room-ktx `withTransaction`) + Robolectric 单测；零新增依赖。

**Spec:** `docs/superpowers/specs/2026-09-10-note-history-design.md`

**执行前提（控制器负责，不在任务内）：** 在 `master` 上创建隔离工作树：`git worktree add .worktrees/note-history -b feature/note-history master`，后续所有命令在 `D:\desktop\myNote\.worktrees\note-history` 下执行（Windows PowerShell，统一 `.\gradlew`）。

---

### Task 1: 快照表、DAO 与 v1→v2 迁移

**Files:**
- Create: `app/src/main/java/com/mynote/app/data/db/NoteRevisionEntity.kt`
- Create: `app/src/main/java/com/mynote/app/data/db/NoteRevisionDao.kt`
- Modify: `app/src/main/java/com/mynote/app/data/db/AppDatabase.kt`
- Modify: `app/src/main/java/com/mynote/app/di/AppContainer.kt:15-17`
- Test: `app/src/test/java/com/mynote/app/data/db/NoteRevisionDaoTest.kt`
- Test: `app/src/test/java/com/mynote/app/data/db/AppDatabaseMigrationTest.kt`

- [ ] **Step 1: 写 DAO 失败测试**

创建 `app/src/test/java/com/mynote/app/data/db/NoteRevisionDaoTest.kt`：

```kotlin
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
class NoteRevisionDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun revision(noteId: Long, savedAt: Long, content: String = "c") =
        NoteRevisionEntity(
            noteId = noteId, title = "t", content = content,
            categoryId = null, pinned = false, color = null, savedAt = savedAt
        )

    @Test
    fun insertAndObserveOrderedBySavedAtDesc() = runTest {
        val noteId = db.noteDao().insert(NoteEntity(0, "t", "c", 1, 1, null, false, null))
        db.noteRevisionDao().insert(revision(noteId, 100, "old"))
        db.noteRevisionDao().insert(revision(noteId, 200, "new"))
        val list = db.noteRevisionDao().observeByNote(noteId).first()
        assertEquals(listOf("new", "old"), list.map { it.content })
    }

    @Test
    fun countByNoteCountsOnlyThatNote() = runTest {
        val a = db.noteDao().insert(NoteEntity(0, "a", "", 1, 1, null, false, null))
        val b = db.noteDao().insert(NoteEntity(0, "b", "", 1, 1, null, false, null))
        db.noteRevisionDao().insert(revision(a, 1))
        db.noteRevisionDao().insert(revision(a, 2))
        db.noteRevisionDao().insert(revision(b, 3))
        assertEquals(2, db.noteRevisionDao().countByNote(a))
    }

    @Test
    fun trimToKeepsNewestAndReturnsDeletedCount() = runTest {
        val noteId = db.noteDao().insert(NoteEntity(0, "t", "", 1, 1, null, false, null))
        for (i in 1..55) db.noteRevisionDao().insert(revision(noteId, i.toLong(), "v$i"))
        val deleted = db.noteRevisionDao().trimTo(noteId, 50)
        assertEquals(5, deleted)
        val remain = db.noteRevisionDao().getByNote(noteId)
        assertEquals(50, remain.size)
        assertEquals("v55", remain.first().content)
        assertEquals("v6", remain.last().content)
    }

    @Test
    fun deletingNoteCascadesRevisions() = runTest {
        val noteId = db.noteDao().insert(NoteEntity(0, "t", "c", 1, 1, null, false, null))
        db.noteRevisionDao().insert(revision(noteId, 1))
        db.noteDao().delete(db.noteDao().getById(noteId)!!)
        assertEquals(0, db.noteRevisionDao().countByNote(noteId))
    }

    @Test
    fun getAllContentsReturnsEverySnapshot() = runTest {
        val noteId = db.noteDao().insert(NoteEntity(0, "t", "", 1, 1, null, false, null))
        db.noteRevisionDao().insert(revision(noteId, 1, "a"))
        db.noteRevisionDao().insert(revision(noteId, 2, "b"))
        assertTrue(db.noteRevisionDao().getAllContents().containsAll(listOf("a", "b")))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.db.NoteRevisionDaoTest"`
Expected: 编译失败 `Unresolved reference: NoteRevisionEntity`（或 `noteRevisionDao`）。

- [ ] **Step 3: 实现实体与 DAO**

创建 `app/src/main/java/com/mynote/app/data/db/NoteRevisionEntity.kt`：

```kotlin
package com.mynote.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "note_revisions",
    foreignKeys = [ForeignKey(
        entity = NoteEntity::class,
        parentColumns = ["id"],
        childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("noteId")]
)
data class NoteRevisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val title: String,
    val content: String,
    val categoryId: Long?,
    val pinned: Boolean,
    val color: Int?,
    val savedAt: Long
)
```

创建 `app/src/main/java/com/mynote/app/data/db/NoteRevisionDao.kt`：

```kotlin
package com.mynote.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteRevisionDao {

    companion object {
        const val MAX_PER_NOTE = 50
        const val WARN_AT = 40
    }

    @Query("SELECT * FROM note_revisions WHERE noteId = :noteId ORDER BY savedAt DESC, id DESC")
    fun observeByNote(noteId: Long): Flow<List<NoteRevisionEntity>>

    @Query("SELECT * FROM note_revisions WHERE noteId = :noteId ORDER BY savedAt DESC, id DESC")
    suspend fun getByNote(noteId: Long): List<NoteRevisionEntity>

    @Query("SELECT * FROM note_revisions WHERE id = :id")
    suspend fun getById(id: Long): NoteRevisionEntity?

    @Query("SELECT COUNT(*) FROM note_revisions WHERE noteId = :noteId")
    suspend fun countByNote(noteId: Long): Int

    @Insert
    suspend fun insert(revision: NoteRevisionEntity): Long

    @Query(
        "DELETE FROM note_revisions WHERE noteId = :noteId AND id NOT IN " +
            "(SELECT id FROM note_revisions WHERE noteId = :noteId ORDER BY savedAt DESC, id DESC LIMIT :keep)"
    )
    suspend fun trimTo(noteId: Long, keep: Int): Int

    @Query("SELECT content FROM note_revisions")
    suspend fun getAllContents(): List<String>
}
```

修改 `AppDatabase.kt`（整文件替换）：

```kotlin
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
```

修改 `AppContainer.kt` 的 `database`：

```kotlin
    val database: AppDatabase by lazy {
        Room.databaseBuilder(context, AppDatabase::class.java, "mynote.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
    }
```

- [ ] **Step 4: 运行 DAO 测试确认通过**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.db.NoteRevisionDaoTest"`
Expected: PASS（5 个用例）。

- [ ] **Step 5: 写迁移测试**

创建 `app/src/test/java/com/mynote/app/data/db/AppDatabaseMigrationTest.kt`：

```kotlin
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
```

- [ ] **Step 6: 运行迁移测试与全量测试**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.db.AppDatabaseMigrationTest"`
Expected: PASS（1 个用例）。

Run: `.\gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，原有 70 个 + 新增 6 个全绿。

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/com/mynote/app/data/db app/src/test/java/com/mynote/app/data/db app/src/main/java/com/mynote/app/di/AppContainer.kt
git commit -m "feat: 历史快照表与 v1→v2 迁移"
```

---

### Task 2: 仓储保存钩子、恢复与图片 GC

**Files:**
- Modify: `app/src/main/java/com/mynote/app/data/repository/NoteRepository.kt`（整文件替换）
- Modify: `app/src/main/java/com/mynote/app/di/AppContainer.kt:23-25`
- Modify: `app/src/test/java/com/mynote/app/data/repository/NoteRepositoryTest.kt`
- Modify: `app/src/test/java/com/mynote/app/ui/notes/NoteEditViewModelTest.kt:41`

- [ ] **Step 1: 更新测试基建并写失败测试**

修改 `NoteRepositoryTest.kt` 的 `setup` 与新增用例（类字段增加 `imageStore`）：

```kotlin
    private lateinit var db: AppDatabase
    private lateinit var repo: NoteRepository
    private lateinit var imageStore: ImageStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        imageStore = ImageStore(context)
        repo = NoteRepository(db.noteDao(), db.categoryDao(), db.noteRevisionDao(), imageStore, db)
    }
```

在类末尾新增用例：

```kotlin
    @Test
    fun newNoteCreatesFirstRevision() = runTest {
        val id = repo.saveNote(null, "t", "c", null, false, null)
        val revisions = db.noteRevisionDao().getByNote(id)
        assertEquals(1, revisions.size)
        assertEquals("c", revisions[0].content)
        assertEquals(id, revisions[0].noteId)
    }

    @Test
    fun saveWithoutChangesDoesNotAddRevision() = runTest {
        val id = repo.saveNote(null, "t", "c", null, false, null)
        repo.saveNote(id, "t", "c", null, false, null)
        assertEquals(1, repo.countRevisions(id))
    }

    @Test
    fun firstSaveOfLegacyNoteCreatesBaselineAndNewRevision() = runTest {
        val legacyId = db.noteDao().insert(NoteEntity(0, "old", "old content", 111, 222, null, false, null))
        repo.saveNote(legacyId, "new", "new content", null, false, null)
        val revisions = db.noteRevisionDao().getByNote(legacyId)
        assertEquals(2, revisions.size)
        assertEquals("new content", revisions[0].content)
        assertEquals("old content", revisions[1].content)
        assertEquals(222L, revisions[1].savedAt)
        assertTrue(revisions[0].savedAt > 222L)
    }

    @Test
    fun trimKeepsAtMost50RevisionsPerNote() = runTest {
        val id = repo.saveNote(null, "t", "v0", null, false, null)
        for (i in 1..55) repo.saveNote(id, "t", "v$i", null, false, null)
        assertEquals(50, repo.countRevisions(id))
    }

    @Test
    fun deleteNoteCascadesRevisions() = runTest {
        val id = repo.saveNote(null, "t", "c", null, false, null)
        repo.deleteNote(repo.getNote(id)!!)
        assertEquals(0, repo.countRevisions(id))
    }

    @Test
    fun restoreRevisionWritesBackAndAddsRevision() = runTest {
        val id = repo.saveNote(null, "t", "v1", null, false, null)
        repo.saveNote(id, "t", "v2", null, false, null)
        val firstRevision = db.noteRevisionDao().getByNote(id).last()
        assertTrue(repo.restoreRevision(id, firstRevision.id))
        assertEquals("v1", repo.getNote(id)?.content)
        assertEquals(3, repo.countRevisions(id))
    }

    @Test
    fun restoreRevisionWithDeletedCategoryFallsBackToNull() = runTest {
        val catId = repo.addCategory("工作", 0)
        val id = repo.saveNote(null, "t", "v1", catId, false, null)
        repo.saveNote(id, "t", "v2", catId, false, null)
        val firstRevision = db.noteRevisionDao().getByNote(id).last()
        repo.deleteCategory(db.categoryDao().getById(catId)!!)
        assertTrue(repo.restoreRevision(id, firstRevision.id))
        assertNull(repo.getNote(id)?.categoryId)
    }

    @Test
    fun restoreMissingRevisionReturnsFalse() = runTest {
        val id = repo.saveNote(null, "t", "c", null, false, null)
        assertEquals(false, repo.restoreRevision(id, 99999L))
    }

    @Test
    fun garbageCollectionKeepsImagesReferencedByHistory() = runTest {
        imageStore.writeFile("a.webp", byteArrayOf(1))
        val id = repo.saveNote(null, "t", "![](img/a.webp)", null, false, null)
        repo.saveNote(id, "t", "no image", null, false, null)
        // 通过删除另一篇笔记触发 GC（GC 只在删除/裁剪后执行）
        val other = repo.saveNote(null, "other", "x", null, false, null)
        repo.deleteNote(repo.getNote(other)!!)
        assertTrue(imageStore.physicalFile("a.webp").exists())
    }

    @Test
    fun garbageCollectionReclaimsImagesAfterTrim() = runTest {
        imageStore.writeFile("b.webp", byteArrayOf(1))
        val id = repo.saveNote(null, "t", "![](img/b.webp)", null, false, null)
        for (i in 1..50) repo.saveNote(id, "t", "v$i", null, false, null)
        assertEquals(false, imageStore.physicalFile("b.webp").exists())
    }
```

同步修改 `NoteEditViewModelTest.kt:41` 为：

```kotlin
        repo = NoteRepository(db.noteDao(), db.categoryDao(), db.noteRevisionDao(), ImageStore(context), db)
```

- [ ] **Step 2: 运行确认编译失败**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.repository.NoteRepositoryTest"`
Expected: 编译失败（`countRevisions` / `restoreRevision` 不存在，构造参数不匹配）。

- [ ] **Step 3: 实现 Repository**

用以下内容替换 `NoteRepository.kt`（保留原有查询/分类方法，新增 import 与历史逻辑）：

```kotlin
package com.mynote.app.data.repository

import androidx.room.withTransaction
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.db.CategoryDao
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.data.db.NoteDao
import com.mynote.app.data.db.NoteEntity
import com.mynote.app.data.db.NoteRevisionDao
import com.mynote.app.data.db.NoteRevisionEntity
import com.mynote.app.data.image.ImageStore
import com.mynote.app.ui.notes.NoteContentParser
import kotlinx.coroutines.flow.Flow

class NoteRepository(
    private val noteDao: NoteDao,
    private val categoryDao: CategoryDao,
    private val revisionDao: NoteRevisionDao,
    private val imageStore: ImageStore,
    private val database: AppDatabase
) {

    fun observeNotes(): Flow<List<NoteEntity>> = noteDao.observeAll()

    fun observeNote(id: Long): Flow<NoteEntity?> = noteDao.observeById(id)

    fun search(query: String): Flow<List<NoteEntity>> =
        if (query.isBlank()) noteDao.observeAll() else noteDao.search(query.trim())

    fun observeByCategory(categoryId: Long): Flow<List<NoteEntity>> = noteDao.observeByCategory(categoryId)

    fun observeCategories(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    fun observeRevisions(noteId: Long): Flow<List<NoteRevisionEntity>> = revisionDao.observeByNote(noteId)

    suspend fun getNote(id: Long): NoteEntity? = noteDao.getById(id)

    suspend fun countRevisions(noteId: Long): Int = revisionDao.countByNote(noteId)

    suspend fun saveNote(
        id: Long?,
        title: String,
        content: String,
        categoryId: Long?,
        pinned: Boolean,
        color: Int?
    ): Long {
        val now = System.currentTimeMillis()
        var trimmed = false
        val resultId = database.withTransaction {
            if (id == null || id == 0L) {
                val newId = noteDao.insert(NoteEntity(0, title, content, now, now, categoryId, pinned, color))
                revisionDao.insert(NoteRevisionEntity(0, newId, title, content, categoryId, pinned, color, now))
                newId
            } else {
                val existing = noteDao.getById(id)
                if (existing == null) {
                    id
                } else {
                    // 基线兜底：功能上线前的老笔记/备份导入的笔记首次保存时补一条老状态
                    if (revisionDao.countByNote(id) == 0) {
                        revisionDao.insert(existing.toRevision(existing.updatedAt))
                    }
                    val changed = existing.title != title || existing.content != content ||
                        existing.categoryId != categoryId || existing.pinned != pinned || existing.color != color
                    if (changed) {
                        noteDao.update(NoteEntity(id, title, content, existing.createdAt, now, categoryId, pinned, color))
                        revisionDao.insert(NoteRevisionEntity(0, id, title, content, categoryId, pinned, color, now))
                        trimmed = revisionDao.trimTo(id, NoteRevisionDao.MAX_PER_NOTE) > 0
                    }
                    id
                }
            }
        }
        if (trimmed) collectImageGarbage()
        return resultId
    }

    suspend fun restoreRevision(noteId: Long, revisionId: Long): Boolean {
        val revision = revisionDao.getById(revisionId) ?: return false
        val categoryId = revision.categoryId?.takeIf { categoryDao.getById(it) != null }
        saveNote(noteId, revision.title, revision.content, categoryId, revision.pinned, revision.color)
        return true
    }

    suspend fun deleteNote(note: NoteEntity) {
        noteDao.delete(note)
        collectImageGarbage()
    }

    suspend fun addCategory(name: String, color: Int): Long =
        categoryDao.getByName(name)?.id ?: categoryDao.insert(CategoryEntity(0, name, color))

    suspend fun renameCategory(category: CategoryEntity, newName: String) =
        categoryDao.update(category.copy(name = newName))

    suspend fun deleteCategory(category: CategoryEntity) {
        noteDao.clearCategory(category.id)
        categoryDao.delete(category)
    }

    private fun NoteEntity.toRevision(savedAt: Long): NoteRevisionEntity =
        NoteRevisionEntity(0, id, title, content, categoryId, pinned, color, savedAt)

    private suspend fun collectImageGarbage() {
        val referenced = noteDao.getAll()
            .flatMap { NoteContentParser.extractImageNames(it.content) }
            .toMutableSet()
        referenced += revisionDao.getContentsWithImageMarkup()
            .flatMap { NoteContentParser.extractImageNames(it) }
        imageStore.collectGarbage(referenced)
    }
}
```

修改 `AppContainer.kt` 的 `noteRepository`：

```kotlin
    val noteRepository: NoteRepository by lazy {
        NoteRepository(database.noteDao(), database.categoryDao(), database.noteRevisionDao(), imageStore, database)
    }
```

- [ ] **Step 4: 运行仓储测试确认通过**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.data.repository.NoteRepositoryTest"`
Expected: PASS（原 6 个 + 新 10 个）。

- [ ] **Step 5: 全量测试**

Run: `.\gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL（含更新后的 `NoteEditViewModelTest`）。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/mynote/app/data/repository/NoteRepository.kt app/src/main/java/com/mynote/app/di/AppContainer.kt app/src/test/java/com/mynote/app/data/repository/NoteRepositoryTest.kt app/src/test/java/com/mynote/app/ui/notes/NoteEditViewModelTest.kt
git commit -m "feat: 保存时写入历史快照与恢复能力（含图片 GC 并集）"
```

---

### Task 3: diff 引擎（行级 + 行内字符级）

**Files:**
- Create: `app/src/main/java/com/mynote/app/ui/history/NoteDiff.kt`
- Test: `app/src/test/java/com/mynote/app/ui/history/NoteDiffTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/mynote/app/ui/history/NoteDiffTest.kt`：

```kotlin
package com.mynote.app.ui.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteDiffTest {

    @Test
    fun identicalContentIsAllUnchanged() {
        val lines = NoteDiff.diff("a\nb", "a\nb")
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.type == NoteDiff.Type.UNCHANGED })
    }

    @Test
    fun appendedLineIsAdded() {
        val lines = NoteDiff.diff("a", "a\nb")
        assertEquals(listOf(NoteDiff.Type.UNCHANGED, NoteDiff.Type.ADDED), lines.map { it.type })
    }

    @Test
    fun removedLineIsRemoved() {
        val lines = NoteDiff.diff("a\nb", "a")
        assertEquals(listOf(NoteDiff.Type.UNCHANGED, NoteDiff.Type.REMOVED), lines.map { it.type })
    }

    @Test
    fun changedLinePairsAndEmphasizesOnlyChangedChars() {
        val lines = NoteDiff.diff("abc", "axc")
        assertEquals(2, lines.size)
        assertEquals(NoteDiff.Type.REMOVED, lines[0].type)
        assertEquals(NoteDiff.Type.ADDED, lines[1].type)
        assertEquals(listOf(1..1), lines[0].emphasis)
        assertEquals(listOf(1..1), lines[1].emphasis)
    }

    @Test
    fun unchangedAffixesAreNotEmphasized() {
        val lines = NoteDiff.diff("hello world", "hello there")
        assertEquals(listOf(6..10), lines[0].emphasis)
        assertEquals(listOf(6..10), lines[1].emphasis)
    }

    @Test
    fun crlfIsTreatedAsLf() {
        val lines = NoteDiff.diff("a\r\nb", "a\nb")
        assertTrue(lines.all { it.type == NoteDiff.Type.UNCHANGED })
    }

    @Test
    fun emptyOldMeansAllAdded() {
        val lines = NoteDiff.diff("", "a\nb")
        assertEquals(listOf(NoteDiff.Type.ADDED, NoteDiff.Type.ADDED), lines.map { it.type })
    }

    @Test
    fun emptyNewMeansAllRemoved() {
        val lines = NoteDiff.diff("a\nb", "")
        assertEquals(listOf(NoteDiff.Type.REMOVED, NoteDiff.Type.REMOVED), lines.map { it.type })
    }

    @Test
    fun unpairedLineIsFullyEmphasized() {
        val lines = NoteDiff.diff("a\nb", "a\nc\nd")
        // 删除 b / 新增 c、d：b 与 c 配对，d 未配对 → 整行强调
        val added = lines.filter { it.type == NoteDiff.Type.ADDED }
        assertEquals(2, added.size)
        assertTrue(added[1].emphasis.isNotEmpty())
    }

    @Test
    fun largeDiffFallsBackToRemoveAllThenAddAll() {
        val old = (1..1100).joinToString("\n") { "old $it" }
        val new = (1..1100).joinToString("\n") { "new $it" }
        val lines = NoteDiff.diff(old, new)
        assertEquals(2200, lines.size)
        assertTrue(lines.take(1100).all { it.type == NoteDiff.Type.REMOVED })
        assertTrue(lines.drop(1100).all { it.type == NoteDiff.Type.ADDED })
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.history.NoteDiffTest"`
Expected: 编译失败 `Unresolved reference: NoteDiff`。

- [ ] **Step 3: 实现引擎**

创建 `app/src/main/java/com/mynote/app/ui/history/NoteDiff.kt`：

```kotlin
package com.mynote.app.ui.history

/** 纯文本行级 diff + 配对行内字符级高亮，零依赖。 */
object NoteDiff {

    enum class Type { UNCHANGED, REMOVED, ADDED }

    data class Line(
        val type: Type,
        val text: String,
        val emphasis: List<IntRange> = emptyList()
    )

    private const val MAX_LCS_CELLS = 1_000_000L

    fun diff(oldText: String, newText: String): List<Line> {
        if (oldText == newText) {
            return splitLines(oldText).map { Line(Type.UNCHANGED, it) }
        }
        val oldLines = if (oldText.isEmpty()) emptyList() else splitLines(oldText)
        val newLines = if (newText.isEmpty()) emptyList() else splitLines(newText)

        val raw = mutableListOf<Line>()
        val n = oldLines.size
        val m = newLines.size
        if (n.toLong() * m > MAX_LCS_CELLS) {
            oldLines.forEach { raw += Line(Type.REMOVED, it) }
            newLines.forEach { raw += Line(Type.ADDED, it) }
        } else {
            val dp = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) {
                for (j in m - 1 downTo 0) {
                    dp[i][j] = if (oldLines[i] == newLines[j]) dp[i + 1][j + 1] + 1
                    else maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
            var i = 0
            var j = 0
            while (i < n && j < m) {
                when {
                    oldLines[i] == newLines[j] -> {
                        raw += Line(Type.UNCHANGED, oldLines[i]); i++; j++
                    }
                    dp[i + 1][j] >= dp[i][j + 1] -> {
                        raw += Line(Type.REMOVED, oldLines[i]); i++
                    }
                    else -> {
                        raw += Line(Type.ADDED, newLines[j]); j++
                    }
                }
            }
            while (i < n) { raw += Line(Type.REMOVED, oldLines[i]); i++ }
            while (j < m) { raw += Line(Type.ADDED, newLines[j]); j++ }
        }
        return pairAdjacent(raw)
    }

    private fun splitLines(text: String): List<String> =
        text.split('\n').map { it.removeSuffix("\r") }

    /** 相邻的「删除段 + 新增段」按下标配对，配对行做字符级前后缀高亮。 */
    private fun pairAdjacent(raw: List<Line>): List<Line> {
        val result = raw.toMutableList()
        var i = 0
        while (i < result.size) {
            if (result[i].type == Type.REMOVED) {
                var removedEnd = i
                while (removedEnd < result.size && result[removedEnd].type == Type.REMOVED) removedEnd++
                if (removedEnd < result.size && result[removedEnd].type == Type.ADDED) {
                    var addedEnd = removedEnd
                    while (addedEnd < result.size && result[addedEnd].type == Type.ADDED) addedEnd++
                    val pairs = minOf(removedEnd - i, addedEnd - removedEnd)
                    for (k in 0 until pairs) {
                        val (oldEmphasis, newEmphasis) = emphasize(result[i + k].text, result[removedEnd + k].text)
                        result[i + k] = result[i + k].copy(emphasis = oldEmphasis)
                        result[removedEnd + k] = result[removedEnd + k].copy(emphasis = newEmphasis)
                    }
                    i = addedEnd
                    continue
                }
            }
            i++
        }
        return result.map {
            if (it.type != Type.UNCHANGED && it.emphasis.isEmpty() && it.text.isNotEmpty()) {
                it.copy(emphasis = listOf(0 until it.text.length))
            } else {
                it
            }
        }
    }

    private fun emphasize(oldLine: String, newLine: String): Pair<List<IntRange>, List<IntRange>> {
        var prefix = 0
        val maxPrefix = minOf(oldLine.length, newLine.length)
        while (prefix < maxPrefix && oldLine[prefix] == newLine[prefix]) prefix++
        var suffix = 0
        while (suffix < maxPrefix - prefix &&
            oldLine[oldLine.length - 1 - suffix] == newLine[newLine.length - 1 - suffix]
        ) {
            suffix++
        }
        val oldRanges = if (prefix < oldLine.length - suffix) listOf(prefix until oldLine.length - suffix) else emptyList()
        val newRanges = if (prefix < newLine.length - suffix) listOf(prefix until newLine.length - suffix) else emptyList()
        return oldRanges to newRanges
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.history.NoteDiffTest"`
Expected: PASS（10 个用例）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mynote/app/ui/history/NoteDiff.kt app/src/test/java/com/mynote/app/ui/history/NoteDiffTest.kt
git commit -m "feat: 笔记 diff 引擎（行级 + 行内字符高亮）"
```

---

### Task 4: NoteHistoryViewModel

**Files:**
- Create: `app/src/main/java/com/mynote/app/ui/history/NoteHistoryViewModel.kt`
- Test: `app/src/test/java/com/mynote/app/ui/history/NoteHistoryViewModelTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/mynote/app/ui/history/NoteHistoryViewModelTest.kt`：

```kotlin
package com.mynote.app.ui.history

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.image.ImageStore
import com.mynote.app.data.repository.NoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteHistoryViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var repo: NoteRepository
    private val vms = mutableListOf<NoteHistoryViewModel>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = NoteRepository(db.noteDao(), db.categoryDao(), db.noteRevisionDao(), ImageStore(context), db)
    }

    @After
    fun teardown() {
        vms.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
        db.close()
    }

    private fun createVm(id: Long): NoteHistoryViewModel =
        NoteHistoryViewModel(repo, id).also { vms += it }

    @Test
    fun listShowsCurrentMarkerAndChangeLabels() = runTest(dispatcher) {
        val id = repo.saveNote(null, "t1", "c1", null, false, null)
        repo.saveNote(id, "t2", "c1", null, false, null)
        val vm = createVm(id)

        val state = vm.state.first { it.count == 2 }
        assertTrue(state.revisions[0].isCurrent)
        assertEquals(listOf("标题已修改"), state.revisions[0].labels)
        assertEquals(listOf("初始版本"), state.revisions[1].labels)
    }

    @Test
    fun bannerAppearsAt40Revisions() = runTest(dispatcher) {
        val id = repo.saveNote(null, "t", "v0", null, false, null)
        for (i in 1..39) repo.saveNote(id, "t", "v$i", null, false, null)
        val vm = createVm(id)
        val state = vm.state.first { it.count == 40 }
        assertNotNull(state.bannerText)
    }

    @Test
    fun firstRevisionHasNoDiff() = runTest(dispatcher) {
        val id = repo.saveNote(null, "t1", "c1", null, false, null)
        repo.saveNote(id, "t2", "c2", null, false, null)
        val vm = createVm(id)
        val state = vm.state.first { it.count == 2 }
        vm.selectRevision(state.revisions[1].revision.id)

        val detail = vm.state.value.detail!!
        assertTrue(detail.isFirst)
        assertTrue(detail.diffLines.isEmpty())
    }

    @Test
    fun selectingRevisionComputesDiff() = runTest(dispatcher) {
        val id = repo.saveNote(null, "t", "line1", null, false, null)
        repo.saveNote(id, "t", "line1\nline2", null, false, null)
        val vm = createVm(id)
        val state = vm.state.first { it.count == 2 }
        vm.selectRevision(state.revisions[0].revision.id)

        val detail = vm.state.first { it.detail?.diffLines?.isNotEmpty() == true }.detail!!
        assertEquals(
            listOf(NoteDiff.Type.UNCHANGED, NoteDiff.Type.ADDED),
            detail.diffLines.map { it.type }
        )
    }

    @Test
    fun restoreIsSingleFlightAndWritesBack() = runTest(dispatcher) {
        val id = repo.saveNote(null, "t", "v1", null, false, null)
        repo.saveNote(id, "t", "v2", null, false, null)
        val vm = createVm(id)
        val state = vm.state.first { it.count == 2 }
        val oldId = state.revisions[1].revision.id

        vm.selectRevision(oldId)
        vm.restore()
        vm.restore() // 第二次应被防重入拦截

        vm.state.first { it.restoreSucceeded }
        assertEquals("v1", repo.getNote(id)?.content)
        assertEquals(3, repo.countRevisions(id))
    }

    @Test
    fun closeDetailResetsDetailState() = runTest(dispatcher) {
        val id = repo.saveNote(null, "t1", "c1", null, false, null)
        repo.saveNote(id, "t2", "c2", null, false, null)
        val vm = createVm(id)
        val state = vm.state.first { it.count == 2 }
        vm.selectRevision(state.revisions[0].revision.id)
        assertNotNull(vm.state.value.detail)
        vm.closeDetail()
        assertEquals(null, vm.state.value.detail)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.history.NoteHistoryViewModelTest"`
Expected: 编译失败 `Unresolved reference: NoteHistoryViewModel`。

- [ ] **Step 3: 实现 ViewModel**

创建 `app/src/main/java/com/mynote/app/ui/history/NoteHistoryViewModel.kt`：

```kotlin
package com.mynote.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mynote.app.data.db.NoteRevisionDao
import com.mynote.app.data.db.NoteRevisionEntity
import com.mynote.app.data.repository.NoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NoteHistoryViewModel(
    private val repository: NoteRepository,
    private val noteId: Long
) : ViewModel() {

    data class RevisionItem(
        val revision: NoteRevisionEntity,
        val isCurrent: Boolean,
        val labels: List<String>
    )

    data class DetailState(
        val revision: NoteRevisionEntity,
        val isFirst: Boolean,
        val isCurrent: Boolean,
        val labels: List<String>,
        val diffLines: List<NoteDiff.Line> = emptyList(),
        val loadingDiff: Boolean = false,
        val showFullText: Boolean = false
    )

    data class UiState(
        val revisions: List<RevisionItem> = emptyList(),
        val count: Int = 0,
        val bannerText: String? = null,
        val detail: DetailState? = null,
        val restoring: Boolean = false,
        val restoreSucceeded: Boolean = false,
        val message: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            repository.observeRevisions(noteId).collectLatest { raw ->
                _state.update { current ->
                    current.copy(
                        revisions = raw.mapIndexed { index, rev ->
                            RevisionItem(
                                revision = rev,
                                isCurrent = index == 0,
                                labels = if (index == raw.lastIndex) listOf("初始版本")
                                else changeLabels(rev, raw[index + 1])
                            )
                        },
                        count = raw.size,
                        bannerText = bannerFor(raw.size),
                        detail = refreshDetail(current.detail, raw)
                    )
                }
            }
        }
    }

    fun selectRevision(revisionId: Long) {
        val list = _state.value.revisions
        val index = list.indexOfFirst { it.revision.id == revisionId }
        if (index < 0) return
        val item = list[index]
        val older = list.getOrNull(index + 1)
        _state.update {
            it.copy(
                detail = DetailState(
                    revision = item.revision,
                    isFirst = older == null,
                    isCurrent = item.isCurrent,
                    labels = item.labels,
                    loadingDiff = older != null
                )
            )
        }
        if (older != null) {
            viewModelScope.launch {
                val lines = withContext(Dispatchers.Default) {
                    NoteDiff.diff(older.revision.content, item.revision.content)
                }
                _state.update { s ->
                    val detail = s.detail
                    if (detail != null && detail.revision.id == revisionId) {
                        s.copy(detail = detail.copy(diffLines = lines, loadingDiff = false))
                    } else {
                        s
                    }
                }
            }
        }
    }

    fun closeDetail() {
        _state.update { it.copy(detail = null) }
    }

    fun toggleFullText() {
        _state.update { s ->
            val detail = s.detail ?: return@update s
            s.copy(detail = detail.copy(showFullText = !detail.showFullText))
        }
    }

    fun restore() {
        val detail = _state.value.detail ?: return
        if (_state.value.restoring) return
        _state.update { it.copy(restoring = true) }
        val revisionId = detail.revision.id
        viewModelScope.launch {
            val ok = repository.restoreRevision(noteId, revisionId)
            _state.update {
                it.copy(restoring = false, restoreSucceeded = ok, message = if (ok) null else "恢复失败")
            }
        }
    }

    fun consumeRestoreSuccess() {
        _state.update { it.copy(restoreSucceeded = false) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun refreshDetail(detail: DetailState?, raw: List<NoteRevisionEntity>): DetailState? {
        if (detail == null) return null
        val index = raw.indexOfFirst { it.id == detail.revision.id }
        if (index < 0) return null
        val rev = raw[index]
        val older = raw.getOrNull(index + 1)
        return detail.copy(
            revision = rev,
            isCurrent = index == 0,
            isFirst = older == null,
            labels = if (older == null) listOf("初始版本") else changeLabels(rev, older)
        )
    }

    private fun changeLabels(newer: NoteRevisionEntity, older: NoteRevisionEntity): List<String> = buildList {
        if (newer.title != older.title) add("标题已修改")
        if (newer.content != older.content) add("正文已修改")
        if (newer.categoryId != older.categoryId) add("分类已修改")
        if (newer.pinned != older.pinned) add("置顶已修改")
        if (newer.color != older.color) add("颜色已修改")
        if (isEmpty()) add("已修改")
    }

    private fun bannerFor(count: Int): String? = when {
        count >= NoteRevisionDao.MAX_PER_NOTE ->
            "历史已满 ${NoteRevisionDao.MAX_PER_NOTE} 条，最旧记录将随新记录自动清理"
        count >= NoteRevisionDao.WARN_AT ->
            "历史已达 $count/${NoteRevisionDao.MAX_PER_NOTE} 条，满 ${NoteRevisionDao.MAX_PER_NOTE} 条后最旧记录会自动清理"
        else -> null
    }

    companion object {
        fun factory(repo: NoteRepository, noteId: Long): ViewModelProvider.Factory =
            viewModelFactory { initializer { NoteHistoryViewModel(repo, noteId) } }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.history.NoteHistoryViewModelTest"`
Expected: PASS（6 个用例）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mynote/app/ui/history/NoteHistoryViewModel.kt app/src/test/java/com/mynote/app/ui/history/NoteHistoryViewModelTest.kt
git commit -m "feat: 历史页 ViewModel（时间线 / 详情 diff / 恢复）"
```

---

### Task 5: 历史页界面、导航与编辑页入口

**Files:**
- Create: `app/src/main/java/com/mynote/app/ui/history/NoteHistoryScreen.kt`
- Modify: `app/src/main/java/com/mynote/app/ui/navigation/AppNavHost.kt:30-42`
- Modify: `app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt:132-218`

- [ ] **Step 1: 实现历史页**

创建 `app/src/main/java/com/mynote/app/ui/history/NoteHistoryScreen.kt`：

```kotlin
package com.mynote.app.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mynote.app.data.db.NoteRevisionDao
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.util.TimeFormat

private val RemovedBg = Color(0x33EF5350)
private val AddedBg = Color(0x334CAF50)
private val RemovedEmphasis = Color(0x66EF5350)
private val AddedEmphasis = Color(0x664CAF50)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteHistoryScreen(
    noteId: Long,
    repository: NoteRepository,
    onRestored: () -> Unit,
    onBack: () -> Unit
) {
    val vm: NoteHistoryViewModel = viewModel(
        key = "note_history_$noteId",
        factory = NoteHistoryViewModel.factory(repository, noteId)
    )
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showRestoreDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = state.detail != null) { vm.closeDetail() }

    LaunchedEffect(state.restoreSucceeded) {
        if (state.restoreSucceeded) {
            vm.consumeRestoreSuccess()
            onRestored()
        }
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        vm.consumeMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("历史记录 (${state.count}/${NoteRevisionDao.MAX_PER_NOTE})") },
                navigationIcon = {
                    IconButton(onClick = { if (state.detail != null) vm.closeDetail() else onBack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            val detail = state.detail
            if (detail == null) {
                state.bannerText?.let { banner ->
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                    ) {
                        Text(
                            banner,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (state.revisions.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("保存一次后开始记录")
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.revisions, key = { it.revision.id }) { item ->
                            ListItem(
                                headlineContent = { Text(TimeFormat.dateTime(item.revision.savedAt)) },
                                supportingContent = { Text(item.labels.joinToString(" · ")) },
                                trailingContent = {
                                    if (item.isCurrent) {
                                        Text(
                                            "当前版本",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                },
                                modifier = Modifier.clickable { vm.selectRevision(item.revision.id) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            } else {
                DetailContent(state = state, detail = detail, vm = vm, onRestore = { showRestoreDialog = true })
            }
        }
    }

    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text("恢复此版本？") },
            text = { Text("将用此版本覆盖当前内容，并生成一条新的历史记录。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreDialog = false
                        vm.restore()
                    },
                    enabled = !state.restoring
                ) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun DetailContent(
    state: NoteHistoryViewModel.UiState,
    detail: NoteHistoryViewModel.DetailState,
    vm: NoteHistoryViewModel,
    onRestore: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(TimeFormat.dateTime(detail.revision.savedAt), style = MaterialTheme.typography.titleMedium)
            Text(
                detail.labels.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !detail.showFullText,
                    onClick = { if (detail.showFullText) vm.toggleFullText() },
                    label = { Text("对比") }
                )
                FilterChip(
                    selected = detail.showFullText,
                    onClick = { if (!detail.showFullText) vm.toggleFullText() },
                    label = { Text("全文") }
                )
            }
        }
        HorizontalDivider()
        if (detail.showFullText || detail.isFirst) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)
            ) {
                Text(detail.revision.content, style = MaterialTheme.typography.bodyMedium)
            }
        } else if (detail.loadingDiff) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(detail.diffLines) { line -> DiffLineRow(line) }
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.End
        ) {
            if (!detail.isCurrent) {
                Button(onClick = onRestore, enabled = !state.restoring) {
                    Text(if (state.restoring) "恢复中…" else "恢复此版本")
                }
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: NoteDiff.Line) {
    val background = when (line.type) {
        NoteDiff.Type.REMOVED -> RemovedBg
        NoteDiff.Type.ADDED -> AddedBg
        NoteDiff.Type.UNCHANGED -> Color.Transparent
    }
    val emphasisColor = when (line.type) {
        NoteDiff.Type.REMOVED -> RemovedEmphasis
        NoteDiff.Type.ADDED -> AddedEmphasis
        NoteDiff.Type.UNCHANGED -> Color.Transparent
    }
    val text = buildAnnotatedString {
        var cursor = 0
        for (range in line.emphasis) {
            if (range.first > cursor) append(line.text.substring(cursor, range.first))
            val end = (range.last + 1).coerceAtMost(line.text.length)
            if (end > range.first) {
                withStyle(SpanStyle(background = emphasisColor)) {
                    append(line.text.substring(range.first, end))
                }
            }
            cursor = end
        }
        if (cursor < line.text.length) append(line.text.substring(cursor))
        if (line.text.isEmpty()) append(" ")
    }
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().background(background).padding(horizontal = 12.dp, vertical = 2.dp),
        style = MaterialTheme.typography.bodyMedium,
        textDecoration = if (line.type == NoteDiff.Type.REMOVED) TextDecoration.LineThrough else null
    )
}
```

- [ ] **Step 2: 接线导航**

修改 `AppNavHost.kt`：`NoteEditScreen` 增加 `onOpenHistory` 参数；新增 `note_history/{noteId}` 路由（import `com.mynote.app.ui.history.NoteHistoryScreen`）：

```kotlin
        composable("edit/{noteId}") { backStack ->
            val idArg = backStack.arguments?.getString("noteId")
            val id = idArg?.takeIf { it != "new" }?.toLongOrNull()
            NoteEditScreen(
                noteId = id,
                repository = container.noteRepository,
                imageStore = container.imageStore,
                backupManager = container.backupManager,
                imageRenderer = container.noteImageRenderer,
                exportManager = container.imageExportManager,
                onOpenHistory = { id?.let { navController.navigate("note_history/$it") } },
                onBack = { navController.popBackStack() }
            )
        }
        composable("note_history/{noteId}") { backStack ->
            val noteId = backStack.arguments?.getString("noteId")?.toLongOrNull() ?: return@composable
            NoteHistoryScreen(
                noteId = noteId,
                repository = container.noteRepository,
                onRestored = { navController.popBackStack("notes", inclusive = false) },
                onBack = { navController.popBackStack() }
            )
        }
```

- [ ] **Step 3: 编辑页加入口图标**

修改 `NoteEditScreen.kt`：

1. 签名增加参数（在 `onBack` 前）：
```kotlin
    onOpenHistory: () -> Unit,
    onBack: () -> Unit
```
2. import 增加：`androidx.compose.material.icons.filled.History`
3. `actions` 中在置顶图标前加入：
```kotlin
                    if (noteId != null && noteId != 0L) {
                        IconButton(onClick = onOpenHistory) {
                            Icon(Icons.Default.History, contentDescription = "历史记录")
                        }
                    }
```

- [ ] **Step 4: 编译验证（单测 + debug APK）**

Run: `.\gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL。

Run: `.\gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/mynote/app/ui/history/NoteHistoryScreen.kt app/src/main/java/com/mynote/app/ui/navigation/AppNavHost.kt app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt
git commit -m "feat: 历史记录界面、导航与编辑页入口"
```

---

### Task 6: 40 条提醒（编辑页 Snackbar）

**Files:**
- Modify: `app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt:111-116,185-221`
- Test: `app/src/test/java/com/mynote/app/ui/notes/NoteEditViewModelTest.kt`

- [ ] **Step 1: 写失败测试**

在 `NoteEditViewModelTest.kt` 类末尾新增（import 增加 `com.mynote.app.data.db.NoteRevisionDao`，无需其他）：

```kotlin
    @Test
    fun saveAt40RevisionsReportsWarningOnce() = runTest(dispatcher) {
        val id = repo.saveNote(null, "t", "v0", null, false, null)
        for (i in 1..38) repo.saveNote(id, "t", "v$i", null, false, null)
        assertEquals(39, repo.countRevisions(id))

        vm.viewModelScope.cancel()
        vm = NoteEditViewModel(repo, ImageStore(ApplicationProvider.getApplicationContext()), noteId = id)
        val warning = CompletableDeferred<String?>()
        vm.save("t", "v39", null, false, null) { warning.complete(it) }
        assertEquals(NoteEditViewModel.HISTORY_WARNING, warning.await())
        assertEquals(40, repo.countRevisions(id))
    }

    @Test
    fun saveBelowWarningThresholdDoesNotReport() = runTest(dispatcher) {
        val id = repo.saveNote(null, "t", "v0", null, false, null)
        for (i in 1..36) repo.saveNote(id, "t", "v$i", null, false, null)
        assertEquals(37, repo.countRevisions(id))

        vm.viewModelScope.cancel()
        vm = NoteEditViewModel(repo, ImageStore(ApplicationProvider.getApplicationContext()), noteId = id)
        val warning = CompletableDeferred<String?>()
        vm.save("t", "v38", null, false, null) { warning.complete(it) }
        assertEquals(null, warning.await())
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.notes.NoteEditViewModelTest"`
Expected: 编译失败（`save` 回调类型为 `() -> Unit`，不匹配）。

- [ ] **Step 3: 实现 VM 警告逻辑**

修改 `NoteEditScreen.kt` 中 `NoteEditViewModel`：

1. `save` 替换为：

```kotlin
    fun save(title: String, content: String, categoryId: Long?, pinned: Boolean, color: Int?, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val id = repository.saveNote(noteId, title, content, categoryId, pinned, color)
            val warning = if (noteId != null && noteId != 0L && repository.countRevisions(id) == NoteRevisionDao.WARN_AT) {
                HISTORY_WARNING
            } else {
                null
            }
            onDone(warning)
        }
    }
```

2. companion object 增加常量（import `com.mynote.app.data.db.NoteRevisionDao`）：

```kotlin
        const val HISTORY_WARNING =
            "该笔记历史已 ${NoteRevisionDao.WARN_AT} 条，满 ${NoteRevisionDao.MAX_PER_NOTE} 条后最旧记录会自动清理"
```

- [ ] **Step 4: 屏幕显示 Snackbar 后返回**

修改 `NoteEditScreen` 的 Scaffold 与保存按钮：

1. Scaffold 前增加：
```kotlin
    val snackbarHostState = remember { SnackbarHostState() }
```
（import `androidx.compose.material3.SnackbarHost`、`androidx.compose.material3.SnackbarHostState`）

2. Scaffold 参数增加 `snackbarHost = { SnackbarHost(snackbarHostState) },`

3. 保存按钮回调替换为：

```kotlin
                    IconButton(onClick = {
                        vm.save(title, content, selectedCategoryId, pinned, note?.color) { warning ->
                            if (warning != null) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(warning)
                                    onBack()
                                }
                            } else {
                                onBack()
                            }
                        }
                    }) {
                        Icon(Icons.Default.Save, contentDescription = "保存")
                    }
```

- [ ] **Step 5: 运行测试确认通过**

Run: `.\gradlew :app:testDebugUnitTest --tests "com.mynote.app.ui.notes.NoteEditViewModelTest"`
Expected: PASS（原 4 个 + 新 2 个）。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/mynote/app/ui/notes/NoteEditScreen.kt app/src/test/java/com/mynote/app/ui/notes/NoteEditViewModelTest.kt
git commit -m "feat: 保存达到 40 条历史时提醒一次"
```

---

### Task 7: 文档更新与全量验证

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `docs/superpowers/specs/2026-09-10-note-history-design.md:4`
- Modify: `docs/superpowers/plans/2026-09-10-note-history.md`（本文件「修订记录」）

- [ ] **Step 1: 全量单测并记录数量**

Run: `.\gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL。统计实际用例数：

```powershell
$sum = 0; Get-ChildItem app/build/test-results/testDebugUnitTest/*.xml | ForEach-Object { $x = [xml](Get-Content $_.FullName); $sum += [int]$x.testsuite.tests }; Write-Output "test count=$sum"
```

- [ ] **Step 2: 更新 README**

在功能列表中补充历史记录一条（措辞参照现有条目，中文），例如：

```markdown
- 笔记历史：每次保存自动留存版本，可按时间线查看 diff 对比并恢复旧版（每篇最多 50 条）
```

同时把 README 中"测试数（70）"更新为 Step 1 得到的实际数字。

- [ ] **Step 3: 更新 AGENTS.md**

- 把「全部单测（当前 70 个）」中的数字更新为实际值。
- 把 Room 一行改为反映现状，例如：
```markdown
- Room `version = 2`、`exportSchema = false`；已有手写 `MIGRATION_1_2`（历史表）。修改 Entity 需升版本并自行补迁移与迁移测试。
```
- 在「数据与代码惯例」补充一句：
```markdown
- 笔记历史：每次保存写一条 `note_revisions` 快照（无变化不写，每篇上限 50 条，超出自动裁最旧）；图片 GC 的引用集 = 当前正文 ∪ 全部历史快照，勿只统计正文。
```

- [ ] **Step 4: 更新设计文档状态**

把 `docs/superpowers/specs/2026-09-10-note-history-design.md` 第 4 行状态改为：

```markdown
- 状态：已实现（2026-09-10，全量单测 N 个全绿 + debug/release 构建通过；真机 UI 手工验证待做）
```

（N 为 Step 1 实际数字）

- [ ] **Step 5: 更新本计划「修订记录」**

在本文件末尾补充实际执行中的修订（格式与 `docs/superpowers/plans/2026-09-10-note-image-export.md` 的「修订记录」一致）。

- [ ] **Step 6: release 构建**

Run: `.\gradlew :app:assembleRelease`
Expected: BUILD SUCCESSFUL（若工作树缺 `keystore.properties`/`*.keystore`，从主检出复制，勿提交）。

- [ ] **Step 7: 提交**

```bash
git add README.md AGENTS.md docs/superpowers/specs/2026-09-10-note-history-design.md docs/superpowers/plans/2026-09-10-note-history.md
git commit -m "docs: 笔记历史功能落地（README / AGENTS / 设计文档状态 / 计划修订记录）"
```

---

## 人工验证清单（实现完成后由控制者汇总提醒用户）

1. 旧版本 APK 覆盖安装新版本：数据完好，老笔记首次保存后时间线出现"基线 + 新版"两条。
2. 连续保存、内容不变：不新增历史。
3. 改标题/正文/分类/置顶/颜色：列表标签正确。
4. 浅色与深色主题下 diff 红绿可读；中文长段落只高亮变化的字。
5. 恢复到旧版：确认框 → 列表出现新记录 → 自动回到笔记列表 → 打开笔记内容为旧版。
6. 恢复分类已删除的版本：显示未分类。
7. 40 条横幅、保存到 40 条时的 Snackbar、第 51 条自动裁剪。
8. 历史引用图片：正文删图保存后图片仍在；该版本被裁出 50 条后图片回收。
9. 删除笔记：历史一并清空。
10. 新建空笔记保存后再编辑：历史正常。

## 修订记录

- 2026-09-10（初稿）：依据设计文档 `2026-09-10-note-history-design.md` 拆分 7 个任务；快照存储选全量快照而非增量 delta；diff 采用行级 LCS + 配对行字符前后缀高亮；迁移测试用手工 v1 库文件而非引入 room-testing（零新增依赖）。
- 2026-09-10（执行期修订，Task 2 质量审查）：`restoreRevision` 增加 `revision.noteId == noteId` 归属校验；`collectImageGarbage` 包 `withContext(Dispatchers.IO)`；GC 与并发写入的 TOCTOU 窗口在设计文档 §14 记为已接受风险。
- 2026-09-10（执行期修订，Task 3 质量审查）：diff 改为先裁剪公共前后缀行再进 LCS（阈值按 `(n+1)*(m+1)`）；`pairAdjacent` 用配对标记数组，单侧前后缀变化不再把未变侧整行高亮；高亮区间对齐码点边界（emoji 不拆半）。
- 2026-09-10（执行期修订，Task 5 质量审查）：首版历史详情隐藏「对比/全文」切换（无上一版可比）；详情被裁剪/关闭时自动收起恢复确认框；`DiffLineRow` 强调区间游标加固（重叠/越界安全）。
- 2026-09-10（执行期修订，Task 6 质量审查）：40 条提醒改为仅在跨过阈值（保存前 < 40 且保存后 == 40）时触发一次；无变化重复保存不再重复提示。
- 2026-09-10（收尾）：全量 113 个单测全绿；README / AGENTS / 设计文档状态已同步；release 构建通过。
