package com.psave.tubesave

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.withStarted
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Checks may run automatically. Download and Android installer always require user consent. */
object UpdateManager {
    const val EXTRA_CHECK_UPDATE = "com.psave.tubesave.CHECK_APP_UPDATE"
    @Volatile private var installerOpen = false
    fun canStartMusicDownload() = !installerOpen
    private val checking = AtomicBoolean(false)
    private val flow = UpdateFlowGate()
    private data class Pending(val file: File, val release: UpdateRelease)
    @Volatile private var pending: Pending? = null

    private fun prefs(context: Context) = context.getSharedPreferences("tubesave_updates", Context.MODE_PRIVATE)
    private fun usable(activity: AppCompatActivity) = !activity.isFinishing && !activity.isDestroyed &&
        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    fun checkThrottled(activity: AppCompatActivity) {
        installerOpen = false // returning from the Android installer (including cancellation)
        if (!usable(activity)) {
            if (!activity.isFinishing && !activity.isDestroyed) activity.lifecycleScope.launch {
                activity.lifecycle.withStarted { checkThrottled(activity) }
            }
            return
        }
        pending?.let { offerInstall(activity, it); return }
        if (activity.intent.getBooleanExtra(EXTRA_CHECK_UPDATE, false)) {
            activity.intent.removeExtra(EXTRA_CHECK_UPDATE)
            checkAsync(activity, manual = true)
            return
        }
        val now = System.currentTimeMillis()
        val settings = prefs(activity)
        val success = settings.getLong("last_success", 0)
        val attempt = settings.getLong("last_attempt", 0)
        if (now >= success && now - success < 6 * 60 * 60 * 1000L) return
        if (now >= attempt && now - attempt < 15 * 60 * 1000L) return
        checkAsync(activity)
    }

    fun checkAsync(activity: AppCompatActivity, manual: Boolean = false) {
        pending?.let { offerInstall(activity, it); return }
        if (!checking.compareAndSet(false, true)) {
            if (manual) toast(activity, "새 버전을 확인하고 있어요.")
            return
        }
        prefs(activity).edit().putLong("last_attempt", System.currentTimeMillis()).apply()
        if (manual) toast(activity, "새 버전을 확인하고 있어요.")
        activity.lifecycleScope.launch {
            try {
                val release = withContext(Dispatchers.IO) { available(activity.applicationContext) }
                prefs(activity).edit().putLong("last_success", System.currentTimeMillis()).apply()
                if (release != null && usable(activity)) promptUpdate(activity, release)
                else if (manual && usable(activity)) message(activity, "새 버전이 아직 게시되지 않았어요.")
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) {
                if (manual && usable(activity)) message(activity, "업데이트를 확인하지 못했어요. 인터넷 연결과 배포 정보를 확인해 주세요.")
            } finally { checking.set(false) }
        }
    }

    /** IO only. Missing hashes on an advertised newer release fail closed. */
    internal fun available(context: Context): UpdateRelease? {
        val config = fetchConfig()
        config.optString("extractor_args").takeIf { it.isNotBlank() }?.let { Prefs.setExtractorArgs(context, it) }
        val current = UpdateVerifier.version(UpdateVerifier.installed(context))
        val version = config.optLong("latest_version_code", 0)
        if (version <= current) return null
        val release = UpdateRelease(version, config.getString("apk_url"), config.getString("apk_sha256"),
            config.getLong("apk_size_bytes"), config.getString("application_id"),
            config.optString("update_message", "더 안정적으로 개선했어요.").take(500))
        UpdatePolicy.validateRelease(release, current, context.packageName)
        return release
    }

    fun refreshConfig(context: Context): Boolean = try {
        val value = fetchConfig().optString("extractor_args").takeIf { it.isNotBlank() }
        if (value == null) false else { Prefs.setExtractorArgs(context, value); true }
    } catch (error: Exception) { false }

    private fun fetchConfig(): JSONObject {
        val url = UpdatePolicy.CONFIG_URL + "?t=" + System.currentTimeMillis()
        return JSONObject(UpdateTransfer(allow = UpdatePolicy::trustedMetadata).metadata(url))
    }

    private fun promptUpdate(activity: AppCompatActivity, release: UpdateRelease) {
        if (!usable(activity) || !flow.acquire()) return
        var downloading = false
        val prompt = AlertDialog.Builder(activity).setTitle("새 버전이 있어요")
            .setMessage(release.message + "\n\n업데이트를 내려받을까요? 설치할 때 한 번 더 확인해요.")
            .setPositiveButton("내려받기") { _, _ -> downloading = true; download(activity, release) }
            .setNegativeButton("나중에", null).create()
        showOwned(activity, prompt) { if (!downloading) flow.release() }
    }

