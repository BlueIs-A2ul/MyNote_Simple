package com.mynote.app.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 在 [viewModelScope] 中启动写操作：取消正常传播，其余异常交给 [onError]。
 *
 * ON_STOP 草稿、删除分类这类“用户未必还停留在本页”的写操作，失败时既不能把异常
 * 抛到主线程导致进程崩溃，也不该静默假装成功；统一走这里，保证 UI 状态可恢复。
 */
internal fun ViewModel.launchSafely(
    onError: (Throwable) -> Unit = {},
    block: suspend CoroutineScope.() -> Unit
): Job = viewModelScope.launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        onError(t)
    }
}
