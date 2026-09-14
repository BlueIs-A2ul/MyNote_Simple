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
import kotlin.math.sqrt

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
            val longWarning: Boolean,
            /** 无法读取（文件缺失或损坏）的图片张数，这些图不会出现在导出成品中。 */
            val unreadableImageCount: Int = 0
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
                val measurement = renderer.measure(note, NoteImageRenderer.PREVIEW_SCALE, measurer)
                val previewScale = previewScaleFor(targetMode, measurement)
                val pages = renderer.render(note, targetMode, previewScale, measurer)
                val exportHeight = measurement.totalHeightPx.toFloat() *
                    NoteImageRenderer.EXPORT_SCALE / NoteImageRenderer.PREVIEW_SCALE
                _state.value = State.Ready(
                    pages = pages,
                    pageCount = measurement.pageCount,
                    mode = targetMode,
                    longWarning = targetMode == PageMode.SINGLE &&
                        exportHeight >= NoteImageRenderer.SINGLE_WARN_HEIGHT_PX,
                    unreadableImageCount = measurement.unreadableImages.size
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

    private fun previewScaleFor(mode: PageMode, measurement: NoteImageRenderer.Measurement): Float =
        when (mode) {
            PageMode.SINGLE -> {
                if (measurement.totalHeightPx <= NoteImageRenderer.PREVIEW_MAX_HEIGHT_PX) {
                    NoteImageRenderer.PREVIEW_SCALE
                } else {
                    NoteImageRenderer.PREVIEW_SCALE * NoteImageRenderer.PREVIEW_MAX_HEIGHT_PX /
                        measurement.totalHeightPx
                }
            }

            PageMode.PAGED -> {
                if (measurement.pageCount <= NoteImageRenderer.PREVIEW_MAX_PAGES) {
                    NoteImageRenderer.PREVIEW_SCALE
                } else {
                    NoteImageRenderer.PREVIEW_SCALE *
                        sqrt(NoteImageRenderer.PREVIEW_MAX_PAGES.toFloat() / measurement.pageCount)
                }
            }
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

/**
 * Ready 界面的缺图提示文案：有 N 张图片无法读取，导出结果不含它们。
 * N=0 时返回 null，界面不显示该行。
 */
internal fun unreadableImagesHint(count: Int): String? =
    if (count > 0) "有 $count 张图片无法读取，导出结果不含它们" else null
