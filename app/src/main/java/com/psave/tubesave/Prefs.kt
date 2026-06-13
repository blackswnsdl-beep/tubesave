package com.psave.tubesave

import android.content.Context
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject

/**
 * 앱 설정을 한 곳에서 관리한다(키 문자열 중복 제거 → 유지보수성).
 * 모든 화면/서비스가 이 객체만 통해 설정을 읽고 쓴다.
 */
object Prefs {
    private const val NAME = "tubesave_prefs"
    private fun p(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // 반복 모드
    fun repeatMode(c: Context) = p(c).getInt("repeat_mode", Player.REPEAT_MODE_ALL)
    fun setRepeatMode(c: Context, m: Int) = p(c).edit().putInt("repeat_mode", m).apply()

    // 셔플
    fun shuffle(c: Context) = p(c).getBoolean("shuffle", false)
    fun setShuffle(c: Context, on: Boolean) = p(c).edit().putBoolean("shuffle", on).apply()

    // 정렬(전체 곡): false=최신순, true=이름순
    fun sortByName(c: Context) = p(c).getBoolean("sort_by_name", false)
    fun setSortByName(c: Context, on: Boolean) = p(c).edit().putBoolean("sort_by_name", on).apply()

    // 관리자 알림(텔레그램)
    fun tgToken(c: Context): String = p(c).getString("tg_token", "") ?: ""
    fun tgChat(c: Context): String = p(c).getString("tg_chat", "") ?: ""
    fun setTelegram(c: Context, token: String, chat: String) =
        p(c).edit().putString("tg_token", token).putString("tg_chat", chat).apply()

    // 즐겨찾기 (uri 문자열 집합)
    fun favorites(c: Context): Set<String> = p(c).getStringSet("favorites", emptySet()) ?: emptySet()

    fun toggleFavorite(c: Context, uri: String): Boolean {
        val set = favorites(c).toMutableSet()
        val nowFav = if (uri in set) { set.remove(uri); false } else { set.add(uri); true }
        p(c).edit().putStringSet("favorites", set).apply()
        return nowFav
    }

    fun pruneFavorites(c: Context, existing: Set<String>) {
        val set = favorites(c)
        val kept = set.intersect(existing)
        if (kept.size != set.size) p(c).edit().putStringSet("favorites", kept.toSet()).apply()
    }

    // 곡 이름 바꾸기 (uri → 사용자 지정 제목). 파일은 그대로, 표시 이름만 덮어씀.
    fun titleOverrides(c: Context): Map<String, String> {
        val json = p(c).getString("title_overrides", null) ?: return emptyMap()
        return try {
            val o = JSONObject(json)
            val m = HashMap<String, String>(o.length())
            for (k in o.keys()) m[k] = o.getString(k)
            m
        } catch (e: Exception) { emptyMap() }
    }

    /** 빈 문자열을 주면 지정 이름 해제(원래 파일명으로 복귀). */
    fun setTitleOverride(c: Context, uri: String, title: String) {
        val m = titleOverrides(c).toMutableMap()
        val t = title.trim()
        if (t.isEmpty()) m.remove(uri) else m[uri] = t
        p(c).edit().putString("title_overrides", JSONObject(m as Map<*, *>).toString()).apply()
    }

    fun pruneTitleOverrides(c: Context, existing: Set<String>) {
        val m = titleOverrides(c)
        val kept = m.filterKeys { it in existing }
        if (kept.size != m.size)
            p(c).edit().putString("title_overrides", JSONObject(kept as Map<*, *>).toString()).apply()
    }

    // 이어듣기 (마지막 재생목록 + 위치). queueJson은 [{uri,title},...].
    fun savePlayback(c: Context, queueJson: String, index: Int, posMs: Long) =
        p(c).edit().putString("last_queue", queueJson).putInt("last_index", index)
            .putLong("last_pos", posMs).apply()
    fun lastQueueJson(c: Context): String = p(c).getString("last_queue", "") ?: ""
    fun lastIndex(c: Context): Int = p(c).getInt("last_index", 0)
    fun lastPositionMs(c: Context): Long = p(c).getLong("last_pos", 0L)

    // 첫 실행 사용법 안내
    fun guideSeen(c: Context) = p(c).getBoolean("guide_seen", false)
    fun setGuideSeen(c: Context, v: Boolean) = p(c).edit().putBoolean("guide_seen", v).apply()

    // yt-dlp 코어 선제 업데이트 여부 (APK에 들어있는 yt-dlp 바이너리는 시간이 지나면 낡아
    // 유튜브가 막으므로, 최초 1회는 다운로드 전에 최신으로 갱신한다)
    fun ytdlpUpdated(c: Context) = p(c).getBoolean("ytdlp_updated", false)
    fun setYtdlpUpdated(c: Context, v: Boolean) = p(c).edit().putBoolean("ytdlp_updated", v).apply()

    // 원격설정으로 갱신되는 yt-dlp 추출 옵션(봇 차단 회피 클라이언트). 기본은 android_vr.
    fun extractorArgs(c: Context): String =
        p(c).getString("extractor_args", "youtube:player_client=android_vr") ?: "youtube:player_client=android_vr"
    fun setExtractorArgs(c: Context, v: String) = p(c).edit().putString("extractor_args", v).apply()

    // 이미 받은 영상(중복 다운로드 방지): videoId → 저장된 파일명
    fun downloadedName(c: Context, videoId: String): String? {
        val json = p(c).getString("downloaded_ids", null) ?: return null
        return try { JSONObject(json).optString(videoId, "").ifEmpty { null } } catch (e: Exception) { null }
    }
    fun putDownloaded(c: Context, videoId: String, name: String) {
        val o = try { JSONObject(p(c).getString("downloaded_ids", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        o.put(videoId, name)
        p(c).edit().putString("downloaded_ids", o.toString()).apply()
    }

    // 백업: 앨범 + 즐겨찾기 + 바꾼 이름을 JSON 한 덩어리로. (노래 파일은 음악 폴더에 그대로 남음)
    fun exportData(c: Context): String {
        val o = JSONObject()
        o.put("v", 1)
        o.put("albums", try { JSONArray(p(c).getString("albums_json", "[]") ?: "[]") } catch (e: Exception) { JSONArray() })
        o.put("favorites", JSONArray(favorites(c).toList()))
        o.put("titles", try { JSONObject(p(c).getString("title_overrides", "{}") ?: "{}") } catch (e: Exception) { JSONObject() })
        return o.toString()
    }

    /** 복원. 형식이 깨졌으면 false 반환하고 기존 데이터는 건드리지 않음. */
    fun importData(c: Context, json: String): Boolean = try {
        val o = JSONObject(json)
        val e = p(c).edit()
        if (o.has("albums")) e.putString("albums_json", o.getJSONArray("albums").toString())
        if (o.has("favorites")) {
            val arr = o.getJSONArray("favorites")
            val set = HashSet<String>(arr.length())
            for (i in 0 until arr.length()) set.add(arr.getString(i))
            e.putStringSet("favorites", set)
        }
        if (o.has("titles")) e.putString("title_overrides", o.getJSONObject("titles").toString())
        e.apply(); true
    } catch (ex: Exception) { false }
}
