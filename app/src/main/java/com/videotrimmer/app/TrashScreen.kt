package com.videotrimmer.app

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

data class TrashedVideo(
    val uri: Uri,
    val name: String,
    val folder: String,
    val size: Long,
    val expiresAtSec: Long,
)

private val trashedPrefix = Regex("^\\.trashed-\\d+-")

/** 시스템 휴지통에 들어간 영상 목록 (Android 11 이상) */
fun queryTrashedVideos(context: Context): List<TrashedVideo> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
    val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val args = Bundle().apply {
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        putString(
            ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
            "${MediaStore.MediaColumns.DATE_EXPIRES} DESC"
        )
    }
    val list = mutableListOf<TrashedVideo>()
    try {
        context.contentResolver.query(
            collection,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_EXPIRES,
            ),
            args, null,
        )?.use { c ->
            while (c.moveToNext()) {
                list.add(
                    TrashedVideo(
                        uri = ContentUris.withAppendedId(collection, c.getLong(0)),
                        name = (c.getString(1) ?: "video").replace(trashedPrefix, ""),
                        folder = c.getString(2) ?: "",
                        size = c.getLong(3),
                        expiresAtSec = c.getLong(4),
                    )
                )
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return list
}

@Composable
fun TrashScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var items by remember { mutableStateOf(listOf<TrashedVideo>()) }
    var loaded by remember { mutableStateOf(false) }

    fun reload() {
        items = queryTrashedVideos(context)
        loaded = true
    }

    val readPermission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, readPermission) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        reload()
    }

    val actionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            Toast.makeText(context, "완료했어요", Toast.LENGTH_SHORT).show()
        }
        reload()
    }

    fun restore(uris: List<Uri>) {
        if (uris.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val pi = MediaStore.createTrashRequest(context.contentResolver, uris, false)
        actionLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }

    fun deleteForever(uris: List<Uri>) {
        if (uris.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val pi = MediaStore.createDeleteRequest(context.contentResolver, uris)
        actionLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(readPermission) else reload()
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            "이 앱에서 삭제한 영상은 여기에 30일 동안 보관돼요.\n" +
                "(삼성 갤러리·내 파일 휴지통과는 별개예요)",
            color = Color(0xFF8888aa), fontSize = 12.sp,
        )
        Spacer(Modifier.height(10.dp))

        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ->
                Text("Android 11 이상에서만 휴지통을 쓸 수 있어요.", color = Color(0xFFe8e8e8))
            !hasPermission -> {
                Text("휴지통을 보려면 동영상 접근 권한이 필요해요.", color = Color(0xFFe8e8e8))
                Spacer(Modifier.height(8.dp))
                Button(onClick = { permissionLauncher.launch(readPermission) }) { Text("권한 허용") }
            }
            loaded && items.isEmpty() ->
                Text("휴지통이 비어 있어요.", color = Color(0xFFe8e8e8))
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { restore(items.map { it.uri }) },
                        modifier = Modifier.weight(1f),
                    ) { Text("전체 복원") }
                    OutlinedButton(
                        onClick = { deleteForever(items.map { it.uri }) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFe94560)),
                    ) { Text("전체 영구 삭제") }
                }
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    items(items, key = { it.uri.toString() }) { v ->
                        val daysLeft = ((v.expiresAtSec - System.currentTimeMillis() / 1000) / 86400)
                            .coerceAtLeast(0)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF232340))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(v.name, color = Color(0xFFe8e8e8), fontSize = 13.sp,
                                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${v.folder} · ${formatFileSize(v.size)} · ${daysLeft}일 후 자동 삭제",
                                     color = Color(0xFF8888aa), fontSize = 11.sp,
                                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = { restore(listOf(v.uri)) }) {
                                Text("복원", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
