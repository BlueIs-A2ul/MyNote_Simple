package com.mynote.app.ui.notes

/** 编辑页从数据行恢复时的动作。 */
internal enum class EditorRestoreAction {
    NONE,
    FILL_ALL,
    FILL_BODY
}

/**
 * 决定拿到数据行后是否回填编辑态。
 *
 * - 首次加载：标题与正文都为空才整页回填，避免覆盖用户抢先输入的内容。
 * - 进程被杀/缓存淘汰后：正文缓存未命中（[bodyCacheHit] 为 false）且正文为空时，只补正文；
 *   标题已在 Bundle 里恢复，不能因为标题非空就跳过正文回填。
 * - 缓存命中但正文为空：用户主动清空，不能复活旧正文。
 */
internal fun decideEditorRestore(
    initialized: Boolean,
    title: String,
    content: String,
    noteContent: String,
    bodyCacheHit: Boolean
): EditorRestoreAction = when {
    !initialized -> if (title.isEmpty() && content.isEmpty()) {
        EditorRestoreAction.FILL_ALL
    } else {
        EditorRestoreAction.NONE
    }

    !bodyCacheHit && content.isEmpty() && noteContent.isNotEmpty() -> EditorRestoreAction.FILL_BODY

    else -> EditorRestoreAction.NONE
}
