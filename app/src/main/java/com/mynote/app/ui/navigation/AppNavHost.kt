package com.mynote.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mynote.app.di.AppContainer
import com.mynote.app.ui.ai.AiChatScreen
import com.mynote.app.ui.calendar.CalendarScreen
import com.mynote.app.ui.categories.CategoriesScreen
import com.mynote.app.ui.history.NoteHistoryScreen
import com.mynote.app.ui.notes.NoteEditScreen
import com.mynote.app.ui.notes.NotesScreen
import com.mynote.app.ui.notes.NotesViewModel
import com.mynote.app.ui.settings.PromptEditScreen
import com.mynote.app.ui.settings.SettingsScreen
import com.mynote.app.ui.trash.TrashScreen

object AiNavKeys {
    const val SEL_START = "ai_sel_start"
    const val SEL_END = "ai_sel_end"
    const val NOTE_TITLE = "ai_note_title"
    const val NOTE_CONTENT = "ai_note_content"
    const val RESULT_TYPE = "ai_result_type"
    const val RESULT_TEXT = "ai_result_text"
}

object NavResults {
    const val RESTORE_MESSAGE = "restore_message"

    /** 条目 48：编辑页删除笔记后回传给列表的目标 id（列表据此弹「撤销」）。 */
    const val DELETED_NOTE_ID = "deleted_note_id"
}

