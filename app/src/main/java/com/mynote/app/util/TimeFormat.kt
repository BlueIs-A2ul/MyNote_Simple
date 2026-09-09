package com.mynote.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeFormat {
    private fun formatter(pattern: String) = SimpleDateFormat(pattern, Locale.getDefault())

    fun dateTime(timestamp: Long): String = formatter("yyyy-MM-dd HH:mm").format(Date(timestamp))

    fun date(timestamp: Long): String = formatter("yyyy-MM-dd").format(Date(timestamp))
}
