package com.mynote.app.di

import android.content.Context
import androidx.room.Room
import com.mynote.app.data.ai.AiChatRepository
import com.mynote.app.data.ai.AiDriverRegistry
import com.mynote.app.data.ai.AiWebDriver
import com.mynote.app.data.ai.AiWebSession
import com.mynote.app.data.ai.DeepSeekDriver
import com.mynote.app.data.ai.WebViewAiSession
import com.mynote.app.data.backup.BackupManager
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.export.ImageExportManager
import com.mynote.app.data.export.NoteImageRenderer
import com.mynote.app.data.image.ImageStore
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.data.settings.AiSettingsStore
import com.mynote.app.data.settings.NoteSortStore
import com.mynote.app.data.settings.ThemeSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {

    val database: AppDatabase by lazy {
        Room.databaseBuilder(context, AppDatabase::class.java, "mynote.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .build()
    }

    val imageStore: ImageStore by lazy { ImageStore(context) }

    val themeSettingsStore: ThemeSettingsStore by lazy { ThemeSettingsStore(context) }

    val noteSortStore: NoteSortStore by lazy { NoteSortStore(context) }

    val noteRepository: NoteRepository by lazy {
        NoteRepository(database.noteDao(), database.categoryDao(), database.noteRevisionDao(), imageStore, database)
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

    val aiDriverRegistry: AiDriverRegistry by lazy {
        AiDriverRegistry(listOf(DeepSeekDriver()))
    }

    val aiWebSessionFactory: (AiWebDriver) -> AiWebSession = { WebViewAiSession(it) }
}
