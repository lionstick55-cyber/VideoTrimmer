package com.videotrimmer.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 화면과 서비스가 함께 보는 처리 상태 (앱이 백그라운드에 있어도 유지됨) */
object ProcessingState {
    var processing by mutableStateOf(false)
    var progress by mutableStateOf(0f)
    var statusText by mutableStateOf("")
    var results by mutableStateOf(listOf<ProcessResult>())
    var showResults by mutableStateOf(false)

    data class Job(
        val files: List<VideoFile>,
        val trimSec: Float,
        val endTrimSec: Float,
        val speed: Float,
        val title: String,
    )

    @Volatile
    var pendingJob: Job? = null
}

/** 영상 변환을 알림창에 진행률을 띄운 채로 백그라운드에서 실행하는 서비스 */
class VideoProcessingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifiedPercent = -1

    override fun onBind(intent: Intent?): IBinder? = null

    private var work: kotlinx.coroutines.Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            work?.cancel()
            return START_NOT_STICKY
        }
        val job = ProcessingState.pendingJob
        ProcessingState.pendingJob = null

        createChannel()
        ServiceCompat.startForeground(
            this, NOTIF_PROGRESS_ID, buildProgressNotification("준비 중...", 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )

        if (job == null) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        // 화면이 꺼져도 CPU가 멈추지 않도록
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoTrimmer:processing")
            .apply { acquire(6 * 60 * 60 * 1000L) }

        work = scope.launch {
            var stopped = false
            val res = try {
                processVideos(
                    context = this@VideoProcessingService,
                    files = job.files,
                    trimSec = job.trimSec,
                    endTrimSec = job.endTrimSec,
                    speed = job.speed,
                    title = job.title,
                    onProgress = { p, s ->
                        ProcessingState.progress = p
                        ProcessingState.statusText = s
                        updateProgressNotification(s, p)
                    },
                    onResult = { r -> ProcessingState.results = ProcessingState.results + r },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                stopped = true
                ProcessingState.results
            } catch (e: Exception) {
                listOf(ProcessResult("전체", false, "처리 중 오류: ${e.message}"))
            }

            ProcessingState.results = res
            ProcessingState.showResults = true
            ProcessingState.processing = false

            val ok = res.count { it.success }
            if (stopped) {
                ProcessingState.statusText = "중지됨"
                showDoneNotification("⏹ 중지됨 (완료된 ${ok}개는 저장됨)")
            } else {
                showDoneNotification("✅ ${ok}/${res.size}개 처리 완료")
            }
            stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        if (ProcessingState.processing) {
            ProcessingState.processing = false
            ProcessingState.statusText = "작업이 중단되었습니다"
        }
        super.onDestroy()
    }

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── 알림 ──

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "영상 변환", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildProgressNotification(text: String, percent: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("영상 변환 중 $percent%")
            .setContentText(text)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(
                android.R.drawable.ic_media_pause, "중지",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, VideoProcessingService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()

    private fun updateProgressNotification(text: String, progress: Float) {
        val percent = (progress * 100).toInt().coerceIn(0, 100)
        if (percent == lastNotifiedPercent) return
        lastNotifiedPercent = percent
        try {
            NotificationManagerCompat.from(this)
                .notify(NOTIF_PROGRESS_ID, buildProgressNotification(text, percent))
        } catch (_: SecurityException) {
            // 알림 권한이 없으면 표시만 안 됨 (처리는 계속)
        }
    }

    private fun showDoneNotification(text: String) {
        try {
            NotificationManagerCompat.from(this).notify(
                NOTIF_DONE_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("영상 변환 완료")
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(openAppIntent())
                    .build()
            )
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val CHANNEL_ID = "video_processing"
        private const val NOTIF_PROGRESS_ID = 1
        private const val NOTIF_DONE_ID = 2
        private const val ACTION_STOP = "com.videotrimmer.app.STOP"

        fun stop(context: Context) {
            context.startService(
                Intent(context, VideoProcessingService::class.java).setAction(ACTION_STOP)
            )
        }

        fun start(context: Context, job: ProcessingState.Job) {
            ProcessingState.pendingJob = job
            ProcessingState.processing = true
            ProcessingState.progress = 0f
            ProcessingState.statusText = "준비 중..."
            ProcessingState.showResults = false
            ProcessingState.results = emptyList()

            val intent = Intent(context, VideoProcessingService::class.java)
            // 영상 읽기 권한을 서비스에도 넘겨줌 (앱 화면을 닫아도 계속 읽을 수 있게)
            if (job.files.isNotEmpty()) {
                val clip = ClipData.newRawUri("videos", job.files[0].uri)
                job.files.drop(1).forEach { clip.addItem(ClipData.Item(it.uri)) }
                intent.clipData = clip
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
