package com.mynote.app.ui.ai

import com.mynote.app.data.ai.AiUsage

/** 上次用量单行文案；cacheHitTokens > 0 时附缓存命中片段。 */
internal fun formatUsageLine(usage: AiUsage): String =
    if (usage.cacheHitTokens > 0) {
        "上次用量：输入 ${usage.promptTokens}（缓存命中 ${usage.cacheHitTokens}）/ 输出 ${usage.completionTokens} / 合计 ${usage.totalTokens} tokens"
    } else {
        "上次用量：输入 ${usage.promptTokens} / 输出 ${usage.completionTokens} / 合计 ${usage.totalTokens} tokens"
    }

/** 本会话累计用量单行文案；口径「本次进入会话以来」（内存态，进程结束清零）。 */
internal fun formatSessionUsageLine(usage: AiUsage): String =
    if (usage.cacheHitTokens > 0) {
        "本会话累计：输入 ${usage.promptTokens}（缓存命中 ${usage.cacheHitTokens}）/ 输出 ${usage.completionTokens} / 合计 ${usage.totalTokens} tokens"
    } else {
        "本会话累计：输入 ${usage.promptTokens} / 输出 ${usage.completionTokens} / 合计 ${usage.totalTokens} tokens"
    }
