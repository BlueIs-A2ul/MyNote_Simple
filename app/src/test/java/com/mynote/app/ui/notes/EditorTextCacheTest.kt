package com.mynote.app.ui.notes

import androidx.compose.ui.text.input.TextFieldValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 条目 100⑦：编辑态正文缓存（超长正文不再进 Bundle）。 */
class EditorTextCacheTest {

    @After
    fun tearDown() {
        EditorTextCache.clear()
    }

    @Test
    fun keepsOnlyMostRecentEntries() {
        (1L..6L).forEach { EditorTextCache.put(it, TextFieldValue("n" + it)) }

        assertNull("最旧的应被淘汰", EditorTextCache.get(1L))
        assertNull(EditorTextCache.get(2L))
        assertEquals("n3", EditorTextCache.get(3L)?.text)
        assertEquals("n6", EditorTextCache.get(6L)?.text)
    }

    @Test
    fun recentlyReadEntrySurvivesEviction() {
        (1L..4L).forEach { EditorTextCache.put(it, TextFieldValue("n" + it)) }
        assertNotNull(EditorTextCache.get(1L))

        EditorTextCache.put(5L, TextFieldValue("n5"))

        assertNotNull("刚访问过的 1 应保留", EditorTextCache.get(1L))
        assertNull("未被访问的 2 应淘汰", EditorTextCache.get(2L))
    }

    @Test
    fun clearDropsEverything() {
        EditorTextCache.put(9L, TextFieldValue("x"))
        EditorTextCache.clear()
        assertNull(EditorTextCache.get(9L))
    }
}
