package com.mynote.app.ui.history

/** 纯文本行级 diff + 配对行内字符级高亮，零依赖。 */
object NoteDiff {

    enum class Type { UNCHANGED, REMOVED, ADDED }

    data class Line(
        val type: Type,
        val text: String,
        /**
         * 需要加重高亮的字符区间（含首含尾、已对齐码点边界、按顺序且互不重叠）。
         * UNCHANGED 行与"配对行变化中段为空"时可能为空列表。
         */
        val emphasis: List<IntRange> = emptyList()
    )

    private const val MAX_LCS_CELLS = 1_000_000L

    fun diff(oldText: String, newText: String): List<Line> {
        if (oldText == newText) {
            return splitLines(oldText).map { Line(Type.UNCHANGED, it) }
        }
        val oldLines = if (oldText.isEmpty()) emptyList() else splitLines(oldText)
        val newLines = if (newText.isEmpty()) emptyList() else splitLines(newText)

        val prefix = commonPrefix(oldLines, newLines)
        val suffix = commonSuffix(oldLines, newLines, prefix)
        val oldMid = oldLines.subList(prefix, oldLines.size - suffix)
        val newMid = newLines.subList(prefix, newLines.size - suffix)

        val raw = mutableListOf<Line>()
        for (i in 0 until prefix) raw += Line(Type.UNCHANGED, oldLines[i])

        val n = oldMid.size
        val m = newMid.size
        if ((n + 1).toLong() * (m + 1) > MAX_LCS_CELLS) {
            oldMid.forEach { raw += Line(Type.REMOVED, it) }
            newMid.forEach { raw += Line(Type.ADDED, it) }
        } else {
            val dp = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) {
                for (j in m - 1 downTo 0) {
                    dp[i][j] = if (oldMid[i] == newMid[j]) dp[i + 1][j + 1] + 1
                    else maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
            var i = 0
            var j = 0
            while (i < n && j < m) {
                when {
                    oldMid[i] == newMid[j] -> {
                        raw += Line(Type.UNCHANGED, oldMid[i]); i++; j++
                    }
                    dp[i + 1][j] >= dp[i][j + 1] -> {
                        raw += Line(Type.REMOVED, oldMid[i]); i++
                    }
                    else -> {
                        raw += Line(Type.ADDED, newMid[j]); j++
                    }
                }
            }
            while (i < n) { raw += Line(Type.REMOVED, oldMid[i]); i++ }
            while (j < m) { raw += Line(Type.ADDED, newMid[j]); j++ }
        }

        val suffixStart = oldLines.size - suffix
        for (i in suffixStart until oldLines.size) raw += Line(Type.UNCHANGED, oldLines[i])

        return pairAdjacent(raw)
    }

    private fun commonPrefix(oldLines: List<String>, newLines: List<String>): Int {
        var k = 0
        val max = minOf(oldLines.size, newLines.size)
        while (k < max && oldLines[k] == newLines[k]) k++
        return k
    }

    private fun commonSuffix(oldLines: List<String>, newLines: List<String>, prefix: Int): Int {
        var k = 0
        val max = minOf(oldLines.size, newLines.size) - prefix
        while (k < max && oldLines[oldLines.size - 1 - k] == newLines[newLines.size - 1 - k]) k++
        return k
    }

    private fun splitLines(text: String): List<String> =
        text.split('\n').map { it.removeSuffix("\r") }

    /** 相邻的「删除段 + 新增段」按下标配对，配对行做字符级前后缀高亮；只有未配对行才整行强调。 */
    private fun pairAdjacent(raw: List<Line>): List<Line> {
        val result = raw.toMutableList()
        val paired = BooleanArray(result.size)
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
                        paired[i + k] = true
                        paired[removedEnd + k] = true
                    }
                    i = addedEnd
                    continue
                }
            }
            i++
        }
        return result.mapIndexed { index, line ->
            if (line.type != Type.UNCHANGED && !paired[index] && line.text.isNotEmpty()) {
                line.copy(emphasis = listOf(0 until line.text.length))
            } else {
                line
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
        val oldRanges = if (prefix < oldLine.length - suffix) {
            listOf(snapToCodePoint(oldLine, prefix until oldLine.length - suffix))
        } else {
            emptyList()
        }
        val newRanges = if (prefix < newLine.length - suffix) {
            listOf(snapToCodePoint(newLine, prefix until newLine.length - suffix))
        } else {
            emptyList()
        }
        return oldRanges to newRanges
    }

    /** 把区间向外扩到码点边界，避免高亮落在代理对中间。 */
    private fun snapToCodePoint(text: String, range: IntRange): IntRange {
        var start = range.first
        var end = range.last
        while (start > 0 && text[start].isLowSurrogate()) start--
        while (end < text.length - 1 && text[end].isHighSurrogate()) end++
        return start..end
    }
}
