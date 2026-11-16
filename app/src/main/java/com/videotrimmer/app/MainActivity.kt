@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.videotrimmer.app

import android.app.Activity
import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.transformer.ProgressHolder
import android.media.MediaMetadataRetriever
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
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
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Effects
import kotlinx.coroutines.CancellationException
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    var endTrimSeconds by remember { mutableStateOf("0") }
    var speed by remember { mutableStateOf(1.0f) }
    var titlePrefix by remember { mutableStateOf("제목") }
    // 처리 상태는 서비스와 공유 (앱을 나갔다 와도 유지)
    var processing by ProcessingState::processing
    val progress by ProcessingState::progress
    val statusText by ProcessingState::statusText
    var results by ProcessingState::results
    var showResults by ProcessingState::showResults

    // 알림 권한 (진행률을 알림창에 표시)
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // 선택한 영상 목록 반영 (파일 앱 / 갤러리 앱 공통)
    fun onVideosPicked(uris: List<Uri>) {
        if (uris.isNotEmpty()) {
            files = uris.map { uri ->
                val (name, size) = getFileInfo(context, uri)
                VideoFile(uri, name, size)
            }
            showResults = false
            results = emptyList()
        }
    }

    // 파일 앱으로 선택
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> onVideosPicked(uris) }

    // 갤러리 앱(삼성 갤러리, Google 포토 등)으로 선택
    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val uris = mutableListOf<Uri>()
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            } else {
                data.data?.let { uris.add(it) }
            }
            onVideosPicked(uris)
        }
    }

    fun launchGallery() {
        val pick = Intent(Intent.ACTION_PICK).apply {
            setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*")
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val chooser = Intent.createChooser(pick, "갤러리 앱 선택")
        try {
            galleryPicker.launch(chooser)
        } catch (e: Exception) {
            // 갤러리 앱이 없으면 일반 미디어 선택으로 대체
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "video/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            galleryPicker.launch(Intent.createChooser(fallback, "영상 선택"))
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
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = { launchGallery() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color(0xFF0f3460),
                                contentColor = Color.White,
                            )
                        ) {
                            Icon(Icons.Default.PhotoLibrary, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("갤러리")
                        }
                        FilledTonalButton(
                            onClick = { filePicker.launch(arrayOf("video/*")) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color(0xFF0f3460),
                                contentColor = Color.White,
                            )
                        ) {
                            Icon(Icons.Default.Folder, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("파일")
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

                    // End trim seconds
                    OutlinedTextField(
                        value = endTrimSeconds,
                        onValueChange = { endTrimSeconds = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("뒷부분 자르기 (초)") },
                        leadingIcon = { Icon(Icons.Default.ContentCut, null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = inputColors(),
                    )

                    Spacer(Modifier.height(14.dp))

                    // Speed
                    Text("⏩ 재생 속도", color = TextPrimary, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(1.0f, 1.2f, 1.5f).forEach { s ->
                            val selected = speed == s
                            FilledTonalButton(
                                onClick = { speed = s },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (selected) Accent else InputBg,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Text(if (s == 1.0f) "원래 속도" else "${s}배속",
                                     fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }

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

                    Spacer(Modifier.height(10.dp))
                    Text("🎞 화질: 원본 그대로 유지", color = TextDim, fontSize = 12.sp)

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
                            val ext = "mp4"
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
                        if (Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(
                                context, Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        VideoProcessingService.start(
                            context,
                            ProcessingState.Job(
                                files = files,
                                trimSec = trimSeconds.toFloatOrNull() ?: 0f,
                                endTrimSec = endTrimSeconds.toFloatOrNull() ?: 0f,
                                speed = speed,
                                title = titlePrefix.ifBlank { "제목" },
                            )
                        )
                        Toast.makeText(context,
                            "변환을 시작했어요. 다른 앱을 써도 계속 진행돼요.",
                            Toast.LENGTH_SHORT).show()
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
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { VideoProcessingService.stop(context) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                        ) {
                            Icon(Icons.Default.Stop, null)
                            Spacer(Modifier.width(6.dp))
                            Text("중지", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // ── Results ──
            if (results.isNotEmpty()) {
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
                        Text("📂 저장 위치: 원본 영상과 같은 폴더",
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
    endTrimSec: Float,
    speed: Float,
    title: String,
    onProgress: (Float, String) -> Unit,
    onResult: (ProcessResult) -> Unit = {},
): List<ProcessResult> = withContext(Dispatchers.IO) {
    val results = mutableListOf<ProcessResult>()
    val outputDir = File(context.cacheDir, "video_output").apply { mkdirs() }

    files.forEachIndexed { index, vf ->
        val num = index + 1
        val outputName = "${title}_${num}.mp4"

        withContext(Dispatchers.Main) {
            onProgress(index.toFloat() / files.size, "[$num/${files.size}] ${vf.name} · 준비 중")
        }

        val outputFile = File(outputDir, "out_$num.mp4")
        try {
            // 1. 폰 내장 HEVC 인코더로 앞부분 자르기 + 인코딩
            val info = sourceInfo(context, vf.uri)
            val startMs = (trimSec * 1000).toLong().coerceAtLeast(0)
            val endMs: Long? = if (endTrimSec > 0f && info.durationMs > 0)
                info.durationMs - (endTrimSec * 1000).toLong() else null
            if (endMs != null && endMs <= startMs) {
                throw IllegalArgumentException("영상이 자를 길이보다 짧아요")
            }
            transcodeHevc(context, vf.uri, startMs, endMs, speed, info.bitrate, outputFile) { f ->
                val pct = (f * 100).toInt()
                onProgress((index + f) / files.size,
                    "[$num/${files.size}] ${vf.name} · 변환 중 $pct%")
            }

            withContext(Dispatchers.Main) {
                onProgress((index + 1f) / files.size, "[$num/${files.size}] ${vf.name} · 저장 중")
            }

            // 2. 원본과 같은 폴더에 저장 (안 되면 Movies/VideoTrimmer/)
            val sourceDir = getSourceRelativePath(context, vf.uri)
            val savedDir = saveToMediaStore(context, outputFile, outputName, "mp4", sourceDir)
            if (savedDir != null) {
                val note = if (sourceDir != null && savedDir != sourceDir)
                    " (원본 폴더에 저장할 수 없어 대체 폴더 사용)" else ""
                results.add(ProcessResult(outputName, true, "저장: $savedDir$note"))
            } else {
                results.add(ProcessResult(outputName, false, "저장 실패"))
            }
        } catch (e: CancellationException) {
            outputFile.delete()
            throw e
        } catch (e: Exception) {
            results.add(ProcessResult(outputName, false, "변환 오류: ${e.message}"))
        } finally {
            outputFile.delete()
        }
        results.lastOrNull()?.let { r -> withContext(Dispatchers.Main) { onResult(r) } }
        // 하드웨어 인코더가 완전히 풀릴 시간을 줌 (다음 파일에서 멈춤 방지)
        delay(800)
    }

    outputDir.deleteRecursively()

    withContext(Dispatchers.Main) {
        onProgress(1f, "완료!")
    }

    results
}

data class SourceInfo(val bitrate: Int, val durationMs: Long)

/** 원본 영상의 비트레이트(→ 원본 화질 유지)와 길이를 읽는다 */
fun sourceInfo(context: Context, uri: Uri): SourceInfo {
    var bitrate: Int? = null
    var durationMs = 0L
    var width = 1920
    var height = 1080
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(context, uri)
        bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
        durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.let { width = it }
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.let { height = it }
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        try { r.release() } catch (_: Exception) {}
    }
    // 정보를 못 읽으면 해상도 기준으로 넉넉하게
    val fallback = width.toLong() * height * 30 / 5
    val b = bitrate?.toLong()?.takeIf { it > 0 } ?: fallback
    return SourceInfo(b.coerceIn(1_000_000L, 150_000_000L).toInt(), durationMs)
}

private const val STALL_TIMEOUT_MS = 120_000L

/** Media3 Transformer로 HEVC(H.265) 변환. 메인 스레드에서 실행해야 한다. */
suspend fun transcodeHevc(
    context: Context, uri: Uri, startMs: Long, endMs: Long?, speed: Float,
    bitrate: Int, output: File,
    onFileProgress: (Float) -> Unit = {},
) = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine<Unit> { cont ->
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs.coerceAtLeast(0))
                    .apply { if (endMs != null) setEndPositionMs(endMs) }
                    .build()
            )
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(bitrate).build()
            )
            .setEnableFallback(true)
            .build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    if (cont.isActive) cont.resumeWithException(exportException)
                }
            })
            .build()

        // 파일 하나 안에서의 진행률을 0.5초마다 확인
        val handler = Handler(Looper.getMainLooper())
        val holder = ProgressHolder()
        var lastProgress = -1
        var lastChange = System.currentTimeMillis()
        val poll = object : Runnable {
            override fun run() {
                if (!cont.isActive) return
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onFileProgress(holder.progress / 100f)
                    if (holder.progress != lastProgress) {
                        lastProgress = holder.progress
                        lastChange = System.currentTimeMillis()
                    }
                }
                // 2분 동안 진행이 전혀 없으면 멈춘 것으로 보고 이 파일은 건너뜀
                if (System.currentTimeMillis() - lastChange > STALL_TIMEOUT_MS) {
                    transformer.cancel()
                    if (cont.isActive) cont.resumeWithException(
                        IllegalStateException(
                            "2분 동안 진행이 없어 건너뜀 (진행률 ${maxOf(lastProgress, 0)}%에서 멈춤)"
                        )
                    )
                    return
                }
                handler.postDelayed(this, 500)
            }
        }

        cont.invokeOnCancellation {
            handler.removeCallbacks(poll)
            transformer.cancel()
        }
        val edited = EditedMediaItem.Builder(mediaItem).apply {
            if (speed != 1.0f) {
                // 영상과 소리 모두 빠르게 (목소리 높낮이는 그대로)
                val sonic = SonicAudioProcessor().apply {
                    setSpeed(speed)
                    setPitch(1.0f)
                }
                setEffects(Effects(listOf<AudioProcessor>(sonic), listOf<Effect>(SpeedChangeEffect(speed))))
            }
        }.build()
        transformer.start(edited, output.absolutePath)
        handler.post(poll)
    }
}

// ── MediaStore 저장 ─────────────────────────────────────────

private const val FALLBACK_DIR = "Movies/VideoTrimmer/"

/** 원본 영상이 들어 있는 폴더(예: "DCIM/Camera/")를 알아낸다. 모르면 null. */
fun getSourceRelativePath(context: Context, uri: Uri): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    try {
        // 파일 앱(내장 저장소)으로 고른 경우: "primary:DCIM/Camera/a.mp4"
        if (DocumentsContract.isDocumentUri(context, uri) &&
            uri.authority == "com.android.externalstorage.documents") {
            val docId = DocumentsContract.getDocumentId(uri)
            if (!docId.startsWith("primary:")) return null
            val path = docId.removePrefix("primary:")
            return if (path.contains('/')) path.substringBeforeLast('/') + "/" else null
        }

        // MediaStore 영상 URI 찾기 (갤러리, 파일 앱의 '동영상' 항목, Google 포토)
        val mediaUri: Uri? = when {
            uri.authority == MediaStore.AUTHORITY -> uri
            DocumentsContract.isDocumentUri(context, uri) &&
                uri.authority == "com.android.providers.media.documents" ->
                MediaStore.getMediaUri(context, uri)
            else -> Regex("content://media/[^/]+/(video|file|images)/media/\\d+")
                .find(Uri.decode(uri.toString()))?.value?.let { Uri.parse(it) }
        }

        val target = mediaUri ?: uri
        context.contentResolver.query(
            target, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                if (idx >= 0) return c.getString(idx)?.takeIf { it.isNotBlank() }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}

private fun insertAndCopy(
    context: Context, file: File, name: String, mimeType: String, relPath: String?,
): Boolean {
    val resolver = context.contentResolver
    val isDownload = relPath?.startsWith(Environment.DIRECTORY_DOWNLOADS) == true
    val collection =
        if (isDownload && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (relPath != null) put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    val uri = resolver.insert(collection, values) ?: return false
    return try {
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { inp -> inp.copyTo(out) }
        } ?: throw IllegalStateException("출력 스트림 없음")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        true
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
}

/**
 * 원본과 같은 폴더에 저장한다. 그 폴더에 쓸 수 없으면 Movies/VideoTrimmer/ 에 저장.
 * 저장된 폴더 경로를 돌려주고, 실패하면 null.
 */
fun saveToMediaStore(
    context: Context, file: File, name: String, ext: String, sourceDir: String?,
): String? {
    val mimeType = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "ts" -> "video/mp2t"
        else -> "video/mp4"
    }

    if (sourceDir != null) {
        try {
            if (insertAndCopy(context, file, name, mimeType, sourceDir)) return sourceDir
        } catch (e: Exception) {
            e.printStackTrace() // 안드로이드가 이 폴더에 영상 저장을 허용하지 않는 경우
        }
    }
    return try {
        if (insertAndCopy(context, file, name, mimeType, FALLBACK_DIR)) FALLBACK_DIR else null
    } catch (e: Exception) {
        e.printStackTrace()
        null
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
