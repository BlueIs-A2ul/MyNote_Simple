package com.mynote.app.data.settings

import android.content.Context

/** AI 助手输入草稿存储：按「笔记 + 会话」键读写未发送的输入。 */
interface AiDraftStore {
    fun get(key: String): String
    fun set(key: String, value: String)
}

/** SharedPreferences 实现（"ai_drafts"）；数据量小，写入走 apply()。 */
class PrefsAiDraftStore(context: Context) : AiDraftStore {

    private val prefs = context.getSharedPreferences("ai_drafts", Context.MODE_PRIVATE)

    override fun get(key: String): String = prefs.getString(key, null).orEmpty()

    /** 空串等价清除该键，避免残留无意义记录。 */
    override fun set(key: String, value: String) {
        if (value.isEmpty()) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, value).apply()
        }
    }
}

/** 草稿键：`<noteId>:<sessionId ?: 0>`；新对话（sessionId 为 null）固定用 0。 */
fun aiDraftKey(noteId: Long, sessionId: Long?): String = "$noteId:${sessionId ?: 0L}"
