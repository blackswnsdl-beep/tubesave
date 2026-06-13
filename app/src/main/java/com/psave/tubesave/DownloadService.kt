package com.psave.tubesave

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 다운로드를 '직렬 큐'로 처리한다.
 * - 여러 곡을 연달아 공유해도 하나씩 순서대로 처리(동시 실행으로 인한 임시폴더/알림 충돌 제거)
 * - 요청마다 UUID 임시폴더 사용
 * - 진행 알림은 "N곡 중 M번째" 형태로 통합
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private lateinit var nm: NotificationManager

    private val total = AtomicInteger(0)   // 누적 요청 수
    private val done = AtomicInteger(0)    // 처리 완료 수
    @Volatile private var working = false
    private var wakeLock: PowerManager.WakeLock? = null   // 화면 꺼져도 받기 지속 (작업 중에만 점유)

    // 취소 기능용 (인스턴스 단위: 서비스가 끝나면 초기화됨)
    @Volatile private var cancelled = false
    @Volatile private var currentProcessId: String? = null
    @Volatile private var currentConvJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "다운로드", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "다운로드 완료", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        // 단일 소비자 코루틴: 큐에서 하나씩 꺼내 순차 처리
        scope.launch { consume() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelAll()
            return START_NOT_STICKY
        }
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url != null) {
            cancelled = false
            total.incrementAndGet()
            val notif = progressNotification(queueText() + " 준비 중...", 0, true)
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else
                startForeground(NOTIF_ID, notif)
            broadcastProgress(queueText() + " 준비 중...", -1, true)
            queue.trySend(url)
        }
        return START_NOT_STICKY
    }

    private suspend fun consume() {
        for (url in queue) {
            working = true
            acquireWake()          // 받는 동안만 CPU 깨움 (화면 꺼져도 진행)
            try {
                ensureInit()
                downloadOne(url)
            } catch (e: Exception) {
                if (cancelled) {
                    // 사용자가 취소한 경우 → 실패로 처리하지 않음 (프로세스 강제 종료로 발생한 예외)
                    consecutiveFailures = 0
                    android.util.Log.i(TAG, "사용자 취소로 중단됨")
                    return   // 정리는 cancelAll()이 담당 (finally에서 working=false 처리됨)
                }
                // 실제 원인은 logcat에서 확인: adb logcat -s TubeSave
                android.util.Log.e(TAG, "다운로드 실패 (연속 ${consecutiveFailures}회): " + (e.message ?: "unknown"), e)
                val needUpdate = consecutiveFailures >= 2
                if (needUpdate) {
                    // 앱이 열려 있으면 업데이트 안내 팝업, 닫혀 있으면 알림으로
                    if (MainActivity.inForeground) sendBroadcast(Intent(ACTION_NEEDS_UPDATE).setPackage(packageName))
                    else notifyDone("받기 실패", "여러 번 실패했어요. 인터넷에 연결한 뒤 여기를 눌러 업데이트해 보세요.", promptUpdate = true)
                } else {
                    result("받기 실패", "받지 못했어요. 인터넷 연결을 확인하고 다시 시도해 주세요.")
                }
                notifyAdmin("[노래저장] 다운로드 실패 (${consecutiveFailures}회): " + (e.message ?: "unknown"))
            } finally {
                done.incrementAndGet()
                working = false
            }
            // 큐가 비고 진행 중인 작업도 없으면 포그라운드 종료 (잠깐 대기 후 재확인)
            if (queue.isEmpty && !working) {
                total.set(0); done.set(0)
                broadcastProgress("", 0, false)   // 앱 내 진행 띠 숨김
                releaseWake()                     // 배터리: 할 일 없으면 즉시 해제
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun ensureInit() {
        synchronized(LOCK) {
            if (!initialized) {
                YoutubeDL.getInstance().init(applicationContext)
                com.yausername.ffmpeg.FFmpeg.getInstance().init(applicationContext)
                initialized = true
            }
        }
        // APK에 동봉된 yt-dlp 바이너리는 빌드 시점에 고정돼 있어, 시간이 지나면
        // 유튜브가 막아 첫 곡부터 "받기 실패"가 난다. 최초 1회는 다운로드 전에
        // 최신(NIGHTLY)으로 선제 갱신해 처음부터 정상 동작하도록 한다.
        if (!Prefs.ytdlpUpdated(applicationContext)) {
            nm.notify(NOTIF_ID, progressNotification("최신 버전으로 준비 중... (처음 1번)", 0, true))
            runCatching {
                YoutubeDL.getInstance()
                    .updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
            }.onSuccess {
                Prefs.setYtdlpUpdated(applicationContext, true)
                consecutiveFailures = 0
            }.onFailure {
                android.util.Log.e(TAG, "yt-dlp 선제 업데이트 실패", it)
            }
        } else if (consecutiveFailures >= 1) {
            // 실패가 누적되면:
            //  ① 원격설정(player_client)을 다시 받아온다 — 카나리가 GitHub 에 바꿔둔 새 값을
            //     앱 재시작 없이 반영해, 다음 시도가 곧바로 새 클라이언트로 동작하게 한다.
            //  ② yt-dlp 도 다시 최신(NIGHTLY)으로 갱신 시도.
            runCatching { UpdateManager.refreshConfig(applicationContext) }
            runCatching {
                YoutubeDL.getInstance()
                    .updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
            }.onSuccess { consecutiveFailures = 0 }
        }
    }

    private fun downloadOne(url: String) {
        val tempDir = File(cacheDir, "dl_" + UUID.randomUUID()).apply { mkdirs() }
        var convJob: kotlinx.coroutines.Job? = null   // 변환 카운트다운(예외 시 finally에서 정리)
        val videoId = extractVideoId(url)
        try {
            // 같은 링크(영상)를 이미 받았고 그 파일이 아직 있으면 → 받지 않고 안내(중복 방지)
            if (videoId != null) {
                val prev = Prefs.downloadedName(applicationContext, videoId)
                if (prev != null && SongRepository.existsByName(applicationContext, prev)) {
                    consecutiveFailures = 0
                    result("이미 받은 노래예요 ♪", prev)
                    broadcastProgress("", 0, false)
                    return
                }
            }
            // 받기 전에 길이를 먼저 확인 → 너무 길면 변환에 시간이 오래 걸리므로 미리 안내한다.
            val durationSec = runCatching {
                YoutubeDL.getInstance().getInfo(
                    YoutubeDLRequest(url).apply {
                        addOption("--no-playlist")
                        addOption("--extractor-args", Prefs.extractorArgs(applicationContext))
                    }
                ).duration
            }.getOrDefault(0)

            if (durationSec > 14400) {   // 4시간 초과: 받지 않고 안내(폰 저장공간/시간 보호)
                consecutiveFailures = 0
                result("너무 긴 영상이에요", "${durationText(durationSec)}짜리라 받지 않았어요. 더 짧은 영상으로 받아 주세요.")
                broadcastProgress("", 0, false)
                return
            }
            if (durationSec > 3600) {    // 1시간 초과: 받되 시간이 걸린다고 미리 안내
                notifyDone("조금 오래 걸려요 ⏳", "이 영상은 ${durationText(durationSec)}이라 받는 데 시간이 걸려요. 그대로 기다려 주세요.")
            }

            val request = YoutubeDLRequest(url).apply {
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "0")
                addOption("--embed-thumbnail")
                addOption("--add-metadata")
                addOption("--no-playlist")
                addOption("--restrict-filenames")
                // [중요] 안드로이드에는 JavaScript 런타임이 없어 web/tv 클라이언트의
                // nsig("n challenge")를 풀 수 없다 → android_vr 클라이언트로 받는다.
                // 이 값은 원격설정(인터넷)에서 갱신 가능 → 차단되면 재빌드 없이 서버에서 교체.
                addOption("--extractor-args", Prefs.extractorArgs(applicationContext))
                addOption("-o", File(tempDir, "%(title)s.%(ext)s").absolutePath)
            }

            // 변환 예상시간(영상 길이 기반, 대략 15배속 가정). 길이를 모르면 0.
            val estConvSec = if (durationSec > 0) (durationSec / 15L).coerceAtLeast(5L) else 0L
            var lastP = -1
            var lastUpdate = 0L

            val processId = "tubesave_" + UUID.randomUUID()
            currentProcessId = processId
            YoutubeDL.getInstance().execute(request, processId) { progress, eta, _ ->
                val p = progress.toInt().coerceIn(0, 100)
                if (p < 100) {
                    // 다운로드 단계: % + 남은시간(yt-dlp ETA).
                    // 배터리: %가 바뀌고 0.6초 이상 지났을 때만 갱신(잦은 깨움 방지).
                    val now = System.currentTimeMillis()
                    if (p != lastP && now - lastUpdate >= 600) {
                        lastP = p; lastUpdate = now
                        val etaTxt = if (eta > 0) " · 약 ${fmtRemain(eta)} 남음" else ""
                        val txt = queueText() + " 받는 중 $p%$etaTxt"
                        nm.notify(NOTIF_ID, progressNotification(txt, p, false))
                        broadcastProgress(txt, p, true)
                    }
                } else if (convJob == null) {
                    // 다운로드 100% → 변환 단계 진입: 예상시간 카운트다운 시작(한 번만)
                    convJob = scope.launch { conversionCountdown(estConvSec) }
                    currentConvJob = convJob   // 취소 시 cancelAll()이 정리할 수 있게 보관
                }
            }
            convJob?.cancel()   // 변환 완료 → 카운트다운 종료
            currentProcessId = null

            val mp3 = tempDir.listFiles { f -> f.extension.equals("mp3", true) }?.firstOrNull()
                ?: throw IllegalStateException("변환된 파일을 찾지 못했습니다.")

            // 중복 다운로드 안내: 같은 제목이 이미 있으면 저장하지 않음
            if (SongRepository.existsByName(applicationContext, mp3.nameWithoutExtension)) {
                consecutiveFailures = 0
                result("이미 있는 노래예요 ♪", mp3.nameWithoutExtension)
                return
            }

            val savedUri = SongRepository.saveToLibrary(applicationContext, mp3)
                ?: throw IllegalStateException("저장 폴더에 쓸 수 없습니다.")

            SongRepository.saveArtwork(applicationContext, savedUri, mp3)   // 잠금화면 커버용 썸네일 추출

            consecutiveFailures = 0
            videoId?.let { Prefs.putDownloaded(applicationContext, it, mp3.nameWithoutExtension) }  // 중복 방지 기록
            result("받기 완료 ♪", mp3.nameWithoutExtension)
            sendBroadcast(Intent(ACTION_DONE).setPackage(packageName).putExtra("uri", savedUri.toString()))
        } catch (e: Exception) {
            consecutiveFailures++
            throw e
        } finally {
            convJob?.cancel()   // 변환 카운트다운 코루틴 정리(누수 방지)
            tempDir.deleteRecursively()
        }
    }

    /** 앱 화면(MainActivity)에도 진행현황을 전달 (알림과 별개로 앱 내 진행 띠 표시용) */
    private fun broadcastProgress(text: String, percent: Int, active: Boolean) {
        sendBroadcast(
            Intent(ACTION_PROGRESS).setPackage(packageName)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_PERCENT, percent)
                .putExtra(EXTRA_ACTIVE, active)
        )
    }

    /** 유튜브 URL에서 영상 ID(11자) 추출 — 중복 다운로드 판별용 */
    private fun extractVideoId(url: String): String? =
        Regex("(?:v=|youtu\\.be/|shorts/|embed/|/v/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)

    /** 초 → "약 N시간 M분" / "약 M분" */
    private fun durationText(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return if (h > 0) "약 ${h}시간 ${m}분" else "약 ${m}분"
    }

    /** "3곡 중 2번째" 같은 진행 맥락 */
    private fun queueText(): String {
        val t = total.get()
        return if (t > 1) "${t}곡 중 ${done.get() + 1}번째" else "노래"
    }

    private fun progressNotification(text: String, progress: Int, indeterminate: Boolean): android.app.Notification {
        val cancelPi = PendingIntent.getService(
            this, 99,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "취소", cancelPi)
            .build()
    }

    private fun notifyDone(title: String, text: String, promptUpdate: Boolean = false) {
        val openIntent = Intent(this, MainActivity::class.java)
        if (promptUpdate) openIntent.putExtra(EXTRA_PROMPT_UPDATE, true)
        val open = PendingIntent.getActivity(
            this, if (promptUpdate) 1 else 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(
            (System.currentTimeMillis() % 100000).toInt(),
            NotificationCompat.Builder(this, CHANNEL_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    /** 받기 결과 안내: 앱이 열려 있으면 팝업(브로드캐스트), 닫혀 있으면 알림. */
    private fun result(title: String, msg: String, promptUpdate: Boolean = false) {
        if (MainActivity.inForeground) {
            sendBroadcast(
                Intent(ACTION_RESULT).setPackage(packageName)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_TEXT, msg)
                    .putExtra(EXTRA_PROMPT_UPDATE, promptUpdate)
            )
        } else {
            notifyDone(title, msg, promptUpdate)
        }
    }

    /** (선택) 관리자에게 텔레그램으로 실패 알림. 설정돼 있을 때만 전송. */
    private fun notifyAdmin(text: String) {
        val token = Prefs.tgToken(applicationContext)
        val chat = Prefs.tgChat(applicationContext)
        if (token.isBlank() || chat.isBlank()) return
        scope.launch {
            runCatching {
                val url = "https://api.telegram.org/bot$token/sendMessage?chat_id=$chat" +
                        "&text=" + URLEncoder.encode(text, "UTF-8")
                (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    responseCode
                    disconnect()
                }
            }
        }
    }

    override fun onDestroy() {
        releaseWake()
        scope.cancel()
        super.onDestroy()
    }

    /** 화면이 꺼져도 다운로드/변환이 멈추지 않도록 작업 중에만 CPU를 깨워 둔다.
     *  배터리 보호를 위해 안전 타임아웃(2시간)을 두고, 큐가 비면 즉시 해제한다. */
    private fun acquireWake() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TubeSave:download")
        }
        if (wakeLock?.isHeld != true) wakeLock?.acquire(2 * 60 * 60 * 1000L)
    }

    private fun releaseWake() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    /** 사용자 취소: 진행 중인 받기/변환을 멈추고 대기 곡도 모두 비운 뒤 서비스 종료. */
    private fun cancelAll() {
        cancelled = true
        // 대기 중인 곡 모두 제거
        while (queue.tryReceive().isSuccess) { /* drain */ }
        // 진행 중인 yt-dlp/ffmpeg 프로세스 강제 종료 (execute가 예외로 풀림 → 취소로 처리)
        currentProcessId?.let { runCatching { YoutubeDL.getInstance().destroyProcessById(it) } }
        currentConvJob?.cancel()
        consecutiveFailures = 0
        total.set(0); done.set(0)
        broadcastProgress("", 0, false)           // 앱 진행 띠 숨김
        result("받기를 멈췄어요", "취소했어요.")        // 앱이면 팝업, 닫혀 있으면 알림
        releaseWake()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 초(Long) → "N분"/"N초" (남은 시간 표시용) */
    private fun fmtRemain(sec: Long): String {
        if (sec <= 0) return ""
        val m = sec / 60; val s = sec % 60
        return if (m > 0) "${m}분" else "${s}초"
    }

    /** 변환(ffmpeg) 단계엔 진행률 콜백이 안 와서 멈춘 듯 보인다.
     *  영상 길이로 예상시간을 잡고 3초마다 줄여가며 "약 N분 남음"을 표시한다.
     *  실제 변환이 끝나면 호출부에서 이 코루틴을 취소한다. */
    private suspend fun conversionCountdown(estSec: Long) {
        var remain = estSec
        while (true) {
            val txt = if (remain > 0)
                queueText() + " 변환 중... 약 ${fmtRemain(remain)} 남음"
            else
                queueText() + " 변환 중... 거의 다 됐어요"
            nm.notify(NOTIF_ID, progressNotification(txt, 100, true))
            broadcastProgress(txt, -1, true)
            kotlinx.coroutines.delay(3000)
            if (remain > 0) remain = (remain - 3).coerceAtLeast(0)
        }
    }

    companion object {
        private const val TAG = "TubeSave"
        private const val CHANNEL = "download"
        private const val CHANNEL_DONE = "download_done"
        private const val NOTIF_ID = 1
        private const val EXTRA_URL = "url"
        const val ACTION_DONE = "com.psave.tubesave.DOWNLOAD_DONE"
        const val ACTION_NEEDS_UPDATE = "com.psave.tubesave.NEEDS_UPDATE"
        const val ACTION_PROGRESS = "com.psave.tubesave.DOWNLOAD_PROGRESS"
        const val ACTION_CANCEL = "com.psave.tubesave.DOWNLOAD_CANCEL"
        const val ACTION_RESULT = "com.psave.tubesave.DOWNLOAD_RESULT"   // 받기 결과 → 앱 팝업
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_PERCENT = "percent"      // 0~100, 알 수 없으면 -1
        const val EXTRA_ACTIVE = "active"        // false면 진행 띠 숨김
        const val EXTRA_PROMPT_UPDATE = "prompt_update"
        private val LOCK = Any()
        @Volatile private var initialized = false
        @Volatile private var consecutiveFailures = 0

        fun start(context: Context, url: String) {
            val i = Intent(context, DownloadService::class.java).putExtra(EXTRA_URL, url)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        /** 앱 화면의 '취소' 버튼에서 호출. 실행 중인 서비스에 취소 명령 전달. */
        fun cancel(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL)
                )
            }
        }
    }
}
