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
        pinned: Boolean = false
    ) = NoteEntity(7L, title, content, 0L, 0L, categoryId, pinned, null)

    @Test
    fun newNoteAllEmptyIsNotDirty() =
        assertFalse(hasUnsavedChanges(true, null, null, "", "", null, false))

    @Test
    fun newNoteBlankTitleIsNotDirty() =
        assertFalse(hasUnsavedChanges(true, null, null, "   ", "", null, false))

    @Test
    fun newNoteContentIsDirty() =
        assertTrue(hasUnsavedChanges(true, null, null, "", "内容", null, false))

    @Test
    fun newNoteTitleIsDirty() =
        assertTrue(hasUnsavedChanges(true, null, null, "标题", "", null, false))

    @Test
    fun newNoteCategoryOnlyIsNotDirty() =
        assertFalse(hasUnsavedChanges(true, null, null, "", "", 3L, false))

    @Test
    fun newNotePreselectedCategoryOnlyIsNotDirty() =
        assertFalse(hasUnsavedChanges(true, null, 3L, "", "", 3L, false))

    @Test
    fun newNoteCategoryChangedOnEmptyNoteIsNotDirty() =
        assertFalse(hasUnsavedChanges(true, null, 3L, "", "", 4L, false))

    @Test
    fun newNoteCategoryClearedOnEmptyNoteIsNotDirty() =
        assertFalse(hasUnsavedChanges(true, null, 3L, "", "", null, false))

    @Test
    fun newNotePinnedOnlyIsNotDirty() =
        assertFalse(hasUnsavedChanges(true, null, null, "", "", null, true))

    @Test
    fun newNoteWithTitleAndCategoryChangedIsDirty() =
        assertTrue(hasUnsavedChanges(true, null, 3L, "标题", "", 4L, false))

    @Test
    fun newNoteWithContentAndPinnedIsDirty() =
        assertTrue(hasUnsavedChanges(true, null, null, "", "内容", null, true))

    @Test
    fun existingNoteNotLoadedAndEmptyIsNotDirty() =
        assertFalse(hasUnsavedChanges(false, null, null, "", "", null, false))

    @Test
    fun existingNoteNotLoadedWithInputIsDirty() =
        assertTrue(hasUnsavedChanges(false, null, null, "输入中", "", null, false))

    @Test
    fun existingNoteUnchangedIsNotDirty() =
        assertFalse(hasUnsavedChanges(false, savedNote(), null, "旧标题", "旧内容", null, false))

    @Test
    fun existingNoteTitleChangedIsDirty() =
        assertTrue(hasUnsavedChanges(false, savedNote(), null, "新标题", "旧内容", null, false))

    @Test
    fun existingNoteContentChangedIsDirty() =
        assertTrue(hasUnsavedChanges(false, savedNote(), null, "旧标题", "新内容", null, false))

    @Test
    fun existingNoteCategoryChangedIsDirty() =
        assertTrue(hasUnsavedChanges(false, savedNote(), null, "旧标题", "旧内容", 3L, false))

    @Test
    fun existingNoteCategoryClearedIsDirty() =
        assertTrue(hasUnsavedChanges(false, savedNote(categoryId = 3L), null, "旧标题", "旧内容", null, false))

    @Test
    fun existingNotePinnedChangedIsDirty() =
        assertTrue(hasUnsavedChanges(false, savedNote(), null, "旧标题", "旧内容", null, true))
}