    private fun download(activity: AppCompatActivity, release: UpdateRelease) {
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; setPadding(48, 40, 48, 40)
        }
        val dialog = AlertDialog.Builder(activity).setTitle("업데이트 내려받는 중...")
            .setView(bar).setCancelable(false).setNegativeButton("취소", null).create()
        dialog.show()
        val directory = File(activity.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, UUID.randomUUID().toString() + ".apk")
        val job = activity.lifecycleScope.launch {
            var verified = false
            try {
                val coroutine = currentCoroutineContext()
                withContext(Dispatchers.IO) {
                    UpdatePolicy.validateRelease(release, UpdateVerifier.version(UpdateVerifier.installed(activity)), activity.packageName)
                    UpdateTransfer().download(release, target, { coroutine.ensureActive() }) { percent ->
                        activity.runOnUiThread { if (dialog.isShowing) bar.progress = percent }
                    }
                    coroutine.ensureActive()
                    UpdateVerifier.verify(activity, target, release)
                }
                ensureActive()
                verified = true
                pending = Pending(target, release)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) {
                if (usable(activity)) message(activity, "업데이트를 받지 못했어요. 인터넷 연결을 확인하고 다시 시도해 주세요.\n" +
                    (if (error is IllegalArgumentException) error.message.orEmpty() else "안전하게 확인된 파일만 설치할 수 있어요."))
            } finally {
                if (!verified) target.delete()
                dialog.dismiss()
                flow.release()
            }
            pending?.takeIf { verified }?.let { if (usable(activity)) offerInstall(activity, it) }
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            job.cancel()
            dialog.dismiss()
            toast(activity, "업데이트 받기를 취소했어요.")
        }
    }

    /** Re-check bytes, version and the installed signers even after visiting Android settings. */
    private fun offerInstall(activity: AppCompatActivity, ready: Pending) {
        if (!usable(activity) || !flow.acquire()) return
        activity.lifecycleScope.launch {
            var offered = false
            try {
                withContext(Dispatchers.IO) { UpdateVerifier.verify(activity, ready.file, ready.release) }
                if (!usable(activity)) return@launch
                if (!UpdatePolicy.mayInstall(DownloadService.hasPendingDownloads())) {
                    message(activity, "노래 받기가 끝난 뒤 설정에서 앱 업데이트를 눌러 주세요. 받은 업데이트 파일은 보관했어요.")
                    return@launch
                }
                val allowed = Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()
                offered = true
                val prompt = AlertDialog.Builder(activity).setTitle(if (allowed) "업데이트 설치 준비 완료" else "설치 허용이 필요해요")
                    .setMessage(if (allowed) "기존 앱을 지우지 않고 업데이트해요. 설치 중 앱이 닫힐 수 있어요.\nAndroid 설치 화면에서 직접 확인해 주세요."
                        else "노래저장에서 앱 업데이트를 설치하도록 허용해 주세요. 설정에서 돌아오면 설치를 이어갈 수 있어요.")
                    .setPositiveButton(if (allowed) "설치 화면 열기" else "설정 열기") { _, _ ->
                        if (!UpdatePolicy.mayInstall(DownloadService.hasPendingDownloads())) {
                            toast(activity, "노래 받기가 끝난 뒤 설치해 주세요.")
                        } else if (allowed) launchInstaller(activity, ready)
                        else runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.packageName))) }
                            .onFailure { message(activity, "설정 화면을 열지 못했어요. 휴대폰 설정의 앱 설치 허용을 확인해 주세요.") }
                    }
                    .setNegativeButton("나중에", null)
                    .setNeutralButton("업데이트 취소") { _, _ -> pending = null; ready.file.delete() }.create()
                showOwned(activity, prompt) { flow.release() }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) {
                pending = null; ready.file.delete()
                if (usable(activity)) message(activity, "업데이트 파일을 확인하지 못해 설치를 멈췄어요. 기존 앱을 지우지 말고 다시 받아 주세요.")
            } finally { if (!offered) flow.release() }
        }
    }

    private fun launchInstaller(activity: AppCompatActivity, ready: Pending) {
        try {
            val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", ready.file)
            if (DownloadService.hasPendingDownloads()) { toast(activity, "노래 받기가 끝난 뒤 설치해 주세요."); return }
            installerOpen = true
            activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            // Keep only the private APK until the platform installer has read it.
            pending = null
        } catch (error: Exception) { installerOpen = false; message(activity, "설치 화면을 열지 못했어요. 잠시 후 다시 시도해 주세요.") }
    }

    private fun showOwned(activity: AppCompatActivity, dialog: AlertDialog, dismissed: () -> Unit) {
        val observer = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { dialog.dismiss() }
        }
        dialog.setOnDismissListener {
            activity.lifecycle.removeObserver(observer)
            dismissed()
        }
        activity.lifecycle.addObserver(observer)
        dialog.show()
    }

    private fun message(activity: AppCompatActivity, text: String) {
        if (!activity.isFinishing && !activity.isDestroyed)
            AlertDialog.Builder(activity).setMessage(text).setPositiveButton("확인", null).show()
    }
    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
