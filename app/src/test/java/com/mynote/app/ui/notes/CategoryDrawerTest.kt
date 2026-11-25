package com.mynote.app.ui.notes

import com.mynote.app.data.db.CategoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryDrawerTest {

    private val work = CategoryEntity(id = 1, name = "工作", color = 0xFF8E9BC4.toInt())
    private val life = CategoryEntity(id = 2, name = "生活", color = 0xFFB8A06E.toInt())
    private val categories = listOf(work, life)

    @Test
    fun `fixed filters come first and categories keep repository order`() {
        val entries = categoryDrawerEntries(categories, CategoryFilter.All)

        assertEquals(
            listOf(CategoryFilter.All, CategoryFilter.Uncategorized, CategoryFilter.Single(1), CategoryFilter.Single(2)),
            entries.map { it.filter }
        )
        assertEquals(listOf("全部", "未分类", "工作", "生活"), entries.map { it.label })
    }

    @Test
    fun `only the current filter is marked selected`() {
        val entries = categoryDrawerEntries(categories, CategoryFilter.Single(2))

        assertEquals(listOf(false, false, false, true), entries.map { it.selected })
    }

    @Test
    fun `category color is passed through and fixed entries have none`() {
        val entries = categoryDrawerEntries(categories, CategoryFilter.All)

        assertNull(entries[0].colorArgb)
        assertNull(entries[1].colorArgb)
        assertEquals(work.color, entries[2].colorArgb)
        assertEquals(life.color, entries[3].colorArgb)
    }

    @Test
    fun `deleted category label falls back to unknown`() {
        val deleted = CategoryFilter.Single(99)

        assertEquals("未知分类", deleted.menuLabel(categories))
        assertEquals("未知分类", deleted.summaryLabel(categories))
        assertTrue(categoryDrawerEntries(categories, deleted).none { it.selected })
    }

    @Test
    fun `summary labels distinguish all from uncategorized`() {
        assertEquals("全部笔记", CategoryFilter.All.summaryLabel(categories))
        assertEquals("未分类", CategoryFilter.Uncategorized.summaryLabel(categories))
        assertEquals("工作", CategoryFilter.Single(1).summaryLabel(categories))
        assertEquals("全部", CategoryFilter.All.menuLabel(categories))
    }
}
