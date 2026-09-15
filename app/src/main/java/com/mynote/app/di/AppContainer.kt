package com.mynote.app.di

import android.content.Context
import androidx.room.Room
import com.mynote.app.data.ai.AiChatRepository
import com.mynote.app.data.ai.AiSession
import com.mynote.app.data.ai.DeepSeekApiClient
import com.mynote.app.data.ai.DeepSeekApiSession
import com.mynote.app.data.backup.BackupManager
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.export.ImageExportManager
import com.mynote.app.data.export.NoteImageRenderer
import com.mynote.app.data.image.ImageStore
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.data.repository.WelcomeNoteSeeder
import com.mynote.app.data.settings.AiDraftStore
import com.mynote.app.data.settings.AiSettingsStore
import com.mynote.app.data.settings.NoteSortStore
import com.mynote.app.data.settings.OnboardingStore
import com.mynote.app.data.settings.PrefsAiDraftStore
import com.mynote.app.data.settings.ThemeSettingsStore
import com.mynote.app.data.settings.TrashRetentionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {

    val database: AppDatabase by lazy {
        Room.databaseBuilder(context, AppDatabase::class.java, "mynote.db")
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6
            )
            .build()
    }

    val imageStore: ImageStore by lazy { ImageStore(context) }

    val themeSettingsStore: ThemeSettingsStore by lazy { ThemeSettingsStore(context) }

    val noteSortStore: NoteSortStore by lazy { NoteSortStore(context) }

    val trashRetentionStore: TrashRetentionStore by lazy { TrashRetentionStore(context) }

    val noteRepository: NoteRepository by lazy {
        NoteRepository(database.noteDao(), database.categoryDao(), database.noteRevisionDao(), imageStore, database)
    }

    val onboardingStore: OnboardingStore by lazy { OnboardingStore(context) }

    /** 新用户首次启动生成欢迎笔记（只一次；已有笔记的用户不生成）。 */
    val welcomeNoteSeeder: WelcomeNoteSeeder by lazy {
        WelcomeNoteSeeder(database.noteDao(), noteRepository, onboardingStore)
    }

    val backupManager: BackupManager by lazy {
        BackupManager(context, imageStore, database)
    }

    val noteImageRenderer: NoteImageRenderer by lazy { NoteImageRenderer(imageStore) }

    val imageExportManager: ImageExportManager by lazy { ImageExportManager(context) }

    /** 应用级协程作用域：ViewModel 清理后仍需完成的收尾写入（流式中断存档）用它。 */
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    val aiChatRepository: AiChatRepository by lazy {
        AiChatRepository(database.aiSessionDao(), database.aiMessageDao())
    }

    val aiSettingsStore: AiSettingsStore by lazy { AiSettingsStore(context) }

    val deepSeekApiClient: DeepSeekApiClient by lazy { DeepSeekApiClient() }

    /** AI 助手输入草稿；提示词输入随会话持久化，切走/回来自动恢复。 */
    val aiDraftStore: AiDraftStore by lazy { PrefsAiDraftStore(context) }

    /**
     * 每次进入 AI 页新建一个会话与独立客户端（取消隔离，条目 62）；
     * 读写设置走 Provider，设置页改 Key / 模型立即生效。
     */
    val aiSessionFactory: () -> AiSession = {
        DeepSeekApiSession(
            streamer = DeepSeekApiClient(),
            credentials = { aiSettingsStore.apiKey() },
            model = { aiSettingsStore.model() },
            deepThinking = { aiSettingsStore.deepThinking() },
            scope = applicationScope
        )
    }
}
