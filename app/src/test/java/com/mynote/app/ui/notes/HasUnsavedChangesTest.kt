package com.mynote.app.ui.notes

import com.mynote.app.data.db.NoteEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HasUnsavedChangesTest {

    private fun savedNote(
        title: String = "旧标题",
        content: String = "旧内容",
        categoryId: Long? = null,
        pinned: Boolean = false,
        noteDate: Long? = null
    ) = NoteEntity(7L, title, content, 0L, 0L, categoryId, pinned, null, deletedAt = null, noteDate = noteDate)

    @Test
    fun newNoteAllEmptyIsNotDirty() =
        assertFalse(hasUnsavedChanges(true, null, null, "", "", null, false, null))

    @Test
    fun newNoteBlankTitleIsNotDirty() =
        assertFalse(hasUnsavedChanges(true, null, null, "   ", "", null, false, null))

    @Test
    fun newNoteContentIsDirty() =
        assertTrue(hasUnsavedChanges(true, null, null, "", "内容", null, false, null))

    @Test
    fun newNoteTitleIsDirty() =
        assertTrue(hasUnsavedChanges(true, null, null, "标题", "", null, false, null))

    @Test
    fun newNoteCategoryOnlyIsNotDirty() =
        assertFalse(hasUnsavedChanges(true, null, null, "", "", 3L, false, null))

    @Test
    fun newNotePreselectedCategoryOnlyIsNotDirty() =
        assertFalse(hasUnsavedChanges(true, null, 3L, "", "", 3L, false, null))

    @Test
    fun newNoteCategoryChangedOnEmptyNoteIsNotDirty() =
        assertFalse(hasUnsavedChanges(true, null, 3L, "", "", 4L, false, null))

    @Test
    fun newNoteCategoryClearedOnEmptyNoteIsNotDirty() =
        assertFalse(hasUnsavedChanges(true, null, 3L, "", "", null, false, null))

    @Test
    fun newNotePinnedOnlyIsNotDirty() =
        assertFalse(hasUnsavedChanges(true, null, null, "", "", null, true, null))

    @Test
    fun newNoteDateOnlyIsNotDirty() =
        assertFalse(hasUnsavedChanges(true, null, null, "", "", null, false, 999L))

    @Test
    fun newNoteWithTitleAndCategoryChangedIsDirty() =
        assertTrue(hasUnsavedChanges(true, null, 3L, "标题", "", 4L, false, null))

    @Test
    fun newNoteWithContentAndPinnedIsDirty() =
        assertTrue(hasUnsavedChanges(true, null, null, "", "内容", null, true, null))

    @Test
    fun existingNoteNotLoadedAndEmptyIsNotDirty() =
        assertFalse(hasUnsavedChanges(false, null, null, "", "", null, false, null))

    @Test
    fun existingNoteNotLoadedWithInputIsDirty() =
        assertTrue(hasUnsavedChanges(false, null, null, "输入中", "", null, false, null))

    @Test
    fun existingNoteUnchangedIsNotDirty() =
        assertFalse(hasUnsavedChanges(false, savedNote(), null, "旧标题", "旧内容", null, false, null))

    @Test
    fun existingNoteTitleChangedIsDirty() =
        assertTrue(hasUnsavedChanges(false, savedNote(), null, "新标题", "旧内容", null, false, null))

    @Test
    fun existingNoteContentChangedIsDirty() =
        assertTrue(hasUnsavedChanges(false, savedNote(), null, "旧标题", "新内容", null, false, null))

    @Test
    fun existingNoteCategoryChangedIsDirty() =
        assertTrue(hasUnsavedChanges(false, savedNote(), null, "旧标题", "旧内容", 3L, false, null))

    @Test
    fun existingNoteCategoryClearedIsDirty() =
        assertTrue(hasUnsavedChanges(false, savedNote(categoryId = 3L), null, "旧标题", "旧内容", null, false, null))

    @Test
    fun existingNotePinnedChangedIsDirty() =
        assertTrue(hasUnsavedChanges(false, savedNote(), null, "旧标题", "旧内容", null, true, null))

    @Test
    fun existingNoteDateChangedIsDirty() =
        assertTrue(hasUnsavedChanges(false, savedNote(noteDate = 111L), null, "旧标题", "旧内容", null, false, 222L))

    @Test
    fun existingNoteDateSetFromNothingIsDirty() =
        assertTrue(hasUnsavedChanges(false, savedNote(), null, "旧标题", "旧内容", null, false, 222L))

    @Test
    fun existingNoteDateClearedIsDirty() =
        assertTrue(hasUnsavedChanges(false, savedNote(noteDate = 111L), null, "旧标题", "旧内容", null, false, null))

    @Test
    fun existingNoteSameDateIsNotDirty() =
        assertFalse(hasUnsavedChanges(false, savedNote(noteDate = 111L), null, "旧标题", "旧内容", null, false, 111L))
}
