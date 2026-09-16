package com.mynote.app.ui.notes

/**
 * 正文焦点门控：编辑页在预览模式下不组合正文 `BasicTextField`，
 * 而 Compose 1.7.3 的 `FocusRequester.requestFocus()` 在没有附着节点时会抛
 * `IllegalStateException`（"FocusRequester is not initialized"）。
 *
 * 因此「请求正文焦点」必须先经过这里判定：预览中只登记待处理请求，等回到编辑态再补一次聚焦，
 * 既避免崩溃，又保留「插图 / 贴入 AI 结果后光标就位」的体验。
 * 纯 Kotlin、无 Android 依赖，便于单元测试。
 */
class BodyFocusGate {

    private var pending = false

    /**
     * 请求正文焦点。
     * @return true 表示调用方可以立即 `requestFocus()`；false 表示当前无法聚焦（预览中），已记为待处理。
     */
    fun requestBodyFocus(previewing: Boolean): Boolean {
        if (previewing) {
            pending = true
            return false
        }
        pending = false
        return true
    }

    /**
     * 预览开关变化时调用。
     * @return true 表示由预览切回编辑且有未处理的聚焦请求，调用方应补一次 `requestFocus()`。
     */
    fun onPreviewModeChanged(previewing: Boolean): Boolean {
        if (previewing || !pending) return false
        pending = false
        return true
    }
}
