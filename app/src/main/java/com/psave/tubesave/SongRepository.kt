package com.psave.tubesave

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.File

data class Song(val title: String, val uri: Uri, val durationMs: Long = 0L) {
    val durationText: String
        get() {
            val totalSec = durationMs / 1000
            return "%d:%02d".format(totalSec / 60, totalSec % 60)
        }
}

object SongRepository {

    private const val PREFS = "tubesave_prefs"
    private const val KEY_FOLDER_URI = "folder_uri"
    const val SUB_DIR = "TubeSave"

    fun getCustomFolder(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FOLDER_URI, null)?.toUri()

    fun setCustomFolder(context: Context, uri: Uri?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .apply { if (uri == null) remove(KEY_FOLDER_URI) else putString(KEY_FOLDER_URI, uri.toString()) }
            .apply()
    }

    fun folderLabel(context: Context): String {
        val custom = getCustomFolder(context) ?: return "기본 (음악/TubeSave)"
        return DocumentFile.fromTreeUri(context, custom)?.name ?: "지정 폴더"
    }

    /** 완성된 mp3 임시파일을 최종 저장 위치로 이동, 재생용 Uri 반환 */
    fun saveToLibrary(context: Context, tempFile: File): Uri? {
        val custom = getCustomFolder(context)
        return if (custom != null) saveToCustomFolder(context, tempFile, custom)
        else saveToDefaultMusic(context, tempFile)
    }

    private fun saveToCustomFolder(context: Context, temp: File, treeUri: Uri): Uri? {
        val dir = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        dir.findFile(temp.name)?.delete()
        val dest = dir.createFile("audio/mpeg", temp.name) ?: return null
        context.contentResolver.openOutputStream(dest.uri)?.use { out ->
            temp.inputStream().use { it.copyTo(out) }
        } ?: return null
        return dest.uri
    }

