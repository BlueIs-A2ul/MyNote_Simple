package com.mynote.app.di

import android.content.Context
import androidx.room.Room
import com.mynote.app.data.ai.AiApiClient
import com.mynote.app.data.ai.AiApiSession
import com.mynote.app.data.ai.AiChatRepository
import com.mynote.app.data.ai.AiEndpoint
import com.mynote.app.data.ai.AiSession
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

    /** 当前服务商端点：预设地址 + 自定义服务商的用户地址。 */
    fun aiEndpoint(): AiEndpoint =
        aiSettingsStore.provider().endpoint(aiSettingsStore.customBaseUrl())

    /** 设置页「测试连接 / 查询余额」用的客户端工厂（按当前服务商构造）。 */
    val aiApiClientFactory: (AiEndpoint) -> AiApiClient = { endpoint -> AiApiClient(endpoint) }

    /** AI 助手输入草稿；提示词输入随会话持久化，切走/回来自动恢复。 */
    val aiDraftStore: AiDraftStore by lazy { PrefsAiDraftStore(context) }

    /**
     * 每次进入 AI 页新建一个会话与独立客户端（取消隔离，条目 62）；
     * 服务商 / Key / 模型 / 思考开关均在发请求时从设置读取，设置页改动立即生效。
     */
    val aiSessionFactory: () -> AiSession = {
        val provider = aiSettingsStore.provider()
        val endpoint = provider.endpoint(aiSettingsStore.customBaseUrl())
        AiApiSession(
            streamer = AiApiClient(endpoint),
            endpoint = endpoint,
            credentials = { aiSettingsStore.apiKey(provider) },
            model = { aiSettingsStore.model(provider) },
            deepThinking = { aiSettingsStore.deepThinking(provider) },
            scope = applicationScope
        )
    }
}
