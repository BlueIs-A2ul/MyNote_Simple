package com.mynote.app.ui.ai

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiChatContextStoreTest {

    @After
    fun tearDown() {
        AiChatContextStore.clear()
    }

    @Test
    fun keepsOnlyMostRecentThreeNotes() {
        AiChatContextStore.put(1, "t1", "c1", 0, 0)
        AiChatContextStore.put(2, "t2", "c2", 0, 0)
        AiChatContextStore.put(3, "t3", "c3", 0, 0)
        AiChatContextStore.put(4, "t4", "c4", 0, 0)

        assertNull("最旧的一篇应被淘汰", AiChatContextStore.get(1))
        assertEquals("t2", AiChatContextStore.get(2)?.title)
        assertEquals("t4", AiChatContextStore.get(4)?.title)
    }

    @Test
    fun selectionFlagAndExplicitRemovalWork() {
        AiChatContextStore.put(7, "标题", "正文", 2, 5)
        val context = AiChatContextStore.get(7)
        assertTrue(context!!.hasSelection)
        assertFalse(AiChatContextStore.Context("t", "c", 3, 3).hasSelection)

        AiChatContextStore.remove(7)
        assertNull(AiChatContextStore.get(7))
    }
}
