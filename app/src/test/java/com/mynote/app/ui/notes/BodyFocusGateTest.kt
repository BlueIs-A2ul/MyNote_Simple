package com.mynote.app.ui.notes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览模式焦点门控的纯 JVM 测试。
 *
 * 背景：预览模式下正文 `BasicTextField` 不在组合中，而 Compose 1.7.3 的
 * `FocusRequester.requestFocus()` 在 requester 没有附着节点时会抛 `IllegalStateException`
 * （已对 `ui-android-1.7.3` 的字节码核实），所以「该不该立刻请求焦点」必须先判定。
 */
class BodyFocusGateTest {

    @Test
    fun editModeRequestsFocusImmediately() {
        val gate = BodyFocusGate()
        assertTrue(gate.requestBodyFocus(previewing = false))
    }

    @Test
    fun previewModeDefersFocusRequest() {
        val gate = BodyFocusGate()
        assertFalse(gate.requestBodyFocus(previewing = true))
    }

    @Test
    fun returningFromPreviewPerformsDeferredFocusOnce() {
        val gate = BodyFocusGate()
        gate.requestBodyFocus(previewing = true)

        // 切回编辑态：补一次聚焦
        assertTrue(gate.onPreviewModeChanged(previewing = false))
        // 再切一次（无待处理请求）不应重复抢焦点
        assertFalse(gate.onPreviewModeChanged(previewing = false))
    }

    @Test
    fun enteringPreviewDoesNotFocus() {
        val gate = BodyFocusGate()
        assertFalse(gate.onPreviewModeChanged(previewing = true))
    }

    @Test
    fun immediateFocusClearsPendingFlag() {
        val gate = BodyFocusGate()
        gate.requestBodyFocus(previewing = true)
        assertTrue(gate.requestBodyFocus(previewing = false))

        // 已即时聚焦过，随后切换模式不应再补一次
        assertFalse(gate.onPreviewModeChanged(previewing = false))
    }

    @Test
    fun deferredFlagNotLeftBehindWhenPreviewRequestHappensAgain() {
        val gate = BodyFocusGate()
        gate.requestBodyFocus(previewing = true)
        assertTrue(gate.onPreviewModeChanged(previewing = false))
        gate.requestBodyFocus(previewing = true) // 再次预览中插入图片

        assertFalse(gate.onPreviewModeChanged(previewing = true)) // 仍在预览：不聚焦
        assertTrue(gate.onPreviewModeChanged(previewing = false)) // 回到编辑：补聚焦
    }
}
