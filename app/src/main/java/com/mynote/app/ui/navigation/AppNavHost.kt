package com.mynote.app.ui.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.mynote.app.di.AppContainer
import com.mynote.app.ui.ai.AiChatContextStore
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

/**
 * 页面转场时长（毫秒）。
 *
 * Navigation 在未指定 transition 时默认是 700ms 全屏淡入淡出：一次跳转要双屏交叉绘制约 42 帧，
 * 体感就是「点了半拍才响应」。这里换成短促的淡入 + 轻微横向位移，只改观感，不改导航语义。
 */
private const val NAV_ENTER_MS = 180
private const val NAV_EXIT_MS = 120
private const val NAV_POP_ENTER_MS = 140

object AiNavKeys {
    const val RESULT_TYPE = "ai_result_type"
    const val RESULT_TEXT = "ai_result_text"
}

object NavResults {
    /** 历史恢复完成的时间戳：编辑页据此重新读库回填（恢复提示本身由编辑页文案固定）。 */
    const val RESTORE_AT = "restore_at"

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
        navController.navigate(EditNoteRoute(noteId = id)) { launchSingleTop = true }
    }
    NavHost(
        navController = navController,
        startDestination = NotesRoute,
        enterTransition = {
            fadeIn(tween(NAV_ENTER_MS, easing = FastOutSlowInEasing)) +
                slideInHorizontally(tween(NAV_ENTER_MS, easing = FastOutSlowInEasing)) { it / 24 }
        },
        exitTransition = { fadeOut(tween(NAV_EXIT_MS)) },
        popEnterTransition = { fadeIn(tween(NAV_POP_ENTER_MS)) },
        popExitTransition = {
            fadeOut(tween(NAV_EXIT_MS)) + slideOutHorizontally(tween(NAV_EXIT_MS)) { it / 24 }
        }
    ) {
        composable<NotesRoute> { backStack ->
            val vm: NotesViewModel = viewModel(
                factory = NotesViewModel.factory(
                    container.noteRepository,
                    container.noteSortStore,
                    container.noteFilterStore,
                    container.backupManager
                )
            )
            val retentionDays by container.trashRetentionStore.retentionDays.collectAsStateWithLifecycle()
            val deletedNoteId by backStack.savedStateHandle
                .getStateFlow<Long?>(NavResults.DELETED_NOTE_ID, null).collectAsStateWithLifecycle()
            NotesScreen(
                viewModel = vm,
                backupManager = container.backupManager,
                retentionDays = retentionDays,
                deletedNoteId = deletedNoteId,
                onDeletedNoteConsumed = {
                    backStack.savedStateHandle.remove<Long>(NavResults.DELETED_NOTE_ID)
                },
                onOpenNote = { id -> navController.navigate(EditNoteRoute(noteId = id)) { launchSingleTop = true } },
                onNewNote = { catId ->
                    navController.navigate(EditNoteRoute(categoryId = catId ?: -1L)) { launchSingleTop = true }
                },
                onManageCategories = { navController.navigate(CategoriesRoute) },
                onOpenTrash = { navController.navigate(TrashRoute) },
                onOpenCalendar = { navController.navigate(CalendarRoute) },
                onOpenSettings = { navController.navigate(SettingsRoute) }
            )
        }
        composable<EditNoteRoute> { backStack ->
            val route = backStack.toRoute<EditNoteRoute>()
            val id = route.noteId.takeIf { it != 0L }
            val initialCategoryId = route.categoryId.takeIf { it >= 0L }
            val resultType by backStack.savedStateHandle
                .getStateFlow<String?>(AiNavKeys.RESULT_TYPE, null).collectAsStateWithLifecycle()
            val resultText by backStack.savedStateHandle
                .getStateFlow<String?>(AiNavKeys.RESULT_TEXT, null).collectAsStateWithLifecycle()
            // 条目 93 / 恢复回填：历史页把恢复完成时间写回本条目，编辑页据此重新读库
            val restoredAt by backStack.savedStateHandle
                .getStateFlow<Long?>(NavResults.RESTORE_AT, null).collectAsStateWithLifecycle()
            val retentionDays by container.trashRetentionStore.retentionDays.collectAsStateWithLifecycle()
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
                restoredAt = restoredAt,
                onRestoreConsumed = {
                    backStack.savedStateHandle.remove<Long>(NavResults.RESTORE_AT)
                },
                onAiResultConsumed = {
                    backStack.savedStateHandle.remove<String>(AiNavKeys.RESULT_TYPE)
                    backStack.savedStateHandle.remove<String>(AiNavKeys.RESULT_TEXT)
                },
                onOpenAi = { selStart, selEnd, noteTitle, noteContent ->
                    // P1：正文只进进程内交接对象，不写 SavedStateHandle（超长笔记会撞 Binder 上限）
                    id?.let { noteId ->
                        AiChatContextStore.put(noteId, noteTitle, noteContent, selStart, selEnd)
                        navController.navigate(AiChatRoute(noteId))
                    }
                },
                onNoteDeleted = { deletedId ->
                    AiChatContextStore.remove(deletedId)
                    // 条目 48：写回列表页（start destination 恒在栈上）
                    navController.getBackStackEntry<NotesRoute>()
                        .savedStateHandle[NavResults.DELETED_NOTE_ID] = deletedId
                },
                onOpenHistory = { dirty -> id?.let { navController.navigate(NoteHistoryRoute(it, dirty)) } },
                onBack = { navController.popBackStack() }
            )
        }
        composable<AiChatRoute> { backStack ->
            val noteId = backStack.toRoute<AiChatRoute>().noteId
            val prev = navController.previousBackStackEntry
            // 会话只在进入本页时创建一次；重组不再重复 new（Provider 变化由 VM 内部重建）
            val aiSession = remember(container) { container.aiSessionFactory() }
            AiChatScreen(
                noteId = noteId,
                aiRepository = container.aiChatRepository,
                noteRepository = container.noteRepository,
                settingsStore = container.aiSettingsStore,
                externalScope = container.applicationScope,
                session = aiSession,
                draftStore = container.aiDraftStore,
                // 条目 81：设置页切服务商后，下一次发送会自动用新服务商重建会话
                sessionFactory = { container.aiSessionFactory() },
                onApplyResult = { type, text ->
                    prev?.savedStateHandle?.set(AiNavKeys.RESULT_TYPE, type)
                    prev?.savedStateHandle?.set(AiNavKeys.RESULT_TEXT, text)
                    navController.popBackStack()
                },
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onBack = { navController.popBackStack() }
            )
        }
        composable<NoteHistoryRoute> { backStack ->
            val route = backStack.toRoute<NoteHistoryRoute>()
            val noteId = route.noteId
            // 编辑页带着未保存修改进入历史页时，恢复确认框需提示草稿将被丢弃
            val hadUnsavedDraft = route.dirty
            NoteHistoryScreen(
                noteId = noteId,
                hadUnsavedDraft = hadUnsavedDraft,
                repository = container.noteRepository,
                onRestored = {
                    // 条目 93：只弹一层（回到编辑器），不要把编辑器一起弹掉；
                    // 恢复完成时间写进上一层，编辑页据此重新读库覆盖本地旧编辑态
                    navController.previousBackStackEntry?.savedStateHandle
                        ?.set(NavResults.RESTORE_AT, System.nanoTime())
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable<CategoriesRoute> {
            CategoriesScreen(
                repository = container.noteRepository,
                onBack = { navController.popBackStack() }
            )
        }
        composable<TrashRoute> {
            TrashScreen(
                repository = container.noteRepository,
                retentionStore = container.trashRetentionStore,
                onOpenNote = { id ->
                    navController.navigate(EditNoteRoute(noteId = id)) { launchSingleTop = true }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable<CalendarRoute> {
            CalendarScreen(
                repository = container.noteRepository,
                onOpenNote = { id -> navController.navigate(EditNoteRoute(noteId = id)) { launchSingleTop = true } },
                onBack = { navController.popBackStack() }
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                themeStore = container.themeSettingsStore,
                sortStore = container.noteSortStore,
                trashStore = container.trashRetentionStore,
                repository = container.noteRepository,
                aiSettingsStore = container.aiSettingsStore,
                clientFactory = container.aiApiClientFactory,
                onOpenPromptEditor = { navController.navigate(PromptRoute) },
                onBack = { navController.popBackStack() }
            )
        }
        composable<PromptRoute> {
            PromptEditScreen(
                aiSettingsStore = container.aiSettingsStore,
                onBack = { navController.popBackStack() }
            )
        }
    }
}