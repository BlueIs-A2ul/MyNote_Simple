package com.mynote.app.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiDraftStoreTest {

    private lateinit var context: Context
    private lateinit var store: PrefsAiDraftStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ai_drafts", Context.MODE_PRIVATE).edit().clear().commit()
        store = PrefsAiDraftStore(context)
    }

    @Test
    fun keyFormatsNoteAndSessionWithNullFallback() {
        assertEquals("7:3", aiDraftKey(7L, 3L))
        assertEquals("7:0", aiDraftKey(7L, null))
    }

    @Test
    fun readWriteRoundTrip() {
        val key = aiDraftKey(1L, 2L)
        assertEquals("", store.get(key))

        store.set(key, "草稿内容")
        assertEquals("草稿内容", store.get(key))
    }

    @Test
    fun setOverwritesPreviousValue() {
        val key = aiDraftKey(1L, null)
        store.set(key, "第一版")
        store.set(key, "第二版")

        assertEquals("第二版", store.get(key))
    }

    @Test
    fun emptyStringClearsDraft() {
        val key = aiDraftKey(1L, 2L)
        store.set(key, "待发送")

        store.set(key, "")

        assertEquals("", store.get(key))
    }

    @Test
    fun differentKeysAreIsolated() {
        store.set(aiDraftKey(1L, 1L), "A")
        store.set(aiDraftKey(1L, 2L), "B")
        store.set(aiDraftKey(2L, null), "C")

        assertEquals("A", store.get(aiDraftKey(1L, 1L)))
        assertEquals("B", store.get(aiDraftKey(1L, 2L)))
        assertEquals("C", store.get(aiDraftKey(2L, null)))
        assertEquals("", store.get(aiDraftKey(1L, null)))
    }
}
