package com.mynote.app.ui.notes

import com.mynote.app.data.db.NoteSortMode

/** 排序文案的唯一来源：主页「整理」面板与设置页「默认排序」共用，改一处即两处同步。 */
internal fun NoteSortMode.sortLabel(): String = when (this) {
    NoteSortMode.UPDATED_DESC -> "最近更新"
    NoteSortMode.CREATED_DESC -> "最新创建"
    NoteSortMode.TITLE_ASC -> "按标题"
}

/** 面板里的一行单选：模式、文案与是否选中。 */
internal data class NoteSortOption(
    val mode: NoteSortMode,
    val label: String,
    val selected: Boolean
)

/** 顺序按 [NoteSortMode.entries] 固定（最近更新 / 最新创建 / 按标题），只有当前项选中。 */
internal fun noteSortOptions(current: NoteSortMode): List<NoteSortOption> =
    NoteSortMode.entries.map { mode ->
        NoteSortOption(mode = mode, label = mode.sortLabel(), selected = mode == current)
    }
