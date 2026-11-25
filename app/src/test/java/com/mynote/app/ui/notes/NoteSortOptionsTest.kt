package com.mynote.app.ui.notes

import com.mynote.app.data.db.NoteSortMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSortOptionsTest {

    @Test
    fun `order follows the sort enum and only the current entry is selected`() {
        val options = noteSortOptions(NoteSortMode.CREATED_DESC)

        assertEquals(
            listOf(NoteSortMode.UPDATED_DESC, NoteSortMode.CREATED_DESC, NoteSortMode.TITLE_ASC),
            options.map { it.mode }
        )
        assertEquals(listOf(false, true, false), options.map { it.selected })
    }

    @Test
    fun `labels match the settings page wording`() {
        val labels = noteSortOptions(NoteSortMode.UPDATED_DESC).map { it.label }

        assertEquals(listOf("最近更新", "最新创建", "按标题"), labels)
    }

    @Test
    fun `every mode selects exactly itself`() {
        NoteSortMode.entries.forEach { mode ->
            val selected = noteSortOptions(mode).filter { it.selected }

            assertEquals(1, selected.size)
            assertTrue(selected.single().mode == mode)
            assertEquals(3, noteSortOptions(mode).size)
        }
    }
}
