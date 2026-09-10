package com.mynote.app.ui.export

import android.content.Intent
import android.util.Log
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
import kotlinx.coroutines.Job
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
    private var previewJob: Job? = null

    init {
        refreshPreview()
    }

    fun setMode(newMode: PageMode) {
        if (_exporting.value || newMode == mode) return
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
        if (!_exporting.compareAndSet(false, true)) return
        val exportMode = mode
        viewModelScope.launch {
            var ready: List<File>? = null
            try {
                withContext(Dispatchers.IO) { exportManager.prepareCache() }
                val base = exportManager.baseName(note.title)
                val files = mutableListOf<File>()
                renderer.renderPages(note, exportMode, NoteImageRenderer.EXPORT_SCALE, measurer) { page, total ->
                    val file = exportManager.cacheFile(base, page.index, total)
                    try {
                        withContext(Dispatchers.IO) { exportManager.writePageFile(file, page) }
                    } finally {
                        page.bitmap.recycle()
                    }
                    files += file
                }
                ready = files
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "导出图片失败", t)
                if (exportMode == PageMode.SINGLE && (t is OutOfMemoryError || t is IllegalArgumentException)) {
                    mode = PageMode.PAGED
                    refreshPreview()
                    onError("内容太长，已切换为分页模式，请重试")
                } else {
                    onError("生成图片失败")
                }
            } finally {
                _exporting.value = false
            }
            ready?.let { files ->
                runCatching { onReady(files) }
                    .onFailure { Log.w(TAG, "导出完成回调异常", it) }
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
        previewJob?.cancel()
        val targetMode = mode
        previewJob = viewModelScope.launch {
            _state.value = State.Loading
            try {
                val measurement = if (targetMode == PageMode.SINGLE) {
                    renderer.measure(note, NoteImageRenderer.PREVIEW_SCALE, measurer)
                } else {
                    null
                }
                val previewScale = if (targetMode == PageMode.SINGLE && measurement != null) {
                    previewScaleFor(measurement)
                } else {
                    NoteImageRenderer.PREVIEW_SCALE
                }
                val pages = renderer.render(note, targetMode, previewScale, measurer)
                val exportHeight = measurement?.let {
                    it.totalHeightPx.toFloat() * NoteImageRenderer.EXPORT_SCALE / NoteImageRenderer.PREVIEW_SCALE
                } ?: 0f
                _state.value = State.Ready(
                    pages = pages,
                    pageCount = if (targetMode == PageMode.PAGED) pages.size else measurement!!.pageCount,
                    mode = targetMode,
                    longWarning = targetMode == PageMode.SINGLE &&
                        exportHeight >= NoteImageRenderer.SINGLE_WARN_HEIGHT_PX
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "预览生成失败", t)
                if (targetMode == PageMode.SINGLE) {
                    mode = PageMode.PAGED
                    refreshPreview()
                } else {
                    _state.value = State.Error("预览生成失败")
                }
            }
        }
    }

    private fun previewScaleFor(measurement: NoteImageRenderer.Measurement): Float {
        if (measurement.totalHeightPx <= NoteImageRenderer.PREVIEW_MAX_HEIGHT_PX) {
            return NoteImageRenderer.PREVIEW_SCALE
        }
        return NoteImageRenderer.PREVIEW_SCALE * NoteImageRenderer.PREVIEW_MAX_HEIGHT_PX /
            measurement.totalHeightPx
    }

    companion object {
        private const val TAG = "NoteExport"

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
