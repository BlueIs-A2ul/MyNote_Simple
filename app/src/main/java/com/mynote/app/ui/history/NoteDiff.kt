package com.mynote.app.ui.history

/** 纯文本行级 diff + 配对行内字符级高亮，零依赖。 */
object NoteDiff {

    enum class Type { UNCHANGED, REMOVED, ADDED }

    data class Line(
        val type: Type,
        val text: String,
        val emphasis: List<IntRange> = emptyList()
    )

    private const val MAX_LCS_CELLS = 1_000_000L

    fun diff(oldText: String, newText: String): List<Line> {
        if (oldText == newText) {
            return splitLines(oldText).map { Line(Type.UNCHANGED, it) }
        }
        val oldLines = if (oldText.isEmpty()) emptyList() else splitLines(oldText)
        val newLines = if (newText.isEmpty()) emptyList() else splitLines(newText)

        val raw = mutableListOf<Line>()
        val n = oldLines.size
        val m = newLines.size
        if (n.toLong() * m > MAX_LCS_CELLS) {
            oldLines.forEach { raw += Line(Type.REMOVED, it) }
            newLines.forEach { raw += Line(Type.ADDED, it) }
        } else {
            val dp = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) {
                for (j in m - 1 downTo 0) {
                    dp[i][j] = if (oldLines[i] == newLines[j]) dp[i + 1][j + 1] + 1
                    else maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
            var i = 0
            var j = 0
            while (i < n && j < m) {
                when {
                    oldLines[i] == newLines[j] -> {
                        raw += Line(Type.UNCHANGED, oldLines[i]); i++; j++
                    }
                    dp[i + 1][j] >= dp[i][j + 1] -> {
                        raw += Line(Type.REMOVED, oldLines[i]); i++
                    }
                    else -> {
                        raw += Line(Type.ADDED, newLines[j]); j++
                    }
                }
            }
            while (i < n) { raw += Line(Type.REMOVED, oldLines[i]); i++ }
            while (j < m) { raw += Line(Type.ADDED, newLines[j]); j++ }
        }
        return pairAdjacent(raw)
    }

    private fun splitLines(text: String): List<String> =
        text.split('\n').map { it.removeSuffix("\r") }

    /** 相邻的「删除段 + 新增段」按下标配对，配对行做字符级前后缀高亮。 */
    private fun pairAdjacent(raw: List<Line>): List<Line> {
        val result = raw.toMutableList()
        var i = 0
        while (i < result.size) {
            if (result[i].type == Type.REMOVED) {
                var removedEnd = i
                while (removedEnd < result.size && result[removedEnd].type == Type.REMOVED) removedEnd++
                if (removedEnd < result.size && result[removedEnd].type == Type.ADDED) {
                    var addedEnd = removedEnd
                    while (addedEnd < result.size && result[addedEnd].type == Type.ADDED) addedEnd++
                    val pairs = minOf(removedEnd - i, addedEnd - removedEnd)
                    for (k in 0 until pairs) {
                        val (oldEmphasis, newEmphasis) = emphasize(result[i + k].text, result[removedEnd + k].text)
                        result[i + k] = result[i + k].copy(emphasis = oldEmphasis)
                        result[removedEnd + k] = result[removedEnd + k].copy(emphasis = newEmphasis)
                    }
                    i = addedEnd
                    continue
                }
            }
            i++
        }
        return result.map {
            if (it.type != Type.UNCHANGED && it.emphasis.isEmpty() && it.text.isNotEmpty()) {
                it.copy(emphasis = listOf(0 until it.text.length))
            } else {
                it
            }
        }
    }

    private fun emphasize(oldLine: String, newLine: String): Pair<List<IntRange>, List<IntRange>> {
        var prefix = 0
        val maxPrefix = minOf(oldLine.length, newLine.length)
        while (prefix < maxPrefix && oldLine[prefix] == newLine[prefix]) prefix++
        var suffix = 0
        while (suffix < maxPrefix - prefix &&
            oldLine[oldLine.length - 1 - suffix] == newLine[newLine.length - 1 - suffix]
        ) {
            suffix++
        }
        val oldRanges = if (prefix < oldLine.length - suffix) listOf(prefix until oldLine.length - suffix) else emptyList()
        val newRanges = if (prefix < newLine.length - suffix) listOf(prefix until newLine.length - suffix) else emptyList()
        return oldRanges to newRanges
    }
}
