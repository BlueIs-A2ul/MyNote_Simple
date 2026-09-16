package com.mynote.app.ui.components

/**
 * 操作失败时的统一提示文案（条目 76）。
 *
 * 批量操作与回收站操作原先没有异常分支，DB 失败会沿 viewModelScope 抛出直接崩溃；
 * 现在统一捕获并提示这句话，选中态保留以便用户重试。
 */
internal const val OPERATION_FAILED = "操作失败，请重试"
