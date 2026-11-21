package com.mynote.app.ui.navigation

import kotlinx.serialization.Serializable

/** Navigation 2.8 type-safe 路由：参数由序列化生成，避免手写字符串与 navArgument 漂移。 */

@Serializable
data object NotesRoute

@Serializable
data class EditNoteRoute(
    /** 0 表示新建；与旧 `edit/new` 语义一致。 */
    val noteId: Long = 0L,
    /** -1 表示未指定分类。 */
    val categoryId: Long = -1L
)

@Serializable
data class AiChatRoute(val noteId: Long)

@Serializable
data class NoteHistoryRoute(val noteId: Long, val dirty: Boolean = false)

@Serializable
data object CategoriesRoute

@Serializable
data object TrashRoute

@Serializable
data object CalendarRoute

@Serializable
data object SettingsRoute

@Serializable
data object PromptRoute
