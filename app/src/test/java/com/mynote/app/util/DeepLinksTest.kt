package com.mynote.app.util

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 条目 50：mynote://note/{id} 解析。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeepLinksTest {

    @Test
    fun parsesNoteIdFromWellFormedLink() {
        assertEquals(42L, deepLinkNoteId(Uri.parse("mynote://note/42")))
        assertEquals(42L, deepLinkNoteId(Uri.parse("MYNOTE://NOTE/42")))
    }

    @Test
    fun rejectsForeignOrMalformedLinks() {
        assertNull(deepLinkNoteId(null))
        assertNull(deepLinkNoteId(Uri.parse("https://note/42")))
        assertNull(deepLinkNoteId(Uri.parse("mynote://other/42")))
        assertNull(deepLinkNoteId(Uri.parse("mynote://note/abc")))
        assertNull(deepLinkNoteId(Uri.parse("mynote://note/0")))
        assertNull(deepLinkNoteId(Uri.parse("mynote://note/-3")))
    }
}
