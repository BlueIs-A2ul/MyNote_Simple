package com.mynote.app.ui.export

import android.content.Context
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.mynote.app.data.export.ImageExportManager
import com.mynote.app.data.export.NoteImageRenderer
import com.mynote.app.data.export.PageMode
import com.mynote.app.data.image.ImageStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteExportViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var vm: NoteExportViewModel
    private lateinit var measurer: TextMeasurer

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        measurer = TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(context),
            defaultDensity = Density(1f, 1f),
            defaultLayoutDirection = LayoutDirection.Ltr,
            cacheSize = 0
        )
        vm = NoteExportViewModel(
            renderer = NoteImageRenderer(ImageStore(context)),
            exportManager = ImageExportManager(context),
            measurer = measurer,
            note = NoteImageRenderer.NoteData("标题", "正文内容", "2026-09-10")
        )
    }

    @After
    fun teardown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun initLoadsPagedPreview() = runTest(dispatcher) {
        val state = vm.state.filterIsInstance<NoteExportViewModel.State.Ready>().first()
        assertEquals(PageMode.PAGED, state.mode)
        assertTrue(state.pageCount >= 1)
        assertTrue(state.pages.isNotEmpty())
        state.pages.forEach { page -> assertEquals(360, page.bitmap.width) }
    }

    @Test
    fun setModeSingleRendersSinglePage() = runTest(dispatcher) {
        vm.state.filterIsInstance<NoteExportViewModel.State.Ready>().first()
        vm.setMode(PageMode.SINGLE)
        val state = vm.state.filterIsInstance<NoteExportViewModel.State.Ready>()
            .first { it.mode == PageMode.SINGLE && it.pages.size == 1 }
        assertEquals(PageMode.SINGLE, state.mode)
        assertTrue(state.pageCount >= 1)
    }

    @Test
    fun renderToCacheWritesPngFiles() = runTest(dispatcher) {
        vm.state.filterIsInstance<NoteExportViewModel.State.Ready>().first()
        val result = CompletableDeferred<List<File>>()
        vm.renderToCache(
            onReady = { result.complete(it) },
            onError = { result.completeExceptionally(AssertionError(it)) }
        )
        val files = result.await()
        assertTrue(files.isNotEmpty())
        assertFalse(vm.exporting.value)
        files.forEach { file ->
            assertTrue(file.exists())
            val header = file.readBytes().take(4).map { it.toInt() and 0xFF }
            assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), header)
        }
    }

    @Test
    fun longSingleModeShowsWarning() = runTest(dispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val longVm = NoteExportViewModel(
            renderer = NoteImageRenderer(ImageStore(context)),
            exportManager = ImageExportManager(context),
            measurer = measurer,
            note = NoteImageRenderer.NoteData("标题", "长文本。".repeat(1000), "2026-09-10")
        )
        longVm.setMode(PageMode.SINGLE)
        val state = longVm.state.filterIsInstance<NoteExportViewModel.State.Ready>()
            .first { it.mode == PageMode.SINGLE }
        assertTrue(state.longWarning)
        longVm.viewModelScope.cancel()
    }

    @Test
    fun longPagedPreviewShrinksScale() = runTest(dispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val longVm = NoteExportViewModel(
            renderer = NoteImageRenderer(ImageStore(context)),
            exportManager = ImageExportManager(context),
            measurer = measurer,
            note = NoteImageRenderer.NoteData("标题", "长文本。".repeat(7000), "2026-09-10")
        )
        val state = longVm.state.filterIsInstance<NoteExportViewModel.State.Ready>().first()
        assertTrue(state.pageCount > 16)
        assertTrue(state.pages.first().bitmap.width < 360)
        longVm.viewModelScope.cancel()
    }

    @Test
    fun readyStateCarriesUnreadableImageCount() = runTest(dispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ghostVm = NoteExportViewModel(
            renderer = NoteImageRenderer(ImageStore(context)),
            exportManager = ImageExportManager(context),
            measurer = measurer,
            note = NoteImageRenderer.NoteData("标题", "![](img/ghost.webp) 缺失图", "2026-09-10")
        )
        val state = ghostVm.state.filterIsInstance<NoteExportViewModel.State.Ready>().first()
        assertEquals(1, state.unreadableImageCount)
        ghostVm.viewModelScope.cancel()
    }

    @Test
    fun unreadableImagesHintIsNullWhenZero() {
        assertNull(unreadableImagesHint(0))
    }

    @Test
    fun unreadableImagesHintMentionsCountWhenMissing() {
        assertEquals("有 3 张图片无法读取，导出结果不含它们", unreadableImagesHint(3))
    }
}
