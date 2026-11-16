package com.videotrimmer.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore

/** 이름 바꾸기 계획: 원본 → 새 이름 */
data class RenameItem(
    val file: VideoFile,
    val mediaUri: Uri?,
    val newName: String,
)

/**
 * 고른 영상의 URI를 갤러리(MediaStore) URI로 바꾼다.
 * 이름 바꾸기·휴지통 이동은 MediaStore URI로만 할 수 있어서 필요하다. 못 찾으면 null.
 */
fun resolveMediaUri(context: Context, uri: Uri): Uri? {
    try {
        if (uri.authority == MediaStore.AUTHORITY) return uri

        if (DocumentsContract.isDocumentUri(context, uri)) {
            when (uri.authority) {
                "com.android.providers.media.documents" ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        return MediaStore.getMediaUri(context, uri)
                    }
                "com.android.externalstorage.documents" -> {
                    // "primary:DCIM/Camera/a.mp4" → 폴더와 이름으로 갤러리에서 찾기
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
                    val docId = DocumentsContract.getDocumentId(uri)
                    if (!docId.startsWith("primary:")) return null
                    val path = docId.removePrefix("primary:")
                    val dir = if (path.contains('/')) path.substringBeforeLast('/') + "/" else ""
                    val name = path.substringAfterLast('/')
                    val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    context.contentResolver.query(
                        collection,
                        arrayOf(MediaStore.MediaColumns._ID),
                        "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                        arrayOf(dir, name), null,
                    )?.use { c ->
                        if (c.moveToFirst()) return ContentUris.withAppendedId(collection, c.getLong(0))
                    }
                    return null
                }
            }
        }

        // Google 포토 등: URI 안에 갤러리 주소가 들어 있는 경우
        return Regex("content://media/[^/]+/video/media/\\d+")
            .find(Uri.decode(uri.toString()))?.value?.let { Uri.parse(it) }
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    }
}

/** 실제로 이름을 바꾼다. (Android 11 이상은 미리 쓰기 허락을 받은 뒤 호출) */
fun renameAll(context: Context, plan: List<RenameItem>): List<ProcessResult> =
    plan.map { item ->
        val ok = try {
            when {
                item.mediaUri != null -> {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, item.newName)
                    }
                    context.contentResolver.update(item.mediaUri, values, null, null) > 0
                }
                DocumentsContract.isDocumentUri(context, item.file.uri) ->
                    DocumentsContract.renameDocument(
                        context.contentResolver, item.file.uri, item.newName
                    ) != null
                else -> false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
        ProcessResult(
            item.newName, ok,
            if (ok) "이름 변경: ${item.file.name} → ${item.newName}"
            else "이름을 바꿀 수 없어요 (같은 이름이 있거나 권한 없음): ${item.file.name}",
        )
    }
