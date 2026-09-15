package com.mynote.app.data.db

/**
 * 日历标记：某天（本地零点毫秒）的笔记数量，用于月网格上的小圆点。
 * 只统计已标记日期且未软删除的笔记。
 */
data class DateMark(
    val noteDate: Long,
    val count: Int
)
