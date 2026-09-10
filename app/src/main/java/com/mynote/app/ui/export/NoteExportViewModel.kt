package com.mynote.app.ui.export

import android.content.Intent
import androidx.compose.ui.text.TextMeasurer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mynote.app.data.export.ImageExportManager
import com.mynote.app.data.export.NoteImageRenderer
import com.mynote.app.data.export.PageMode
import com.mynote.app.data.export.RenderedPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class NoteExportViewModel(
    private val renderer: NoteImageRenderer,
    private val exportManager: ImageExportManager,
    private val measurer: TextMeasurer,
    private val note: NoteImageRenderer.NoteData
) : ViewModel() {

    sealed interface State {
        data object Loading : State

        data class Ready(
            val pages: List<RenderedPage>,
            val pageCount: Int,
            val mode: PageMode,
            val longWarning: Boolean
        ) : State

        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting

    private var mode = PageMode.PAGED

    init {
        refreshPreview()
    }

    fun setMode(newMode: PageMode) {
        if (newMode == mode) return
        mode = newMode
        refreshPreview()
    }

    fun retry() {
        refreshPreview()
    }

    /**
     * 全清渲染到分享缓存：逐页写盘并立即回收位图（峰值内存 ≈ 一页），
     * [onReady] 收到按页序排列的缓存文件。失败时 [onError] 给出可展示提示。
     */
    fun renderToCache(onReady: (List<File>) -> Unit, onError: (String) -> Unit) {
        if (_exporting.value) return
        viewModelScope.launch {
            _exporting.value = true
            try {
                exportManager.prepareCache()
                val base = exportManager.baseName(note.title)
                val files = mutableListOf<File>()
                renderer.renderPages(note, mode, NoteImageRenderer.EXPORT_SCALE, measurer) { page, total ->
                    val file = exportManager.cacheFile(base, page.index, total)
                    withContext(Dispatchers.IO) { exportManager.writePageFile(file, page) }
                    page.bitmap.recycle()
                    files += file
                }
                onReady(files)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (mode == PageMode.SINGLE) {
                    mode = PageMode.PAGED
                    refreshPreview()
                    onError("内容太长，已切换为分页模式，请重试")
                } else {
                    onError(t.message ?: "生成图片失败")
                }
            } finally {
                _exporting.value = false
            }
        }
    }

    fun suggestedBaseName(): String = exportManager.baseName(note.title)

    fun suggestedFileName(): String = "${suggestedBaseName()}.png"

    /** 构建分享 Intent（缓存文件的 FileProvider Uri）。 */
    suspend fun shareIntentFor(files: List<File>): Result<Intent> =
        withContext(Dispatchers.IO) {
            runCatching {
                val uris = files.map { exportManager.cachePageUri(it) }
                exportManager.buildShareIntent(uris)
            }
        }

    private fun refreshPreview() {
        viewModelScope.launch {
            _state.value = State.Loading
            try {
                val measurement = renderer.measure(note, NoteImageRenderer.PREVIEW_SCALE, measurer)
                val previewScale = previewScaleFor(mode, measurement)
                val pages = renderer.render(note, mode, previewScale, measurer)
                val exportHeight = measurement.totalHeightPx.toFloat() *
                    NoteImageRenderer.EXPORT_SCALE / NoteImageRenderer.PREVIEW_SCALE
                _state.value = State.Ready(
                    pages = pages,
                    pageCount = if (mode == PageMode.PAGED) pages.size else measurement.pageCount,
                    mode = mode,
                    longWarning = mode == PageMode.SINGLE &&
                        exportHeight >= NoteImageRenderer.SINGLE_WARN_HEIGHT_PX
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (mode == PageMode.SINGLE) {
                    mode = PageMode.PAGED
                    refreshPreview()
                } else {
                    _state.value = State.Error("预览生成失败")
                }
            }
        }
    }

    private fun previewScaleFor(
        mode: PageMode,
        measurement: NoteImageRenderer.Measurement
    ): Float {
        if (mode != PageMode.SINGLE) return NoteImageRenderer.PREVIEW_SCALE
        if (measurement.totalHeightPx <= NoteImageRenderer.PREVIEW_MAX_HEIGHT_PX) {
            return NoteImageRenderer.PREVIEW_SCALE
        }
        return NoteImageRenderer.PREVIEW_SCALE * NoteImageRenderer.PREVIEW_MAX_HEIGHT_PX /
            measurement.totalHeightPx
    }

    companion object {
        fun factory(
            renderer: NoteImageRenderer,
            exportManager: ImageExportManager,
            measurer: TextMeasurer,
            note: NoteImageRenderer.NoteData
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { NoteExportViewModel(renderer, exportManager, measurer, note) }
        }
    }
}
