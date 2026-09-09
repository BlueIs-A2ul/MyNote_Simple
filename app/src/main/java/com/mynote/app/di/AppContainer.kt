package com.mynote.app.di

import android.content.Context
import androidx.room.Room
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.image.ImageStore
import com.mynote.app.data.repository.NoteRepository

class AppContainer(context: Context) {

    val database: AppDatabase by lazy {
        Room.databaseBuilder(context, AppDatabase::class.java, "mynote.db").build()
    }

    val imageStore: ImageStore by lazy { ImageStore(context) }

    val noteRepository: NoteRepository by lazy {
        NoteRepository(database.noteDao(), database.categoryDao(), imageStore)
    }
}
