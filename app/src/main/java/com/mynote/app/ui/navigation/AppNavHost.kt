package com.mynote.app.ui.navigation

import androidx.compose.runtime.Composable
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
}

@Composable
fun AppNavHost(container: AppContainer) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "notes") {
        composable("notes") { backStack ->
            val vm: NotesViewModel = viewModel(factory = NotesViewModel.factory(container.noteRepository, container.noteSortStore))
            // 历史恢复成功后回传的提示：由历史页写入本条目的 savedStateHandle，此处读取
            val restoreMessage by backStack.savedStateHandle
                .getStateFlow<String?>(NavResults.RESTORE_MESSAGE, null).collectAsState()
            NotesScreen(
                viewModel = vm,
                backupManager = container.backupManager,
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
            NoteEditScreen(
                noteId = id,
                initialCategoryId = initialCategoryId,
                repository = container.noteRepository,
                imageStore = container.imageStore,
                backupManager = container.backupManager,
                imageRenderer = container.noteImageRenderer,
                exportManager = container.imageExportManager,
                aiResultType = resultType,
                aiResultText = resultText,
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
                    // 恢复成功提示先写到 notes 条目（start destination 恒在），弹出后由 NotesScreen 弹 snackbar
                    navController.getBackStackEntry("notes").savedStateHandle[NavResults.RESTORE_MESSAGE] =
                        "已恢复历史版本"
                    navController.popBackStack("notes", inclusive = false)
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
                aiSettingsStore = container.aiSettingsStore,
                clientFactory = container.aiApiClientFactory,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