@Composable
fun AppNavHost(
    container: AppContainer,
    deepLinkNoteId: Long? = null,
    onDeepLinkConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    // 条目 50：冷启动/热启动收到的 mynote://note/{id} 只消费一次
    LaunchedEffect(deepLinkNoteId) {
        val id = deepLinkNoteId ?: return@LaunchedEffect
        onDeepLinkConsumed()
        navController.navigate("edit/" + id) { launchSingleTop = true }
    }
    NavHost(navController = navController, startDestination = "notes") {
        composable("notes") { backStack ->
            val vm: NotesViewModel = viewModel(
                factory = NotesViewModel.factory(
                    container.noteRepository,
                    container.noteSortStore,
                    container.backupManager
                )
            )
            // 历史恢复成功后回传的提示：由历史页写入本条目的 savedStateHandle，此处读取
            val restoreMessage by backStack.savedStateHandle
                .getStateFlow<String?>(NavResults.RESTORE_MESSAGE, null).collectAsState()
            val retentionDays by container.trashRetentionStore.retentionDays.collectAsState()
            val deletedNoteId by backStack.savedStateHandle
                .getStateFlow<Long?>(NavResults.DELETED_NOTE_ID, null).collectAsState()
            NotesScreen(
                viewModel = vm,
                backupManager = container.backupManager,
                retentionDays = retentionDays,
                deletedNoteId = deletedNoteId,
                onDeletedNoteConsumed = {
                    backStack.savedStateHandle.remove<Long>(NavResults.DELETED_NOTE_ID)
                },
                restoreMessage = restoreMessage,
                onRestoreMessageConsumed = {
                    backStack.savedStateHandle.remove<String>(NavResults.RESTORE_MESSAGE)
                },
                onOpenNote = { id -> navController.navigate("edit/$id") { launchSingleTop = true } },
                onNewNote = { catId ->
                    navController.navigate("edit/new?categoryId=${catId ?: -1L}") { launchSingleTop = true }
                },
                onManageCategories = { navController.navigate("categories") },
                onOpenTrash = { navController.navigate("trash") },
                onOpenCalendar = { navController.navigate("calendar") },
                onOpenSettings = { navController.navigate("settings") }
            )
        }
        composable(
            route = "edit/{noteId}?categoryId={categoryId}",
            arguments = listOf(
                navArgument("categoryId") {
                    type = NavType.LongType
                    defaultValue = -1L
                }
            )
        ) { backStack ->
            val idArg = backStack.arguments?.getString("noteId")
            val id = idArg?.takeIf { it != "new" }?.toLongOrNull()
            val initialCategoryId = backStack.arguments
                ?.getLong("categoryId")
                ?.takeIf { it >= 0L }
            val resultType by backStack.savedStateHandle
                .getStateFlow<String?>(AiNavKeys.RESULT_TYPE, null).collectAsState()
            val resultText by backStack.savedStateHandle
                .getStateFlow<String?>(AiNavKeys.RESULT_TEXT, null).collectAsState()
            // 条目 93：历史恢复提示现在回传给自己（编辑器），而不是弹到主页
            val restoreMessage by backStack.savedStateHandle
                .getStateFlow<String?>(NavResults.RESTORE_MESSAGE, null).collectAsState()
            val retentionDays by container.trashRetentionStore.retentionDays.collectAsState()
            NoteEditScreen(
                noteId = id,
                initialCategoryId = initialCategoryId,
                retentionDays = retentionDays,
                repository = container.noteRepository,
                imageStore = container.imageStore,
                backupManager = container.backupManager,
                imageRenderer = container.noteImageRenderer,
                exportManager = container.imageExportManager,
                // 条目 73：草稿目标 id 与编辑页同生命周期地存进导航 entry，进程死亡后仍可恢复
                draftIdHandle = backStack.savedStateHandle,
                aiResultType = resultType,
                aiResultText = resultText,
                restoreMessage = restoreMessage,
                onRestoreMessageConsumed = {
                    backStack.savedStateHandle.remove<String>(NavResults.RESTORE_MESSAGE)
                },
                onAiResultConsumed = {
                    backStack.savedStateHandle.remove<String>(AiNavKeys.RESULT_TYPE)
                    backStack.savedStateHandle.remove<String>(AiNavKeys.RESULT_TEXT)
                },
                onOpenAi = { selStart, selEnd, noteTitle, noteContent ->
                    backStack.savedStateHandle[AiNavKeys.SEL_START] = selStart
                    backStack.savedStateHandle[AiNavKeys.SEL_END] = selEnd
                    backStack.savedStateHandle[AiNavKeys.NOTE_TITLE] = noteTitle
                    backStack.savedStateHandle[AiNavKeys.NOTE_CONTENT] = noteContent
                    navController.navigate("ai_chat/$id")
                },
                onNoteDeleted = { deletedId ->
                    // 条目 48：写回列表页（start destination 恒在栈上）
                    navController.getBackStackEntry("notes")
                        .savedStateHandle[NavResults.DELETED_NOTE_ID] = deletedId
                },
                onOpenHistory = { dirty -> id?.let { navController.navigate("note_history/$it?dirty=$dirty") } },
                onBack = { navController.popBackStack() }
            )
        }
        composable("ai_chat/{noteId}") { backStack ->
            val noteId = backStack.arguments?.getString("noteId")?.toLongOrNull()
                ?: return@composable
            val prev = navController.previousBackStackEntry?.savedStateHandle
            AiChatScreen(
                noteId = noteId,
                noteTitle = prev?.get<String>(AiNavKeys.NOTE_TITLE).orEmpty(),
                noteContent = prev?.get<String>(AiNavKeys.NOTE_CONTENT).orEmpty(),
                hasSelection = (prev?.get<Int>(AiNavKeys.SEL_END) ?: 0) >
                    (prev?.get<Int>(AiNavKeys.SEL_START) ?: 0),
                aiRepository = container.aiChatRepository,
                noteRepository = container.noteRepository,
                settingsStore = container.aiSettingsStore,
                externalScope = container.applicationScope,
                session = container.aiSessionFactory(),
                draftStore = container.aiDraftStore,
                // 条目 81：设置页切服务商后，下一次发送会自动用新服务商重建会话
                sessionFactory = { container.aiSessionFactory() },
                onApplyResult = { type, text ->
                    prev?.set(AiNavKeys.RESULT_TYPE, type)
                    prev?.set(AiNavKeys.RESULT_TEXT, text)
                    navController.popBackStack()
                },
                onOpenSettings = { navController.navigate("settings") },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = "note_history/{noteId}?dirty={dirty}",
            arguments = listOf(
                navArgument("dirty") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStack ->
            val noteId = backStack.arguments?.getString("noteId")?.toLongOrNull() ?: return@composable
            // 编辑页带着未保存修改进入历史页时，恢复确认框需提示草稿将被丢弃
            val hadUnsavedDraft = backStack.arguments?.getBoolean("dirty") == true
            NoteHistoryScreen(
                noteId = noteId,
                hadUnsavedDraft = hadUnsavedDraft,
                repository = container.noteRepository,
                onRestored = {
                    // 条目 93：只弹一层（回到编辑器），不要把编辑器一起弹掉；
                    // 提示写进上一层的 savedStateHandle，由该页消费
                    navController.previousBackStackEntry?.savedStateHandle
                        ?.set(NavResults.RESTORE_MESSAGE, "已恢复历史版本")
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable("categories") {
            CategoriesScreen(
                repository = container.noteRepository,
                onBack = { navController.popBackStack() }
            )
        }
        composable("trash") {
            TrashScreen(
                repository = container.noteRepository,
                retentionStore = container.trashRetentionStore,
                onOpenNote = { id ->
                    navController.navigate("edit/" + id) { launchSingleTop = true }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable("calendar") {
            CalendarScreen(
                repository = container.noteRepository,
                onOpenNote = { id -> navController.navigate("edit/$id") { launchSingleTop = true } },
                onBack = { navController.popBackStack() }
            )
        }
        composable("settings") {
            SettingsScreen(
                themeStore = container.themeSettingsStore,
                sortStore = container.noteSortStore,
                trashStore = container.trashRetentionStore,
                repository = container.noteRepository,
                aiSettingsStore = container.aiSettingsStore,
                clientFactory = container.aiApiClientFactory,
                onOpenPromptEditor = { navController.navigate("settings/prompt") },
                onBack = { navController.popBackStack() }
            )
        }
        composable("settings/prompt") {
            PromptEditScreen(
                aiSettingsStore = container.aiSettingsStore,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
