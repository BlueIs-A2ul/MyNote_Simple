package com.mynote.app.util

import android.net.Uri

/**
 * 条目 50：解析 mynote://note/{id} 深链。
 * 只接受完全匹配的 scheme/host，其余（含空、非法 id）返回 null，避免外部 App 用畸形链接把应用打开到空页。
 */
fun deepLinkNoteId(uri: Uri?): Long? {
    if (uri == null) return null
    if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
    if (!uri.host.equals(HOST, ignoreCase = true)) return null
    val id = uri.lastPathSegment?.toLongOrNull() ?: return null
    return id.takeIf { it > 0L }
}

private const val SCHEME = "mynote"
private const val HOST = "note"
