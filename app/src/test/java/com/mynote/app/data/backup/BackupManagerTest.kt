package com.mynote.app.data.backup

import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.image.ImageStore
import com.mynote.app.ui.notes.NoteContentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupManagerTest {

    private val store = ImageStore(ApplicationProvider.getApplicationContext())
    private val manager = BackupManager(ApplicationProvider.getApplicationContext(), store)

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
}
