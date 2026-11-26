package com.mynote.app.ui.notes

import com.mynote.app.data.db.CategoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * token 会落进 SharedPreferences，字面量一旦改动老用户就丢记忆，因此逐字锁死；
 * 脏值一律回落「全部」，不能让主页卡在空分类上。
 */
class CategoryFilterCodecTest {

    private fun category(id: Long, name: String = "分类$id") =
        CategoryEntity(id = id, name = name, color = 0)

    @Test
    fun encodesStableTokens() {
        assertEquals("all", CategoryFilter.All.encodeToken())
        assertEquals("uncategorized", CategoryFilter.Uncategorized.encodeToken())
        assertEquals("category:42", CategoryFilter.Single(42L).encodeToken())
    }

    @Test
    fun roundTripsEveryKind() {
        listOf(
            CategoryFilter.All,
            CategoryFilter.Uncategorized,
            CategoryFilter.Single(1L),
            CategoryFilter.Single(Long.MAX_VALUE)
        ).forEach { filter ->
            assertEquals("往返应一致：$filter", filter, decodeCategoryFilter(filter.encodeToken()))
        }
    }

    @Test
    fun missingTokenFallsBackToAll() {
        assertEquals(CategoryFilter.All, decodeCategoryFilter(null))
        assertEquals(CategoryFilter.All, decodeCategoryFilter(""))
    }

    @Test
    fun garbageTokensFallBackToAll() {
        listOf(
            "unknown", "ALL", "category", "category:", "category:abc",
            "category:-3", "category:0", "category:1x", "category:1 "
        ).forEach { token ->
            assertEquals("脏值应回落「全部」：$token", CategoryFilter.All, decodeCategoryFilter(token))
        }
    }

    @Test
    fun remembersNothingButAllAndUncategorizedForever() {
        val categories = listOf(category(1L), category(2L))
        assertFalse(CategoryFilter.All.isMissingFrom(categories))
        assertFalse(CategoryFilter.Uncategorized.isMissingFrom(categories))
    }

    @Test
    fun detectsRememberedCategoryThatDisappeared() {
        val categories = listOf(category(1L))
        assertFalse("分类仍在：不应回落", CategoryFilter.Single(1L).isMissingFrom(categories))
        assertTrue("分类已删：应回落", CategoryFilter.Single(9L).isMissingFrom(categories))
        assertTrue("分类被删到一个不剩：也应回落", CategoryFilter.Single(1L).isMissingFrom(emptyList()))
    }
}
