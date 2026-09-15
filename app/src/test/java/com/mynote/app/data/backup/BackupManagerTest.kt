package com.mynote.app.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.db.CategoryEntity
import com.mynote.app.data.db.NoteEntity
import com.mynote.app.data.image.ImageStore
import com.mynote.app.ui.notes.NoteContentParser
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = ImageStore(context)
    private val manager = BackupManager(context, store)

    private lateinit var db: AppDatabase
    private val exportUri: Uri = Uri.parse("content://test/export.zip")
    private val backupUri: Uri = Uri.parse("content://test/backup.zip")

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun teardown() {
        db.close()
    }

    /** 构造注入内存流的管理器：测试不依赖 ShadowContentResolver。 */
    private fun managerWith(
        input: ByteArray? = null,
        output: ByteArrayOutputStream? = null
    ): BackupManager = BackupManager(
        context,
        store,
        db,
        openOutput = { output },
        openInput = { input?.let { ByteArrayInputStream(it) } }
    )

    private fun backupJson(
        notes: List<BackupManager.BackupNote>,
        categories: List<BackupManager.BackupCategory> = emptyList()
    ): ByteArray =
        manager.encode(BackupManager.BackupData(notes, categories)).toByteArray(Charsets.UTF_8)

    private fun buildZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun backupDataJsonRoundTrips() {
        val data = BackupManager.BackupData(
            notes = listOf(
                BackupManager.BackupNote(1, "标题", "正文 ![](img/a.jpg)", 1L, 2L, null, true, 0)
            ),
            categories = listOf(BackupManager.BackupCategory(1, "工作", 0))
        )
        val json = manager.encode(data)
        val decoded = manager.decode(json)
        assertEquals(data, decoded)
    }

    @Test
    fun collectReferencedImagesFromNotes() {
        val notes = listOf(
            BackupManager.BackupNote(1, "a", "![](img/1.jpg)", 0, 0, null, false, null),
            BackupManager.BackupNote(2, "b", "无图", 0, 0, null, false, null)
        )
        val refs = notes.flatMap { NoteContentParser.extractImageNames(it.content) }.toSet()
        assertEquals(setOf("1.jpg"), refs)
    }

    @Test
    fun incomingWinsKeepsNewerVersion() {
        assertTrue(manager.incomingWins(null, 1L))
        assertTrue(manager.incomingWins(1L, 2L))
        assertFalse(manager.incomingWins(2L, 1L))
        assertFalse(manager.incomingWins(1L, 1L))
    }

    @Test
    fun backupNoteJsonRoundTripsWithDeletedAt() {
        val note = BackupManager.BackupNote(1, "标题", "正文", 1L, 2L, null, true, 0, 12345L)
        val data = BackupManager.BackupData(listOf(note), emptyList())
        val decoded = manager.decode(manager.encode(data))
        assertEquals(12345L, decoded.notes[0].deletedAt)
    }

    @Test
    fun oldBackupJsonWithoutDeletedAtDecodesAsNull() {
        val json = """{"notes":[{"id":1,"title":"旧","content":"c","createdAt":1,"updatedAt":2,"categoryId":null,"pinned":false,"color":null}],"categories":[]}"""
        val decoded = manager.decode(json)
        assertNull(decoded.notes[0].deletedAt)
    }

    @Test
    fun exportZipWithNullOutputThrows() = runTest {
        db.noteDao().insert(NoteEntity(1, "t", "c", 1, 1, null, false, null))
        val broken = BackupManager(context, store, db, openOutput = { null }, openInput = { null })

        val error = runCatching { broken.exportZip(exportUri) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("无法写入所选文件", error?.message)
    }

    @Test
    fun exportTxtWithNullOutputReturnsFalse() = runTest {
        val broken = BackupManager(context, store, db, openOutput = { null }, openInput = { null })

        assertFalse(broken.exportNoteAsTxt(exportUri, "标题", "正文"))
    }

    @Test
    fun exportTxtIncludesTitleAndBlankLine() = runTest {
        val out = ByteArrayOutputStream()

        assertTrue(managerWith(output = out).exportNoteAsTxt(exportUri, "标题", "正文"))

        assertEquals("标题\n\n正文", String(out.toByteArray(), Charsets.UTF_8))
    }

    @Test
    fun exportTxtWithBlankTitleWritesContentOnly() = runTest {
        val out = ByteArrayOutputStream()

        assertTrue(managerWith(output = out).exportNoteAsTxt(exportUri, "   ", "正文"))

        assertEquals("正文", String(out.toByteArray(), Charsets.UTF_8))
    }

    @Test
    fun importReportsInsertedUpdatedSkippedAndTrashed() = runTest {
        // 本地已有两条：id=201 比备份旧（应被覆盖），id=202 比备份新（应跳过）
        db.noteDao().insert(NoteEntity(201, "本地较旧", "local-old", 1, 50, null, false, null))
        db.noteDao().insert(NoteEntity(202, "本地较新", "local-new", 1, 50, null, false, null))
        val zip = buildZip(
            "notes.json" to backupJson(
                listOf(
                    BackupManager.BackupNote(100, "新增", "backup-new", 1, 10, null, false, null),
                    BackupManager.BackupNote(201, "较新", "backup-newer", 1, 100, null, false, null),
                    BackupManager.BackupNote(202, "较旧", "backup-older", 1, 10, null, false, null),
                    BackupManager.BackupNote(300, "回收站", "trashed", 1, 10, null, false, null, 123L)
                )
            )
        )

        val result = managerWith(input = zip).importZip(backupUri)

        assertEquals(1, result.inserted)
        assertEquals(1, result.updated)
        assertEquals(1, result.skipped)
        assertEquals(1, result.trashed)
        assertEquals("backup-new", db.noteDao().getById(100)?.content)
        assertEquals("backup-newer", db.noteDao().getById(201)?.content)
        assertEquals("local-new", db.noteDao().getById(202)?.content)
        assertEquals(123L, db.noteDao().getById(300)?.deletedAt)
    }

    @Test
    fun importRejectsInvalidEntryNameWithoutDbWrites() = runTest {
        val zip = buildZip(
            "notes.json" to backupJson(
                listOf(BackupManager.BackupNote(1, "t", "c", 1, 1, null, false, null))
            ),
            "img/../../evil" to "x".toByteArray(Charsets.UTF_8)
        )

        val error = runCatching { managerWith(input = zip).importZip(backupUri) }.exceptionOrNull()

        assertTrue(error is BackupFormatException)
        assertEquals("不是有效的备份文件", error?.message)
        assertTrue(db.noteDao().getAll().isEmpty())
    }

    @Test
    fun importRejectsNonZipWithoutDbWrites() = runTest {
        val error = runCatching {
            managerWith(input = "not a zip".toByteArray(Charsets.UTF_8)).importZip(backupUri)
        }.exceptionOrNull()

        assertTrue(error is BackupFormatException)
        assertEquals("不是有效的备份文件", error?.message)
        assertTrue(db.noteDao().getAll().isEmpty())
    }

    @Test
    fun importStreamsImageBytesToPhysicalFile() = runTest {
        val imageBytes = ByteArray(128 * 1024) { (it % 251).toByte() }
        val zip = buildZip(
            "notes.json" to backupJson(emptyList()),
            "img/pic.png" to imageBytes
        )

        val result = managerWith(input = zip).importZip(backupUri)

        assertEquals(BackupImportResult(0, 0, 0, 0), result)
        val file = store.physicalFile("pic.png")
        assertTrue(file.exists())
        assertTrue(file.readBytes().contentEquals(imageBytes))
    }

    @Test
    fun importReusesCategoryWithSameNameAndRemapsNote() = runTest {
        // 本地分类 id=1「工作」；备份分类 id=7 同名但颜色不同
        val localId = db.categoryDao().insert(CategoryEntity(0, "工作", 111))
        val zip = buildZip(
            "notes.json" to backupJson(
                notes = listOf(BackupManager.BackupNote(500, "n", "c", 1, 1, 7L, false, null)),
                categories = listOf(BackupManager.BackupCategory(7, "工作", 222))
            )
        )

        val result = managerWith(input = zip).importZip(backupUri)

        assertEquals(1, result.inserted)
        val categories = db.categoryDao().getAll()
        assertEquals(1, categories.size)
        assertEquals(localId, categories[0].id)
        assertEquals("工作", categories[0].name)
        assertEquals(222, categories[0].color)
        assertEquals(localId, db.noteDao().getById(500)?.categoryId)
    }

    @Test
    fun importNullsUnknownCategoryReference() = runTest {
        val zip = buildZip(
            "notes.json" to backupJson(
                listOf(BackupManager.BackupNote(700, "n", "c", 1, 1, 999L, false, null))
            )
        )

        managerWith(input = zip).importZip(backupUri)

        val note = db.noteDao().getById(700)
        assertNotNull(note)
        assertNull(note?.categoryId)
    }

    @Test
    fun importRollsBackPartialWritesWhenLaterEntryFails() = runTest {
        // 备份先插入新分类 id=5，再把 id=1 改名为「B」与本地 id=2 冲突 → 事务整体回滚
        db.categoryDao().insert(CategoryEntity(0, "A", 1))
        db.categoryDao().insert(CategoryEntity(0, "B", 2))
        val zip = buildZip(
            "notes.json" to backupJson(
                notes = listOf(BackupManager.BackupNote(500, "n", "c", 1, 1, null, false, null)),
                categories = listOf(
                    BackupManager.BackupCategory(5, "新分类", 3),
                    BackupManager.BackupCategory(1, "B", 4)
                )
            )
        )

        val error = runCatching { managerWith(input = zip).importZip(backupUri) }.exceptionOrNull()

        assertNotNull(error)
        assertNull(db.categoryDao().getById(5))
        assertNull(db.noteDao().getById(500))
        assertEquals(2, db.categoryDao().getAll().size)
    }
}
