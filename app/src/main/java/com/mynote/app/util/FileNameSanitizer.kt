package com.mynote.app.util

/**
 * 把任意标题清洗成合法的导出文件名（Android SAF 场景）。
 * 规则：将 \ / : * ? " < > | 及控制字符替换为 "_"；去除首尾空白与 "."；
 * 超过 [maxLength] 字符截断；清洗后为空则回退 [fallback]。
 *
 * txt 导出与图片导出共享本实现（图片导出传 `maxLength = 40`、`fallback = "笔记"`），
 * 两侧差异只体现在参数上，不再各写一套正则。
 */
object FileNameSanitizer {

    /** 导出文件名默认最大长度，超过则截断。 */
    private const val MAX_LENGTH = 80

    /** SAF 场景下文件名不允许出现的字符。 */
    private val ILLEGAL_CHARS = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    /**
     * 把任意标题清洗成合法的导出文件名。
     *
     * @param raw 原始标题。
     * @param fallback 清洗结果为空时使用的回退文件名。
     * @param maxLength 文件名最大长度，超出按该长度截断（图片导出用 40）。
     * @return 清洗后的文件名。
     */
    fun sanitize(raw: String, fallback: String = "note", maxLength: Int = MAX_LENGTH): String {
        // 逐个字符映射：非法字符与控制字符替换为下划线，其余保留。
        val replaced = buildString(raw.length) {
            for (c in raw) {
                append(if (c in ILLEGAL_CHARS || c.code < 32) '_' else c)
            }
        }
        // 先按上限截断，再去掉首尾空白与首尾连续的 "."：截断点落在空白处不会留下尾随空格。
        val result = replaced.take(maxLength.coerceAtLeast(0)).trim().trim('.')
        // 为空串（含纯点）则回退到 fallback。
        return if (result.isEmpty()) fallback else result
    }
}