    private fun saveToDefaultMusic(context: Context, temp: File): Uri? {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, temp.name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + SUB_DIR)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
            ) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                temp.inputStream().use { it.copyTo(out) }
            } ?: return null
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), SUB_DIR
            ).apply { mkdirs() }
            val dest = File(dir, temp.name)
            temp.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
            return Uri.fromFile(dest)
        }
    }

    /** 앨범 폴더명으로 안전하게 쓸 수 있도록 정리 */
    fun sanitizeAlbumName(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().ifEmpty { "앨범" }

    /**
     * 곡 파일을 앨범 폴더(albumName)로 이동한다. albumName이 null/빈값이면 기본 폴더(루트)로 되돌림.
     * 기본 저장(음악/TubeSave, MediaStore, Android10+)에서만 동작하며, 이때 RELATIVE_PATH만 바꾸면
     * 파일이 실제로 이동되고 **재생용 URI는 그대로 유지**된다(즐겨찾기·앨범 메타데이터 보존).
     * 사용자 지정(SAF) 폴더나 구형 안드로이드에선 물리 이동을 건너뛰고 false 반환(앱 내 분류만 유지).
     */
    fun moveToAlbumFolder(context: Context, uri: Uri, albumName: String?): Boolean {
        if (getCustomFolder(context) != null || Build.VERSION.SDK_INT < 29) return false
        val sub = if (albumName.isNullOrBlank()) "" else "/" + sanitizeAlbumName(albumName)
        val rel = Environment.DIRECTORY_MUSIC + "/" + SUB_DIR + sub + "/"
        return try {
            val values = ContentValues().apply { put(MediaStore.Audio.Media.RELATIVE_PATH, rel) }
            context.contentResolver.update(uri, values, null, null) > 0
        } catch (e: Exception) { false }
    }

    /** 저장된 곡 목록 (최신순, 재생시간 포함). IO 스레드에서 호출할 것 */
    fun listSongs(context: Context): List<Song> {
        val custom = getCustomFolder(context)
        if (custom != null) {
            val dir = DocumentFile.fromTreeUri(context, custom) ?: return emptyList()
            return dir.listFiles()
                .filter { it.isFile && (it.name ?: "").endsWith(".mp3", ignoreCase = true) }
                .sortedByDescending { it.lastModified() }
                .map { f ->
                    Song(
                        f.name!!.removeSuffix(".mp3"),
                        f.uri,
                        cachedDuration(context, f.uri, f.lastModified())
                    )
                }
        }

        if (Build.VERSION.SDK_INT >= 29) {
            val songs = mutableListOf<Song>()
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DURATION
            )
            context.contentResolver.query(
                collection, projection,
                MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?",
                arrayOf("%" + Environment.DIRECTORY_MUSIC + "/" + SUB_DIR + "%"),
                MediaStore.Audio.Media.DATE_ADDED + " DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, c.getLong(idCol))
                    songs += Song(c.getString(nameCol).removeSuffix(".mp3"), uri, c.getLong(durCol))
                }
            }
            return songs
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), SUB_DIR
            )
            return dir.listFiles { f -> f.extension.equals("mp3", true) }
                ?.sortedByDescending { it.lastModified() }
                ?.map {
                    val uri = Uri.fromFile(it)
                    Song(it.nameWithoutExtension, uri, cachedDuration(context, uri, it.lastModified()))
                }
                ?: emptyList()
        }
    }

    /** uri가 실제로 존재하고 열 수 있는지 확인 (재생목록 무결성 검사용) */
    fun exists(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { true } ?: false
    } catch (e: Exception) {
        // file:// 경로는 직접 확인
        uri.path?.let { File(it).exists() } ?: false
    }

    /** (uri + 수정시각) 키로 재생시간을 캐시. 변경 없으면 디코딩 생략 */
    private fun cachedDuration(context: Context, uri: Uri, lastModified: Long): Long {
        val prefs = context.getSharedPreferences("tubesave_dur_cache", Context.MODE_PRIVATE)
        val key = uri.toString() + "|" + lastModified
        val hit = prefs.getLong(key, -1L)
        if (hit >= 0L) return hit
        val dur = probeDuration(context, uri)
        prefs.edit().putLong(key, dur).apply()
        return dur
    }

    private fun probeDuration(context: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try { r.release() } catch (e: Exception) {}
        }
    }

    /**
     * 파일 삭제. 성공 시 true.
     * Android 11+에서 권한 보강이 필요하면 SecurityException이 던져지므로
     * 호출 측(MainActivity)에서 MediaStore.createDeleteRequest 로 처리한다.
     */
    fun deleteSong(context: Context, song: Song): Boolean {
        runCatching { artFile(context, song.uri).delete() }   // 앨범 커버 함께 정리
        val custom = getCustomFolder(context)
        return when {
            custom != null ->
                DocumentFile.fromSingleUri(context, song.uri)?.delete() ?: false
            Build.VERSION.SDK_INT >= 29 ->
                context.contentResolver.delete(song.uri, null, null) > 0
            else -> {
                val f = File(song.uri.path ?: return false)
                val ok = f.delete()
                if (ok) MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), null, null)
                ok
            }
        }
    }

    /** 같은 이름(제목)의 곡이 이미 라이브러리에 있는지 — 중복 다운로드 방지용. name은 확장자 없는 제목. */
    fun existsByName(context: Context, name: String): Boolean {
        val custom = getCustomFolder(context)
        if (custom != null) {
            val dir = DocumentFile.fromTreeUri(context, custom) ?: return false
            return dir.findFile("$name.mp3") != null
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            context.contentResolver.query(
                collection, arrayOf(MediaStore.Audio.Media._ID),
                MediaStore.Audio.Media.DISPLAY_NAME + " = ? AND " + MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?",
                arrayOf("$name.mp3", "%" + Environment.DIRECTORY_MUSIC + "/" + SUB_DIR + "%"), null
            )?.use { return it.moveToFirst() }
            return false
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), SUB_DIR)
        return File(dir, "$name.mp3").exists()
    }

    // ── 앨범 커버(잠금화면 표시용): 다운로드 시 1회 추출해 앱 내부에 jpg로 보관 ──
    private fun artDir(context: Context): File =
        File(context.filesDir, "art").apply { mkdirs() }

    fun artFile(context: Context, uri: Uri): File =
        File(artDir(context), Integer.toHexString(uri.toString().hashCode()) + ".jpg")

    /** mp3에 임베드된 썸네일을 추출해 저장(있을 때만). 다운로드 직후 IO 스레드에서 호출. */
    fun saveArtwork(context: Context, uri: Uri, sourceMp3: File) {
        runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(sourceMp3.absolutePath)
                r.embeddedPicture?.let { bytes -> artFile(context, uri).writeBytes(bytes) }
            } finally { runCatching { r.release() } }
        }
    }

    /** 저장된 커버 jpg의 Uri(없으면 null). 재생 시 artworkUri로 사용. */
    fun artUri(context: Context, uri: Uri): Uri? {
        val f = artFile(context, uri)
        return if (f.exists()) Uri.fromFile(f) else null
    }

    // ── 휴지통 (앱 내부 보관 → 일정 기간 후 자동 삭제, 그 전엔 복원 가능) ──
    data class TrashEntry(val id: String, val name: String, val deletedAt: Long, val durationMs: Long)

    private const val KEY_TRASH = "trash_index"
    private fun trashDir(context: Context): File = File(context.filesDir, "trash").apply { mkdirs() }

    fun listTrash(context: Context): List<TrashEntry> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TRASH, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                TrashEntry(o.getString("id"), o.getString("name"), o.getLong("at"), o.optLong("dur", 0L))
            }.sortedByDescending { it.deletedAt }
        } catch (e: Exception) { emptyList() }
    }

    private fun writeTrash(context: Context, list: List<TrashEntry>) {
        val arr = org.json.JSONArray()
        list.forEach {
            arr.put(org.json.JSONObject().put("id", it.id).put("name", it.name).put("at", it.deletedAt).put("dur", it.durationMs))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TRASH, arr.toString()).apply()
    }

    /** 원본 곡 파일을 휴지통으로 복사(삭제 전 단계). 성공 시 항목 반환. */
    fun copyToTrash(context: Context, song: Song): TrashEntry? {
        val id = System.currentTimeMillis().toString() + "_" + Integer.toHexString(song.uri.toString().hashCode())
        val dest = File(trashDir(context), "$id.mp3")
        return try {
            val copied = context.contentResolver.openInputStream(song.uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }; true
            } ?: song.uri.path?.let { p ->
                File(p).takeIf { it.exists() }?.copyTo(dest, overwrite = true) != null
            } ?: false
            if (!copied || !dest.exists() || dest.length() == 0L) { dest.delete(); return null }
            val entry = TrashEntry(id, song.title, System.currentTimeMillis(), song.durationMs)
            writeTrash(context, listTrash(context).toMutableList().apply { add(0, entry) })
            entry
        } catch (e: Exception) { dest.delete(); null }
    }

    /** 휴지통 항목 제거(파일 + 색인). 롤백/복원완료/개별삭제에서 사용. */
    fun removeTrashEntry(context: Context, id: String) {
        File(trashDir(context), "$id.mp3").delete()
        writeTrash(context, listTrash(context).filterNot { it.id == id })
    }

    /** 휴지통 → 라이브러리로 되돌리기. */
    fun restoreFromTrash(context: Context, entry: TrashEntry): Boolean {
        val src = File(trashDir(context), "${entry.id}.mp3")
        if (!src.exists()) { removeTrashEntry(context, entry.id); return false }
        // saveToLibrary는 temp.name을 파일명으로 사용 → 원래 이름으로 임시 복사본을 만든다.
        val named = File(context.cacheDir, entry.name + ".mp3")
        return try {
            src.copyTo(named, overwrite = true)
            val savedUri = saveToLibrary(context, named) ?: return false
            saveArtwork(context, savedUri, named)   // 임베드 썸네일에서 커버 재생성
            removeTrashEntry(context, entry.id)
            true
        } catch (e: Exception) { false } finally { named.delete() }
    }

    /** 보관기간(일)을 지난 항목 자동 삭제. 삭제 개수 반환. */
    fun purgeOldTrash(context: Context, days: Int): Int {
        val cutoff = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000
        val old = listTrash(context).filter { it.deletedAt < cutoff }
        if (old.isEmpty()) return 0
        old.forEach { File(trashDir(context), "${it.id}.mp3").delete() }
        writeTrash(context, listTrash(context).filterNot { e -> old.any { it.id == e.id } })
        return old.size
    }

    fun emptyTrash(context: Context) {
        trashDir(context).listFiles()?.forEach { it.delete() }
        writeTrash(context, emptyList())
    }
}
