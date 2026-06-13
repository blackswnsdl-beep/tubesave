package com.psave.tubesave

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 유튜브 "공유하기"로 들어온 텍스트를 받아 즉시 다운로드 서비스를 시작한다.
 * 화면을 띄우지 않고(투명 테마) 토스트만 보여준 뒤 바로 종료 — 어르신 조작 최소화.
 */
class ShareReceiverActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val url = extractYoutubeUrl(sharedText)

        if (url != null) {
            DownloadService.start(this, url)
            Toast.makeText(this, "노래를 받고 있어요. 잠시만 기다려 주세요!", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "유튜브 링크를 찾지 못했어요.", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    companion object {
        /** 공유 텍스트에서 유튜브 URL만 추출 (제목 등 다른 문구가 섞여 와도 OK) */
        fun extractYoutubeUrl(text: String): String? {
            val regex = Regex(
                "(https?://)?(www\\.|m\\.|music\\.)?(youtube\\.com/(watch\\?[^\\s]+|shorts/[^\\s]+)|youtu\\.be/[^\\s]+)"
            )
            return regex.find(text)?.value?.let {
                if (it.startsWith("http")) it else "https://$it"
            }
        }
    }
}
