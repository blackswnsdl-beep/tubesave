package com.psave.tubesave

import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.net.Uri
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.Manifest
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        /** 앱 화면이 보이는 동안 true → 받기 결과를 알림 대신 앱 팝업으로 보여주기 위함 */
        @Volatile var inForeground = false
    }


    private enum class Tab { LIBRARY, ALBUMS, TRASH }
    private enum class FilterMode { ALL, FAV, UNFILED }
    private var filterMode = FilterMode.ALL

    private var tab = Tab.LIBRARY
    private var librarySongs: List<Song> = emptyList()
    private var albums: MutableList<Album> = mutableListOf()
    private var openAlbumId: String? = null
    private var favorites: Set<String> = emptySet()
    private var searchQuery: String = ""
    private var titleOverrides: Map<String, String> = emptyMap()

    private lateinit var songAdapter: SongAdapter
    private lateinit var albumAdapter: AlbumAdapter
    private lateinit var trashAdapter: TrashAdapter
    private lateinit var songList: RecyclerView
    private var trashItems: List<SongRepository.TrashEntry> = emptyList()

    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnRepeat: Button
    private lateinit var seekBar: SeekBar
    private lateinit var timeCurrent: TextView
    private lateinit var timeTotal: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var tabLibrary: Button
    private lateinit var tabAlbums: Button
    private lateinit var tabTrash: Button
    private lateinit var btnEmptyTrash: View
    private lateinit var selectionBar: View
    private lateinit var btnAddToPlaylist: Button
    private lateinit var btnDeleteSel: Button
    private lateinit var btnRenameSel: Button
    private lateinit var selCount: TextView
    private lateinit var searchRow: View
    private lateinit var searchInput: EditText
    private lateinit var emptyView: TextView
    private lateinit var btnSelectMode: View
    private lateinit var btnSearch: View
    private lateinit var albumBar: View
    private lateinit var btnNewAlbum: View
    private lateinit var btnBackToAlbums: View
    private lateinit var albumDetailTitle: TextView
    private lateinit var btnPlayAlbum: View

    private var userSeeking = false
    private var updateFlowActive = false
    private val handler = Handler(Looper.getMainLooper())

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = controllerFuture?.takeIf { it.isDone }?.get()

    private var player: PlayerDialog? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshAll() }

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            SongRepository.setCustomFolder(this, uri)
            refreshAll()
        }

    private var pendingDeleteSongs: List<Song> = emptyList()
    private var pendingTrash: List<SongRepository.TrashEntry> = emptyList()
    private val TRASH_RETENTION_DAYS = 7   // 휴지통 보관 기간
    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                AlbumRepository.purgeDeleted(this, pendingDeleteSongs)
                toast("${pendingDeleteSongs.size}곡을 휴지통으로 옮겼어요")
            } else {
                // 사용자가 시스템 삭제 확인을 취소 → 원본이 남아 있으므로 휴지통 복사본 정리(중복 방지)
                pendingTrash.forEach { SongRepository.removeTrashEntry(this, it.id) }
            }
            pendingDeleteSongs = emptyList()
            pendingTrash = emptyList()
            exitSelection()
            refreshAll()
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try { contentResolver.openOutputStream(uri)?.use { it.write(Prefs.exportData(this@MainActivity).toByteArray()) }; true }
                    catch (e: Exception) { false }
                }
                toast(if (ok) "백업을 저장했어요." else "백업을 저장하지 못했어요.")
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) {
                    try { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } } catch (e: Exception) { null }
                }
                val ok = text != null && Prefs.importData(this@MainActivity, text)
                toast(if (ok) "복원했어요." else "복원하지 못했어요. 올바른 백업 파일인지 확인해 주세요.")
                if (ok) refreshAll()
            }
        }

    private val doneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshAll()   // 목록 새로고침 (사용자 안내는 결과 팝업이 담당)
        }
    }

    /** 받기 결과(완료/이미 받음/실패 등)를 앱 화면 팝업으로 보여준다. */
    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val title = intent?.getStringExtra(DownloadService.EXTRA_TITLE) ?: "안내"
            val msg = intent?.getStringExtra(DownloadService.EXTRA_TEXT) ?: ""
            val promptUpdate = intent?.getBooleanExtra(DownloadService.EXTRA_PROMPT_UPDATE, false) ?: false
            val b = AlertDialog.Builder(this@MainActivity).setTitle(title).setMessage(msg)
            if (promptUpdate) b.setPositiveButton("업데이트") { _, _ -> showUpdatePrompt() }.setNegativeButton("닫기", null)
            else b.setPositiveButton("확인", null)
            b.show()
        }
    }

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { showUpdatePrompt() }
    }

    /** 다운로드 진행현황을 앱 화면 상단 진행 띠에 표시 */
    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val box = findViewById<View>(R.id.progressBox)
            val active = intent?.getBooleanExtra(DownloadService.EXTRA_ACTIVE, false) ?: false
            if (!active) { box.visibility = View.GONE; return }
            val text = intent?.getStringExtra(DownloadService.EXTRA_TEXT) ?: "노래 받는 중..."
            val percent = intent?.getIntExtra(DownloadService.EXTRA_PERCENT, -1) ?: -1
            findViewById<TextView>(R.id.progressText).text = text
            val bar = findViewById<ProgressBar>(R.id.progressBar)
            if (percent < 0) bar.isIndeterminate = true
            else { bar.isIndeterminate = false; bar.progress = percent }
            box.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupList()
        setupControls()
        switchTab(Tab.LIBRARY)
        requestNeededPermissions()
        lifecycleScope.launch(Dispatchers.IO) { SongRepository.purgeOldTrash(this@MainActivity, TRASH_RETENTION_DAYS) }
        UpdateManager.checkAsync(this)   // 원격설정 갱신 + 새 버전 있으면 업데이트 안내
        maybePromptUpdateFromIntent(intent)
        if (!Prefs.guideSeen(this)) { Prefs.setGuideSeen(this, true); handler.post { showGuide() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybePromptUpdateFromIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        inForeground = true
        // 앱이 앞으로 올 때마다 원격설정(player_client) 최신화 — 콜드 재시작이 아니어도
        // 카나리가 바꿔둔 값을 받아 반영한다(가벼운 GET, 실패해도 무시).
        lifecycleScope.launch(Dispatchers.IO) { runCatching { UpdateManager.refreshConfig(applicationContext) } }
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { f ->
            f.addListener({
                val c = f.get()
                c.addListener(playerListener)
                c.repeatMode = Prefs.repeatMode(this)
                c.shuffleModeEnabled = Prefs.shuffle(this)
                restorePlayback(c)
                syncPlayerUi(c)
            }, MoreExecutors.directExecutor())
        }
        ContextCompat.registerReceiver(
            this, doneReceiver, IntentFilter(DownloadService.ACTION_DONE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, updateReceiver, IntentFilter(DownloadService.ACTION_NEEDS_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, progressReceiver, IntentFilter(DownloadService.ACTION_PROGRESS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, resultReceiver, IntentFilter(DownloadService.ACTION_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshAll()
        startProgressUpdates()
    }

    override fun onStop() {
        inForeground = false
        savePlayback()
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(doneReceiver)
        unregisterReceiver(updateReceiver)
        unregisterReceiver(progressReceiver)
        unregisterReceiver(resultReceiver)
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            songAdapter.selectionMode -> exitSelection()
            searchRow.visibility == View.VISIBLE -> toggleSearch(false)
            inAlbumDetail() -> backToAlbums()
            else -> super.onBackPressed()
        }
    }

    private fun bindViews() {
        songList = findViewById(R.id.songList)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnRepeat = findViewById(R.id.btnRepeat)
        seekBar = findViewById(R.id.seekBar)
        timeCurrent = findViewById(R.id.timeCurrent)
        timeTotal = findViewById(R.id.timeTotal)
        nowPlaying = findViewById(R.id.nowPlaying)
        tabLibrary = findViewById(R.id.tabLibrary)
        tabAlbums = findViewById(R.id.tabPlaylist)
        tabTrash = findViewById(R.id.tabTrash)
        btnEmptyTrash = findViewById(R.id.btnEmptyTrash)
        selectionBar = findViewById(R.id.selectionBar)
        btnAddToPlaylist = findViewById(R.id.btnAddToPlaylist)
        btnDeleteSel = findViewById(R.id.btnDeleteSel)
        btnRenameSel = findViewById(R.id.btnRenameSel)
        selCount = findViewById(R.id.selCount)
        searchRow = findViewById(R.id.searchRow)
        searchInput = findViewById(R.id.searchInput)
        emptyView = findViewById(R.id.emptyView)
        btnSelectMode = findViewById(R.id.btnSelectMode)
        btnSearch = findViewById(R.id.btnSearch)
        albumBar = findViewById(R.id.albumBar)
        btnNewAlbum = findViewById(R.id.btnNewAlbum)
        btnBackToAlbums = findViewById(R.id.btnBackToAlbums)
        albumDetailTitle = findViewById(R.id.albumDetailTitle)
        btnPlayAlbum = findViewById(R.id.btnPlayAlbum)
    }

    private fun setupList() {
        songAdapter = SongAdapter(
            onTap = ::onSongTapped,
            onLongPress = ::enterSelection,
            onCheckChanged = ::updateSelectionBar,
            onStartDrag = { vh -> itemTouchHelper.startDrag(vh) },
            onMove = ::moveSongInAlbum,
            onToggleFav = ::toggleFavorite,
            onDelete = { song -> deleteFiles(listOf(song)) }    // 스와이프→삭제 버튼 탭
        )
        albumAdapter = AlbumAdapter(
            onOpen = { openAlbum(it.id) },
            onMore = ::showAlbumMenu,
            onMoveUp = { moveAlbum(it, it - 1) },
            onMoveDown = { moveAlbum(it, it + 1) },
            onStartDrag = { vh -> itemTouchHelper.startDrag(vh) },
            onDelete = ::showDeleteAlbumDialog            // 스와이프→삭제 버튼 탭
        )
        trashAdapter = TrashAdapter(
            onRestore = ::restoreTrash,
            onDelete = ::deleteTrashOne
        )
        songList.layoutManager = LinearLayoutManager(this)
        songList.adapter = songAdapter
        songList.setHasFixedSize(true)
        itemTouchHelper.attachToRecyclerView(songList)
    }

    private fun setupControls() {
        btnSelectMode.setOnClickListener {
            if (songAdapter.selectionMode) exitSelection() else enterSelectionEmpty()
        }
        btnSearch.setOnClickListener { toggleSearch(searchRow.visibility != View.VISIBLE) }
        findViewById<View>(R.id.btnSearchClear).setOnClickListener { searchInput.text.clear() }
        searchInput.addTextChangedListener { searchQuery = it?.toString().orEmpty(); renderList() }

        btnRepeat.setOnClickListener { cycleRepeatMode() }
        seekBar.setOnSeekBarChangeListener(seekListener(seekBar) { timeCurrent.text = it })

        tabLibrary.setOnClickListener { switchTab(Tab.LIBRARY) }
        tabAlbums.setOnClickListener { switchTab(Tab.ALBUMS) }
        tabTrash.setOnClickListener { switchTab(Tab.TRASH) }
        btnEmptyTrash.setOnClickListener { confirmEmptyTrash() }
        findViewById<View>(R.id.filterAll).setOnClickListener { setFilter(FilterMode.ALL) }
        findViewById<View>(R.id.filterFav).setOnClickListener { setFilter(FilterMode.FAV) }
        findViewById<View>(R.id.filterUnfiled).setOnClickListener { setFilter(FilterMode.UNFILED) }

        btnNewAlbum.setOnClickListener { showCreateAlbumDialog() }
        btnBackToAlbums.setOnClickListener { backToAlbums() }
        btnPlayAlbum.setOnClickListener { if (currentAlbum() != null) playSongs(shownList(), 0) }

        findViewById<View>(R.id.btnOpenYoutube).setOnClickListener { openYoutube() }
        findViewById<View>(R.id.btnPasteDownload).setOnClickListener { pasteAndDownload() }
        findViewById<View>(R.id.btnCancelDownload).setOnClickListener {
            DownloadService.cancel(this)
            findViewById<View>(R.id.progressBox).visibility = View.GONE
        }

        val linkInput: EditText = findViewById(R.id.linkInput)
        findViewById<View>(R.id.btnDownload).setOnClickListener {
            val url = ShareReceiverActivity.extractYoutubeUrl(linkInput.text.toString())
            if (url == null) toast("유튜브 링크가 아니에요.")
            else {
                DownloadService.start(this, url)
                linkInput.text.clear()
                toast("노래를 받고 있어요!")
            }
        }

        btnPlayPause.setOnClickListener { togglePlay() }
        findViewById<View>(R.id.btnPrev).setOnClickListener { controller?.seekToPrevious() }
        findViewById<View>(R.id.btnNext).setOnClickListener { controller?.seekToNext() }
        nowPlaying.setOnClickListener { openPlayer() }

        btnAddToPlaylist.text = "앨범에 추가"
        findViewById<View>(R.id.btnSelectAll).setOnClickListener { songAdapter.selectAll(); updateSelectionBar() }
        findViewById<View>(R.id.btnPlaySel).setOnClickListener { playSongs(songAdapter.selectedSongs(), 0) }
        btnAddToPlaylist.setOnClickListener { showAddToAlbumDialog(songAdapter.selectedSongs()) }
        btnDeleteSel.setOnClickListener { confirmDelete(songAdapter.selectedSongs()) }
        btnRenameSel.setOnClickListener { renameSelected() }
        findViewById<View>(R.id.btnCancelSel).setOnClickListener { exitSelection() }
        findViewById<View>(R.id.btnFolder).setOnClickListener { showSettingsDialog() }
    }

    // ── 상태 헬퍼 ─────────────────────────────────────────
    private fun inAlbumList() = tab == Tab.ALBUMS && openAlbumId == null
    private fun inAlbumDetail() = tab == Tab.ALBUMS && openAlbumId != null
    private fun currentAlbum(): Album? = albums.find { it.id == openAlbumId }

    private fun switchTab(t: Tab) {
        tab = t
        openAlbumId = null
        exitSelection()
        if (searchRow.visibility == View.VISIBLE) toggleSearch(false)
        applyTabVisuals()
        renderList()
        if (t == Tab.TRASH) loadTrash()
    }

    private fun loadTrash() {
        lifecycleScope.launch {
            trashItems = withContext(Dispatchers.IO) { SongRepository.listTrash(this@MainActivity) }
            if (tab == Tab.TRASH) renderList()
        }
    }

    private fun restoreTrash(entry: SongRepository.TrashEntry) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { SongRepository.restoreFromTrash(this@MainActivity, entry) }
            toast(if (ok) "‘${entry.name}’을(를) 되돌렸어요 ♪" else "되돌리지 못했어요")
            loadTrash(); refreshAll()
        }
    }

    private fun deleteTrashOne(entry: SongRepository.TrashEntry) {
        AlertDialog.Builder(this)
            .setTitle("완전히 삭제")
            .setMessage("‘${entry.name}’을(를) 완전히 삭제할까요?\n되돌릴 수 없어요.")
            .setPositiveButton("삭제") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { SongRepository.removeTrashEntry(this@MainActivity, entry.id) }
                    loadTrash()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmEmptyTrash() {
        if (trashItems.isEmpty()) { toast("휴지통이 비어 있어요."); return }
        AlertDialog.Builder(this)
            .setTitle("휴지통 비우기")
            .setMessage("휴지통의 ${trashItems.size}곡을 완전히 삭제할까요?\n되돌릴 수 없어요.")
            .setPositiveButton("비우기") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { SongRepository.emptyTrash(this@MainActivity) }
                    toast("휴지통을 비웠어요"); loadTrash()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun applyTabVisuals() {
        val on = R.drawable.bg_tab_on
        val off = R.drawable.bg_tab_off
        val onText = android.graphics.Color.WHITE
        val offText = android.graphics.Color.parseColor("#7A7A7A")
        tabLibrary.setBackgroundResource(if (tab == Tab.LIBRARY) on else off)
        tabLibrary.setTextColor(if (tab == Tab.LIBRARY) onText else offText)
        tabAlbums.setBackgroundResource(if (tab == Tab.ALBUMS) on else off)
        tabAlbums.setTextColor(if (tab == Tab.ALBUMS) onText else offText)
        tabTrash.setBackgroundResource(if (tab == Tab.TRASH) on else off)
        tabTrash.setTextColor(if (tab == Tab.TRASH) onText else offText)
    }

    private fun setFilter(m: FilterMode) {
        filterMode = m
        applyFilterVisuals()
        renderList()
    }

    private fun applyFilterVisuals() {
        val brown = android.graphics.Color.parseColor("#5D4037")
        val white = android.graphics.Color.WHITE
        val btns = listOf(
            FilterMode.ALL to findViewById<Button>(R.id.filterAll),
            FilterMode.FAV to findViewById<Button>(R.id.filterFav),
            FilterMode.UNFILED to findViewById<Button>(R.id.filterUnfiled)
        )
        for ((mode, b) in btns) {
            val on = mode == filterMode
            b.setBackgroundResource(if (on) R.drawable.bg_btn_primary else R.drawable.bg_pill)
            b.setTextColor(if (on) white else brown)
        }
    }

    private fun openAlbum(id: String) { openAlbumId = id; exitSelection(); renderList() }
    private fun backToAlbums() { openAlbumId = null; exitSelection(); renderList() }

    /** 지정 이름(있으면)으로 제목을 바꿔 표시용 Song으로 변환. */
    private fun displayTitle(s: Song): Song =
        titleOverrides[s.uri.toString()]?.let { s.copy(title = it) } ?: s

    /** 화면에 보여줄 곡 목록(전체 곡: 정렬 + 즐겨찾기 상단 + 검색 / 앨범 상세: 그 앨범 곡 순서). */
    private fun shownList(): List<Song> {
        if (tab != Tab.LIBRARY)
            return (currentAlbum()?.songs ?: emptyList()).map(::displayTitle)
        var list = librarySongs.map(::displayTitle)
        // 필터: 즐겨찾기만 / 앨범에 없는 곡만
        when (filterMode) {
            FilterMode.FAV -> list = list.filter { favorites.contains(it.uri.toString()) }
            FilterMode.UNFILED -> {
                val inAlbums = albums.flatMap { a -> a.songs.map { it.uri.toString() } }.toSet()
                list = list.filter { it.uri.toString() !in inAlbums }
            }
            else -> {}
        }
        if (Prefs.sortByName(this)) list = list.sortedBy { it.title.lowercase() }
        list = list.sortedByDescending { favorites.contains(it.uri.toString()) }   // 즐겨찾기 상단(안정 정렬)
        val q = searchQuery.trim()
        return if (q.isNotEmpty()) list.filter { it.title.contains(q, ignoreCase = true) } else list
    }

    private fun renderList() {
        val albumList = inAlbumList()
        val albumDetail = inAlbumDetail()
        val trash = tab == Tab.TRASH

        albumBar.visibility = if (tab == Tab.ALBUMS) View.VISIBLE else View.GONE
        btnNewAlbum.visibility = if (albumList) View.VISIBLE else View.GONE
        btnBackToAlbums.visibility = if (albumDetail) View.VISIBLE else View.GONE
        albumDetailTitle.visibility = if (albumDetail) View.VISIBLE else View.GONE
        btnPlayAlbum.visibility = if (albumDetail) View.VISIBLE else View.GONE
        if (albumDetail) albumDetailTitle.text = currentAlbum()?.name ?: ""

        // 동작 줄: 파일선택/검색은 곡 화면에서만, '휴지통 비우기'는 휴지통 탭에서만
        btnSelectMode.visibility = if (!trash && !albumList) View.VISIBLE else View.GONE
        btnSearch.visibility = if (tab == Tab.LIBRARY) View.VISIBLE else View.GONE
        findViewById<View>(R.id.filterRow).visibility =
            if (tab == Tab.LIBRARY && !songAdapter.selectionMode) View.VISIBLE else View.GONE
        btnAddToPlaylist.visibility = if (tab == Tab.LIBRARY) View.VISIBLE else View.GONE
        btnDeleteSel.text = if (albumDetail) "앨범에서 빼기" else "삭제"

        when {
            trash -> {
                if (songList.adapter !== trashAdapter) songList.adapter = trashAdapter
                trashAdapter.submit(trashItems, TRASH_RETENTION_DAYS)
                emptyView.visibility = if (trashItems.isEmpty()) View.VISIBLE else View.GONE
                emptyView.text = "휴지통이 비어 있어요.\n삭제한 노래가 ${TRASH_RETENTION_DAYS}일간 여기 보관돼요."
                btnEmptyTrash.visibility = if (trashItems.isEmpty()) View.GONE else View.VISIBLE
            }
            albumList -> {
                if (songList.adapter !== albumAdapter) songList.adapter = albumAdapter
                albumAdapter.submit(albums)
                emptyView.visibility = if (albums.isEmpty()) View.VISIBLE else View.GONE
                emptyView.text = "아직 앨범이 없어요.\n+ 새 앨범 만들기를 눌러보세요!"
                btnEmptyTrash.visibility = View.GONE
            }
            else -> {
                if (songList.adapter !== songAdapter) songList.adapter = songAdapter
                val list = shownList()
                songAdapter.reorderEnabled = albumDetail && !songAdapter.selectionMode && searchQuery.isBlank()
                songAdapter.libraryMode = (tab == Tab.LIBRARY)
                songAdapter.favorites = favorites
                songAdapter.submit(list)
                emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                emptyView.text = when {
                    searchQuery.isNotBlank() -> "‘${searchQuery}’ 검색 결과가 없어요."
                    tab == Tab.LIBRARY && filterMode == FilterMode.FAV -> "즐겨찾기한 곡이 없어요.\n곡 오른쪽 ⭐을 눌러 등록하세요."
                    tab == Tab.LIBRARY && filterMode == FilterMode.UNFILED -> "모든 곡이 앨범에 들어 있어요."
                    tab == Tab.LIBRARY -> "아직 저장된 노래가 없어요.\n유튜브에서 공유 → 노래저장을 눌러보세요!"
                    else -> "이 앨범은 비어 있어요.\n전체 곡에서 노래를 골라 추가하세요."
                }
                btnEmptyTrash.visibility = View.GONE
            }
        }
    }

    private fun refreshAll() {
        lifecycleScope.launch {
            val (lib, al) = withContext(Dispatchers.IO) {
                val lib = SongRepository.listSongs(this@MainActivity)
                val liveUris = lib.mapTo(HashSet()) { it.uri.toString() }
                val al = AlbumRepository.loadSynced(this@MainActivity, liveUris)
                Prefs.pruneFavorites(this@MainActivity, liveUris)
                Prefs.pruneTitleOverrides(this@MainActivity, liveUris)
                lib to al
            }
            librarySongs = lib
            albums = al
            favorites = Prefs.favorites(this@MainActivity)
            titleOverrides = Prefs.titleOverrides(this@MainActivity)
            if (openAlbumId != null && albums.none { it.id == openAlbumId }) openAlbumId = null
            renderList()
        }
    }

    private fun toggleSearch(show: Boolean) {
        searchRow.visibility = if (show) View.VISIBLE else View.GONE
        if (show) searchInput.requestFocus()
        else { searchInput.text.clear(); searchQuery = ""; renderList() }
    }

    private fun toggleFavorite(song: Song) {
        val key = song.uri.toString()
        val nowFav = Prefs.toggleFavorite(this, key)
        favorites = if (nowFav) favorites + key else favorites - key
        renderList()
    }

    private fun enterSelection(index: Int) {
        songAdapter.selectionMode = true
        songAdapter.toggle(index)
        selectionBar.visibility = View.VISIBLE
        setAddChrome(false)
        renderList()
        updateSelectionBar()
    }

    private fun enterSelectionEmpty() {
        songAdapter.selectionMode = true
        selectionBar.visibility = View.VISIBLE
        setAddChrome(false)
        renderList()
        updateSelectionBar()
    }

    private fun exitSelection() {
        if (!songAdapter.selectionMode) return
        songAdapter.selectionMode = false
        songAdapter.clearSelection()
        selectionBar.visibility = View.GONE
        setAddChrome(true)
        renderList()
    }

    /** 선택 모드일 땐 위쪽 탭/동작 줄과 아래쪽 추가 영역을 숨겨 곡 목록이 화면을 넓게 쓰도록 한다. */
    private fun setAddChrome(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.addZone).visibility = v
        findViewById<View>(R.id.tabRow).visibility = v
        findViewById<View>(R.id.actionRow).visibility = v
    }

    private fun updateSelectionBar() {
        val n = songAdapter.selectedSongs().size
        selCount.text = "${n}곡 선택"
        btnRenameSel.visibility = if (n == 1 && tab == Tab.LIBRARY) View.VISIBLE else View.GONE
    }

    private fun cycleRepeatMode() {
        val next = when (Prefs.repeatMode(this)) {
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_ALL
        }
        Prefs.setRepeatMode(this, next)
        controller?.repeatMode = next
        updateRepeatLabel(next)
    }

    private fun updateRepeatLabel(mode: Int) {
        val text = when (mode) {
            Player.REPEAT_MODE_ALL -> "↻ 전체반복"
            Player.REPEAT_MODE_ONE -> "↺ 한곡반복"
            else -> "→ 반복없음"
        }
        btnRepeat.text = text
        player?.setRepeat(text)
    }

    private fun onSongTapped(index: Int) {
        if (songAdapter.selectionMode) { songAdapter.toggle(index); updateSelectionBar() }
        else playSongs(shownList(), index)
    }

    private fun playSongs(list: List<Song>, startIndex: Int) {
        if (list.isEmpty()) return
        val c = controller ?: return
        val items = list.map {
            val meta = MediaMetadata.Builder().setTitle(it.title)
            SongRepository.artUri(this, it.uri)?.let { art -> meta.setArtworkUri(art) }
            MediaItem.Builder().setUri(it.uri).setMediaMetadata(meta.build()).build()
        }
        c.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0)
        c.repeatMode = Prefs.repeatMode(this)
        c.shuffleModeEnabled = Prefs.shuffle(this)
        c.prepare()
        c.play()
        if (songAdapter.selectionMode) exitSelection()
    }

    private fun togglePlay() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }

    private fun confirmDelete(selected: List<Song>) {
        if (selected.isEmpty()) return
        if (inAlbumDetail()) {
            val a = currentAlbum() ?: return
            albums = AlbumRepository.removeSongs(this, a.id, selected)
            toast("앨범에서 ${selected.size}곡 뺐어요")
            exitSelection(); renderList()
            moveFilesToAlbum(selected, null)   // 파일을 기본 폴더로 되돌림
            return
        }
        AlertDialog.Builder(this)
            .setTitle("휴지통으로 보낼까요?")
            .setMessage("${selected.size}곡을 휴지통으로 보냅니다.\n7일 안에는 설정 > 휴지통에서 되돌릴 수 있어요.")
            .setPositiveButton("휴지통으로") { _, _ -> deleteFiles(selected) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun deleteFiles(selected: List<Song>) {
        lifecycleScope.launch {
            val deleted = mutableListOf<Song>()
            val failed = mutableListOf<Song>()
            val failedTrash = mutableListOf<SongRepository.TrashEntry>()
            withContext(Dispatchers.IO) {
                for (s in selected) {
                    val entry = SongRepository.copyToTrash(this@MainActivity, s)   // 먼저 휴지통에 복사
                    val ok = try { SongRepository.deleteSong(this@MainActivity, s) }
                    catch (e: SecurityException) { false }
                    if (ok) deleted += s
                    else { failed += s; entry?.let { failedTrash += it } }
                }
            }
            AlbumRepository.purgeDeleted(this@MainActivity, deleted)
            when {
                failed.isEmpty() -> { toast("${deleted.size}곡을 휴지통으로 옮겼어요"); exitSelection(); refreshAll() }
                Build.VERSION.SDK_INT >= 30 -> {
                    pendingDeleteSongs = failed
                    pendingTrash = failedTrash
                    val pi = MediaStore.createDeleteRequest(contentResolver, failed.map { it.uri })
                    deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                }
                else -> {
                    // 삭제 실패 → 휴지통 복사본도 정리(중복 방지)
                    failedTrash.forEach { SongRepository.removeTrashEntry(this@MainActivity, it.id) }
                    toast("${failed.size}곡은 삭제하지 못했어요"); exitSelection(); refreshAll()
                }
            }
        }
    }

    // ── 앨범 안 곡 순서 / 앨범 순서 이동 ───────────────────
    private fun moveSongInAlbum(from: Int, to: Int) {
        val a = currentAlbum() ?: return
        if (from !in a.songs.indices || to !in a.songs.indices) return
        a.songs.add(to, a.songs.removeAt(from))
        AlbumRepository.setSongOrder(this, a.id, a.songs)
        renderList()
    }

    private fun moveAlbum(from: Int, to: Int) {
        if (from !in albums.indices || to !in albums.indices) return
        albums.add(to, albums.removeAt(from))
        AlbumRepository.setAlbumOrder(this, albums)
        renderList()
    }

    private val swipeBgPaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#C62828") }
    private val swipeTextPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE; isFakeBoldText = true; isAntiAlias = true; textSize = 48f
    }

    private val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
            if (songAdapter.selectionMode) return makeMovementFlags(0, 0)
            val drag = if (reorderActive()) ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0
            val swipe = when {
                inAlbumList() -> ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT       // 앨범 삭제
                tab == Tab.LIBRARY -> ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT  // 곡 → 휴지통
                else -> 0   // 앨범 상세(곡 순서변경 중)에서는 스와이프 끔
            }
            return makeMovementFlags(drag, swipe)
        }

        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            if (inAlbumList()) {
                if (from !in albums.indices || to !in albums.indices) return false
                albums.add(to, albums.removeAt(from))
                albumAdapter.notifyItemMoved(from, to)
            } else {
                val a = currentAlbum() ?: return false
                if (from !in a.songs.indices || to !in a.songs.indices) return false
                a.songs.add(to, a.songs.removeAt(from))
                songAdapter.notifyItemMoved(from, to)
            }
            return true
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            if (inAlbumList()) AlbumRepository.setAlbumOrder(this@MainActivity, albums)
            else currentAlbum()?.let { AlbumRepository.setSongOrder(this@MainActivity, it.id, it.songs) }
        }

        override fun onChildDraw(
            c: android.graphics.Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
            dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
        ) {
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
                val v = vh.itemView
                val label = if (inAlbumList()) "앨범 삭제" else "🗑 휴지통"
                val cy = v.top + (v.height + swipeTextPaint.textSize) / 2f - 8f
                if (dX > 0) {
                    c.drawRect(v.left.toFloat(), v.top.toFloat(), v.left + dX, v.bottom.toFloat(), swipeBgPaint)
                    c.drawText(label, v.left + 48f, cy, swipeTextPaint)
                } else {
                    c.drawRect(v.right + dX, v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat(), swipeBgPaint)
                    c.drawText(label, v.right - swipeTextPaint.measureText(label) - 48f, cy, swipeTextPaint)
                }
            }
            super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
            val pos = vh.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return
            // 스와이프하면 곧바로 삭제하지 않고, 그 행에 '삭제' 버튼을 보여준다(탭하면 삭제).
            if (inAlbumList()) albumAdapter.setRevealed(albums.getOrNull(pos)?.id)
            else songAdapter.setRevealed(songAdapter.itemAt(pos)?.uri)
        }
        override fun isLongPressDragEnabled() = false
    })

    private fun reorderActive(): Boolean = when {
        inAlbumList() -> !songAdapter.selectionMode
        inAlbumDetail() -> !songAdapter.selectionMode && searchQuery.isBlank()
        else -> false
    }

    private fun startProgressUpdates() {
        handler.removeCallbacksAndMessages(null)
        handler.post(object : Runnable {
            override fun run() { updateSeek(); handler.postDelayed(this, 500) }
        })
    }

    private fun updateSeek() {
        val c = controller ?: return
        val dur = c.duration.coerceAtLeast(0L)
        val pos = c.currentPosition.coerceAtLeast(0L)
        if (dur > 0 && !userSeeking) {
            val pct = (pos * 1000 / dur).toInt().coerceIn(0, 1000)
            seekBar.progress = pct
            timeCurrent.text = formatMs(pos)
            timeTotal.text = formatMs(dur)
            player?.updateProgress(pct, formatMs(pos), formatMs(dur))
        } else if (dur <= 0) {
            seekBar.progress = 0
            timeCurrent.text = "0:00"; timeTotal.text = "0:00"
        }
    }

    private fun seekListener(bar: SeekBar, onPreview: (String) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) (controller?.duration ?: 0L).let { if (it > 0) onPreview(formatMs(it * progress / 1000)) }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                (controller?.duration ?: 0L).let { if (it > 0) controller?.seekTo(it * (sb?.progress ?: 0) / 1000) }
            }
        }

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            setPlayIcon(isPlaying)
            if (!isPlaying) savePlayback()
        }
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            val raw = item?.mediaMetadata?.title?.toString()
            nowPlaying.text = if (raw != null) "♪ $raw" else getString(R.string.no_song)
            player?.setTitle(raw ?: getString(R.string.no_song))
            songAdapter.setPlaying(item?.localConfiguration?.uri)
        }
    }

    private fun setPlayIcon(playing: Boolean) {
        val res = if (playing) R.drawable.ic_pause_white else R.drawable.ic_play_white
        btnPlayPause.setImageResource(res)
        player?.setPlayIcon(res)
    }

    private fun syncPlayerUi(c: MediaController) {
        setPlayIcon(c.isPlaying)
        val raw = c.currentMediaItem?.mediaMetadata?.title?.toString()
        nowPlaying.text = if (raw != null) "♪ $raw" else getString(R.string.no_song)
        songAdapter.setPlaying(c.currentMediaItem?.localConfiguration?.uri)
        updateRepeatLabel(Prefs.repeatMode(this))
    }

    private fun openPlayer() {
        if (player != null) return
        if (controller?.currentMediaItem == null) return
        player = PlayerDialog().also { it.show() }
    }

    private inner class PlayerDialog {
        private val dialog = Dialog(this@MainActivity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        private val title: TextView
        private val cur: TextView
        private val total: TextView
        private val seek: SeekBar
        private val play: ImageButton
        private val repeat: Button

        init {
            dialog.setContentView(R.layout.dialog_player)
            title = dialog.findViewById(R.id.pTitle)
            cur = dialog.findViewById(R.id.pCurrent)
            total = dialog.findViewById(R.id.pTotal)
            seek = dialog.findViewById(R.id.pSeek)
            play = dialog.findViewById(R.id.pPlay)
            repeat = dialog.findViewById(R.id.pRepeat)

            dialog.findViewById<View>(R.id.pClose).setOnClickListener { dialog.dismiss() }
            play.setOnClickListener { togglePlay() }
            dialog.findViewById<View>(R.id.pPrev).setOnClickListener { controller?.seekToPrevious() }
            dialog.findViewById<View>(R.id.pNext).setOnClickListener { controller?.seekToNext() }
            repeat.setOnClickListener { cycleRepeatMode() }
            seek.setOnSeekBarChangeListener(seekListener(seek) { cur.text = it })
            dialog.setOnDismissListener { player = null }

            controller?.let {
                title.text = it.currentMediaItem?.mediaMetadata?.title ?: getString(R.string.no_song)
                play.setImageResource(if (it.isPlaying) R.drawable.ic_pause_white else R.drawable.ic_play_white)
            }
            repeat.text = btnRepeat.text
        }

        fun show() = dialog.show()
        fun setTitle(t: String) { title.text = t }
        fun setRepeat(t: String) { repeat.text = t }
        fun setPlayIcon(res: Int) { play.setImageResource(res) }
        fun updateProgress(pct: Int, c: String, t: String) {
            if (!userSeeking) { seek.progress = pct; cur.text = c; total.text = t }
        }
    }

    // ── 앨범 만들기 / 이름변경 / 삭제 / 추가 ───────────────
    private fun padded(v: View): LinearLayout =
        LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0); addView(v) }

    private fun showCreateAlbumDialog() {
        val input = EditText(this).apply { hint = "앨범 이름"; inputType = android.text.InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this)
            .setTitle("새 앨범 만들기")
            .setView(padded(input))
            .setPositiveButton("만들기") { _, _ ->
                albums = AlbumRepository.create(this, input.text.toString())
                toast("앨범을 만들었어요.")
                renderList()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showAlbumMenu(album: Album) {
        AlertDialog.Builder(this)
            .setTitle(album.name)
            .setItems(arrayOf("이름 변경", "삭제")) { _, which ->
                if (which == 0) showRenameAlbumDialog(album) else showDeleteAlbumDialog(album)
            }
            .show()
    }

    private fun showRenameAlbumDialog(album: Album) {
        val input = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_TEXT; setText(album.name); setSelection(album.name.length) }
        AlertDialog.Builder(this)
            .setTitle("이름 변경")
            .setView(padded(input))
            .setPositiveButton("저장") { _, _ ->
                albums = AlbumRepository.rename(this, album.id, input.text.toString())
                val newName = albums.find { it.id == album.id }?.name
                renderList()
                moveFilesToAlbum(album.songs.toList(), newName)   // 폴더 이름도 함께 변경
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showDeleteAlbumDialog(album: Album) {
        AlertDialog.Builder(this)
            .setTitle("앨범 삭제")
            .setMessage("‘${album.name}’ 앨범을 삭제할까요?\n(노래 파일은 기본 폴더로 옮겨져 그대로 남아요.)")
            .setPositiveButton("삭제") { _, _ ->
                val songsInAlbum = album.songs.toList()
                albums = AlbumRepository.delete(this, album.id)
                if (openAlbumId == album.id) openAlbumId = null
                renderList()
                moveFilesToAlbum(songsInAlbum, null)   // 파일을 기본 폴더로 되돌림
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 선택 곡 파일들을 앨범 폴더(또는 루트)로 실제 이동(기본 저장에서만). 끝나면 목록 새로고침. */
    private fun moveFilesToAlbum(songs: List<Song>, albumName: String?) {
        if (songs.isEmpty()) { refreshAll(); return }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                songs.forEach { SongRepository.moveToAlbumFolder(this@MainActivity, it.uri, albumName) }
            }
            refreshAll()
        }
    }

    private fun showAddToAlbumDialog(songs: List<Song>) {
        if (songs.isEmpty()) { toast("먼저 노래를 선택하세요."); return }
        val current = albums
        val labels = current.map { "${it.name} (${it.songs.size}곡)" }.toMutableList()
        labels.add("+ 새 앨범 만들기")
        AlertDialog.Builder(this)
            .setTitle("앨범에 추가")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < current.size) {
                    val a = current[which]
                    val added = AlbumRepository.addSongs(this, a.id, songs)
                    toast("‘${a.name}’ 폴더로 ${added}곡 옮겼어요")
                    exitSelection(); moveFilesToAlbum(songs, a.name)
                } else {
                    val input = EditText(this).apply { hint = "앨범 이름"; inputType = android.text.InputType.TYPE_CLASS_TEXT }
                    AlertDialog.Builder(this)
                        .setTitle("새 앨범 만들기")
                        .setView(padded(input))
                        .setPositiveButton("만들고 추가") { _, _ ->
                            val list = AlbumRepository.create(this, input.text.toString())
                            val created = list.last()
                            val added = AlbumRepository.addSongs(this, created.id, songs)
                            toast("‘${created.name}’ 폴더로 ${added}곡 옮겼어요")
                            exitSelection(); moveFilesToAlbum(songs, created.name)
                        }
                        .setNegativeButton("취소", null)
                        .show()
                }
            }
            .show()
    }

    // ── 설정 ──────────────────────────────────────────────
    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val dialog = AlertDialog.Builder(this)
            .setTitle("설정")
            .setView(view)
            .setPositiveButton("닫기", null)
            .create()

        // 스위치: 셔플 재생
        view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.swShuffle).apply {
            isChecked = Prefs.shuffle(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setShuffle(this@MainActivity, on)
                controller?.shuffleModeEnabled = on
            }
        }
        // 스위치: 모두 닫기 후 음악 계속
        view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.swKeepPlaying).apply {
            isChecked = Prefs.keepPlayingInBackground(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setKeepPlayingInBackground(this@MainActivity, on)
            }
        }

        // 현재 선택값 표시
        view.findViewById<TextView>(R.id.tvSortValue).text =
            if (Prefs.sortByName(this)) "이름순" else "최신순"
        view.findViewById<TextView>(R.id.tvFolderValue).text =
            SongRepository.folderLabel(this)

        // 스위치 행은 어디를 눌러도 토글되게 (탭 영역 크게 — 어르신용)
        view.findViewById<View>(R.id.rowShuffle).setOnClickListener {
            view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.swShuffle).toggle()
        }
        view.findViewById<View>(R.id.rowKeepPlaying).setOnClickListener {
            view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.swKeepPlaying).toggle()
        }

        // 눌러서 이동/실행하는 항목
        view.findViewById<View>(R.id.rowSleep).setOnClickListener { dialog.dismiss(); showSleepDialog() }
        view.findViewById<View>(R.id.rowSort).setOnClickListener {
            Prefs.setSortByName(this, !Prefs.sortByName(this))
            view.findViewById<TextView>(R.id.tvSortValue).text =
                if (Prefs.sortByName(this)) "이름순" else "최신순"
            renderList()
            toast(if (Prefs.sortByName(this)) "이름순으로 정렬해요." else "최신순으로 정렬해요.")
        }
        view.findViewById<View>(R.id.rowFolder).setOnClickListener { dialog.dismiss(); showFolderDialog() }
        view.findViewById<View>(R.id.rowBackup).setOnClickListener { dialog.dismiss(); showBackupDialog() }
        view.findViewById<View>(R.id.rowGuide).setOnClickListener { dialog.dismiss(); showGuide() }
        view.findViewById<View>(R.id.rowAdmin).setOnClickListener { dialog.dismiss(); showAdminDialog() }

        dialog.show()
    }

    private fun showFolderDialog() {
        AlertDialog.Builder(this)
            .setTitle("저장 폴더")
            .setMessage(
                "지금 저장 위치: ${SongRepository.folderLabel(this)}\n\n" +
                "받은 노래는 휴대폰의 ‘음악/TubeSave’ 폴더에 저장돼요. 앨범을 만들면 그 안에 앨범 이름 폴더로 자동 정리됩니다.\n" +
                "다른 폴더를 쓰고 싶으면 아래에서 바꿀 수 있어요."
            )
            .setItems(arrayOf("폴더 직접 고르기", "기본 폴더로 되돌리기")) { _, which ->
                when (which) {
                    0 -> folderPicker.launch(null)
                    1 -> { SongRepository.setCustomFolder(this, null); refreshAll() }
                }
            }.show()
    }

    private fun showSleepDialog() {
        AlertDialog.Builder(this)
            .setTitle("취침 타이머")
            .setItems(arrayOf("15분 후 멈춤", "30분 후 멈춤", "60분 후 멈춤", "타이머 끄기")) { _, which ->
                val min = intArrayOf(15, 30, 60, 0)[which]
                PlaybackService.setSleepTimer(this, min)
                toast(if (min > 0) "${min}분 후에 자동으로 멈춰요." else "취침 타이머를 껐어요.")
            }.show()
    }

    private fun showAdminDialog() {
        val tokenInput = EditText(this).apply { hint = "텔레그램 봇 토큰"; setText(Prefs.tgToken(this@MainActivity)); inputType = android.text.InputType.TYPE_CLASS_TEXT }
        val chatInput = EditText(this).apply { hint = "챗 ID"; setText(Prefs.tgChat(this@MainActivity)); inputType = android.text.InputType.TYPE_CLASS_TEXT }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0)
            addView(tokenInput); addView(chatInput)
        }
        AlertDialog.Builder(this)
            .setTitle("실패 알림 설정 (관리자)")
            .setMessage("비워두면 알림을 보내지 않아요.")
            .setView(container)
            .setPositiveButton("저장") { _, _ ->
                Prefs.setTelegram(this, tokenInput.text.toString().trim(), chatInput.text.toString().trim())
                toast("저장했어요.")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showBackupDialog() {
        AlertDialog.Builder(this)
            .setTitle("백업 / 복원")
            .setItems(arrayOf("백업 만들기", "백업 불러오기 (현재 목록 덮어씀)")) { _, which ->
                if (which == 0) exportLauncher.launch("노래저장_백업.json")
                else AlertDialog.Builder(this)
                    .setTitle("백업 불러오기")
                    .setMessage("지금 앨범·즐겨찾기가 백업 내용으로 바뀌어요. 계속할까요?")
                    .setPositiveButton("불러오기") { _, _ -> importLauncher.launch(arrayOf("*/*")) }
                    .setNegativeButton("취소", null)
                    .show()
            }
            .show()
    }

    private fun showGuide() {
        AlertDialog.Builder(this)
            .setTitle("노래저장 사용법")
            .setMessage(
                "① 유튜브 앱에서 듣고 싶은 노래를 찾아요.\n\n" +
                "② 영상 아래 ‘공유’를 누르고, 목록에서 ‘노래저장’을 선택해요.\n\n" +
                "③ 잠시 기다리면 노래가 저장돼요. ‘전체 곡’에서 눌러 들으면 됩니다.\n\n" +
                "여러 곡을 모아 ‘앨범’을 만들 수도 있어요."
            )
            .setPositiveButton("알겠어요", null)
            .show()
    }


    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    /** 클립보드에 복사된 유튜브 링크를 붙여넣고, 받을지 한 번 확인한다. */
    private fun pasteAndDownload() {
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = clip.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val url = ShareReceiverActivity.extractYoutubeUrl(text)
        if (url == null) {
            toast("복사한 유튜브 링크가 없어요.\n유튜브에서 공유 → ‘링크 복사’ 후 다시 눌러주세요.")
            return
        }
        // 사용자가 입력칸에서 직접 확인할 수 있게 채워준 뒤, 받을지 묻는다
        findViewById<EditText>(R.id.linkInput).setText(url)
        AlertDialog.Builder(this)
            .setTitle("이 노래를 받을까요?")
            .setMessage(url)
            .setPositiveButton("받기") { _, _ ->
                DownloadService.start(this, url)
                toast("받기 시작했어요! 진행 상황이 아래에 표시돼요.")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 유튜브 앱을 연다. 없으면 웹(크롬/브라우저)으로 연다. */
    private fun openYoutube() {
        val launch = packageManager.getLaunchIntentForPackage("com.google.android.youtube")
        try {
            if (launch != null) {
                startActivity(launch)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://m.youtube.com")))
            }
        } catch (e: Exception) {
            toast("유튜브를 열 수 없어요.")
        }
    }

    // ── yt-dlp 업데이트 (다운로드가 막혔을 때) ───────────────
    private fun maybePromptUpdateFromIntent(i: Intent?) {
        if (i?.getBooleanExtra(DownloadService.EXTRA_PROMPT_UPDATE, false) == true) {
            i.removeExtra(DownloadService.EXTRA_PROMPT_UPDATE)
            handler.post { showUpdatePrompt() }
        }
    }

    private fun showUpdatePrompt() {
        if (updateFlowActive || isFinishing) return
        updateFlowActive = true
        AlertDialog.Builder(this)
            .setTitle("노래가 잘 안 받아져요")
            .setMessage("인터넷에 연결한 뒤 업데이트하면 다시 받을 수 있어요.\n지금 업데이트할까요?")
            .setPositiveButton("지금 업데이트") { _, _ -> runUpdate() }
            .setNegativeButton("나중에") { _, _ -> updateFlowActive = false }
            .setOnCancelListener { updateFlowActive = false }
            .show()
    }

    private fun runUpdate() {
        val progress = AlertDialog.Builder(this)
            .setMessage("업데이트 중이에요. 잠시만 기다려 주세요…")
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    UpdateManager.refreshConfig(applicationContext)   // 새 player_client(원격설정)도 함께 반영
                    YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
                    true
                } catch (e: Exception) { false }
            }
            progress.dismiss()
            updateFlowActive = false
            AlertDialog.Builder(this@MainActivity)
                .setMessage(if (ok) "업데이트했어요! 노래를 다시 받아보세요." else "업데이트하지 못했어요. 인터넷 연결을 확인하고 다시 시도해 주세요.")
                .setPositiveButton("확인", null)
                .show()
        }
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            needed += Manifest.permission.POST_NOTIFICATIONS
            needed += Manifest.permission.READ_MEDIA_AUDIO
        } else {
            needed += Manifest.permission.READ_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT < 29) needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        permissionLauncher.launch(needed.toTypedArray())
    }

    // ── 곡 이름 바꾸기 / 이어듣기 ──────────────────────────
    private fun renameSelected() {
        val song = songAdapter.selectedSongs().singleOrNull() ?: return
        val input = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_TEXT; setText(song.title); setSelection(song.title.length) }
        AlertDialog.Builder(this)
            .setTitle("곡 이름 바꾸기")
            .setMessage("보기 쉬운 이름으로 바꿔요. (노래 파일은 그대로예요.)")
            .setView(padded(input))
            .setPositiveButton("저장") { _, _ ->
                Prefs.setTitleOverride(this, song.uri.toString(), input.text.toString())
                exitSelection(); refreshAll()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 마지막 재생목록 + 위치 저장(일시정지/앱 종료 시). */
    private fun savePlayback() {
        val c = controller ?: return
        val n = c.mediaItemCount
        if (n == 0) { Prefs.savePlayback(this, "", 0, 0L); return }
        val arr = JSONArray()
        for (i in 0 until n) {
            val item = c.getMediaItemAt(i)
            arr.put(
                JSONObject()
                    .put("uri", item.localConfiguration?.uri?.toString() ?: "")
                    .put("title", item.mediaMetadata.title?.toString() ?: "")
            )
        }
        Prefs.savePlayback(this, arr.toString(), c.currentMediaItemIndex, c.currentPosition)
    }

    /** 저장해 둔 재생목록/위치를 복원(준비만, 자동재생 안 함). 이미 재생 중이면 건너뜀. */
    private fun restorePlayback(c: MediaController) {
        if (c.mediaItemCount > 0) return
        val json = Prefs.lastQueueJson(this)
        if (json.isBlank()) return
        val items = try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull {
                val o = arr.getJSONObject(it)
                val uri = o.optString("uri")
                if (uri.isEmpty()) null
                else {
                    val u = uri.toUri()
                    val meta = MediaMetadata.Builder().setTitle(o.optString("title"))
                    SongRepository.artUri(this, u)?.let { art -> meta.setArtworkUri(art) }
                    MediaItem.Builder().setUri(u).setMediaMetadata(meta.build()).build()
                }
            }
        } catch (e: Exception) { return }
        if (items.isEmpty()) return
        val idx = Prefs.lastIndex(this).coerceIn(0, items.lastIndex)
        c.setMediaItems(items, idx, Prefs.lastPositionMs(this).coerceAtLeast(0L))
        c.prepare()
    }

    // ── 곡 목록 어댑터 ─────────────────────────────────────
    private class SongAdapter(
        val onTap: (Int) -> Unit,
        val onLongPress: (Int) -> Unit,
        val onCheckChanged: () -> Unit,
        val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
        val onMove: (Int, Int) -> Unit,
        val onToggleFav: (Song) -> Unit,
        val onDelete: (Song) -> Unit
    ) : RecyclerView.Adapter<SongAdapter.VH>() {

        private var items: List<Song> = emptyList()
        private val checked = mutableSetOf<Uri>()
        var playingUri: Uri? = null
        var favorites: Set<String> = emptySet()
        var libraryMode = true
        var reorderEnabled = false
        var selectionMode = false
        var revealedUri: Uri? = null   // 스와이프로 '삭제' 버튼이 열린 행

        fun submit(list: List<Song>) {
            items = list
            revealedUri = null
            notifyDataSetChanged()
        }

        /** 스와이프 시 해당 행의 삭제 버튼을 보여줌(한 번에 하나만). */
        fun setRevealed(uri: Uri?) {
            if (uri == revealedUri) return
            val old = items.indexOfFirst { it.uri == revealedUri }
            val now = items.indexOfFirst { it.uri == uri }
            revealedUri = uri
            if (old >= 0) notifyItemChanged(old)
            if (now >= 0) notifyItemChanged(now)
        }

        /** 재생 중인 곡 표시를 바뀐 행만 갱신(전체 rebind 회피 → 재생 중 매끄러움) */
        fun setPlaying(uri: Uri?) {
            if (uri == playingUri) return
            val old = items.indexOfFirst { it.uri == playingUri }
            val now = items.indexOfFirst { it.uri == uri }
            playingUri = uri
            if (old >= 0) notifyItemChanged(old)
            if (now >= 0) notifyItemChanged(now)
        }

        fun toggle(index: Int) {
            if (index !in items.indices) return
            val uri = items[index].uri
            if (uri in checked) checked.remove(uri) else checked.add(uri)
            notifyItemChanged(index)
        }

        fun itemAt(i: Int): Song? = items.getOrNull(i)
        fun selectAll() { checked.clear(); checked.addAll(items.map { it.uri }); notifyDataSetChanged() }
        fun clearSelection() { checked.clear(); notifyDataSetChanged() }
        fun selectedSongs(): List<Song> = items.filter { it.uri in checked }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val check: CheckBox = v.findViewById(R.id.songCheck)
            val title: TextView = v.findViewById(R.id.songTitle)
            val duration: TextView = v.findViewById(R.id.songDuration)
            val fav: ImageButton = v.findViewById(R.id.btnFav)
            val moveUp: ImageButton = v.findViewById(R.id.btnMoveUp)
            val moveDown: ImageButton = v.findViewById(R.id.btnMoveDown)
            val dragHandle: ImageView = v.findViewById(R.id.dragHandle)
            val swipeDelete: Button = v.findViewById(R.id.btnSwipeDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val song = items[position]
            val playing = song.uri == playingUri

            val ctx = holder.itemView.context
            holder.title.text = if (playing) "♪ " + song.title else song.title
            holder.title.setTypeface(null, if (playing) Typeface.BOLD else Typeface.NORMAL)
            // 재생 중인 곡: 옅은 앰버 배경 + 브랜드색 글씨로 한눈에 구분
            holder.itemView.setBackgroundColor(
                if (playing) ContextCompat.getColor(ctx, R.color.brand_pale)
                else android.graphics.Color.TRANSPARENT
            )
            holder.title.setTextColor(
                ContextCompat.getColor(ctx, if (playing) R.color.brand else R.color.on_surface)
            )
            holder.duration.text = if (song.durationMs > 0) song.durationText else ""

            holder.check.visibility = if (selectionMode) View.VISIBLE else View.GONE
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = song.uri in checked
            holder.check.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) checked.add(song.uri) else checked.remove(song.uri)
                onCheckChanged()
            }

            val showFav = libraryMode && !selectionMode
            holder.fav.visibility = if (showFav) View.VISIBLE else View.GONE
            if (showFav) {
                val isFav = favorites.contains(song.uri.toString())
                holder.fav.setImageResource(if (isFav) R.drawable.ic_star else R.drawable.ic_star_border)
                holder.fav.setOnClickListener { onToggleFav(song) }
            } else holder.fav.setOnClickListener(null)

            val showMove = reorderEnabled
            holder.dragHandle.visibility = if (showMove) View.VISIBLE else View.GONE
            holder.moveUp.visibility = if (showMove && position > 0) View.VISIBLE else if (showMove) View.INVISIBLE else View.GONE
            holder.moveDown.visibility = if (showMove && position < items.lastIndex) View.VISIBLE else if (showMove) View.INVISIBLE else View.GONE
            if (showMove) {
                holder.dragHandle.setOnTouchListener { _, ev ->
                    if (ev.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(holder)
                    false
                }
                holder.moveUp.setOnClickListener { holder.bindingAdapterPosition.let { if (it > 0) onMove(it, it - 1) } }
                holder.moveDown.setOnClickListener { holder.bindingAdapterPosition.let { if (it in 0 until items.lastIndex) onMove(it, it + 1) } }
            } else {
                holder.dragHandle.setOnTouchListener(null)
                holder.moveUp.setOnClickListener(null)
                holder.moveDown.setOnClickListener(null)
            }

            // 스와이프로 열린 행: '삭제' 버튼 표시(나머지 보조 버튼은 숨김)
            val revealed = song.uri == revealedUri
            holder.swipeDelete.visibility = if (revealed) View.VISIBLE else View.GONE
            if (revealed) {
                holder.fav.visibility = View.GONE
                holder.duration.visibility = View.GONE
                holder.swipeDelete.setOnClickListener { onDelete(song); setRevealed(null) }
            } else {
                holder.duration.visibility = View.VISIBLE
                holder.swipeDelete.setOnClickListener(null)
            }

            holder.itemView.setOnClickListener {
                if (revealedUri != null) setRevealed(null)   // 열린 삭제 버튼 닫기
                else onTap(holder.bindingAdapterPosition)
            }
            holder.itemView.setOnLongClickListener {
                if (!selectionMode) onLongPress(holder.bindingAdapterPosition)
                true
            }
        }

        override fun getItemCount() = items.size
    }

    // ── 앨범 목록 어댑터 ───────────────────────────────────
    private class AlbumAdapter(
        val onOpen: (Album) -> Unit,
        val onMore: (Album) -> Unit,
        val onMoveUp: (Int) -> Unit,
        val onMoveDown: (Int) -> Unit,
        val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
        val onDelete: (Album) -> Unit
    ) : RecyclerView.Adapter<AlbumAdapter.VH>() {

        private var items: List<Album> = emptyList()
        var revealedId: String? = null

        fun submit(list: List<Album>) { items = list; revealedId = null; notifyDataSetChanged() }

        fun setRevealed(id: String?) {
            if (id == revealedId) return
            val old = items.indexOfFirst { it.id == revealedId }
            val now = items.indexOfFirst { it.id == id }
            revealedId = id
            if (old >= 0) notifyItemChanged(old)
            if (now >= 0) notifyItemChanged(now)
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.albumName)
            val count: TextView = v.findViewById(R.id.albumCount)
            val up: ImageButton = v.findViewById(R.id.btnAlbumUp)
            val down: ImageButton = v.findViewById(R.id.btnAlbumDown)
            val more: ImageButton = v.findViewById(R.id.btnAlbumMore)
            val drag: ImageView = v.findViewById(R.id.albumDrag)
            val delete: Button = v.findViewById(R.id.btnAlbumDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_album, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val album = items[position]
            holder.name.text = album.name
            holder.count.text = "${album.songs.size}곡"
            holder.up.setOnClickListener { holder.bindingAdapterPosition.let { if (it > 0) onMoveUp(it) } }
            holder.down.setOnClickListener { holder.bindingAdapterPosition.let { if (it in 0 until items.lastIndex) onMoveDown(it) } }
            holder.more.setOnClickListener { onMore(album) }
            holder.drag.setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(holder)
                false
            }

            val revealed = album.id == revealedId
            holder.delete.visibility = if (revealed) View.VISIBLE else View.GONE
            holder.up.visibility = if (revealed) View.GONE else if (position > 0) View.VISIBLE else View.INVISIBLE
            holder.down.visibility = if (revealed) View.GONE else if (position < items.lastIndex) View.VISIBLE else View.INVISIBLE
            holder.more.visibility = if (revealed) View.GONE else View.VISIBLE
            holder.drag.visibility = if (revealed) View.GONE else View.VISIBLE
            if (revealed) holder.delete.setOnClickListener { onDelete(album); setRevealed(null) }
            else holder.delete.setOnClickListener(null)

            holder.itemView.setOnClickListener {
                if (revealedId != null) setRevealed(null) else onOpen(album)
            }
        }

        override fun getItemCount() = items.size
    }

    // ── 휴지통 어댑터 ──────────────────────────────────────
    private class TrashAdapter(
        val onRestore: (SongRepository.TrashEntry) -> Unit,
        val onDelete: (SongRepository.TrashEntry) -> Unit
    ) : RecyclerView.Adapter<TrashAdapter.VH>() {

        private var items: List<SongRepository.TrashEntry> = emptyList()
        private var retentionDays: Int = 7

        fun submit(list: List<SongRepository.TrashEntry>, retentionDays: Int) {
            items = list; this.retentionDays = retentionDays; notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.trashName)
            val due: TextView = v.findViewById(R.id.trashDue)
            val restore: Button = v.findViewById(R.id.btnRestore)
            val delete: Button = v.findViewById(R.id.btnTrashDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_trash, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            holder.name.text = e.name
            val dueAt = e.deletedAt + retentionDays.toLong() * 24 * 60 * 60 * 1000
            holder.due.text = "삭제 예정: " + android.text.format.DateFormat.format("M월 d일", dueAt)
            holder.restore.setOnClickListener { onRestore(e) }
            holder.delete.setOnClickListener { onDelete(e) }
        }

        override fun getItemCount() = items.size
    }
}
