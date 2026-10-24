package com.psave.tubesave

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.io.IOException
import java.util.concurrent.TimeUnit

class UpdateCheckWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return try {
            val release = UpdateManager.available(applicationContext) ?: return Result.success()
            val prefs = applicationContext.getSharedPreferences("tubesave_updates", Context.MODE_PRIVATE)
            if (prefs.getLong("notified_version", 0) >= release.version) return Result.success()
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
            val manager = NotificationManagerCompat.from(applicationContext)
            if (!manager.areNotificationsEnabled()) return Result.success()
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                NotificationChannel("app_updates", "앱 업데이트", NotificationManager.IMPORTANCE_DEFAULT))
            val intent = Intent(applicationContext, MainActivity::class.java)
                .putExtra(UpdateManager.EXTRA_CHECK_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(applicationContext, 5541, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(5541, NotificationCompat.Builder(applicationContext, "app_updates")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("새 버전이 있어요")
                .setContentText("눌러서 안전하게 업데이트해 주세요.")
                .setContentIntent(pending).setAutoCancel(true).build())
            prefs.edit().putLong("notified_version", release.version).apply()
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
          catch (error: IOException) { Result.retry() }
          catch (error: Exception) { android.util.Log.w("TubeSaveUpdate", "안전한 업데이트 정보가 아님", error); Result.success() }
    }

    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                .setInitialDelay(12, TimeUnit.HOURS).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("tubesave-app-update",
                ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
