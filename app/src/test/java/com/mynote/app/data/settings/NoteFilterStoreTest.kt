package com.mynote.app.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteFilterStoreTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(NoteFilterStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun readDefaultsToNull() {
        assertNull(NoteFilterStore(context).read())
    }

    @Test
    fun writePersistsAcrossInstances() {
        NoteFilterStore(context).write("category:7")
        assertEquals("category:7", NoteFilterStore(context).read())
    }

    @Test
    fun laterWriteOverwritesEarlierOne() {
        val store = NoteFilterStore(context)
        store.write("uncategorized")
        store.write("all")
        assertEquals("all", NoteFilterStore(context).read())
    }
}
