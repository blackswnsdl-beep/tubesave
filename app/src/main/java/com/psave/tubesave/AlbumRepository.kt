package com.psave.tubesave

import android.content.Context
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 사용자가 만든 '앨범'(곡 모음) 하나. */
data class Album(val id: String, var name: String, val songs: MutableList<Song>)

/**
 * 여러 앨범을 prefs에 JSON으로 저장/관리한다.
 * 모든 변경 연산은 load → 수정 → save 후 최신 목록을 돌려주어 호출 측이 바로 반영할 수 있다.
 */
object AlbumRepository {

    private const val PREFS = "tubesave_prefs"
    private const val KEY = "albums_json"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): MutableList<Album> {
        val json = prefs(context).getString(KEY, null) ?: return mutableListOf()
        val albums = mutableListOf<Album>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val songs = mutableListOf<Song>()
                val s = o.getJSONArray("songs")
                for (j in 0 until s.length()) {
                    val so = s.getJSONObject(j)
                    songs += Song(so.getString("title"), so.getString("uri").toUri(), so.optLong("dur", 0L))
                }
                albums += Album(o.getString("id"), o.getString("name"), songs)
            }
        } catch (e: Exception) { /* 손상 시 빈 목록 */ }
        return albums
    }

    fun save(context: Context, albums: List<Album>) {
        val arr = JSONArray()
        for (a in albums) {
            val s = JSONArray()
            for (song in a.songs) {
                s.put(JSONObject().put("title", song.title).put("uri", song.uri.toString()).put("dur", song.durationMs))
            }
            arr.put(JSONObject().put("id", a.id).put("name", a.name).put("songs", s))
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    fun create(context: Context, name: String): MutableList<Album> {
        val list = load(context)
        list += Album(UUID.randomUUID().toString(), name.trim().ifBlank { "새 앨범" }, mutableListOf())
        save(context, list); return list
    }

    fun rename(context: Context, id: String, name: String): MutableList<Album> {
        val list = load(context)
        list.find { it.id == id }?.name = name.trim().ifBlank { "새 앨범" }
        save(context, list); return list
    }

    fun delete(context: Context, id: String): MutableList<Album> {
        val list = load(context).filterNot { it.id == id }.toMutableList()
        save(context, list); return list
    }

    /** 앨범 순서를 통째로 저장(드래그/버튼 이동 종료 시 1회). */
    fun setAlbumOrder(context: Context, ordered: List<Album>) = save(context, ordered)

    /** 선택한 곡들을 앨범에 추가. 한 곡은 한 앨범에만 속하므로 다른 앨범에서는 자동으로 빠진다. 추가된 개수 반환. */
    fun addSongs(context: Context, id: String, songs: List<Song>): Int {
        val list = load(context)
        val album = list.find { it.id == id } ?: return 0
        val incoming = songs.mapTo(HashSet()) { it.uri.toString() }
        // 단일 소속: 다른 앨범에 있던 같은 곡은 제거(실제 폴더 이동과 일관되게)
        for (a in list) if (a.id != id) a.songs.removeAll { it.uri.toString() in incoming }
        val have = album.songs.mapTo(HashSet()) { it.uri.toString() }
        var added = 0
        for (s in songs) if (have.add(s.uri.toString())) { album.songs += s; added++ }
        save(context, list); return added
    }

    fun removeSongs(context: Context, id: String, songs: List<Song>): MutableList<Album> {
        val rm = songs.mapTo(HashSet()) { it.uri.toString() }
        val list = load(context)
        list.find { it.id == id }?.songs?.removeAll { it.uri.toString() in rm }
        save(context, list); return list
    }

    /** 앨범 안 곡 순서를 통째로 저장. */
    fun setSongOrder(context: Context, id: String, ordered: List<Song>): MutableList<Album> {
        val list = load(context)
        list.find { it.id == id }?.songs?.apply { clear(); addAll(ordered) }
        save(context, list); return list
    }

    /** 파일이 삭제된 곡을 모든 앨범에서 제거. */
    fun purgeDeleted(context: Context, deleted: List<Song>) {
        if (deleted.isEmpty()) return
        val rm = deleted.mapTo(HashSet()) { it.uri.toString() }
        val list = load(context)
        var changed = false
        for (a in list) if (a.songs.removeAll { it.uri.toString() in rm }) changed = true
        if (changed) save(context, list)
    }

    /**
     * 라이브러리와 동기화하며 로드한다. 라이브러리에 있는 곡(liveUris)은 즉시 통과(IO 없음),
     * 없는 곡만 실제 파일 존재를 확인(폴더 변경 등 예외 케이스 대응)해 정리한다.
     * liveUris가 비어 있으면(권한 거부/초기 상태) 정리를 건너뛰어 데이터 보호.
     */
    fun loadSynced(context: Context, liveUris: Set<String>): MutableList<Album> {
        val list = load(context)
        if (liveUris.isEmpty()) return list
        var changed = false
        for (a in list) {
            if (a.songs.removeAll { s ->
                    val u = s.uri.toString()
                    u !in liveUris && !SongRepository.exists(context, s.uri)
                }) changed = true
        }
        if (changed) save(context, list)
        return list
    }
}
