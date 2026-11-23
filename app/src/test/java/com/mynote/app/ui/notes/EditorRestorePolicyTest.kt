package com.mynote.app.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Test

/** 编辑页正文回填策略：区分“进程被杀导致缓存丢失”和“用户主动清空正文”。 */
class EditorRestorePolicyTest {

    @Test
    fun titledNoteRefillsBodyWhenProcessDeathLostCache() {
        val action = decideEditorRestore(
            initialized = true,
            title = "会议纪要",
            content = "",
            noteContent = "退出后台前的正文",
            bodyCacheHit = false
        )

        assertEquals(EditorRestoreAction.FILL_BODY, action)
    }

    @Test
    fun clearedBodyIsNotResurrectedWhenCacheStillHasEmptyValue() {
        val action = decideEditorRestore(
            initialized = true,
            title = "会议纪要",
            content = "",
            noteContent = "旧正文",
            bodyCacheHit = true
        )

        assertEquals(EditorRestoreAction.NONE, action)
    }

    @Test
    fun firstLoadFillsAllWhenBothFieldsAreEmpty() {
        val action = decideEditorRestore(
            initialized = false,
            title = "",
            content = "",
            noteContent = "库里的正文",
            bodyCacheHit = false
        )

        assertEquals(EditorRestoreAction.FILL_ALL, action)
    }

    @Test
    fun firstLoadDoesNotOverwriteUserTyping() {
        val action = decideEditorRestore(
            initialized = false,
            title = "刚输入的标题",
            content = "",
            noteContent = "库里的正文",
            bodyCacheHit = false
        )

        assertEquals(EditorRestoreAction.NONE, action)
    }

    @Test
    fun nonEmptyContentNeedsNoRefill() {
        val action = decideEditorRestore(
            initialized = true,
            title = "标题",
            content = "正在编辑的正文",
            noteContent = "库里的旧正文",
            bodyCacheHit = false
        )

        assertEquals(EditorRestoreAction.NONE, action)
    }

    @Test
    fun emptyNoteContentNeedsNoRefill() {
        val action = decideEditorRestore(
            initialized = true,
            title = "空笔记",
            content = "",
            noteContent = "",
            bodyCacheHit = false
        )

        assertEquals(EditorRestoreAction.NONE, action)
    }
}
