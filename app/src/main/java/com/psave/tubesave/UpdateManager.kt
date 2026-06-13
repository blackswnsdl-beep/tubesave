package com.psave.tubesave

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 인터넷의 작은 설정 파일(JSON)로 앱을 원격 유지보수한다.
 *  1) yt-dlp 옵션(extractor_args)을 갱신 → 유튜브가 클라이언트를 막아도 재빌드 없이 서버에서 교체.
 *  2) 새 APK가 올라오면 어르신에게 "업데이트" 한 번으로 설치하게 안내.
 *
 * 배포자는 아래 CONFIG_URL을 자신의 주소(GitHub raw 등)로 바꾸기만 하면 된다.
 * 그대로 두면 원격 기능은 꺼진 채(앱은 정상) 동작한다.
 *
 * 설정 파일 예시(tubesave_config.json):
 * {
 *   "extractor_args": "youtube:player_client=android_vr",
 *   "latest_version_code": 2,
 *   "apk_url": "https://github.com/USER/REPO/releases/download/v1.1/app-debug.apk",
 *   "update_message": "유튜브 호환성을 개선했어요."
 * }
 * ※ 올리는 APK는 반드시 '같은 키'로 서명돼야 덮어쓰기 설치됩니다(같은 PC에서 빌드한 디버그 APK면 OK).
 */
object UpdateManager {

    // ▼▼▼ 여기를 본인 주소로 바꾸세요 ▼▼▼
    private const val CONFIG_URL = "https://raw.githubusercontent.com/blackswnsdl-beep/tubesave/main/tubesave_config.json"

    fun checkAsync(activity: AppCompatActivity) {
        if (CONFIG_URL.contains("USER/REPO")) return   // 미설정이면 조용히 건너뜀
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val json = fetchText(cacheBusted(CONFIG_URL)) ?: return@launch
            val o = try { JSONObject(json) } catch (e: Exception) { return@launch }

            // 1) yt-dlp 옵션 원격 갱신 (다음 받기부터 적용)
            o.optString("extractor_args").takeIf { it.isNotBlank() }
                ?.let { Prefs.setExtractorArgs(activity, it) }

            // 2) 새 버전 확인
            val latest = o.optInt("latest_version_code", 0)
            val apk = o.optString("apk_url")
            val msg = o.optString("update_message", "유튜브 호환성을 개선했어요.")
            if (latest > currentVersion(activity) && apk.isNotBlank()) {
                withContext(Dispatchers.Main) { promptUpdate(activity, apk, msg) }
            }
        }
    }

    /**
     * 원격설정에서 extractor_args(yt-dlp player_client)만 받아 Prefs 에 반영한다. UI 없음.
     * 화면(Activity)이 없어도 호출 가능하므로 다운로드 실패 복구·백그라운드에서 쓴다.
     * 반드시 백그라운드(IO) 스레드에서 호출할 것. 설정을 읽어 반영했으면 true.
     *
     * 이게 있어야: 유튜브가 막혀 카나리가 GitHub 의 player_client 를 바꿔도,
     * 앱을 완전히 껐다 켜지 않고 그 값을 받아 다음 시도부터 바로 정상화된다.
     */
    fun refreshConfig(context: Context): Boolean {
        if (CONFIG_URL.contains("USER/REPO")) return false
        val json = fetchText(cacheBusted(CONFIG_URL)) ?: return false
        val o = try { JSONObject(json) } catch (e: Exception) { return false }
        val args = o.optString("extractor_args").takeIf { it.isNotBlank() } ?: return false
        Prefs.setExtractorArgs(context, args)
        return true
    }

    // GitHub raw 는 CDN 캐시(약 5분)가 있어, 카나리가 막 push 한 새 설정 대신 옛 값이
    // 올 수 있다. 매번 다른 쿼리(t=현재시각)를 붙여 항상 최신을 받게 한다.
    private fun cacheBusted(url: String): String =
        url + (if (url.contains("?")) "&" else "?") + "t=" + System.currentTimeMillis()

    private fun currentVersion(activity: AppCompatActivity): Int = try {
        val pi = activity.packageManager.getPackageInfo(activity.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pi.versionCode
    } catch (e: Exception) { Int.MAX_VALUE }   // 못 읽으면 업데이트 안내 안 함

    private fun promptUpdate(activity: AppCompatActivity, apkUrl: String, msg: String) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle("새 버전이 있어요")
            .setMessage("$msg\n\n지금 업데이트할까요?")
            .setPositiveButton("업데이트") { _, _ -> downloadAndInstall(activity, apkUrl) }
            .setNegativeButton("나중에", null)
            .setCancelable(false)
            .show()
    }

    private fun downloadAndInstall(activity: AppCompatActivity, apkUrl: String) {
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = false
            setPadding(48, 40, 48, 40)
        }
        val dlg = AlertDialog.Builder(activity)
            .setTitle("업데이트 내려받는 중...")
            .setView(bar).setCancelable(false).create()
        dlg.show()
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val apkFile = File(activity.cacheDir, "update.apk")
            val ok = downloadTo(apkUrl, apkFile) { pct -> activity.runOnUiThread { bar.progress = pct } }
            withContext(Dispatchers.Main) {
                if (dlg.isShowing) dlg.dismiss()
                if (ok) installApk(activity, apkFile)
                else AlertDialog.Builder(activity)
                    .setMessage("업데이트를 받지 못했어요. 인터넷을 확인하고 다시 시도해 주세요.")
                    .setPositiveButton("확인", null).show()
            }
        }
    }

    private fun installApk(activity: AppCompatActivity, file: File) {
        try {
            val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            AlertDialog.Builder(activity)
                .setMessage("설치 화면을 열지 못했어요.\n설정에서 ‘출처를 알 수 없는 앱 설치’를 허용한 뒤 다시 시도해 주세요.")
                .setPositiveButton("확인", null).show()
        }
    }

    private fun fetchText(urlStr: String): String? = try {
        (URL(urlStr).openConnection() as HttpURLConnection).run {
            connectTimeout = 8000; readTimeout = 8000; requestMethod = "GET"
            val t = inputStream.bufferedReader().use { it.readText() }
            disconnect(); t
        }
    } catch (e: Exception) { null }

    private fun downloadTo(urlStr: String, dest: File, onProgress: (Int) -> Unit): Boolean = try {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 20000; requestMethod = "GET"; instanceFollowRedirects = true; connect()
        }
        if (conn.responseCode !in 200..299) { conn.disconnect(); throw Exception("HTTP " + conn.responseCode) }
        val total = conn.contentLength.toLong()
        conn.inputStream.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(8192); var read: Int; var sum = 0L
                while (input.read(buf).also { read = it } >= 0) {
                    out.write(buf, 0, read); sum += read
                    if (total > 0) onProgress((sum * 100 / total).toInt())
                }
            }
        }
        conn.disconnect(); true
    } catch (e: Exception) { dest.delete(); false }
}
