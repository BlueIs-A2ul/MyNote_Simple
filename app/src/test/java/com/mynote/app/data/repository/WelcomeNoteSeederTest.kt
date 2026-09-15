package com.mynote.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.db.AppDatabase
import com.mynote.app.data.db.NoteEntity
import com.mynote.app.data.image.ImageStore
import com.mynote.app.data.settings.OnboardingStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WelcomeNoteSeederTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: NoteRepository
    private lateinit var onboardingStore: OnboardingStore
    private lateinit var seeder: WelcomeNoteSeeder

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = NoteRepository(
            db.noteDao(), db.categoryDao(), db.noteRevisionDao(), ImageStore(context), db
        )
        onboardingStore = OnboardingStore(context)
        seeder = WelcomeNoteSeeder(db.noteDao(), repository, onboardingStore)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun emptyHomeSeedsWelcomeNote() = runTest {
        seeder.seedIfNeeded()

        val notes = repository.observeNotes().first()
        assertEquals(1, notes.size)
        assertEquals(WelcomeNoteSeeder.WELCOME_TITLE, notes[0].title)

        val full = db.noteDao().getById(notes[0].id)!!
        assertTrue(full.content.contains("【AI 助手】"))
        assertTrue(full.content.contains("API Key"))
        assertTrue(full.content.contains("回收站"))
        assertTrue(onboardingStore.isWelcomeSeeded())
    }

    @Test
    fun secondRunDoesNotDuplicate() = runTest {
        seeder.seedIfNeeded()
        seeder.seedIfNeeded()

        assertEquals(1, repository.observeNotes().first().size)
    }

    @Test
    fun existingNoteSkipsSeedingButMarksFlag() = runTest {
        repository.saveNote(null, "已有笔记", "内容", null, false, null)

        seeder.seedIfNeeded()

        assertEquals(listOf("已有笔记"), repository.observeNotes().first().map { it.title })
        assertTrue(onboardingStore.isWelcomeSeeded())

        // 用户删光后也不再补建
        db.noteDao().getAll().forEach { db.noteDao().delete(it) }
        seeder.seedIfNeeded()
        assertTrue(repository.observeNotes().first().isEmpty())
    }

    @Test
    fun alreadySeededWithEmptyHomeSkips() = runTest {
        onboardingStore.markWelcomeSeeded()

        seeder.seedIfNeeded()

        assertTrue(repository.observeNotes().first().isEmpty())
    }

    @Test
    fun trashOnlyNotesStillSeeds() = runTest {
        // 回收站中的笔记不算主页可见笔记
        db.noteDao().insert(
            NoteEntity(0, "已删除", "内容", 1, 1, null, false, null, deletedAt = 2)
        )

        seeder.seedIfNeeded()

        assertEquals(
            listOf(WelcomeNoteSeeder.WELCOME_TITLE),
            repository.observeNotes().first().map { it.title }
        )
    }
}
