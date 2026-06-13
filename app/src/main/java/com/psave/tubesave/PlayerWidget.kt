package com.psave.tubesave

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors

/**
 * 홈 화면 위젯: 제목 + 이전 / 재생·정지 / 다음.
 *
 * - 버튼은 이 리시버로 broadcast → MediaController로 세션에 명령.
 *   (서비스를 직접 startService 하지 않으므로 Android 8+ 백그라운드 시작 제한 크래시를 피함)
 * - 재생 상태가 바뀌면 PlaybackService가 pushUpdate()로 위젯을 갱신(앱이 닫혀 있어도).
 * - 위젯 쪽이 실패해도 본체 재생엔 영향이 없도록 모든 컨트롤을 try/catch로 격리.
 */
class PlayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = build(context, null, false)
        for (id in ids) manager.updateAppWidget(id, views)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PLAY_PAUSE, ACTION_NEXT, ACTION_PREV -> {
                val pending = goAsync()
                control(context, intent.action!!) { pending.finish() }
            }
            else -> super.onReceive(context, intent)
        }
    }

    private fun control(context: Context, action: String, done: () -> Unit) {
        try {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            future.addListener({
                try {
                    val c = future.get()
                    when (action) {
                        ACTION_PLAY_PAUSE -> if (c.isPlaying) c.pause() else c.play()
                        ACTION_NEXT -> c.seekToNext()
                        ACTION_PREV -> c.seekToPrevious()
                    }
                    MediaController.releaseFuture(future)
                } catch (e: Exception) { /* 무시 */ }
                done()
            }, MoreExecutors.directExecutor())
        } catch (e: Exception) { done() }
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.psave.tubesave.widget.PLAY_PAUSE"
        const val ACTION_NEXT = "com.psave.tubesave.widget.NEXT"
        const val ACTION_PREV = "com.psave.tubesave.widget.PREV"

        private fun pi(context: Context, action: String): PendingIntent {
            val i = Intent(context, PlayerWidget::class.java).setAction(action)
            return PendingIntent.getBroadcast(
                context, action.hashCode(), i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        private fun build(context: Context, title: String?, playing: Boolean): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_player)
            v.setTextViewText(R.id.wTitle, title ?: context.getString(R.string.no_song))
            v.setImageViewResource(
                R.id.wPlayPause,
                if (playing) R.drawable.ic_pause_white else R.drawable.ic_play_white
            )
            v.setOnClickPendingIntent(R.id.wPrev, pi(context, ACTION_PREV))
            v.setOnClickPendingIntent(R.id.wPlayPause, pi(context, ACTION_PLAY_PAUSE))
            v.setOnClickPendingIntent(R.id.wNext, pi(context, ACTION_NEXT))
            return v
        }

        /** PlaybackService에서 재생 상태가 바뀔 때 호출 — 놓여 있는 모든 위젯 갱신. */
        fun pushUpdate(context: Context, title: String?, playing: Boolean) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
            if (ids.isEmpty()) return
            val views = build(context, title, playing)
            for (id in ids) manager.updateAppWidget(id, views)
        }
    }
}
