package com.psave.tubesave

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 잠금화면에서도 라디오처럼 계속 재생되는 미디어 서비스.
 * MediaSessionService가 미디어 알림 + 잠금화면 컨트롤 + 오디오 포커스를 자동 처리한다.
 *
 * 취침 타이머도 여기서 관리한다 — Activity가 종료돼도(화면 잠금 등) 타이머가 유지되어야 하므로,
 * 재생을 소유한 이 서비스에 두는 것이 올바른 위치다.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        mediaSession = MediaSession.Builder(this, player).build()

        // 홈 위젯 갱신: 재생 상태/곡이 바뀌면 위젯 제목·아이콘 동기화(앱이 닫혀 있어도)
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                PlayerWidget.pushUpdate(
                    this@PlaybackService,
                    p.currentMediaItem?.mediaMetadata?.title?.toString(),
                    p.isPlaying
                )
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 취침 타이머 명령 처리
        if (intent?.action == ACTION_SLEEP) {
            scheduleSleep(intent.getIntExtra(EXTRA_MINUTES, 0))
            return START_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * 사용자가 최근앱 목록에서 앱을 밀어내면(모두 닫기) 호출된다.
     *
     * 기본 동작: 음악을 멈추고 서비스도 종료한다 → "모두 닫기 = 다 꺼짐"(어르신이
     * 이해하기 쉬움). "노래가 안 꺼진다"는 혼란을 막는다.
     *
     * 설정에서 '모두 닫기 후 재생'을 켠 경우에만, 재생 중이면 그대로 두어
     * 백그라운드에서 계속 듣게 한다(원하는 사람용).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        val keepPlaying = Prefs.keepPlayingInBackground(this) &&
            player != null && player.playWhenReady && player.mediaItemCount > 0
        if (!keepPlaying) {
            player?.pause()   // 음악 정지
            stopSelf()        // 서비스 종료 → 앱 완전 종료
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun scheduleSleep(minutes: Int) {
        sleepRunnable?.let { handler.removeCallbacks(it) }
        sleepRunnable = null
        if (minutes <= 0) return
        val r = Runnable { mediaSession?.player?.pause() }
        sleepRunnable = r
        handler.postDelayed(r, minutes * 60_000L)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        sleepRunnable?.let { handler.removeCallbacks(it) }
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    companion object {
        private const val ACTION_SLEEP = "com.psave.tubesave.SLEEP"
        private const val EXTRA_MINUTES = "minutes"

        /** 취침 타이머 설정(분). 0이면 해제. */
        fun setSleepTimer(context: Context, minutes: Int) {
            val i = Intent(context, PlaybackService::class.java)
                .setAction(ACTION_SLEEP)
                .putExtra(EXTRA_MINUTES, minutes)
            context.startService(i)
        }
    }
}
