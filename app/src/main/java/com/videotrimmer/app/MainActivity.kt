package com.videotrimmer.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// ── Colors ──────────────────────────────────────────────────

private val BgDark = Color(0xFF1a1a2e)
private val Surface = Color(0xFF232340)
private val Accent = Color(0xFFe94560)
private val TextPrimary = Color(0xFFe8e8e8)
private val TextDim = Color(0xFF8888aa)
private val InputBg = Color(0xFF151525)

private val AppColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    surface = Surface,
    background = BgDark,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    surfaceVariant = Surface,
    outline = Color(0xFF444466),
)

// ── Data ────────────────────────────────────────────────────

data class VideoFile(
    val uri: Uri,
    val name: String,
    val size: Long,
)

data class ProcessResult(
    val fileName: String,
    val success: Boolean,
    val message: String,
)

// ── Activity ────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = AppColorScheme) {
                VideoTrimmerScreen()
            }
        }
    }
}

// ── Main Screen ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoTrimmerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // State
    var files by remember { mutableStateOf(listOf<VideoFile>()) }
    var trimSeconds by remember { mutableStateOf("3") }
    var titlePrefix by remember { mutableStateOf("제목") }
    var crfValue by remember { mutableStateOf(20f) }
    var processing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(listOf<ProcessResult>()) }
    var showResults by remember { mutableStateOf(false) }

    // File picker
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            files = uris.map { uri ->
                val (name, size) = getFileInfo(context, uri)
                VideoFile(uri, name, size)
            }
            showResults = false
            results = emptyList()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text("🎬 영상 일괄 편집기",
                         fontWeight = FontWeight.Bold, fontSize = 18.sp)
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = BgDark,
                    titleContentColor = Accent,
                )
            )
        },
        containerColor = BgDark,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            // ── Subtitle ──
            item {
                Text(
                    "앞부분 자르기 · HEVC 인코딩 · 자동 번호 매기기",
                    color = TextDim, fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            // ── File Selection Card ──
            item {
                AppCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("📁 영상 파일", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        FilledTonalButton(
                            onClick = { filePicker.launch(arrayOf("video/*")) },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color(0xFF0f3460),
                                contentColor = Color.White,
                            )
                        ) {
                            Icon(Icons.Default.VideoLibrary, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("파일 선택")
                        }
                    }

                    if (files.isEmpty()) {
                        Text("영상 파일을 선택해주세요",
                             color = TextDim, fontSize = 13.sp,
                             modifier = Modifier.padding(top = 12.dp))
                    } else {
                        Spacer(Modifier.height(10.dp))
                        files.forEachIndexed { i, vf ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(InputBg)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("${i + 1}",
                                     color = Accent, fontWeight = FontWeight.Bold,
                                     fontSize = 13.sp, modifier = Modifier.width(24.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(vf.name, color = TextPrimary, fontSize = 13.sp,
                                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(formatFileSize(vf.size),
                                         color = TextDim, fontSize = 11.sp)
                                }
                            }
                        }
                        Text("${files.size}개 파일",
                             color = TextDim, fontSize = 12.sp,
                             modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }

            // ── Settings Card ──
            item {
                AppCard {
                    Text("⚙️ 설정", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(14.dp))

                    // Trim seconds
                    OutlinedTextField(
                        value = trimSeconds,
                        onValueChange = { trimSeconds = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("앞부분 자르기 (초)") },
                        leadingIcon = { Icon(Icons.Default.ContentCut, null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = inputColors(),
                    )

                    Spacer(Modifier.height(10.dp))

                    // Title prefix
                    OutlinedTextField(
                        value = titlePrefix,
                        onValueChange = { titlePrefix = it },
                        label = { Text("출력 파일 제목") },
                        leadingIcon = { Icon(Icons.Default.Title, null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = inputColors(),
                    )

                    Spacer(Modifier.height(16.dp))

                    // CRF slider
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("화질 CRF", color = TextPrimary, fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${crfValue.toInt()}",
                             color = Accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Slider(
                        value = crfValue,
                        onValueChange = { crfValue = it },
                        valueRange = 0f..51f,
                        steps = 50,
                        colors = SliderDefaults.colors(
                            thumbColor = Accent,
                            activeTrackColor = Accent,
                            inactiveTrackColor = InputBg,
                        )
                    )
                    Text("💡 0 = 무손실  ·  18~23 = 고화질 권장  ·  28 = 보통",
                         color = TextDim, fontSize = 11.sp)

                    // Preview
                    if (files.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Divider(color = Color(0xFF333355))
                        Spacer(Modifier.height(10.dp))
                        Text("👁 출력 미리보기", color = TextDim, fontSize = 12.sp,
                             fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        val t = titlePrefix.ifBlank { "제목" }
                        files.take(4).forEachIndexed { i, vf ->
                            val ext = vf.name.substringAfterLast('.', "mp4")
                            Text("${vf.name}  →  ${t}_${i + 1}.$ext",
                                 color = Color(0xFF66ddaa), fontSize = 12.sp)
                        }
                        if (files.size > 4) {
                            Text("  … 외 ${files.size - 4}개", color = TextDim, fontSize = 12.sp)
                        }
                    }
                }
            }

            // ── Process Button ──
            item {
                Button(
                    onClick = {
                        scope.launch {
                            processing = true
                            progress = 0f
                            showResults = false
                            results = emptyList()

                            val res = processVideos(
                                context = context,
                                files = files,
                                trimSec = trimSeconds.toFloatOrNull() ?: 0f,
                                title = titlePrefix.ifBlank { "제목" },
                                crf = crfValue.toInt(),
                                onProgress = { p, s ->
                                    progress = p
                                    statusText = s
                                }
                            )

                            results = res
                            showResults = true
                            processing = false

                            val ok = res.count { it.success }
                            Toast.makeText(context,
                                "✅ ${ok}/${res.size} 파일 처리 완료!", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = files.isNotEmpty() && !processing,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        disabledContainerColor = Color(0xFF553344),
                    ),
                ) {
                    if (processing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color.White, strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("처리 중...", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.RocketLaunch, null)
                        Spacer(Modifier.width(8.dp))
                        Text("처리 시작", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // ── Progress ──
            if (processing) {
                item {
                    AppCard {
                        Text("⏳ 진행 상황", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = Accent,
                            trackColor = InputBg,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(statusText, color = TextDim, fontSize = 13.sp)
                        Text("${(progress * 100).toInt()}%",
                             color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // ── Results ──
            if (showResults && results.isNotEmpty()) {
                item {
                    val ok = results.count { it.success }
                    val fail = results.size - ok
                    AppCard {
                        Text("✅ 처리 결과", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("성공: ${ok}개  ·  실패: ${fail}개",
                             color = if (fail > 0) Color(0xFFff6b6b) else Color(0xFF4ecdc4),
                             fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))

                        results.forEach { r ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(InputBg)
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (r.success) Icons.Default.CheckCircle
                                    else Icons.Default.Error,
                                    contentDescription = null,
                                    tint = if (r.success) Color(0xFF4ecdc4) else Color(0xFFff6b6b),
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(r.fileName, color = TextPrimary, fontSize = 13.sp,
                                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (!r.success) {
                                        Text(r.message, color = Color(0xFFff6b6b), fontSize = 11.sp,
                                             maxLines = 2)
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Text("📂 저장 위치: Movies/VideoTrimmer/",
                             color = Color(0xFF66ddaa), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ── Components ──────────────────────────────────────────────

@Composable
fun AppCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun inputColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent,
    unfocusedBorderColor = Color(0xFF444466),
    cursorColor = Accent,
    focusedLabelColor = Accent,
    unfocusedLabelColor = TextDim,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedContainerColor = InputBg,
    unfocusedContainerColor = InputBg,
    focusedLeadingIconColor = Accent,
    unfocusedLeadingIconColor = TextDim,
)

// ── Video Processing ────────────────────────────────────────

suspend fun processVideos(
    context: Context,
    files: List<VideoFile>,
    trimSec: Float,
    title: String,
    crf: Int,
    onProgress: (Float, String) -> Unit,
): List<ProcessResult> = withContext(Dispatchers.IO) {
    val results = mutableListOf<ProcessResult>()
    val cacheDir = File(context.cacheDir, "video_input").apply { mkdirs() }
    val outputDir = File(context.cacheDir, "video_output").apply { mkdirs() }

    files.forEachIndexed { index, vf ->
        val num = index + 1
        val ext = vf.name.substringAfterLast('.', "mp4")
        val outputName = "${title}_${num}.$ext"

        withContext(Dispatchers.Main) {
            onProgress(index.toFloat() / files.size, "처리 중: ${vf.name} ($num/${files.size})")
        }

        // 1. Content URI → temp file
        val inputFile = File(cacheDir, "input_$num.$ext")
        try {
            context.contentResolver.openInputStream(vf.uri)?.use { input ->
                FileOutputStream(inputFile).use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                results.add(ProcessResult(outputName, false, "파일을 읽을 수 없습니다"))
                return@forEachIndexed
            }
        } catch (e: Exception) {
            results.add(ProcessResult(outputName, false, "파일 복사 실패: ${e.message}"))
            return@forEachIndexed
        }

        // 2. FFmpeg 처리
        val outputFile = File(outputDir, outputName)
        val cmd = "-y -i \"${inputFile.absolutePath}\" " +
                  "-ss $trimSec " +
                  "-c:v libx265 -crf $crf -tag:v hvc1 " +
                  "-c:a copy " +
                  "\"${outputFile.absolutePath}\""

        try {
            val session = FFmpegKit.execute(cmd)

            if (ReturnCode.isSuccess(session.returnCode)) {
                // 3. MediaStore에 저장 (Movies/VideoTrimmer/)
                val saved = saveToMediaStore(context, outputFile, outputName, ext)
                if (saved) {
                    results.add(ProcessResult(outputName, true, "성공"))
                } else {
                    results.add(ProcessResult(outputName, false, "MediaStore 저장 실패"))
                }
            } else {
                val log = session.allLogsAsString ?: "알 수 없는 오류"
                val lastLine = log.lines().lastOrNull { it.isNotBlank() } ?: log.take(200)
                results.add(ProcessResult(outputName, false, lastLine))
            }
        } catch (e: Exception) {
            results.add(ProcessResult(outputName, false, "FFmpeg 오류: ${e.message}"))
        } finally {
            inputFile.delete()
            outputFile.delete()
        }
    }

    // 캐시 정리
    cacheDir.deleteRecursively()
    outputDir.deleteRecursively()

    withContext(Dispatchers.Main) {
        onProgress(1f, "완료!")
    }

    results
}

// ── MediaStore 저장 ─────────────────────────────────────────

fun saveToMediaStore(context: Context, file: File, name: String, ext: String): Boolean {
    return try {
        val mimeType = when (ext.lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "ts" -> "video/mp2t"
            else -> "video/mp4"
        }

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/VideoTrimmer")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return false

        context.contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { inp -> inp.copyTo(out) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        }

        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

// ── Utilities ───────────────────────────────────────────────

fun getFileInfo(context: Context, uri: Uri): Pair<String, Long> {
    var name = "video"
    var size = 0L
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: "video"
            if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
        }
    }
    return name to size
}

fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    bytes < 1024 * 1024 * 1024 -> String.format("%.1fMB", bytes / (1024.0 * 1024))
    else -> String.format("%.2fGB", bytes / (1024.0 * 1024 * 1024))
}
