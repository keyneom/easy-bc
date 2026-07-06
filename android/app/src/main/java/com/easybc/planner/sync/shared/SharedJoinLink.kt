package com.easybc.planner.sync.shared

import android.net.Uri

data class SharedJoinLink(
    val invitationFileId: String,
    val ownerFolderId: String,
    val ownerEmail: String,
)

/**
 * Parses an EasyBC join link. sync-kit emits `sync-kit-folder` (plus
 * `sync-kit-join`/`sync-kit-exchange` markers) and the app appends
 * `owner` and `invitation`; older links used bare `folder`.
 */
fun parseSharedJoinLink(uri: Uri): SharedJoinLink? {
    val invitation = uri.getQueryParameter("invitation")?.takeIf { it.isNotBlank() }
        ?: return null
    val folder = (uri.getQueryParameter("sync-kit-folder") ?: uri.getQueryParameter("folder"))
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val owner = uri.getQueryParameter("owner")?.takeIf { it.isNotBlank() }
        ?: return null
    return SharedJoinLink(invitation, folder, owner)
}

fun parseSharedJoinLink(raw: String): SharedJoinLink? =
    runCatching { Uri.parse(raw.trim()) }.getOrNull()?.let(::parseSharedJoinLink)
