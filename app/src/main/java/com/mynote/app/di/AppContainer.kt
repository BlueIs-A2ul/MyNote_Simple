package com.mynote.app.di

import android.content.Context
import androidx.room.Room
import com.mynote.app.data.backup.BackupManager
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.export.ImageExportManager
import com.mynote.app.data.export.NoteImageRenderer
import com.mynote.app.data.image.ImageStore
import com.mynote.app.data.repository.NoteRepository
import com.mynote.app.data.settings.ThemeSettingsStore

class AppContainer(context: Context) {

    val database: AppDatabase by lazy {
        Room.databaseBuilder(context, AppDatabase::class.java, "mynote.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
    }

    val imageStore: ImageStore by lazy { ImageStore(context) }

    val themeSettingsStore: ThemeSettingsStore by lazy { ThemeSettingsStore(context) }

    val noteRepository: NoteRepository by lazy {
        NoteRepository(database.noteDao(), database.categoryDao(), imageStore)
    }

    val backupManager: BackupManager by lazy {
        BackupManager(context, imageStore, database)
    }

    val noteImageRenderer: NoteImageRenderer by lazy { NoteImageRenderer(imageStore) }

    val imageExportManager: ImageExportManager by lazy { ImageExportManager(context) }
}
