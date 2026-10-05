package io.github.ytdlpandroid

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.content.ContentValues
import android.text.format.DateUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.animation.ValueAnimator
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.SeekBar.OnSeekBarChangeListener
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors

// AudioTrack is defined in AudioTrack.kt (shared with TrackRepository)

data class PlaylistImportItem(
    val uri: String,
    val metadata: SavedTrackMetadata? = null,
)

data class SearchDownloadIndicator(
    val item: MusicSearchResult,
    val button: Button,
    val spinner: ProgressBar,
)

class MainActivity : Activity(), DownloadService.Listener {
    private lateinit var content: FrameLayout
    private lateinit var searchInput: EditText
    private lateinit var searchButton: Button
    private lateinit var searchResults: GridLayout
    private lateinit var searchResultsTitle: TextView
    private lateinit var homeCategories: LinearLayout
    private lateinit var homeSubcategories: LinearLayout
    private lateinit var homeLibraryContent: LinearLayout
    private lateinit var homeMusicGrid: GridLayout
    private lateinit var downloadSettingsContent: LinearLayout
    private lateinit var downloadSettingsToggle: TextView
    private var homeCategory = HOME_CATEGORY_MUSIC
    private var homeSubcategory = 0
    private var selectedHomeGroup: String? = null
    private lateinit var formatSpinner: Spinner
    private lateinit var concurrencySpinner: Spinner
    private lateinit var wifiOnlyToggle: CheckBox
    private lateinit var queueSummary: TextView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var queueList: LinearLayout
    private lateinit var historyList: LinearLayout
    private lateinit var downloadHistoryList: GridLayout
    private lateinit var libraryRecentHistory: GridLayout
    private lateinit var libraryFilterButton: Button
    private lateinit var librarySortButton: Button
    private lateinit var libraryTrackHeading: TextView
    private lateinit var libraryPlayAllButton: Button
    private lateinit var librarySearch: EditText
    private var libraryGroupMode = 0
    private var librarySortMode = 0
    private var visibleLibraryTracks: List<AudioTrack> = emptyList()
    private var libraryErrors: List<String> = emptyList()
    private lateinit var playlistList: GridLayout
    private lateinit var playlistSearch: EditText
    private lateinit var playerArtwork: ImageView
    private lateinit var playerTitle: TextView
    private lateinit var playerArtist: TextView
    private lateinit var playerPosition: TextView
    private lateinit var playerDuration: TextView
    private lateinit var playerSeek: SeekBar
    private lateinit var playButton: Button
    private lateinit var shuffleButton: Button
    private lateinit var repeatButton: Button
    private lateinit var playerStatus: TextView
    private lateinit var speedSpinner: Spinner
    private lateinit var sleepTimerButton: Button
    private lateinit var equalizerToggle: CheckBox
    private lateinit var equalizerLevelSeek: SeekBar
    private lateinit var nowPlayingBar: LinearLayout
    private lateinit var nowPlayingArtwork: ImageView
    private lateinit var nowPlayingTitle: TextView
    private lateinit var nowPlayingArtist: TextView
    private lateinit var nowPlayingButton: Button
    private var downloadService: DownloadService? = null
    private var playbackService: PlaybackService? = null
    private var bound = false
    private var playbackBound = false
    private var destroyed = false
    private var searchRequestId = 0
    private var previousJobStates = emptyMap<Long, String>()
    private var latestJobs: List<DownloadJob> = emptyList()
    private var renderedJobStates = emptyMap<Long, String>()
    private var concurrencySettingReady = false
    private var wifiSettingReady = false
    private var currentTab = R.string.nav_home
    private var visibleTabIndex = -1
    private var activeSearchQuery = ""
    private var searchOffset = 0
    private var searchCardCount = 0
    private var loadingMoreSearchResults = false
    private var hasMoreSearchResults = false
    private val searchDownloadIndicators = mutableMapOf<String, SearchDownloadIndicator>()
    private val recentHistoryEntries = mutableListOf<PlaybackHistoryEntry>()
    private var recentHistoryCursor: PlaybackHistoryEntry? = null
    private var recentHistoryGeneration = 0
    private var recentHistoryLoading = false
    private var recentHistoryHasMore = true
    private var recentHistoryLoadFailed = false
    private var libraryTracks: List<AudioTrack> = emptyList()
    private var seeking = false
    private var syncingPlaybackControls = false
    private var currentPlaybackTrack: AudioTrack? = null
    private var playbackSnapshot: PlaybackSnapshot? = null
    private var lastArtworkAnimationUri: String? = null
    private val metadataExecutor = Executors.newSingleThreadExecutor()
    private val thumbnailExecutor = Executors.newFixedThreadPool(2)
    private val playlists by lazy { PlaylistStore(this) }
    private val importedUris: Set<String>
        get() =
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).getStringSet(KEY_IMPORTED, emptySet())
            .orEmpty().toSet()
    private val formatModes by lazy { resources.getStringArray(R.array.format_modes) }
    private val formatKeys = arrayOf("best", "1080p", "720p", "480p", "audio", "audio_mp3")
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            downloadService = (binder as DownloadService.LocalBinder).service
            downloadService?.setListener(this@MainActivity)
            downloadService?.resumeQueuedJobs()
            concurrencySettingReady = true
            concurrencySpinner.setSelection(
                (downloadService?.getMaxConcurrentDownloads() ?: DEFAULT_CONCURRENT_DOWNLOADS) - 1,
            )
            wifiSettingReady = true
            wifiOnlyToggle.isChecked = downloadService?.getWifiOnly() ?: false
            refreshHistory()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            downloadService = null
        }
    }

    private fun addHistoryEmptyMessage(message: String) {
        downloadHistoryList.addView(
            emptyMessage(message),
            GridLayout.LayoutParams().apply {
                width = MATCH
                height = WRAP
                columnSpec = GridLayout.spec(0, downloadHistoryList.columnCount)
            },
        )
    }

    private val playbackConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            playbackService = (binder as PlaybackService.LocalBinder).service
            playbackService?.setListener(playbackListener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
        }
    }

    private val playbackListener = object : PlaybackService.Listener {
        override fun onPlaybackChanged(snapshot: PlaybackSnapshot) {
            if (destroyed) return
            runOnUiThread {
                if (!destroyed) renderPlaybackSnapshot(snapshot)
            }
        }

        override fun onPlaybackHistoryChanged() {
            if (!destroyed) runOnUiThread {
                if (!destroyed) refreshPlaybackHistory()
            }
        }

        override fun onPlaybackHistoryError() {
            if (!destroyed) runOnUiThread {
                if (!destroyed) {
                    Toast.makeText(this@MainActivity, R.string.playback_history_save_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COLOR_BACKGROUND
        window.navigationBarColor = COLOR_BACKGROUND
        window.decorView.systemUiVisibility = 0
        buildScreen()
        restoreSettings()
        bound = bindService(Intent(this, DownloadService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        playbackBound = bindService(Intent(this, PlaybackService::class.java), playbackConnection, Context.BIND_AUTO_CREATE)
        refreshHistory()
        refreshLibrary()
        refreshPlaybackHistory()
        refreshPlaylists()
        requestMissingPermissions()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS &&
            (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED)
        ) if (::statusText.isInitialized) statusText.text = getString(R.string.notifications_disabled)
        if (requestCode == REQUEST_AUDIO_LIBRARY) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                refreshLibrary()
                requestMissingPermissions()
            } else {
                renderHomeLibrary()
            }
        }
    }

    private fun requestMissingPermissions() {
        val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (checkSelfPermission(audioPermission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(audioPermission), REQUEST_AUDIO_LIBRARY)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun hasAudioLibraryPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun launchAudioImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_IMPORT_AUDIO)
    }

    private fun buildScreen() {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }
        content = FrameLayout(this)
        screen.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val homeScreen = newScrollScreen()
        buildHomeScreen(homeScreen)
        content.addView(homeScreen, FrameLayout.LayoutParams(MATCH, MATCH))
        val searchScreen = newScrollScreen()
        buildSearchScreen(searchScreen)
        content.addView(searchScreen, FrameLayout.LayoutParams(MATCH, MATCH))
        val queueScreen = newScrollScreen()
        buildQueueScreen(queueScreen)
        content.addView(queueScreen, FrameLayout.LayoutParams(MATCH, MATCH))
        val libraryScreen = newScrollScreen()
        buildLibraryScreen(libraryScreen)
        content.addView(libraryScreen, FrameLayout.LayoutParams(MATCH, MATCH))
        val playlistsScreen = newScrollScreen()
        buildPlaylistsScreen(playlistsScreen)
        content.addView(playlistsScreen, FrameLayout.LayoutParams(MATCH, MATCH))
        val playerScreen = newScrollScreen()
        buildPlayerScreen(playerScreen)
        content.addView(playerScreen, FrameLayout.LayoutParams(MATCH, MATCH))

        nowPlayingBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(COLOR_PLAYER_BAR, dp(14))
            setPadding(dp(10), dp(8), dp(8), dp(8))
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener { showTab(R.string.nav_player) }
        }
        nowPlayingArtwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(COLOR_SURFACE, dp(7))
            setImageResource(android.R.drawable.ic_media_play)
        }
        nowPlayingBar.addView(nowPlayingArtwork, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            marginEnd = dp(10)
        })
        val nowPlayingLabels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nowPlayingTitle = TextView(this).apply {
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
        }
        nowPlayingArtist = TextView(this).apply {
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(COLOR_MUTED)
        }
        nowPlayingLabels.addView(nowPlayingTitle)
        nowPlayingLabels.addView(nowPlayingArtist)
        nowPlayingBar.addView(nowPlayingLabels, LinearLayout.LayoutParams(0, WRAP, 1f))
        nowPlayingButton = Button(this).apply {
            text = "▶"
            contentDescription = getString(R.string.play)
            styleActionButton(this, false)
            setOnClickListener { togglePlayback() }
        }
        nowPlayingBar.addView(nowPlayingButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginStart = dp(6)
        })

        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(COLOR_BACKGROUND)
            setPadding(dp(4), dp(5), dp(4), dp(7))
        }
        listOf(
            R.string.nav_home to homeScreen,
            R.string.nav_search to searchScreen,
            R.string.nav_library to libraryScreen,
        ).forEach { (label, view) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                tag = label
                contentDescription = getString(label)
                background = roundedBackground(
                    if (label == currentTab) COLOR_SURFACE else Color.TRANSPARENT,
                    dp(14),
                )
                setOnClickListener {
                    animateSuccess(this)
                    showTab(label)
                }
            }
            val icon = TextView(this).apply {
                text = navigationGlyph(label)
                textSize = 21f
                gravity = Gravity.CENTER
                setTextColor(if (label == currentTab) COLOR_ACCENT else COLOR_MUTED)
            }
            val title = TextView(this).apply {
                text = getString(label)
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(if (label == currentTab) COLOR_ACCENT else COLOR_MUTED)
            }
            item.addView(icon)
            item.addView(title)
            item.isSelected = label == currentTab
            navigation.addView(item, LinearLayout.LayoutParams(0, dp(56), 1f))
        }
        screen.addView(nowPlayingBar, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            leftMargin = dp(12)
            rightMargin = dp(12)
            topMargin = dp(5)
            bottomMargin = dp(3)
        })
        screen.addView(navigation, matchWidth())
        setContentView(screen)
        showTab(currentTab)
    }

    private fun newScrollScreen() = ScrollView(this).apply {
        isFillViewport = true
        clipToPadding = false
        val maxWidth = minOf(
            dp(MAX_CONTENT_WIDTH_DP),
            (windowWidthPx() - dp(24)).coerceAtLeast(0),
        )
        addView(FrameLayout(this@MainActivity).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                val horizontalPadding = minOf(dp(18), maxWidth / 12)
                setPadding(horizontalPadding, dp(22), horizontalPadding, dp(26))
                setBackgroundColor(COLOR_BACKGROUND)
                tag = "tab-content"
            }, FrameLayout.LayoutParams(maxWidth, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }, ViewGroup.LayoutParams(MATCH, WRAP))
    }

    private fun scrollContent(scroll: ScrollView) =
        (scroll.getChildAt(0) as FrameLayout).getChildAt(0) as LinearLayout

    private fun navigationGlyph(label: Int): String = when (label) {
        R.string.nav_home -> "⌂"
        R.string.nav_search -> "⌕"
        else -> "▤"
    }

    private fun searchGridColumnCount(): Int {
        val availableWidthDp = (resources.configuration.screenWidthDp - 24)
            .coerceAtMost(MAX_CONTENT_WIDTH_DP)
            .coerceAtLeast(0)
        return when {
            availableWidthDp >= 680 -> 4
            availableWidthDp >= 470 -> 3
            else -> 2
        }
    }

    private fun windowWidthPx(): Int =
        resources.configuration.screenWidthDp.takeIf { it > 0 }?.let(::dp)
            ?: resources.displayMetrics.widthPixels

    private fun addSearchMessage(message: CharSequence, onClick: (() -> Unit)? = null) {
        val item = emptyMessage(message.toString()).apply {
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
                setTextColor(COLOR_ACCENT)
            }
        }
        val columns = searchResults.columnCount.coerceAtLeast(1)
        searchResults.addView(
            item,
            GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(0, columns),
            ).apply {
                width = MATCH
                height = WRAP
                setMargins(0, dp(6), 0, dp(10))
            },
        )
    }

    private fun addSearchResultCard(item: MusicSearchResult) {
        val columns = searchResults.columnCount.coerceAtLeast(1)
        val containerWidth = minOf(
            dp(MAX_CONTENT_WIDTH_DP),
            (windowWidthPx() - dp(24)).coerceAtLeast(0),
        )
        val contentWidth = (containerWidth - dp(36) - dp(8) * (columns - 1))
            .coerceAtLeast(dp(columns * 68))
        val cardWidth = contentWidth / columns
        val cellIndex = searchCardCount++
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_CARD, dp(14))
            setPadding(dp(7), dp(7), dp(7), dp(8))
        }
        val artworkSize = (cardWidth - dp(14)).coerceAtLeast(dp(48))
        val artwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(COLOR_SURFACE, dp(9))
            setImageResource(android.R.drawable.ic_media_play)
            contentDescription = getString(R.string.track_artwork)
        }
        card.addView(artwork, LinearLayout.LayoutParams(MATCH, artworkSize))
        item.thumbnailUrl?.let { loadThumbnail(it, artwork) }
        card.addView(TextView(this).apply {
            text = item.title
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            setPadding(dp(2), dp(8), dp(2), dp(2))
        }, LinearLayout.LayoutParams(MATCH, dp(48)))
        card.addView(TextView(this).apply {
            text = item.uploader
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(COLOR_MUTED)
            setPadding(dp(2), 0, dp(2), dp(4))
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(View(this), LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(Button(this).apply {
            text = "⋮"
            contentDescription = getString(R.string.track_actions_accessibility, item.title)
            styleActionButton(this, false)
            setOnClickListener { showSearchResultMenu(item, this) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(3) })
        val downloadButton = Button(this).apply {
            text = "↓"
            contentDescription = getString(R.string.download_track_named, item.title)
            styleActionButton(this, false)
            setOnClickListener { queueSearchResultDownload(item) }
        }
        val downloadSpinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
            visibility = View.GONE
        }
        val downloadIndicator = FrameLayout(this).apply {
            addView(downloadButton, FrameLayout.LayoutParams(dp(44), dp(44)))
            addView(
                downloadSpinner,
                FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER),
            )
        }
        actions.addView(downloadIndicator, LinearLayout.LayoutParams(dp(44), dp(44)))
        card.addView(actions, LinearLayout.LayoutParams(MATCH, dp(44)).apply { topMargin = dp(2) })
        searchDownloadIndicators[item.url] = SearchDownloadIndicator(item, downloadButton, downloadSpinner)
        val params = GridLayout.LayoutParams(
            GridLayout.spec(cellIndex / columns),
            GridLayout.spec(cellIndex % columns),
        ).apply {
            width = cardWidth
            height = WRAP
            setMargins(dp(4), dp(4), dp(4), dp(8))
        }
        searchResults.addView(card, params)
        renderSearchDownloadIndicators(latestJobs)
    }

    private fun showSearchResultMenu(item: MusicSearchResult, anchor: View) {
        val track = item.toAudioTrack()
        val likedName = getString(R.string.liked_songs)
        val likedPlaylist = try {
            playlists.getPlaylists().firstOrNull { it.name.equals(likedName, ignoreCase = true) }
        } catch (error: Exception) {
            showError(getString(R.string.playlist_error, error.localizedMessage ?: ""))
            return
        }
        val isLiked = likedPlaylist?.tracks?.contains(item.url) == true
        android.widget.PopupMenu(this, anchor).apply {
            menu.add(0, 0, 0, R.string.add_to_playlist)
            menu.add(0, 1, 1, if (isLiked) R.string.remove_from_liked_songs else R.string.like)
            menu.add(0, 2, 2, R.string.share)
            menu.add(0, 3, 3, R.string.find_similar_music)
            menu.add(0, 4, 4, R.string.find_similar_artists)
            menu.add(0, 5, 5, R.string.download_track)
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    0 -> choosePlaylistForTrack(track) { saveSearchResultMetadata(item) }
                    1 -> toggleTrackLike(track, likedPlaylist) { saveSearchResultMetadata(item) }
                    2 -> shareSearchResult(item)
                    3 -> searchMusic("${item.title} ${item.uploader}")
                    4 -> searchMusic("${item.uploader} similar artists")
                    5 -> queueSearchResultDownload(item)
                }
                true
            }
            show()
        }
    }

    private fun MusicSearchResult.toAudioTrack() = AudioTrack(
        uri = Uri.parse(url),
        title = title,
        artist = uploader,
        album = "",
        durationMs = (durationSeconds ?: 0) * 1000L,
        artwork = null,
        thumbnailUrl = thumbnailUrl,
    )

    private fun saveSearchResultMetadata(item: MusicSearchResult) {
        playlists.saveTrackMetadata(
            trackUri = item.url,
            title = item.title,
            artist = item.uploader,
            album = "",
            durationMs = item.durationSeconds?.times(1000L),
            artworkPath = item.thumbnailUrl,
        )
    }

    private fun resolvePlaylistTrack(raw: String): AudioTrack? {
        libraryTracks.firstOrNull { it.uri.toString() == raw }?.let { return it }
        val uri = Uri.parse(raw)
        if (uri.scheme !in setOf("http", "https")) return null
        val metadata = playlists.getTrackMetadata(raw) ?: return null
        return AudioTrack(
            uri = uri,
            title = metadata.title,
            artist = metadata.artist,
            album = metadata.album,
            durationMs = metadata.durationMs ?: 0L,
            artwork = null,
            thumbnailUrl = metadata.artworkPath,
        )
    }

    private fun shareSearchResult(item: MusicSearchResult) {
        try {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, item.title)
                putExtra(Intent.EXTRA_TEXT, item.url)
            }
            startActivity(Intent.createChooser(share, getString(R.string.share_track)))
        } catch (error: Exception) {
            showError(getString(R.string.file_action_error, error.localizedMessage ?: error.javaClass.simpleName))
        }
    }

    private fun queueSearchResultDownload(item: MusicSearchResult) {
        val indicator = searchDownloadIndicators[item.url]
        indicator?.let {
            it.button.visibility = View.GONE
            it.spinner.visibility = View.VISIBLE
        }
        try {
            enqueueUrls(listOf(item.url), showStatus = false)
        } catch (error: Exception) {
            indicator?.let {
                it.spinner.visibility = View.GONE
                it.button.visibility = View.VISIBLE
            }
            showError(getString(R.string.download_error, error.localizedMessage ?: error.javaClass.simpleName))
        }
    }

    private fun renderSearchDownloadIndicators(jobs: List<DownloadJob>) {
        // Build a url→job map once (O(n)) instead of scanning all jobs per indicator (O(n×m)).
        val jobByUrl = mutableMapOf<String, DownloadJob>()
        jobs.forEach { job ->
            val jobUrls = org.json.JSONArray(job.urlsJson)
            for (i in 0 until jobUrls.length()) {
                val url = jobUrls.optString(i)
                if (url.isNotEmpty()) jobByUrl[url] = job
            }
        }

        searchDownloadIndicators.forEach { (url, indicator) ->
            val job = jobByUrl[url]
            val active = job?.state == DownloadQueueStore.STATE_QUEUED ||
                job?.state == DownloadQueueStore.STATE_RUNNING
            indicator.spinner.visibility = if (active) View.VISIBLE else View.GONE
            indicator.button.visibility = if (active) View.GONE else View.VISIBLE
            when (job?.state) {
                DownloadQueueStore.STATE_COMPLETE -> {
                    indicator.button.text = "✓"
                    indicator.button.contentDescription = getString(R.string.download_complete_accessibility, indicator.item.title)
                }
                DownloadQueueStore.STATE_PAUSED -> {
                    indicator.button.text = "Ⅱ"
                    indicator.button.contentDescription = getString(R.string.download_paused_accessibility, indicator.item.title)
                    indicator.button.setOnClickListener { downloadService?.resumeJob(job.id) }
                }
                DownloadQueueStore.STATE_PARTIAL, DownloadQueueStore.STATE_FAILED, DownloadQueueStore.STATE_CANCELED -> {
                    indicator.button.text = "↻"
                    indicator.button.contentDescription = getString(R.string.retry_download_accessibility, indicator.item.title)
                    indicator.button.setOnClickListener { queueSearchResultDownload(indicator.item) }
                }
                else -> {
                    indicator.button.text = "↓"
                    indicator.button.contentDescription = getString(R.string.download_track_named, indicator.item.title)
                    indicator.button.setOnClickListener { queueSearchResultDownload(indicator.item) }
                }
            }
        }
    }

    private fun showHomeCollection() {
        homeLibraryContent.visibility = View.VISIBLE
        animateHomeContent()
    }

    private fun clearOnlineSearch() {
        searchRequestId++
        loadingMoreSearchResults = false
        hasMoreSearchResults = false
        searchInput.text.clear()
        searchDownloadIndicators.clear()
        searchResults.removeAllViews()
        searchResultsTitle.visibility = View.GONE
        searchResults.visibility = View.VISIBLE
        addSearchMessage(getString(R.string.search_prompt))
    }

    private fun updateHomeCategoryStyles() {
        for (index in 0 until homeCategories.childCount) {
            val chip = homeCategories.getChildAt(index) as TextView
            val selected = chip.tag == homeCategory
            chip.setTextColor(
                if (selected) COLOR_BACKGROUND
                else if (chip.tag == HOME_CATEGORY_DISCOVER) COLOR_ACCENT_WARM else COLOR_TEXT,
            )
            chip.isSelected = selected
            chip.background = roundedBackground(
                if (selected) homeCategoryColor(homeCategory) else COLOR_SURFACE,
                dp(22),
            )
        }
        for (index in 0 until homeSubcategories.childCount) {
            val chip = homeSubcategories.getChildAt(index) as TextView
            val selected = chip.tag == homeSubcategory
            chip.setTextColor(if (selected) COLOR_BACKGROUND else COLOR_TEXT)
            chip.isSelected = selected
            chip.background = roundedBackground(
                if (selected) COLOR_ACCENT_SECONDARY else COLOR_SURFACE,
                dp(22),
            )
        }
    }

    private fun homeCategoryColor(category: Int): Int = when (category) {
        HOME_CATEGORY_ARTISTS -> COLOR_ACCENT_SECONDARY
        HOME_CATEGORY_ALBUMS -> COLOR_ACCENT_WARM
        HOME_CATEGORY_PLAYLISTS -> COLOR_ACCENT_BLUE
        HOME_CATEGORY_DISCOVER -> COLOR_ACCENT_PINK
        else -> COLOR_ACCENT
    }

    private fun homeSubcategoryLabels(category: Int): List<Int> = when (category) {
        HOME_CATEGORY_RECENT_PLAYED -> emptyList()
        HOME_CATEGORY_MUSIC -> listOf(
            R.string.home_music_all,
            R.string.home_music_recent,
            R.string.home_music_longest,
        )
        HOME_CATEGORY_ARTISTS -> listOf(
            R.string.home_artists_az,
            R.string.home_artists_top,
            R.string.home_artists_recent,
        )
        HOME_CATEGORY_ALBUMS -> listOf(
            R.string.home_albums_az,
            R.string.home_albums_top,
            R.string.home_albums_recent,
        )
        HOME_CATEGORY_PLAYLISTS -> listOf(
            R.string.home_playlists_mine,
            R.string.home_playlists_create,
            R.string.home_playlists_import,
        )
        else -> listOf(
            R.string.mood_pop,
            R.string.mood_chill,
            R.string.mood_focus,
        )
    }

    private fun renderHomeSubcategories() {
        if (!::homeSubcategories.isInitialized) return
        homeSubcategories.removeAllViews()
        homeSubcategoryLabels(homeCategory).forEachIndexed { index, label ->
            homeSubcategories.addView(TextView(this).apply {
                text = getString(label)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(9), dp(14), dp(9))
                textSize = 14f
                minHeight = dp(48)
                isClickable = true
                isFocusable = true
                tag = index
                contentDescription = getString(R.string.home_category_accessibility, getString(label))
                setOnClickListener {
                    homeSubcategory = index
                    animateSuccess(this)
                    updateHomeCategoryStyles()
                    if (homeCategory == HOME_CATEGORY_DISCOVER) {
                        showTab(R.string.nav_search)
                        searchInput.setText(getString(label))
                        searchMusic(getString(label))
                    } else {
                        showHomeCollection()
                        renderHomeLibrary()
                    }
                }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
        }
    }

    private fun homeGridCardWidth(columns: Int): Int {
        val containerWidth = minOf(
            dp(MAX_CONTENT_WIDTH_DP),
            (windowWidthPx() - dp(24)).coerceAtLeast(0),
        )
        return (containerWidth - dp(36) - dp(8) * (columns - 1))
            .coerceAtLeast(dp(columns * 68)) / columns
    }

    private fun addHomeTrackCard(
        track: AudioTrack,
        queue: List<AudioTrack>,
        index: Int,
        targetGrid: GridLayout = homeMusicGrid,
        playedAt: Long? = null,
        historyEntryId: Long? = null,
    ) {
        val columns = searchGridColumnCount()
        val cellIndex = targetGrid.childCount
        val cardWidth = homeGridCardWidth(columns)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_CARD, dp(14))
            setPadding(dp(7), dp(7), dp(7), dp(8))
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.home_track_accessibility, track.title, track.artist)
            setOnClickListener {
                animateSuccess(this)
                playTrackList(queue, index)
            }
            setOnLongClickListener {
                showTrackDetails(track)
                true
            }
        }
        card.addView(
            artworkView(track, cardWidth - dp(14)),
            LinearLayout.LayoutParams(MATCH, cardWidth - dp(14)),
        )
        card.addView(TextView(this).apply {
            text = track.title
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            setPadding(dp(2), dp(8), dp(2), 0)
        }, LinearLayout.LayoutParams(MATCH, dp(45)))
        if (playedAt != null) {
            card.addView(TextView(this).apply {
                text = getString(
                    R.string.recent_played_at,
                    DateUtils.getRelativeTimeSpanString(playedAt),
                )
                textSize = 10f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(COLOR_MUTED)
                setPadding(dp(2), dp(2), dp(2), 0)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val artistLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        artistLine.addView(TextView(this).apply {
            text = track.artist
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(COLOR_MUTED)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        artistLine.addView(Button(this).apply {
            text = "⋮"
            contentDescription = getString(R.string.track_actions_accessibility, track.title)
            styleActionButton(this, false)
            setOnClickListener { showTrackMenu(track, this, historyEntryId) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(3) })
        card.addView(artistLine, matchWidth())
        val params = GridLayout.LayoutParams(
            GridLayout.spec(cellIndex / columns),
            GridLayout.spec(cellIndex % columns),
        ).apply {
            width = cardWidth
            height = WRAP
            setMargins(dp(4), dp(4), dp(4), dp(8))
        }
        targetGrid.addView(card, params)
        animateHomeCard(card, cellIndex)
    }

    private fun showTrackMenu(track: AudioTrack, anchor: View, historyEntryId: Long? = null) {
        val likedName = getString(R.string.liked_songs)
        val likedPlaylist = try {
            playlists.getPlaylists().firstOrNull { it.name.equals(likedName, ignoreCase = true) }
        } catch (error: Exception) {
            showError(getString(R.string.playlist_error, error.localizedMessage ?: ""))
            return
        }
        val isLiked = likedPlaylist?.tracks?.contains(track.uri.toString()) == true
        android.widget.PopupMenu(this, anchor).apply {
            menu.add(0, 0, 0, R.string.add_to_playlist)
            menu.add(0, 1, 1, if (isLiked) R.string.remove_from_liked_songs else R.string.like)
            menu.add(0, 2, 2, R.string.share)
            menu.add(0, 3, 3, R.string.find_similar_music)
            menu.add(0, 4, 4, R.string.find_similar_artists)
            if (historyEntryId != null) menu.add(0, 5, 5, R.string.remove_from_play_history)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> choosePlaylistForTrack(track)
                    1 -> toggleTrackLike(track, likedPlaylist)
                    2 -> shareDownloadedFile(
                        track.uri,
                        contentResolver.getType(track.uri) ?: "audio/*",
                        track.title,
                    )
                    3 -> searchMusic("${track.title} ${track.artist}")
                    4 -> searchMusic("${track.artist} similar artists")
                    5 -> historyEntryId?.let(::removePlaybackHistoryEntry)
                }
                true
            }
            show()
        }
    }

    private fun toggleTrackLike(
        track: AudioTrack,
        likedPlaylist: SavedPlaylist?,
        prepareForSave: () -> Unit = {},
    ) {
        try {
            if (likedPlaylist != null && track.uri.toString() in likedPlaylist.tracks) {
                check(playlists.removeTrack(likedPlaylist.id, track.uri.toString())) {
                    getString(R.string.playlist_track_failed)
                }
                Toast.makeText(this, getString(R.string.removed_from_liked_songs), Toast.LENGTH_SHORT).show()
            } else {
                prepareForSave()
                val playlistId = likedPlaylist?.id ?: playlists.createPlaylist(getString(R.string.liked_songs))
                check(playlists.addTrack(playlistId, track.uri.toString())) {
                    getString(R.string.playlist_track_failed)
                }
                Toast.makeText(this, getString(R.string.added_to_liked_songs), Toast.LENGTH_SHORT).show()
            }
            refreshPlaylists()
        } catch (error: Exception) {
            showError(error.localizedMessage ?: getString(R.string.playlist_track_failed))
        }
    }

    private fun addHomeGroupCard(
        name: String,
        tracks: List<AudioTrack>,
        cellIndex: Int,
    ) {
        val columns = searchGridColumnCount()
        val cardWidth = homeGridCardWidth(columns)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_CARD, dp(14))
            setPadding(dp(7), dp(7), dp(7), dp(8))
            isClickable = true
            isFocusable = true
            contentDescription = getString(
                R.string.home_group_accessibility,
                name,
                getString(R.string.home_artist_count, tracks.size),
            )
            setOnClickListener {
                animateSuccess(this)
                selectedHomeGroup = name
                renderHomeLibrary()
            }
        }
        card.addView(
            artworkView(tracks.first(), cardWidth - dp(14)),
            LinearLayout.LayoutParams(MATCH, cardWidth - dp(14)),
        )
        card.addView(TextView(this).apply {
            text = name
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            setPadding(dp(2), dp(8), dp(2), dp(2))
        }, LinearLayout.LayoutParams(MATCH, dp(46)))
        card.addView(TextView(this).apply {
            text = getString(R.string.home_artist_count, tracks.size)
            textSize = 11f
            setTextColor(COLOR_MUTED)
            setPadding(dp(2), 0, dp(2), dp(3))
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        val params = GridLayout.LayoutParams(
            GridLayout.spec(cellIndex / columns),
            GridLayout.spec(cellIndex % columns),
        ).apply {
            width = cardWidth
            height = WRAP
            setMargins(dp(4), dp(4), dp(4), dp(8))
        }
        homeMusicGrid.addView(card, params)
        animateHomeCard(card, cellIndex)
    }

    private fun renderHomeRecentHistory() {
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(sectionTitle(getString(R.string.recent_history_title)), LinearLayout.LayoutParams(0, WRAP, 1f))
            if (recentHistoryEntries.isNotEmpty()) addView(Button(this@MainActivity).apply {
                text = getString(R.string.clear_play_history)
                styleActionButton(this, false)
                setOnClickListener { confirmClearPlaybackHistory() }
            }, LinearLayout.LayoutParams(WRAP, dp(44)))
        }
        homeLibraryContent.addView(heading, matchWidth())
        if (recentHistoryEntries.isEmpty()) {
            val message = when {
                recentHistoryLoadFailed -> getString(R.string.recent_history_load_error)
                recentHistoryLoading -> getString(R.string.recent_history_loading)
                else -> getString(R.string.recent_history_empty)
            }
            homeLibraryContent.addView(emptyMessage(message), matchWidth())
            if (recentHistoryLoadFailed) {
                homeLibraryContent.addView(Button(this).apply {
                    text = getString(R.string.action_retry)
                    styleActionButton(this, false)
                    setOnClickListener { loadMorePlaybackHistory() }
                }, matchWidth())
            }
            return
        }
        val tracks = recentHistoryEntries.map(::toAudioTrack)
        homeMusicGrid = newHomeMusicGrid()
        recentHistoryEntries.forEachIndexed { index, entry ->
            addHomeTrackCard(
                track = tracks[index],
                queue = tracks,
                index = index,
                playedAt = entry.playedAt,
                historyEntryId = entry.id,
            )
        }
        homeLibraryContent.addView(homeMusicGrid, matchWidth())
        if (recentHistoryLoadFailed) {
            homeLibraryContent.addView(emptyMessage(getString(R.string.recent_history_load_error)), matchWidth())
            homeLibraryContent.addView(Button(this).apply {
                text = getString(R.string.action_retry)
                styleActionButton(this, false)
                setOnClickListener { loadMorePlaybackHistory() }
            }, matchWidth())
        } else if (recentHistoryHasMore || recentHistoryLoading) {
            homeLibraryContent.addView(Button(this).apply {
                text = getString(if (recentHistoryLoading) R.string.recent_history_loading else R.string.load_older)
                styleActionButton(this, false)
                isEnabled = !recentHistoryLoading
                setOnClickListener { loadMorePlaybackHistory() }
            }, matchWidth().apply { topMargin = dp(8) })
        }
    }

    private fun addHomePlaylistCard(playlist: SavedPlaylist, cellIndex: Int) {
        val columns = searchGridColumnCount()
        val cardWidth = homeGridCardWidth(columns)
        val coverTrack = playlist.tracks.firstNotNullOfOrNull { uri ->
            resolvePlaylistTrack(uri)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_CARD, dp(14))
            setPadding(dp(7), dp(7), dp(7), dp(8))
            isClickable = true
            isFocusable = true
            contentDescription = getString(
                R.string.home_playlist_accessibility,
                playlist.name,
                getString(R.string.home_artist_count, playlist.tracks.size),
            )
            setOnClickListener {
                animateSuccess(this)
                openPlaylist(playlist.id)
            }
        }
        card.addView(
            coverTrack?.let { artworkView(it, cardWidth - dp(14)) } ?: ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageResource(android.R.drawable.ic_media_play)
                background = roundedBackground(COLOR_SURFACE, dp(9))
                contentDescription = getString(R.string.track_artwork)
            },
            LinearLayout.LayoutParams(MATCH, cardWidth - dp(14)),
        )
        card.addView(TextView(this).apply {
            text = playlist.name
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            setPadding(dp(2), dp(8), dp(2), dp(2))
        }, LinearLayout.LayoutParams(MATCH, dp(46)))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(TextView(this).apply {
            text = getString(R.string.home_artist_count, playlist.tracks.size)
            textSize = 11f
            setTextColor(COLOR_MUTED)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        actions.addView(Button(this).apply {
            text = "▶"
            contentDescription = getString(R.string.play_track_named, playlist.name)
            styleActionButton(this, true)
            setOnClickListener { loadPlaylistAndPlay(playlist.id) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(3) })
        card.addView(actions)
        val params = GridLayout.LayoutParams(
            GridLayout.spec(cellIndex / columns),
            GridLayout.spec(cellIndex % columns),
        ).apply {
            width = cardWidth
            height = WRAP
            setMargins(dp(4), dp(4), dp(4), dp(8))
        }
        homeMusicGrid.addView(card, params)
        animateHomeCard(card, cellIndex)
    }

    private fun renderHomeLibrary() {
        if (!::homeLibraryContent.isInitialized) return
        updateHomeCategoryStyles()
        homeLibraryContent.removeAllViews()
        animateHomeContent()
        if (homeCategory == HOME_CATEGORY_DISCOVER) {
            homeLibraryContent.addView(
                sectionTitle(getString(R.string.home_discover_prompt)),
                matchWidth(),
            )
            homeLibraryContent.addView(TextView(this).apply {
                text = getString(R.string.home_discover_hint)
                textSize = 14f
                setTextColor(COLOR_MUTED)
                setPadding(0, dp(6), 0, dp(12))
            }, matchWidth())
            homeSubcategoryLabels(HOME_CATEGORY_DISCOVER).forEachIndexed { index, label ->
                homeLibraryContent.addView(Button(this).apply {
                    text = getString(R.string.home_discover_search, getString(label))
                    styleActionButton(this, index == homeSubcategory)
                    setOnClickListener {
                        homeSubcategory = index
                        updateHomeCategoryStyles()
                        showTab(R.string.nav_search)
                        searchInput.setText(getString(label))
                        searchMusic(getString(label))
                    }
                }, matchWidth().apply { bottomMargin = dp(8) })
            }
            return
        }
        if (homeCategory == HOME_CATEGORY_RECENT_PLAYED) {
            renderHomeRecentHistory()
            return
        }
        if (homeCategory == HOME_CATEGORY_PLAYLISTS) {
            if (homeSubcategory == 1) {
                homeLibraryContent.addView(Button(this).apply {
                    text = getString(R.string.home_playlists_create_action)
                    styleActionButton(this, true)
                    setOnClickListener { promptCreatePlaylist() }
                }, matchWidth().apply { bottomMargin = dp(8) })
            } else if (homeSubcategory == 2) {
                homeLibraryContent.addView(Button(this).apply {
                    text = getString(R.string.import_playlist_m3u)
                    styleActionButton(this, true)
                    setOnClickListener { launchPlaylistImport() }
                }, matchWidth().apply { bottomMargin = dp(8) })
            }
            val saved = try {
                playlists.getPlaylists()
            } catch (error: Exception) {
                homeLibraryContent.addView(
                    emptyMessage(getString(R.string.playlist_error, error.localizedMessage ?: "")),
                )
                return
            }
            if (saved.isEmpty()) {
                homeLibraryContent.addView(emptyMessage(getString(
                    if (homeSubcategory == 1) R.string.home_playlists_create_empty
                    else if (homeSubcategory == 2) R.string.home_playlists_import_empty
                    else R.string.home_playlists_empty,
                )))
                return
            }
            homeLibraryContent.addView(
                sectionTitle(getString(R.string.home_category_playlists)),
                matchWidth(),
            )
            homeMusicGrid = newHomeMusicGrid()
            saved.forEachIndexed { index, playlist -> addHomePlaylistCard(playlist, index) }
            homeLibraryContent.addView(homeMusicGrid, matchWidth())
            return
        }
        if (libraryTracks.isEmpty()) {
            homeLibraryContent.addView(buildHomeEmptyState(), matchWidth())
            return
        }
        if (homeCategory == HOME_CATEGORY_ARTISTS || homeCategory == HOME_CATEGORY_ALBUMS) {
            val categoryName = getString(
                if (homeCategory == HOME_CATEGORY_ARTISTS) R.string.home_category_artists
                else R.string.home_category_albums,
            )
            val grouped = libraryTracks.groupBy {
                if (homeCategory == HOME_CATEGORY_ARTISTS) {
                    it.artist.ifBlank { getString(R.string.unknown_artist) }
                } else {
                    it.album.ifBlank { getString(R.string.unknown_album) }
                }
            }
            val sortedGroups = when (homeSubcategory) {
                1 -> grouped.entries.sortedWith(
                    compareByDescending<Map.Entry<String, List<AudioTrack>>> { it.value.size }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.key },
                )
                2 -> grouped.entries.sortedByDescending { entry ->
                    entry.value.maxOfOrNull { it.dateAdded } ?: 0L
                }
                else -> grouped.entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key })
            }
            val selectedGroup = selectedHomeGroup
            if (selectedGroup != null) {
                homeLibraryContent.addView(Button(this).apply {
                    text = getString(R.string.home_group_back, categoryName.lowercase())
                    styleActionButton(this, false)
                    setOnClickListener {
                        selectedHomeGroup = null
                        renderHomeLibrary()
                    }
                }, matchWidth())
                homeLibraryContent.addView(sectionTitle(selectedGroup), sectionSpacing())
                homeMusicGrid = newHomeMusicGrid()
                val members = grouped[selectedGroup].orEmpty()
                members.forEachIndexed { index, track -> addHomeTrackCard(track, members, index) }
                homeLibraryContent.addView(homeMusicGrid, matchWidth())
                return
            }
            homeLibraryContent.addView(sectionTitle(categoryName), matchWidth())
            homeMusicGrid = newHomeMusicGrid()
            sortedGroups.forEachIndexed { index, (name, tracks) ->
                addHomeGroupCard(name, tracks, index)
            }
            homeLibraryContent.addView(homeMusicGrid, matchWidth())
            return
        }

        val ordered = when (homeSubcategory) {
            1 -> libraryTracks.sortedByDescending { it.dateAdded }
            2 -> libraryTracks.sortedByDescending { it.durationMs }
            else -> libraryTracks
        }
        val tracks = if (homeSubcategory == 1) ordered.take(40) else ordered
        val heading = getString(
            when {
                homeSubcategory == 1 -> R.string.home_recent_heading
                homeSubcategory == 2 -> R.string.home_music_longest
                else -> R.string.home_collection_heading
            },
        )
        homeLibraryContent.addView(
            homeSectionHeading(
                "$heading · ${getString(R.string.home_artist_count, tracks.size)}",
                getString(R.string.play),
                onAction = { if (tracks.isNotEmpty()) playTrackList(tracks, 0) },
            ),
            sectionSpacing(),
        )
        homeMusicGrid = newHomeMusicGrid()
        tracks.forEachIndexed { index, track -> addHomeTrackCard(track, tracks, index) }
        homeLibraryContent.addView(homeMusicGrid, matchWidth())
    }

    private fun buildHomeEmptyState(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBackground(COLOR_CARD, dp(22))
        setPadding(dp(18), dp(18), dp(18), dp(18))

        val artAndCopy = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        artAndCopy.addView(TextView(this@MainActivity).apply {
            text = "♫"
            textSize = 36f
            gravity = Gravity.CENTER
            setTextColor(COLOR_BACKGROUND)
            background = roundedBackground(COLOR_ACCENT_SECONDARY, dp(20))
            contentDescription = getString(R.string.music_collection_art)
        }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(16) })
        val message = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.home_empty_title)
                textSize = 19f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(COLOR_TEXT)
            })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.home_import_prompt)
                textSize = 14f
                setTextColor(COLOR_MUTED)
                setPadding(0, dp(5), 0, 0)
            })
        }
        artAndCopy.addView(message, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(artAndCopy, matchWidth())

        addView(Button(this@MainActivity).apply {
            text = getString(R.string.import_audio)
            styleActionButton(this, true)
            setOnClickListener {
                animateSuccess(this)
                launchAudioImport()
            }
        }, matchWidth().apply { topMargin = dp(16) })
    }

    private fun newHomeMusicGrid() = GridLayout(this).apply {
        columnCount = searchGridColumnCount()
        alignmentMode = GridLayout.ALIGN_BOUNDS
        useDefaultMargins = false
    }

    private fun animateHomeCard(card: View, index: Int) {
        if (!animationsEnabled()) return
        card.alpha = 0f
        card.translationY = dp(10).toFloat()
        card.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay((index * 28L).coerceAtMost(224L))
            .setDuration(SUCCESS_ANIMATION_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.15f))
            .start()
    }

    private fun homeSectionHeading(
        title: String,
        actionText: String,
        onAction: () -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(sectionTitle(title), LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(Button(this@MainActivity).apply {
            text = actionText
            minWidth = dp(48)
            styleActionButton(this, false)
            setOnClickListener { onAction() }
        }, LinearLayout.LayoutParams(WRAP, dp(48)))
    }

    private fun buildHomeScreen(scroll: ScrollView) {
        val root = scrollContent(scroll)
        root.addView(TextView(this).apply {
            text = getString(R.string.home_title)
            textSize = 32f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            setPadding(0, 0, 0, dp(12))
        })
        root.addView(buildDiscoveryHero(), matchWidth().apply { bottomMargin = dp(12) })
        val categoryItems = listOf(
            HOME_CATEGORY_MUSIC to R.string.home_category_all,
            HOME_CATEGORY_RECENT_PLAYED to R.string.home_category_recent_played,
            HOME_CATEGORY_ARTISTS to R.string.home_category_artists,
            HOME_CATEGORY_ALBUMS to R.string.home_category_albums,
            HOME_CATEGORY_PLAYLISTS to R.string.home_category_playlists,
            HOME_CATEGORY_DISCOVER to R.string.home_category_discover,
        )
        homeCategories = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        categoryItems.forEach { (category, title) ->
            homeCategories.addView(TextView(this).apply {
                text = getString(title)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(9), dp(14), dp(9))
                isClickable = true
                isFocusable = true
                minHeight = dp(48)
                contentDescription = getString(R.string.home_category_accessibility, getString(title))
                if (category == HOME_CATEGORY_DISCOVER) {
                    setTextColor(COLOR_ACCENT_WARM)
                }
                setOnClickListener {
                    homeCategory = category
                    homeSubcategory = 0
                    selectedHomeGroup = null
                    animateSuccess(this)
                    renderHomeSubcategories()
                    showHomeCollection()
                    renderHomeLibrary()
                }
                tag = category
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
        }
        val categoryScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(homeCategories)
        }
        root.addView(categoryScroll, matchWidth().apply { topMargin = dp(14) })

        homeSubcategories = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val subcategoryScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(homeSubcategories)
        }
        root.addView(subcategoryScroll, matchWidth().apply { topMargin = dp(8) })
        renderHomeSubcategories()

        homeLibraryContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(homeLibraryContent, matchWidth().apply { topMargin = dp(8) })

        downloadSettingsToggle = TextView(this).apply {
            text = getString(R.string.download_settings_summary)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(52)
            setPadding(dp(16), dp(4), dp(16), dp(4))
            background = roundedBackground(COLOR_SURFACE, dp(16))
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.download_settings_accessibility)
            setOnClickListener {
                val expanded = downloadSettingsContent.visibility != View.VISIBLE
                downloadSettingsContent.visibility = if (expanded) View.VISIBLE else View.GONE
                text = getString(
                    if (expanded) R.string.download_settings_expanded else R.string.download_settings_summary,
                )
                contentDescription = getString(
                    if (expanded) R.string.download_settings_collapse
                    else R.string.download_settings_accessibility,
                )
                animateSuccess(this)
            }
        }
        root.addView(downloadSettingsToggle, sectionSpacing())
        formatSpinner = Spinner(this).apply {
            background = roundedBackground(COLOR_SURFACE, dp(12))
            adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, formatModes) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup) =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        setTextColor(COLOR_TEXT); textSize = 14f; setPadding(dp(14), dp(12), dp(14), dp(12))
                    }
            }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
        }
        statusText = TextView(this).apply {
            text = getString(R.string.ready_to_find_music); textSize = 13f
            setTextColor(COLOR_MUTED); setPadding(0, dp(6), 0, 0)
        }
        downloadSettingsContent = sectionCard().apply {
            addView(formatSpinner, matchWidth())
            addView(progressBar, matchWidth().apply { topMargin = dp(8) })
            addView(statusText, matchWidth())
            visibility = View.GONE
        }
        root.addView(downloadSettingsContent, matchWidth().apply { topMargin = dp(6) })
        renderHomeLibrary()
    }

    private fun buildSearchScreen(scroll: ScrollView) {
        val root = scrollContent(scroll)
        root.addView(sectionTitle(getString(R.string.nav_search)).apply {
            textSize = 32f
            setPadding(0, 0, 0, dp(14))
        }, matchWidth())
        searchInput = EditText(this).apply {
            hint = getString(R.string.search_hint)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textSize = 16f
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            setPadding(dp(16), 0, dp(10), 0)
            background = null
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { searchMusic(); true } else false
            }
        }
        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(COLOR_SURFACE, dp(16))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        searchBox.addView(searchInput, LinearLayout.LayoutParams(0, dp(52), 1f))
        val clearSearchButton = Button(this).apply {
            text = "×"
            contentDescription = getString(R.string.clear_search)
            styleActionButton(this, false)
            setOnClickListener { clearOnlineSearch() }
            visibility = View.GONE
        }
        searchBox.addView(clearSearchButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearSearchButton.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        searchButton = Button(this).apply {
            text = getString(R.string.search_music)
            styleActionButton(this, true)
            setOnClickListener { searchMusic() }
        }
        searchBox.addView(searchButton, LinearLayout.LayoutParams(WRAP, dp(48)))
        root.addView(sectionCard().apply { addView(searchBox, matchWidth()) }, matchWidth())
        searchResultsTitle = sectionTitle(getString(R.string.search_results)).apply {
            visibility = View.GONE
        }
        root.addView(searchResultsTitle, sectionSpacing())
        searchResults = GridLayout(this).apply {
            columnCount = searchGridColumnCount()
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        root.addView(searchResults, matchWidth())
        scroll.setOnScrollChangeListener { view: View, _: Int, scrollY: Int, _: Int, _: Int ->
            val scrollView = view as ScrollView
            val contentHeight = scrollView.getChildAt(0)?.height ?: 0
            if (hasMoreSearchResults && !loadingMoreSearchResults &&
                scrollY + scrollView.height >= contentHeight - dp(320)
            ) loadNextSearchPage()
        }
        addSearchMessage(getString(R.string.search_prompt))
    }

    private fun buildDiscoveryHero(): View = FrameLayout(this).apply {
        val compact = resources.configuration.screenWidthDp < 380
        val artworkSize = dp(if (compact) 78 else 112)
        val heroGradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(COLOR_HERO_START, COLOR_HERO_MIDDLE, COLOR_HERO_END),
        ).apply { cornerRadius = dp(24).toFloat() }
        background = heroGradient
        setPadding(dp(if (compact) 16 else 20), dp(18), dp(14), dp(18))
        minimumHeight = dp(if (compact) 138 else 150)
        elevation = dp(2).toFloat()

        val copy = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        copy.addView(TextView(this@MainActivity).apply {
            text = getString(R.string.home_hero_title)
            textSize = if (compact) 20f else 22f
            maxLines = 2
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_HERO_TEXT)
            setPadding(0, dp(4), 0, dp(2))
        })
        copy.addView(TextView(this@MainActivity).apply {
            text = getString(R.string.home_hero_subtitle)
            textSize = if (compact) 12f else 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(COLOR_HERO_TEXT.withAlpha(220))
        })
        copy.addView(Button(this@MainActivity).apply {
            text = getString(R.string.home_hero_action)
            textSize = 13f
            isAllCaps = false
            setTextColor(COLOR_BACKGROUND)
            background = roundedBackground(Color.WHITE, dp(14))
            minHeight = dp(44)
            minWidth = dp(if (compact) 128 else 142)
            setPadding(dp(12), 0, dp(12), 0)
            contentDescription = getString(R.string.home_hero_action_accessibility)
            setOnClickListener {
                homeCategory = HOME_CATEGORY_DISCOVER
                homeSubcategory = 0
                selectedHomeGroup = null
                renderHomeSubcategories()
                updateHomeCategoryStyles()
                showHomeCollection()
                renderHomeLibrary()
                animateSuccess(this)
            }
        }, LinearLayout.LayoutParams(WRAP, dp(46)).apply {
            topMargin = dp(if (compact) 8 else 12)
        })

        addView(copy, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.START or Gravity.CENTER_VERTICAL).apply {
            rightMargin = artworkSize + dp(12)
        })
        addView(TextView(this@MainActivity).apply {
            text = "♫"
            textSize = if (compact) 62f else 88f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.argb(45, 255, 255, 255), artworkSize / 2)
            contentDescription = getString(R.string.home_hero_art_description)
        }, FrameLayout.LayoutParams(artworkSize, artworkSize, Gravity.END or Gravity.CENTER_VERTICAL))
    }

    private fun buildQueueScreen(scroll: ScrollView) {
        val root = scrollContent(scroll)
        root.addView(sectionTitle(getString(R.string.queue_recent_jobs)))
        val settingsPanel = sectionCard()
        settingsPanel.addView(TextView(this).apply {
            text = getString(R.string.concurrent_downloads_setting)
            textSize = 14f
            setTextColor(COLOR_MUTED)
            setPadding(0, 0, 0, dp(4))
        }, matchWidth())
        concurrencySpinner = Spinner(this).apply {
            background = roundedBackground(COLOR_SURFACE, dp(12))
            adapter = object : ArrayAdapter<String>(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                (1..MAX_CONCURRENT_DOWNLOADS).map { getString(R.string.concurrent_downloads_value, it) },
            ) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup) =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        setTextColor(COLOR_TEXT)
                        textSize = 14f
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                    }
            }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    if (concurrencySettingReady) {
                        downloadService?.setMaxConcurrentDownloads(position + 1)
                        renderQueueSummary(latestJobs)
                    }
                }
            }
        }
        settingsPanel.addView(concurrencySpinner, matchWidth())
        wifiOnlyToggle = CheckBox(this).apply {
            text = getString(R.string.wifi_only_downloads)
            setTextColor(COLOR_TEXT)
            buttonTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
            setOnCheckedChangeListener { _, checked ->
                if (wifiSettingReady) downloadService?.setWifiOnly(checked)
            }
        }
        settingsPanel.addView(wifiOnlyToggle, matchWidth())
        queueSummary = emptyMessage(getString(R.string.queue_empty))
        settingsPanel.addView(queueSummary, matchWidth())
        renderQueueSummary(emptyList())
        root.addView(settingsPanel, matchWidth().apply { bottomMargin = dp(12) })
        root.addView(Button(this).apply {
            text = getString(R.string.retry_all_failed)
            styleActionButton(this, true)
            setOnClickListener { retryAllFailedJobs() }
        }, matchWidth().apply { topMargin = dp(8) })
        root.addView(Button(this).apply {
            text = getString(R.string.clear_finished); styleActionButton(this, false)
            setOnClickListener {
                val removed = downloadService?.clearFinishedJobs() ?: 0
                Toast.makeText(this@MainActivity, getString(R.string.removed_finished_jobs, removed), Toast.LENGTH_SHORT).show()
            }
        }, matchWidth())
        queueList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(queueList, matchWidth())
        renderJobs(emptyList())
    }

    private fun buildLibraryScreen(scroll: ScrollView) {
        val root = scrollContent(scroll)
        root.addView(sectionTitle(getString(R.string.library_title)))
        val libraryActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        libraryActions.addView(Button(this).apply {
            text = getString(R.string.nav_playlists)
            styleActionButton(this, false)
            setOnClickListener { showTab(R.string.nav_playlists) }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
        libraryActions.addView(Button(this).apply {
            text = getString(R.string.import_audio)
            styleActionButton(this, true)
            setOnClickListener { launchAudioImport() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })
        root.addView(libraryActions, matchWidth().apply { topMargin = dp(8) })

        librarySearch = EditText(this).apply {
            hint = getString(R.string.library_search_hint)
            isSingleLine = true
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedBackground(COLOR_SURFACE, dp(14))
            minHeight = dp(48)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderFilteredLibrary()
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        root.addView(librarySearch, matchWidth().apply { topMargin = dp(14) })

        val libraryViewControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        libraryFilterButton = Button(this).apply {
            styleLibraryDropdown(this)
            setOnClickListener { showLibraryGroupMenu(this) }
        }
        librarySortButton = Button(this).apply {
            styleLibraryDropdown(this)
            setOnClickListener { showLibrarySortMenu(this) }
        }
        libraryViewControls.addView(
            libraryFilterButton,
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6) },
        )
        libraryViewControls.addView(
            librarySortButton,
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(6) },
        )
        root.addView(libraryViewControls, matchWidth().apply { topMargin = dp(6) })
        updateLibraryControlLabels()

        val trackHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        libraryTrackHeading = sectionTitle(getString(R.string.library_tracks))
        trackHeading.addView(libraryTrackHeading, LinearLayout.LayoutParams(0, WRAP, 1f))
        libraryPlayAllButton = Button(this).apply {
            text = getString(R.string.play_all)
            styleActionButton(this, false)
            minHeight = dp(40)
            visibility = View.GONE
            setOnClickListener {
                if (visibleLibraryTracks.isNotEmpty()) playTrackList(visibleLibraryTracks, 0)
            }
        }
        trackHeading.addView(libraryPlayAllButton, LinearLayout.LayoutParams(WRAP, dp(40)))
        root.addView(trackHeading, sectionSpacing())

        historyList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(historyList, matchWidth().apply { topMargin = dp(4) })
        val recentHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        recentHeading.addView(
            sectionTitle(getString(R.string.recent_history_title)),
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        recentHeading.addView(Button(this).apply {
            text = getString(R.string.see_all)
            styleActionButton(this, false)
            minHeight = dp(40)
            setOnClickListener { showRecentHistoryCollection() }
        }, LinearLayout.LayoutParams(WRAP, dp(40)))
        root.addView(recentHeading, sectionSpacing())
        libraryRecentHistory = newHomeMusicGrid()
        root.addView(libraryRecentHistory, matchWidth())
        root.addView(sectionTitle(getString(R.string.downloads)), sectionSpacing())
        downloadHistoryList = newHomeMusicGrid()
        root.addView(downloadHistoryList, matchWidth())
    }

    private fun styleLibraryDropdown(button: Button) {
        button.isAllCaps = false
        button.textSize = 12f
        button.gravity = Gravity.CENTER
        button.maxLines = 1
        button.ellipsize = android.text.TextUtils.TruncateAt.END
        button.setTextColor(COLOR_TEXT)
        button.background = roundedBackground(COLOR_SURFACE, dp(20))
        button.setPadding(dp(10), 0, dp(10), 0)
        button.minWidth = 0
        button.minHeight = dp(40)
        button.stateListAnimator = null
    }

    private fun updateLibraryControlLabels() {
        if (!::libraryFilterButton.isInitialized || !::librarySortButton.isInitialized) return
        val filterLabel = when (libraryGroupMode) {
            1 -> getString(R.string.library_group_artist)
            2 -> getString(R.string.library_group_album)
            else -> getString(R.string.library_filter_all)
        }
        val sortLabel = getString(
            when (librarySortMode) {
                1 -> R.string.library_sort_artist
                2 -> R.string.library_sort_album
                3 -> R.string.library_sort_recent
                else -> R.string.library_sort_title
            },
        )
        libraryFilterButton.text = getString(R.string.library_filter_button, filterLabel)
        librarySortButton.text = getString(R.string.library_sort_button, sortLabel)
    }

    private fun showLibraryGroupMenu(anchor: View) {
        val options = listOf(
            R.string.library_filter_all,
            R.string.library_group_artist,
            R.string.library_group_album,
        )
        android.widget.PopupMenu(this, anchor).apply {
            options.forEachIndexed { index, label -> menu.add(0, index, index, label) }
            setOnMenuItemClickListener { item ->
                libraryGroupMode = item.itemId
                updateLibraryControlLabels()
                renderFilteredLibrary()
                true
            }
            show()
        }
    }

    private fun showLibrarySortMenu(anchor: View) {
        val options = listOf(
            R.string.library_sort_title,
            R.string.library_sort_artist,
            R.string.library_sort_album,
            R.string.library_sort_recent,
        )
        android.widget.PopupMenu(this, anchor).apply {
            options.forEachIndexed { index, label -> menu.add(0, index, index, label) }
            setOnMenuItemClickListener { item ->
                librarySortMode = item.itemId
                updateLibraryControlLabels()
                renderFilteredLibrary()
                true
            }
            show()
        }
    }

    private fun buildPlaylistsScreen(scroll: ScrollView) {
        val root = scrollContent(scroll)
        root.addView(sectionTitle(getString(R.string.playlists_title)))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = getString(R.string.create_playlist)
            styleActionButton(this, true)
            setOnClickListener { promptCreatePlaylist() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
        actions.addView(Button(this).apply {
            text = getString(R.string.import_playlist_m3u)
            styleActionButton(this, false)
            setOnClickListener { launchPlaylistImport() }
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })
        root.addView(actions, matchWidth())

        playlistSearch = EditText(this).apply {
            hint = getString(R.string.playlist_search_hint)
            isSingleLine = true
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedBackground(COLOR_SURFACE, dp(14))
            minHeight = dp(48)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    refreshPlaylists()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        root.addView(playlistSearch, matchWidth().apply { topMargin = dp(12) })

        playlistList = GridLayout(this).apply {
            columnCount = searchGridColumnCount().coerceAtMost(2)
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        root.addView(playlistList, matchWidth().apply { topMargin = dp(8) })
    }

    private fun launchPlaylistImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/x-mpegurl"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/x-mpegurl", "audio/mpegurl", "text/plain"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_IMPORT_M3U)
    }

    private fun buildPlayerScreen(scroll: ScrollView) {
        val root = scrollContent(scroll)
        root.addView(sectionTitle(getString(R.string.player_title)))
        playerArtwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBackground(COLOR_SURFACE, dp(16))
            setImageResource(android.R.drawable.ic_media_play)
            contentDescription = getString(R.string.track_artwork)
        }
        val artworkSize = minOf(dp(360), resources.displayMetrics.widthPixels - dp(80))
        root.addView(playerArtwork, LinearLayout.LayoutParams(artworkSize, artworkSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(12); bottomMargin = dp(20)
        })
        playerTitle = TextView(this).apply {
            text = getString(R.string.nothing_playing); textSize = 23f
            setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(COLOR_TEXT)
        }
        root.addView(playerTitle, matchWidth())
        playerArtist = TextView(this).apply { textSize = 16f; setTextColor(COLOR_MUTED) }
        root.addView(playerArtist, matchWidth().apply { topMargin = dp(4) })
        playerSeek = SeekBar(this).apply {
            contentDescription = getString(R.string.seek_playback)
            progressTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(seekBar: SeekBar?) { seeking = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    seeking = false
                    playbackService?.seekTo(seekBar?.progress ?: 0)
                }
            })
        }
        root.addView(playerSeek, matchWidth().apply { topMargin = dp(18) })
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        playerPosition = TextView(this).apply { text = "0:00"; setTextColor(COLOR_MUTED) }
        playerDuration = TextView(this).apply { text = "0:00"; gravity = Gravity.END; setTextColor(COLOR_MUTED) }
        times.addView(playerPosition, LinearLayout.LayoutParams(0, WRAP, 1f))
        times.addView(playerDuration, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(times)
        val mainControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val playbackButtonSize = ((resources.displayMetrics.widthPixels - dp(128)) / 3)
            .coerceAtLeast(dp(56))
            .coerceAtMost(dp(76))
        val previousButton = playerControlButton("|◀", getString(R.string.previous)) { playPrevious() }
        mainControls.addView(previousButton, LinearLayout.LayoutParams(playbackButtonSize, dp(60)).apply {
            marginEnd = dp(12)
        })
        playButton = playerControlButton("▶", getString(R.string.play)) { togglePlayback() }.apply {
            styleActionButton(this, true)
            textSize = 22f
        }
        mainControls.addView(playButton, LinearLayout.LayoutParams(playbackButtonSize, dp(64)))
        val nextButton = playerControlButton("▶|", getString(R.string.next)) { playNext() }
        mainControls.addView(nextButton, LinearLayout.LayoutParams(playbackButtonSize, dp(60)).apply {
            marginStart = dp(12)
        })
        root.addView(mainControls, matchWidth().apply { topMargin = dp(14) })

        val secondaryControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        shuffleButton = playerControlButton("⇄  Shuffle", getString(R.string.shuffle)) {
            val state = playbackSnapshot
            playbackService?.setShuffle(!(state?.shuffle ?: false))
        }
        secondaryControls.addView(shuffleButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
            marginEnd = dp(6)
        })
        repeatButton = playerControlButton("↻  Repeat", getString(R.string.repeat)) {
            val mode = playbackSnapshot?.repeatMode ?: 0
            playbackService?.setRepeatMode((mode + 1) % 3)
        }
        secondaryControls.addView(repeatButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
            marginStart = dp(6)
        })
        root.addView(secondaryControls, matchWidth().apply { topMargin = dp(6) })
        root.addView(Button(this).apply {
            text = getString(R.string.track_details)
            styleActionButton(this, false)
            setOnClickListener {
                currentPlaybackTrack?.let(::showTrackDetails)
                    ?: Toast.makeText(this@MainActivity, getString(R.string.player_empty), Toast.LENGTH_SHORT).show()
            }
        }, matchWidth().apply { topMargin = dp(8) })
        root.addView(sectionTitle(getString(R.string.playback_options)), sectionSpacing())
        val optionsCard = sectionCard()
        speedSpinner = Spinner(this).apply {
            contentDescription = getString(R.string.playback_speed_label)
            background = roundedBackground(COLOR_SURFACE, dp(12))
            adapter = styledSpinnerAdapter(
                PLAYBACK_SPEEDS.map { getString(R.string.playback_speed, it) },
            )
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!syncingPlaybackControls) playbackService?.setPlaybackSpeed(PLAYBACK_SPEEDS[position])
                }
            }
        }
        optionsCard.addView(speedSpinner, matchWidth())
        sleepTimerButton = Button(this).apply {
            text = getString(R.string.sleep_timer)
            styleActionButton(this, false)
            setOnClickListener { showSleepTimerOptions() }
        }
        optionsCard.addView(sleepTimerButton, matchWidth().apply { topMargin = dp(8) })
        equalizerToggle = CheckBox(this).apply {
            text = getString(R.string.equalizer)
            minHeight = dp(48)
            setTextColor(COLOR_TEXT)
            buttonTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
            setOnCheckedChangeListener { _, checked ->
                if (!syncingPlaybackControls) playbackService?.setEqualizerEnabled(checked)
            }
        }
        optionsCard.addView(equalizerToggle, matchWidth())
        equalizerLevelSeek = SeekBar(this).apply {
            contentDescription = getString(R.string.equalizer_level)
            max = 100
            progress = DEFAULT_EQUALIZER_LEVEL
            progressTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
            setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) playbackService?.setEqualizerLevel(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        optionsCard.addView(equalizerLevelSeek, matchWidth())
        playerStatus = emptyMessage(getString(R.string.player_empty))
        optionsCard.addView(playerStatus, matchWidth())
        root.addView(optionsCard, matchWidth())
        root.addView(sectionTitle(getString(R.string.library_tracks)), sectionSpacing())
        val playNow = Button(this).apply {
            text = getString(R.string.play_library); styleActionButton(this, false)
            setOnClickListener { playTrackList(libraryTracks, 0) }
        }
        root.addView(playNow, matchWidth())
        root.addView(Button(this).apply {
            text = getString(R.string.queue_recent_jobs)
            styleActionButton(this, false)
            setOnClickListener { showTab(R.string.nav_queue) }
        }, matchWidth().apply { topMargin = dp(8) })
    }

    private fun playerControlButton(
        label: String,
        accessibilityLabel: String,
        action: () -> Unit,
    ) = Button(this).apply {
        text = label
        contentDescription = accessibilityLabel
        styleActionButton(this, false)
        setOnClickListener {
            animateSuccess(this)
            action()
        }
    }

    private fun showTab(label: Int) {
        currentTab = label
        val index = when (label) {
            R.string.nav_home -> 0
            R.string.nav_search -> 1
            R.string.nav_queue -> 2
            R.string.nav_library -> 3
            R.string.nav_playlists -> 4
            else -> 5
        }
        for (screenIndex in 0 until content.childCount) {
            val screen = content.getChildAt(screenIndex)
            if (screenIndex == index) {
                screen.animate().cancel()
                screen.visibility = View.VISIBLE
                if (animationsEnabled() && visibleTabIndex != -1 && visibleTabIndex != index) {
                    screen.alpha = 0f
                    screen.translationY = dp(8).toFloat()
                    screen.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(TAB_TRANSITION_MS)
                        .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
                        .start()
                } else {
                    screen.alpha = 1f
                    screen.translationY = 0f
                }
            } else {
                screen.animate().cancel()
                screen.visibility = View.GONE
                screen.alpha = 1f
                screen.translationY = 0f
            }
        }
        visibleTabIndex = index
        val navigation = (content.parent as LinearLayout).getChildAt(2) as LinearLayout
        for (i in 0 until navigation.childCount) {
            val item = navigation.getChildAt(i) as LinearLayout
            val selected = item.tag == label
            item.isSelected = selected
            item.background = roundedBackground(
                if (selected) COLOR_SURFACE else Color.TRANSPARENT,
                dp(14),
            )
            for (child in 0 until item.childCount) {
                (item.getChildAt(child) as TextView).setTextColor(if (selected) COLOR_ACCENT else COLOR_MUTED)
            }
        }
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value; textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(COLOR_TEXT); setPadding(0, dp(4), 0, dp(8))
    }

    private fun restoreSettings() {
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        formatSpinner.setSelection(preferences.getInt(KEY_FORMAT, formatModes.lastIndex).coerceIn(formatModes.indices))
    }

    private fun saveSettings() {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
            .putInt(KEY_FORMAT, formatSpinner.selectedItemPosition).apply()
    }

    private fun searchMusic(queryOverride: String? = null) {
        val query = (queryOverride ?: searchInput.text.toString()).trim()
        showTab(R.string.nav_search)
        if (query.isEmpty()) {
            searchResultsTitle.visibility = View.VISIBLE
            searchDownloadIndicators.clear()
            searchResults.removeAllViews()
            searchResults.visibility = View.VISIBLE
            addSearchMessage(getString(R.string.search_query_required))
            return
        }
        searchInput.setText(query)
        activeSearchQuery = query
        searchOffset = 0
        searchCardCount = 0
        searchDownloadIndicators.clear()
        hasMoreSearchResults = true
        loadingMoreSearchResults = false
        searchResults.removeAllViews()
        searchResults.columnCount = searchGridColumnCount()
        searchResultsTitle.visibility = View.VISIBLE
        searchResults.visibility = View.VISIBLE
        addSearchMessage(getString(R.string.searching))
        searchRequestId++
        loadNextSearchPage(initial = true)
    }

    private fun loadNextSearchPage(initial: Boolean = false) {
        if (loadingMoreSearchResults || (!initial && !hasMoreSearchResults)) return
        val service = downloadService
        if (service == null) {
            if (initial) {
                hasMoreSearchResults = false
                searchResults.removeAllViews()
                addSearchMessage(getString(R.string.search_service_unavailable))
            }
            return
        }
        loadingMoreSearchResults = true
        val requestId = searchRequestId
        val offset = searchOffset
        setSearchLoadingFooter(true)
        if (initial) {
            searchButton.isEnabled = false
            searchButton.alpha = 0.65f
        }
        service.searchMusic(activeSearchQuery, offset, SEARCH_PAGE_SIZE) callback@{ result ->
            if (destroyed || requestId != searchRequestId) return@callback
            loadingMoreSearchResults = false
            setSearchLoadingFooter(false)
            searchButton.isEnabled = true
            searchButton.alpha = 1f
            result.fold(
                onSuccess = { items ->
                    if (initial) searchResults.removeAllViews()
                    if (items.isEmpty()) {
                        hasMoreSearchResults = false
                        if (searchCardCount == 0) addSearchMessage(getString(R.string.search_no_results))
                    } else {
                        items.forEach(::addSearchResultCard)
                        searchOffset += items.size
                        hasMoreSearchResults = items.size == SEARCH_PAGE_SIZE
                    }
                },
                onFailure = { error ->
                    val failure = getString(
                        R.string.search_failed,
                        error.message ?: getString(R.string.unknown_error),
                    )
                    if (searchCardCount == 0 && initial) {
                        addSearchMessage(failure)
                        hasMoreSearchResults = false
                    } else {
                        hasMoreSearchResults = true
                        addSearchMessage(failure, ::loadNextSearchPage)
                    }
                },
            )
        }
    }

    private fun setSearchLoadingFooter(loading: Boolean) {
        searchResults.findViewWithTag<View>(SEARCH_LOADING_TAG)?.let(searchResults::removeView)
        if (!loading) return
        val progress = LinearLayout(this).apply {
            tag = SEARCH_LOADING_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(ProgressBar(this@MainActivity).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.loading_more_results)
                setTextColor(COLOR_MUTED)
                textSize = 13f
            })
        }
        val columns = searchResults.columnCount.coerceAtLeast(1)
        searchResults.addView(
            progress,
            GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(0, columns),
            ).apply {
                width = MATCH
                height = dp(48)
                setMargins(0, dp(8), 0, dp(8))
            },
        )
    }

    private fun loadThumbnail(url: String, target: ImageView) {
        val safeUri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (safeUri.scheme != "https" || safeUri.userInfo != null ||
            (safeUri.port != -1 && safeUri.port != 443)
        ) return
        thumbnailExecutor.execute {
            val bitmap = try {
                val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    instanceFollowRedirects = false
                }
                try {
                    if (connection.responseCode !in 200..299 ||
                        connection.contentType?.startsWith("image/") != true ||
                        connection.contentLengthLong > MAX_THUMBNAIL_BYTES
                    ) null else connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (total <= MAX_THUMBNAIL_BYTES) {
                            val count = input.read(buffer, 0, minOf(buffer.size, MAX_THUMBNAIL_BYTES + 1 - total))
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            total += count
                        }
                        val bytes = output.toByteArray()
                        if (bytes.size > MAX_THUMBNAIL_BYTES) null else {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            val sample = (maxOf(bounds.outWidth, bounds.outHeight) / MAX_THUMBNAIL_PIXELS)
                                .coerceAtLeast(1)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                                inSampleSize = sample
                            })
                        }
                    }
                } finally { connection.disconnect() }
            } catch (_: java.io.IOException) { null }
            if (bitmap != null && !destroyed) runOnUiThread { if (!destroyed) target.setImageBitmap(bitmap) }
        }
    }

    private fun enqueueUrls(urls: List<String>, showStatus: Boolean = true) {
        val validUrls = urls.distinctBy { it.lowercase() }
        if (validUrls.isEmpty()) return
        saveSettings()
        val serviceIntent = Intent(this, DownloadService::class.java)
            .setAction(DownloadService.ACTION_ENQUEUE)
            .putExtra(DownloadService.EXTRA_URLS, org.json.JSONArray(validUrls).toString())
            .putExtra(DownloadService.EXTRA_FORMAT, formatKeys[formatSpinner.selectedItemPosition])
        startForegroundService(serviceIntent)
        if (showStatus) statusText.text = getString(R.string.downloads_queued, validUrls.size)
        animateSuccess(progressBar)
    }

    override fun onDownloadState(message: String, active: Boolean, paused: Boolean, canPause: Boolean, canCancel: Boolean, percent: Int?) {
        runOnUiThread {
            progressBar.animate().cancel()
            if (active) {
                val wasHidden = progressBar.visibility != View.VISIBLE
                progressBar.visibility = View.VISIBLE
                if (wasHidden && animationsEnabled()) {
                    progressBar.alpha = 0f
                    progressBar.animate().alpha(1f).setDuration(TAB_TRANSITION_MS).start()
                } else {
                    progressBar.alpha = 1f
                }
            } else if (progressBar.visibility == View.VISIBLE && animationsEnabled()) {
                progressBar.animate()
                    .alpha(0f)
                    .setDuration(TAB_TRANSITION_MS)
                    .withEndAction {
                        progressBar.visibility = View.GONE
                        progressBar.alpha = 1f
                    }
                    .start()
            } else {
                progressBar.visibility = View.GONE
                progressBar.alpha = 1f
            }
            progressBar.isIndeterminate = active && percent == null
            if (percent != null) {
                val boundedPercent = percent.coerceIn(0, 100)
                if (animationsEnabled()) progressBar.setProgress(boundedPercent, true)
                else progressBar.progress = boundedPercent
            }
        }
    }

    override fun onQueueChanged(jobs: List<DownloadJob>) {
        runOnUiThread {
            val completed = setOf(DownloadQueueStore.STATE_COMPLETE, DownloadQueueStore.STATE_PARTIAL,
                DownloadQueueStore.STATE_FAILED, DownloadQueueStore.STATE_CANCELED)
            val newlyDone = jobs.any { it.state in completed && previousJobStates[it.id] != it.state }
            previousJobStates = jobs.associate { it.id to it.state }
            latestJobs = jobs
            renderSearchDownloadIndicators(jobs)
            renderJobs(jobs)
            renderQueueSummary(jobs)
            if (newlyDone) refreshHistory()
        }
    }

    private fun renderJobs(jobs: List<DownloadJob>) {
        val priorStates = renderedJobStates
        renderedJobStates = jobs.associate { it.id to it.state }
        queueList.removeAllViews()
        if (jobs.isEmpty()) { queueList.addView(emptyMessage(getString(R.string.queue_empty))); return }
        jobs.forEach { job ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBackground(COLOR_CARD, dp(14))
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val detail = TextView(this).apply {
                text = getString(R.string.queue_job_detail, job.id, getString(jobStateLabel(job.state)),
                    job.percent?.let { getString(R.string.percent_suffix, it) }.orEmpty())
                textSize = 13f; setTextColor(COLOR_TEXT); setPadding(0, dp(4), dp(8), dp(4))
            }
            header.addView(detail, LinearLayout.LayoutParams(0, WRAP, 1f))
            when (job.state) {
                DownloadQueueStore.STATE_RUNNING -> {
                    header.addView(queueActionButton(getString(R.string.pause)) { downloadService?.pauseJob(job.id) })
                    header.addView(queueActionButton(getString(R.string.action_cancel)) { downloadService?.cancelJob(job.id) })
                }
                DownloadQueueStore.STATE_QUEUED -> {
                    header.addView(queueActionButton(getString(R.string.move_job_to_front)) {
                        val moved = downloadService?.moveJobToFront(job.id) ?: false
                        if (!moved) showError(getString(R.string.move_job_failed))
                    })
                    header.addView(queueActionButton(getString(R.string.action_cancel)) { downloadService?.cancelJob(job.id) })
                }
                DownloadQueueStore.STATE_PAUSED -> {
                    header.addView(queueActionButton(getString(R.string.resume)) { downloadService?.resumeJob(job.id) })
                    header.addView(queueActionButton(getString(R.string.action_cancel)) { downloadService?.cancelJob(job.id) })
                }
                DownloadQueueStore.STATE_FAILED, DownloadQueueStore.STATE_PARTIAL, DownloadQueueStore.STATE_CANCELED -> {
                    header.addView(queueActionButton(getString(R.string.download_details)) {
                        showDownloadJobDetails(job)
                    })
                    header.addView(queueActionButton(getString(R.string.action_retry)) { downloadService?.retryJob(job.id) })
                }
            }
            row.addView(header, matchWidth())
            if (job.state == DownloadQueueStore.STATE_RUNNING || job.state == DownloadQueueStore.STATE_QUEUED) {
                row.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    isIndeterminate = job.state == DownloadQueueStore.STATE_RUNNING && job.percent == null
                    progress = job.percent?.coerceIn(0, 100) ?: 0
                    progressTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
                }, matchWidth().apply { topMargin = dp(6) })
            }
            queueList.addView(row, matchWidth().apply { bottomMargin = dp(8) })
            val priorState = priorStates[job.id]
            if (priorState != job.state) animateSuccess(row)
        }
    }

    private fun showDownloadJobDetails(job: DownloadJob) {
        val message = job.message.trim().ifEmpty { getString(R.string.download_details_unavailable) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.download_details_title, job.id, getString(jobStateLabel(job.state))))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun retryAllFailedJobs() {
        val retryable = latestJobs.filter {
            it.state == DownloadQueueStore.STATE_FAILED ||
                it.state == DownloadQueueStore.STATE_PARTIAL ||
                it.state == DownloadQueueStore.STATE_CANCELED
        }
        retryable.forEach { downloadService?.retryJob(it.id) }
        Toast.makeText(this, getString(R.string.retry_all_started, retryable.size), Toast.LENGTH_SHORT).show()
    }

    private fun queueActionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        styleActionButton(this, false)
        setOnClickListener { action() }
    }

    private fun renderQueueSummary(jobs: List<DownloadJob>) {
        if (!::queueSummary.isInitialized) return
        val active = jobs.count { it.state == DownloadQueueStore.STATE_RUNNING }
        val waiting = jobs.count { it.state == DownloadQueueStore.STATE_QUEUED }
        queueSummary.text = getString(
            R.string.queue_activity,
            active,
            downloadService?.getMaxConcurrentDownloads() ?: DEFAULT_CONCURRENT_DOWNLOADS,
            waiting,
        )
    }

    private fun jobStateLabel(state: String): Int = when (state) {
        DownloadQueueStore.STATE_QUEUED -> R.string.state_queued
        DownloadQueueStore.STATE_RUNNING -> R.string.state_running
        DownloadQueueStore.STATE_PAUSED -> R.string.state_paused
        DownloadQueueStore.STATE_COMPLETE -> R.string.state_complete
        DownloadQueueStore.STATE_PARTIAL -> R.string.state_partial
        DownloadQueueStore.STATE_FAILED -> R.string.state_failed
        DownloadQueueStore.STATE_CANCELED -> R.string.state_canceled
        else -> R.string.state_queued
    }

    private fun refreshPlaybackHistory() {
        recentHistoryGeneration++
        recentHistoryEntries.clear()
        recentHistoryCursor = null
        recentHistoryHasMore = true
        recentHistoryLoading = false
        recentHistoryLoadFailed = false
        loadMorePlaybackHistory()
        refreshPlaybackHistoryViews()
    }

    private fun loadMorePlaybackHistory() {
        if (recentHistoryLoading || !recentHistoryHasMore) return
        recentHistoryLoading = true
        recentHistoryLoadFailed = false
        val generation = recentHistoryGeneration
        val cursor = recentHistoryCursor
        refreshPlaybackHistoryViews()
        metadataExecutor.execute {
            val result = try {
                Result.success(
                    playlists.getPlaybackHistory(
                        limit = RECENT_HISTORY_PAGE_SIZE + 1,
                        beforeId = cursor?.id,
                    ),
                )
            } catch (error: Exception) {
                android.util.Log.e("MainActivity", "Could not load playback history", error)
                Result.failure(error)
            }
            runOnUiThread {
                if (destroyed || generation != recentHistoryGeneration) return@runOnUiThread
                recentHistoryLoading = false
                result.fold(
                    onSuccess = { page ->
                        val visiblePage = page.take(RECENT_HISTORY_PAGE_SIZE)
                        recentHistoryEntries.addAll(visiblePage)
                        recentHistoryCursor = visiblePage.lastOrNull() ?: recentHistoryCursor
                        recentHistoryHasMore = page.size > RECENT_HISTORY_PAGE_SIZE
                    },
                    onFailure = { recentHistoryLoadFailed = true },
                )
                refreshPlaybackHistoryViews()
            }
        }
    }

    private fun refreshPlaybackHistoryViews() {
        if (::libraryRecentHistory.isInitialized) renderLibraryRecentHistory()
        if (::homeLibraryContent.isInitialized && homeCategory == HOME_CATEGORY_RECENT_PLAYED) {
            renderHomeLibrary()
        }
    }

    private fun renderLibraryRecentHistory() {
        libraryRecentHistory.removeAllViews()
        if (recentHistoryEntries.isEmpty()) {
            val message = when {
                recentHistoryLoadFailed -> getString(R.string.recent_history_load_error)
                recentHistoryLoading -> getString(R.string.recent_history_loading)
                else -> getString(R.string.recent_history_empty)
            }
            libraryRecentHistory.addView(
                emptyMessage(message),
                GridLayout.LayoutParams().apply {
                    width = MATCH
                    height = WRAP
                    columnSpec = GridLayout.spec(0, libraryRecentHistory.columnCount)
                },
            )
            return
        }
        val queue = recentHistoryEntries.take(RECENT_HISTORY_PAGE_SIZE).map(::toAudioTrack)
        recentHistoryEntries.take(RECENT_HISTORY_PREVIEW_COUNT).forEachIndexed { index, entry ->
            addHomeTrackCard(
                track = queue[index],
                queue = queue,
                index = index,
                targetGrid = libraryRecentHistory,
                playedAt = entry.playedAt,
                historyEntryId = entry.id,
            )
        }
    }

    private fun toAudioTrack(entry: PlaybackHistoryEntry): AudioTrack =
        libraryTracks.firstOrNull { it.uri.toString() == entry.trackUri } ?: AudioTrack(
            uri = Uri.parse(entry.trackUri),
            title = entry.title,
            artist = entry.artist,
            album = entry.album,
            durationMs = entry.durationMs,
            artwork = null,
            dateAdded = entry.playedAt,
        )

    private fun showRecentHistoryCollection() {
        homeCategory = HOME_CATEGORY_RECENT_PLAYED
        homeSubcategory = 0
        selectedHomeGroup = null
        renderHomeSubcategories()
        showHomeCollection()
        renderHomeLibrary()
        showTab(R.string.nav_home)
    }

    private fun removePlaybackHistoryEntry(id: Long) {
        metadataExecutor.execute {
            val result = try {
                playlists.deletePlaybackHistoryEntry(id)
            } catch (error: Exception) {
                android.util.Log.e("MainActivity", "Could not remove playback history entry", error)
                false
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                if (result) refreshPlaybackHistory()
                else showError(getString(R.string.play_history_remove_failed))
            }
        }
    }

    private fun confirmClearPlaybackHistory() {
        if (recentHistoryEntries.isEmpty()) return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.clear_play_history_confirm))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.clear_play_history) { _, _ ->
                metadataExecutor.execute {
                    val result = try {
                        playlists.clearPlaybackHistory()
                        true
                    } catch (error: Exception) {
                        android.util.Log.e("MainActivity", "Could not clear playback history", error)
                        false
                    }
                    runOnUiThread {
                        if (destroyed) return@runOnUiThread
                        if (result) refreshPlaybackHistory()
                        else showError(getString(R.string.play_history_clear_failed))
                    }
                }
            }
            .show()
    }

    private fun refreshHistory() {
        refreshLibrary()
        refreshDownloadedFiles()
    }

    private fun refreshDownloadedFiles() {
        if (!::downloadHistoryList.isInitialized) return
        metadataExecutor.execute {
            val items = mutableListOf<Triple<String, Long, Pair<String, Uri>>>()
            var failure: Exception? = null
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.MIME_TYPE,
            )
            try {
                val cursor = contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                    arrayOf("${Environment.DIRECTORY_DOWNLOADS}/yt-dlp/"),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC",
                ) ?: error("Downloads provider returned no cursor")
                cursor.use {
                    while (it.moveToNext()) {
                        val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        val name = it.getString(it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                            ?: getString(R.string.unknown_track)
                        val date = it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED))
                        val mime = it.getString(it.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE))
                            ?: "application/octet-stream"
                        val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                        items.add(Triple(name, date, mime to uri))
                    }
                }
            } catch (caught: Exception) {
                failure = caught
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                downloadHistoryList.removeAllViews()
                if (failure != null) {
                    addHistoryEmptyMessage(getString(R.string.history_unavailable))
                } else if (items.isEmpty()) {
                    addHistoryEmptyMessage(getString(R.string.history_empty))
                } else {
                    items.forEach { (name, date, media) ->
                        addHistoryItem(name, date, media.first, media.second)
                    }
                }
            }
        }
    }

    private fun refreshLibrary() {
        if (!::historyList.isInitialized) return
        metadataExecutor.execute {
            val tracks = mutableListOf<AudioTrack>()
            val errors = mutableListOf<String>()
            val cachedUris = mutableSetOf<String>()
            try {
                playlists.getAllTrackMetadata().forEach { saved ->
                    val uri = Uri.parse(saved.uri)
                    val mime = runCatching { contentResolver.getType(uri) }.getOrNull()
                    if (!isAudioUri(uri, mime)) return@forEach
                    val name = displayName(uri)
                    cachedUris.add(uri.toString())
                    val artBytes = saved.artworkPath?.let { path ->
                        runCatching { File(path).takeIf(File::isFile)?.readBytes() }.getOrNull()
                    }
                    tracks.add(
                        AudioTrack(
                            uri = uri,
                            title = saved.title.ifBlank { name.substringBeforeLast('.', name) },
                            artist = saved.artist.ifBlank { getString(R.string.unknown_artist) },
                            album = saved.album.ifBlank { getString(R.string.unknown_album) },
                            durationMs = saved.durationMs ?: 0L,
                            artwork = artBytes?.let(::decodeArtwork),
                            dateAdded = 0L,
                        )
                    )
                }
            } catch (error: Exception) {
                errors.add(getString(R.string.cached_metadata_error, error.localizedMessage ?: error.javaClass.simpleName))
            }
            if (hasAudioLibraryPermission()) try {
                val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.MIME_TYPE,
                    MediaStore.Audio.Media.DATE_ADDED)
                contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, null, null,
                    "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE))
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                        val added = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED))
                        val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                        if (!isAudioUri(uri, mime)) continue
                        val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME))
                            ?: getString(R.string.unknown_track)
                        if (uri.toString() in cachedUris) continue
                        try {
                            tracks.add(readTrack(uri, name, added))
                        } catch (error: Exception) {
                            errors.add(getString(R.string.track_read_error, error.localizedMessage ?: error.javaClass.simpleName))
                        }
                    }
                } ?: errors.add(getString(R.string.library_unavailable))
            } catch (error: Exception) {
                errors.add(getString(R.string.library_load_error, error.localizedMessage ?: error.javaClass.simpleName))
            }
            importedUris.forEach { raw ->
                try {
                    val uri = Uri.parse(raw)
                    if (!isAudioUri(uri, contentResolver.getType(uri))) return@forEach
                    tracks.add(readTrack(uri, displayName(uri)))
                } catch (error: Exception) {
                    errors.add(getString(R.string.track_read_error, error.localizedMessage ?: error.javaClass.simpleName))
                }
            }
            val unique = tracks.distinctBy { it.uri.toString() }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                libraryTracks = unique
                renderLibrary(unique, errors)
                refreshPlaybackHistoryViews()
                refreshPlaylists()
            }
        }
    }

    private fun readTrack(uri: Uri, fallback: String, dateAdded: Long = 0L): AudioTrack {
        val cached = runCatching { playlists.getTrackMetadata(uri.toString()) }.getOrNull()
        val metadata = MediaMetadataRetriever()
        return try {
            metadata.setDataSource(this, uri)
            fun text(key: Int) = metadata.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            val title = cached?.title?.takeIf { it.isNotBlank() }
                ?: text(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: fallback.substringBeforeLast('.', fallback)
            val artist = cached?.artist?.takeIf { it.isNotBlank() }
                ?: text(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: getString(R.string.unknown_artist)
            val album = cached?.album?.takeIf { it.isNotBlank() }
                ?: text(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?: getString(R.string.unknown_album)
            val duration = cached?.durationMs
                ?: text(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val embeddedArt = metadata.embeddedPicture
            val artworkBytes = cached?.artworkPath?.let { path ->
                runCatching { File(path).takeIf(File::isFile)?.readBytes() }.getOrNull()
            } ?: embeddedArt
            if (embeddedArt != null || cached == null) {
                val path = embeddedArt?.let { saveArtwork(uri, it) } ?: cached?.artworkPath
                runCatching { playlists.saveTrackMetadata(uri.toString(), title, artist, album, duration, path) }
            }
            AudioTrack(
                uri = uri,
                title = title,
                artist = artist,
                album = album,
                durationMs = duration,
                artwork = artworkBytes?.let(::decodeArtwork),
                dateAdded = dateAdded,
            )
        } catch (error: Exception) {
            if (cached == null) throw error
            val artwork = cached.artworkPath?.let { path ->
                runCatching { File(path).takeIf(File::isFile)?.readBytes() }.getOrNull()
            }?.let(::decodeArtwork)
            AudioTrack(
                uri = uri,
                title = cached.title.ifBlank { fallback.substringBeforeLast('.', fallback) },
                artist = cached.artist.ifBlank { getString(R.string.unknown_artist) },
                album = cached.album.ifBlank { getString(R.string.unknown_album) },
                durationMs = cached.durationMs ?: 0L,
                artwork = artwork,
                dateAdded = dateAdded,
            )
        } finally { metadata.release() }
    }

    private fun saveArtwork(uri: Uri, bytes: ByteArray): String? {
        return try {
            val directory = File(filesDir, "track-artwork")
            if (!directory.exists() && !directory.mkdirs()) return null
            val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
            val target = File(directory, "$digest.jpg")
            FileOutputStream(target).use { it.write(bytes) }
            target.absolutePath
        } catch (_: java.io.IOException) {
            null
        }
    }

    private fun decodeArtwork(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / MAX_TRACK_ART_PIXELS).coerceAtLeast(1)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        })
    }

    private fun displayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment ?: getString(R.string.unknown_track)
        } catch (_: SecurityException) { uri.lastPathSegment ?: getString(R.string.unknown_track) }
    }

    private fun isAudioUri(uri: Uri, mimeType: String?): Boolean {
        if (mimeType != null) return mimeType.startsWith("audio/")
        val name = displayName(uri)
        return name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS
    }

    private fun renderLibrary(tracks: List<AudioTrack>, errors: List<String>) {
        libraryTracks = tracks
        libraryErrors = errors.distinct()
        renderHomeLibrary()
        renderFilteredLibrary()
    }

    private fun renderFilteredLibrary() {
        if (!::historyList.isInitialized || !::libraryFilterButton.isInitialized ||
            !::librarySearch.isInitialized || !::libraryTrackHeading.isInitialized ||
            !::libraryPlayAllButton.isInitialized
        ) return
        val query = librarySearch.text.toString().trim()
        val filtered = libraryTracks.filter { track ->
            query.isEmpty() || track.title.contains(query, true) ||
                track.artist.contains(query, true) || track.album.contains(query, true)
        }
        val matches = when (librarySortMode) {
            1 -> filtered.sortedBy { it.artist.lowercase() }
            2 -> filtered.sortedBy { it.album.lowercase() }
            3 -> filtered.sortedByDescending { it.dateAdded }
            else -> filtered.sortedBy { it.title.lowercase() }
        }
        visibleLibraryTracks = matches
        libraryTrackHeading.text = getString(R.string.library_track_count, matches.size)
        libraryPlayAllButton.visibility = if (matches.isEmpty()) View.GONE else View.VISIBLE
        historyList.removeAllViews()
        if (libraryErrors.isNotEmpty()) historyList.addView(emptyMessage(libraryErrors.joinToString("\n")))
        val duplicateGroups = matches.groupBy { track ->
            "${track.artist.normalizedForDuplicate()}\u0000${track.title.normalizedForDuplicate()}"
        }.values.filter { group -> group.size > 1 }
        if (duplicateGroups.isNotEmpty()) {
            historyList.addView(emptyMessage(getString(R.string.duplicate_tracks_notice, duplicateGroups.size)))
        }
        if (matches.isEmpty()) {
            historyList.addView(emptyMessage(
                if (libraryTracks.isEmpty()) getString(R.string.library_empty) else getString(R.string.library_no_filter_matches)
            ))
            return
        }
        when (libraryGroupMode) {
            1 -> renderTrackGroups(matches, { it.artist.ifBlank { getString(R.string.unknown_artist) } })
            2 -> renderTrackGroups(matches, { it.album.ifBlank { getString(R.string.unknown_album) } })
            else -> {
                val grid = newHomeMusicGrid()
                matches.forEachIndexed { index, track ->
                    addHomeTrackCard(track, matches, index, grid)
                }
                historyList.addView(grid, matchWidth())
            }
        }
    }

    private fun renderTrackGroups(tracks: List<AudioTrack>, groupName: (AudioTrack) -> String) {
        tracks.groupBy(groupName).toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, members) ->
            historyList.addView(sectionTitle("$name  ·  ${members.size}"), matchWidth().apply { topMargin = dp(10) })
            val grid = newHomeMusicGrid()
            members.forEachIndexed { index, track ->
                addHomeTrackCard(track, members, index, grid)
            }
            historyList.addView(grid, matchWidth())
        }
    }

    private fun addHistoryItem(name: String, dateSeconds: Long, mime: String, uri: Uri) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(COLOR_CARD, dp(12))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        card.addView(TextView(this).apply {
            text = getString(R.string.history_item, name, DateUtils.getRelativeTimeSpanString(dateSeconds * 1000))
            textSize = 14f
            setTextColor(COLOR_TEXT)
            setPadding(0, dp(4), 0, dp(8))
            setOnClickListener { openDownloadedFile(uri, mime) }
        }, matchWidth())
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(queueActionButton(getString(R.string.rename)) { promptRenameDownload(uri, name) },
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        actions.addView(queueActionButton(getString(R.string.share)) { shareDownloadedFile(uri, mime, name) },
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        actions.addView(queueActionButton(getString(R.string.delete)) { confirmDeleteDownload(uri, name) },
            LinearLayout.LayoutParams(0, dp(42), 1f))
        card.addView(actions, matchWidth())
        val columns = downloadHistoryList.columnCount.coerceAtLeast(1)
        val position = downloadHistoryList.childCount
        downloadHistoryList.addView(
            card,
            GridLayout.LayoutParams(
                GridLayout.spec(position / columns),
                GridLayout.spec(position % columns),
            ).apply {
                width = homeGridCardWidth(columns)
                height = WRAP
                setMargins(dp(4), dp(4), dp(4), dp(8))
            },
        )
        animateHomeCard(card, position)
    }

    private fun openDownloadedFile(uri: Uri, mime: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: android.content.ActivityNotFoundException) {
            showError(getString(R.string.no_file_handler))
        } catch (error: SecurityException) {
            showError(getString(R.string.file_action_error, error.localizedMessage ?: "permission denied"))
        }
    }

    private fun promptRenameDownload(uri: Uri, oldName: String) {
        val input = EditText(this).apply { setText(oldName); selectAll() }
        AlertDialog.Builder(this).setTitle(getString(R.string.rename_download)).setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rename) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName.contains('/') || newName.contains('\\')) {
                    showError(getString(R.string.invalid_download_name))
                    return@setPositiveButton
                }
                try {
                    val changed = contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) },
                        null,
                        null,
                    )
                    if (changed <= 0) showError(getString(R.string.file_action_failed))
                    refreshHistory()
                } catch (error: Exception) {
                    showError(getString(R.string.file_action_error, error.localizedMessage ?: error.javaClass.simpleName))
                }
            }.show()
    }

    private fun confirmDeleteDownload(uri: Uri, name: String) {
        AlertDialog.Builder(this).setMessage(getString(R.string.delete_download_confirm, name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                try {
                    if (contentResolver.delete(uri, null, null) <= 0) showError(getString(R.string.file_action_failed))
                    refreshHistory()
                } catch (error: Exception) {
                    showError(getString(R.string.file_action_error, error.localizedMessage ?: error.javaClass.simpleName))
                }
            }.show()
    }

    private fun shareDownloadedFile(uri: Uri, mime: String, name: String) {
        try {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, getString(R.string.share_download)))
        } catch (error: Exception) {
            showError(getString(R.string.file_action_error, error.localizedMessage ?: error.javaClass.simpleName))
        }
    }

    private fun trackRow(
        track: AudioTrack,
        withPlaylistAction: Boolean,
        playQueue: List<AudioTrack> = libraryTracks,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(COLOR_CARD, dp(12)); setPadding(dp(10), dp(8), dp(8), dp(8))
        }
        row.addView(artworkView(track, dp(54)), LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(10) })
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this).apply {
            text = track.title; textSize = 15f; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END; setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(COLOR_TEXT)
        })
        labels.addView(TextView(this).apply {
            text = "${track.artist} · ${track.album} · ${formatTime(track.durationMs)}"
            textSize = 12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(COLOR_MUTED); setPadding(0, dp(3), 0, 0)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.setOnClickListener { playTrackList(playQueue, playQueue.indexOfFirst { it.uri == track.uri }) }
        if (withPlaylistAction) row.addView(Button(this).apply {
            text = "+"; contentDescription = getString(R.string.add_to_playlist); styleActionButton(this, false)
            setOnClickListener { choosePlaylistForTrack(track) }
        }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(6) })
        row.setOnLongClickListener {
            AlertDialog.Builder(this).setTitle(track.title).setItems(arrayOf(
                getString(R.string.track_details), getString(R.string.play_now),
            )) { _, which ->
                if (which == 0) showTrackDetails(track) else
                    playTrackList(playQueue, playQueue.indexOfFirst { it.uri == track.uri })
            }.show()
            true
        }
        return row
    }

    private fun artworkView(track: AudioTrack, size: Int): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = roundedBackground(COLOR_SURFACE, dp(8))
        contentDescription = getString(
            R.string.track_artwork_description,
            track.title,
            track.artist,
        )
        val bitmap = track.artwork
        if (bitmap != null) setImageBitmap(bitmap)
        else {
            setImageResource(android.R.drawable.ic_media_play)
            track.thumbnailUrl?.let { loadThumbnail(it, this) }
        }
    }

    private fun showTrackDetails(track: AudioTrack) {
        val art = artworkView(track, dp(220))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(art, LinearLayout.LayoutParams(MATCH, dp(220)).apply { bottomMargin = dp(12) })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.track_detail_text, track.title, track.artist, track.album, formatTime(track.durationMs))
                setTextColor(COLOR_TEXT); textSize = 15f
            })
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.track_details)).setView(body)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun promptCreatePlaylist(onCreated: (Long) -> Unit = {}) {
        val input = EditText(this).apply { hint = getString(R.string.playlist_name) }
        AlertDialog.Builder(this).setTitle(getString(R.string.create_playlist)).setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) Toast.makeText(this, getString(R.string.playlist_name_required), Toast.LENGTH_SHORT).show()
                else try {
                    val id = playlists.createPlaylist(name)
                    refreshPlaylists()
                    onCreated(id)
                } catch (error: Exception) { showError(getString(R.string.playlist_error, error.localizedMessage ?: "")) }
            }.show()
    }

    private fun refreshPlaylists() {
        if (!::playlistList.isInitialized) {
            if (homeCategory == HOME_CATEGORY_PLAYLISTS) renderHomeLibrary()
            return
        }
        playlistList.removeAllViews()
        val saved = try { playlists.getPlaylists() } catch (error: Exception) {
            playlistList.addView(
                emptyMessage(getString(R.string.playlist_error, error.localizedMessage ?: "")),
                GridLayout.LayoutParams().apply {
                    width = MATCH
                    height = WRAP
                    columnSpec = GridLayout.spec(0, playlistList.columnCount)
                },
            )
            if (homeCategory == HOME_CATEGORY_PLAYLISTS) renderHomeLibrary()
            return
        }
        val query = if (::playlistSearch.isInitialized) playlistSearch.text.toString().trim() else ""
        val filtered = saved.filter { it.name.contains(query, ignoreCase = true) }
        if (filtered.isEmpty()) {
            val message = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(emptyMessage(
                    if (saved.isEmpty()) getString(R.string.playlists_empty)
                    else getString(R.string.playlists_no_matches),
                ))
                if (saved.isNotEmpty()) {
                    addView(Button(this@MainActivity).apply {
                        text = getString(R.string.clear_search)
                        styleActionButton(this, false)
                        setOnClickListener { playlistSearch.text.clear() }
                    }, matchWidth())
                }
            }
            playlistList.addView(
                message,
                GridLayout.LayoutParams().apply {
                    width = MATCH
                    height = WRAP
                    columnSpec = GridLayout.spec(0, playlistList.columnCount)
                },
            )
            if (homeCategory == HOME_CATEGORY_PLAYLISTS) renderHomeLibrary()
            return
        }
        filtered.forEach { playlist ->
            val coverTrack = playlist.tracks.firstNotNullOfOrNull { uri ->
                resolvePlaylistTrack(uri)
            }
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBackground(COLOR_CARD, dp(14))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                tag = "playlist-${playlist.id}"
                isClickable = true
                isFocusable = true
                contentDescription = getString(
                    R.string.playlist_card_accessibility,
                    playlist.name,
                    playlist.tracks.size,
                )
                setOnClickListener { openPlaylist(playlist.id) }
            }
            val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            header.addView(
                coverTrack?.let { artworkView(it, dp(52)) } ?: ImageView(this).apply {
                    setImageResource(android.R.drawable.ic_media_play)
                    background = roundedBackground(COLOR_SURFACE, dp(8))
                },
                LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(10) },
            )
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(this@MainActivity).apply {
                text = playlist.name
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(COLOR_TEXT)
            })
            labels.addView(TextView(this@MainActivity).apply {
                text = getString(R.string.playlist_track_count, playlist.tracks.size)
                textSize = 12f
                setTextColor(COLOR_MUTED)
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) })
            header.addView(labels, LinearLayout.LayoutParams(0, WRAP, 1f))
            header.addView(Button(this).apply {
                text = "⋮"
                contentDescription = getString(R.string.playlist_actions_accessibility, playlist.name)
                styleActionButton(this, false)
                setOnClickListener { showPlaylistMenu(playlist, this) }
            }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) })
            panel.addView(header, matchWidth())
            val columns = playlistList.columnCount
            val position = playlistList.childCount
            playlistList.addView(
                panel,
                GridLayout.LayoutParams(
                    GridLayout.spec(position / columns),
                    GridLayout.spec(position % columns),
                ).apply {
                    width = homeGridCardWidth(columns)
                    height = WRAP
                    setMargins(dp(4), dp(4), dp(4), dp(8))
                },
            )
            animateHomeCard(panel, position)
        }
        if (homeCategory == HOME_CATEGORY_PLAYLISTS) renderHomeLibrary()
    }

    private fun showPlaylistMenu(playlist: SavedPlaylist, anchor: View) {
        android.widget.PopupMenu(this, anchor).apply {
            menu.add(0, 0, 0, R.string.play)
            menu.add(0, 1, 1, R.string.add_tracks)
            menu.add(0, 2, 2, R.string.share_playlist)
            menu.add(0, 3, 3, R.string.delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> loadPlaylistAndPlay(playlist.id)
                    1 -> chooseTracksForPlaylist(playlist.id)
                    2 -> sharePlaylistM3u(playlist)
                    3 -> confirmDeletePlaylist(playlist.id, playlist.name)
                }
                true
            }
            show()
        }
    }

    private fun choosePlaylistForTrack(track: AudioTrack, prepareForSave: () -> Unit = {}) {
        val saved = try {
            playlists.getPlaylists()
        } catch (error: Exception) {
            showError(getString(R.string.playlist_error, error.localizedMessage ?: ""))
            return
        }
        val search = EditText(this).apply {
            hint = getString(R.string.playlist_search_hint)
            isSingleLine = true
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedBackground(COLOR_SURFACE, dp(12))
            minHeight = dp(48)
        }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val resultScroll = ScrollView(this).apply {
            isFillViewport = false
            addView(results)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(search, matchWidth())
            addView(resultScroll, LinearLayout.LayoutParams(MATCH, dp(300)).apply {
                topMargin = dp(8)
            })
        }
        lateinit var dialog: AlertDialog
        fun render(query: String) {
            results.removeAllViews()
            val matching = saved.filter { it.name.contains(query.trim(), ignoreCase = true) }
            if (matching.isEmpty()) {
                results.addView(emptyMessage(
                    if (saved.isEmpty()) getString(R.string.playlists_empty)
                    else getString(R.string.playlists_no_matches),
                ))
                return
            }
            matching.forEach { playlist ->
                results.addView(Button(this).apply {
                    text = getString(R.string.playlist_picker_item, playlist.name, playlist.tracks.size)
                    styleActionButton(this, false)
                    setOnClickListener {
                        addTrackToPlaylist(playlist.id, playlist.name, track, prepareForSave)
                        dialog.dismiss()
                    }
                }, matchWidth().apply { bottomMargin = dp(6) })
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                render(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        render("")
        dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.add_to_playlist))
            .setView(body)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.create_playlist, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialog.dismiss()
                promptCreatePlaylist { playlistId ->
                    val created = playlists.getPlaylists().firstOrNull { it.id == playlistId }
                    if (created == null) showError(getString(R.string.playlist_track_failed))
                    else addTrackToPlaylist(playlistId, created.name, track, prepareForSave)
                }
            }
        }
        dialog.show()
    }

    private fun addTrackToPlaylist(
        playlistId: Long,
        playlistName: String,
        track: AudioTrack,
        prepareForSave: () -> Unit = {},
    ) {
        try {
            prepareForSave()
            if (playlists.addTrack(playlistId, track.uri.toString())) {
                Toast.makeText(
                    this,
                    getString(R.string.track_added_to_playlist, playlistName),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                Toast.makeText(this, getString(R.string.playlist_track_already_added), Toast.LENGTH_SHORT).show()
            }
            refreshPlaylists()
            playlistList.findViewWithTag<View>("playlist-$playlistId")?.let(::animateSuccess)
        } catch (error: Exception) {
            showError(error.localizedMessage ?: getString(R.string.playlist_track_failed))
        }
    }

    private fun chooseTracksForPlaylist(id: Long) {
        if (libraryTracks.isEmpty()) { Toast.makeText(this, getString(R.string.library_empty), Toast.LENGTH_SHORT).show(); return }
        val selectedUris = linkedSetOf<String>()
        val selectedCount = TextView(this).apply {
            text = getString(R.string.selected_tracks, 0)
            textSize = 13f
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(8), 0, dp(4))
        }
        val search = EditText(this).apply {
            hint = getString(R.string.library_search_hint)
            isSingleLine = true
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedBackground(COLOR_SURFACE, dp(12))
            minHeight = dp(48)
        }
        val trackRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val trackScroll = ScrollView(this).apply { addView(trackRows) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(search, matchWidth())
            addView(selectedCount, matchWidth())
            addView(trackScroll, LinearLayout.LayoutParams(MATCH, dp(320)))
        }
        var addButton: Button? = null
        fun renderTrackChoices(query: String) {
            trackRows.removeAllViews()
            val matches = libraryTracks.filter { track ->
                query.isBlank() || track.title.contains(query, true) ||
                    track.artist.contains(query, true) || track.album.contains(query, true)
            }
            if (matches.isEmpty()) {
                trackRows.addView(emptyMessage(getString(R.string.library_no_filter_matches)))
                return
            }
            matches.forEach { track ->
                val uri = track.uri.toString()
                val checkBox = CheckBox(this).apply {
                    text = "${track.title} — ${track.artist}"
                    isChecked = uri in selectedUris
                    minHeight = dp(48)
                    setTextColor(COLOR_TEXT)
                    buttonTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedUris.add(uri) else selectedUris.remove(uri)
                        selectedCount.text = getString(R.string.selected_tracks, selectedUris.size)
                        addButton?.isEnabled = selectedUris.isNotEmpty()
                    }
                }
                trackRows.addView(checkBox, matchWidth())
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderTrackChoices(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        renderTrackChoices("")
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.select_tracks))
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.add_tracks) { _, _ ->
                try {
                    var addedCount = 0
                    selectedUris.forEach { if (playlists.addTrack(id, it)) addedCount++ }
                    refreshPlaylists()
                    playlistList.findViewWithTag<View>("playlist-$id")?.let(::animateSuccess)
                    if (addedCount > 0) {
                        Toast.makeText(this, getString(R.string.tracks_added_to_playlist, addedCount), Toast.LENGTH_SHORT).show()
                    }
                } catch (error: Exception) { showError(error.localizedMessage ?: getString(R.string.playlist_track_failed)) }
            }.show()
        addButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        addButton?.isEnabled = selectedUris.isNotEmpty()
    }

    private fun openPlaylist(id: Long) {
        val playlist = playlists.getPlaylists().firstOrNull { it.id == id } ?: return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val available = playlist.tracks.mapNotNull { raw ->
            resolvePlaylistTrack(raw)?.let { raw to it }
        }
        val columns = searchGridColumnCount()
        val grid = newHomeMusicGrid()
        if (available.isEmpty()) panel.addView(emptyMessage(getString(R.string.playlist_no_available_tracks)))
        available.forEachIndexed { cellIndex, (raw, track) ->
            val cardWidth = homeGridCardWidth(columns)
            val originalIndex = playlist.tracks.indexOf(raw)
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBackground(COLOR_CARD, dp(14))
                setPadding(dp(7), dp(7), dp(7), dp(8))
            }
            cell.addView(artworkView(track, cardWidth - dp(14)), LinearLayout.LayoutParams(MATCH, cardWidth - dp(14)))
            cell.addView(TextView(this).apply {
                text = track.title
                textSize = 13f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(COLOR_TEXT)
                setPadding(dp(2), dp(8), dp(2), dp(4))
            }, LinearLayout.LayoutParams(MATCH, dp(48)))
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            actions.addView(Button(this).apply {
                text = "↑"
                contentDescription = getString(R.string.move_track_up)
                styleActionButton(this, false)
                isEnabled = originalIndex > 0
                setOnClickListener { movePlaylistTrack(playlist.id, originalIndex, originalIndex - 1) }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(2) })
            actions.addView(Button(this).apply {
                text = "↓"
                contentDescription = getString(R.string.move_track_down)
                styleActionButton(this, false)
                isEnabled = originalIndex >= 0 && originalIndex < playlist.tracks.lastIndex
                setOnClickListener { movePlaylistTrack(playlist.id, originalIndex, originalIndex + 1) }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            })
            actions.addView(Button(this).apply {
                text = "×"
                contentDescription = getString(R.string.remove_track)
                styleActionButton(this, false)
                setOnClickListener {
                    removeFromPlaylist(id, raw)
                    grid.removeView(cell)
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(2) })
            cell.addView(actions, matchWidth())
            grid.addView(
                cell,
                GridLayout.LayoutParams(
                    GridLayout.spec(cellIndex / columns),
                    GridLayout.spec(cellIndex % columns),
                ).apply {
                    width = cardWidth
                    height = WRAP
                    setMargins(dp(4), dp(4), dp(4), dp(8))
                },
            )
            animateHomeCard(cell, cellIndex)
            cell.setOnClickListener {
                if (track.uri.scheme == "http" || track.uri.scheme == "https") {
                    enqueueUrls(listOf(raw), showStatus = false)
                } else {
                    val localTracks = available.map { it.second }
                        .filter { it.uri.scheme != "http" && it.uri.scheme != "https" }
                    val localIndex = localTracks.indexOf(track)
                    if (localIndex >= 0) playTrackList(localTracks, localIndex)
                }
            }
            cell.isClickable = true
            cell.setOnLongClickListener {
                showTrackDetails(track)
                true
            }
        }
        if (available.isNotEmpty()) panel.addView(grid, matchWidth())
        AlertDialog.Builder(this).setTitle(playlist.name).setView(panel)
            .setNeutralButton(R.string.play) { _, _ ->
                val tracks = available.map { it.second }
                    .filter { it.uri.scheme != "http" && it.uri.scheme != "https" }
                if (tracks.isNotEmpty()) {
                    playTrackList(tracks, 0)
                } else {
                    val remoteUrls = available.map { it.first }
                        .filter { it.startsWith("http://") || it.startsWith("https://") }
                    if (remoteUrls.isNotEmpty()) enqueueUrls(remoteUrls, showStatus = false)
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun movePlaylistTrack(playlistId: Long, fromIndex: Int, toIndex: Int) {
        try {
            val moved = playlists.moveTrack(playlistId, fromIndex, toIndex)
            if (!moved) showError(getString(R.string.playlist_track_failed))
            refreshPlaylists()
            openPlaylist(playlistId)
        } catch (error: Exception) {
            showError(error.localizedMessage ?: getString(R.string.playlist_track_failed))
        }
    }

    private fun sharePlaylistM3u(playlist: SavedPlaylist) {
        val entries = playlist.tracks.mapNotNull { raw ->
            val track = resolvePlaylistTrack(raw) ?: return@mapNotNull null
            val label = listOf(track.artist, track.title)
                .filter(String::isNotBlank)
                .joinToString(" - ")
            "#EXTINF:${(track.durationMs / 1000).coerceAtLeast(-1)},$label\n$raw"
        }
        if (entries.isEmpty()) {
            showError(getString(R.string.playlist_no_available_tracks))
            return
        }
        val m3u = (listOf("#EXTM3U") + entries).joinToString("\n")
        try {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "audio/x-mpegurl"
                putExtra(Intent.EXTRA_SUBJECT, "${playlist.name}.m3u")
                putExtra(Intent.EXTRA_TEXT, m3u)
            }
            startActivity(Intent.createChooser(share, getString(R.string.share_playlist)))
        } catch (error: Exception) {
            showError(getString(R.string.playlist_error, error.localizedMessage ?: ""))
        }
    }

    private fun importPlaylistM3u(uri: Uri) {
        metadataExecutor.execute {
            val result = try {
                val text = contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (total <= MAX_M3U_BYTES) {
                        val count = input.read(buffer, 0, minOf(buffer.size, MAX_M3U_BYTES + 1 - total))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        total += count
                    }
                    check(total <= MAX_M3U_BYTES) { getString(R.string.playlist_file_too_large) }
                    output.toString(Charsets.UTF_8.name())
                } ?: error(getString(R.string.playlist_import_failed))
                val available = libraryTracks.associateBy { it.uri.toString() }
                val matches = linkedMapOf<String, PlaylistImportItem>()
                var pendingTitle: String? = null
                var pendingDurationMs: Long? = null
                text.lineSequence().map(String::trim).forEach { line ->
                    when {
                        line.startsWith("#EXTINF:", ignoreCase = true) -> {
                            val details = line.substringAfter(':')
                            val durationSeconds = details.substringBefore(',').toLongOrNull()
                            pendingDurationMs = durationSeconds?.takeIf { it >= 0 }?.times(1000L)
                            pendingTitle = details.substringAfter(',', "").trim().takeIf(String::isNotEmpty)
                        }
                        line.isEmpty() || line.startsWith("#") -> Unit
                        available.containsKey(line) -> {
                            matches.putIfAbsent(line, PlaylistImportItem(line))
                            pendingTitle = null
                            pendingDurationMs = null
                        }
                        else -> {
                            val remoteUri = Uri.parse(line)
                            if (remoteUri.scheme in setOf("http", "https") && !remoteUri.host.isNullOrBlank()) {
                                val displayTitle = pendingTitle.orEmpty()
                                val separator = displayTitle.indexOf(" - ")
                                val artist = if (separator > 0) displayTitle.substring(0, separator) else ""
                                val title = if (separator > 0) displayTitle.substring(separator + 3) else displayTitle
                                val metadata = SavedTrackMetadata(
                                    uri = line,
                                    title = title.ifBlank { remoteUri.lastPathSegment ?: line },
                                    artist = artist,
                                    album = "",
                                    durationMs = pendingDurationMs,
                                    artworkPath = null,
                                )
                                matches.putIfAbsent(line, PlaylistImportItem(line, metadata))
                            }
                            pendingTitle = null
                            pendingDurationMs = null
                        }
                    }
                }
                if (matches.isEmpty()) error(getString(R.string.playlist_no_matching_tracks))
                Result.success(matches.values.toList())
            } catch (error: Exception) {
                Result.failure(error)
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { tracks ->
                        val input = EditText(this).apply { hint = getString(R.string.playlist_name) }
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.playlist_import_name))
                            .setView(input)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.create) { _, _ ->
                                val name = input.text.toString().trim()
                                try {
                                    require(name.isNotEmpty()) { getString(R.string.playlist_name_required) }
                                    val id = playlists.createPlaylist(name)
                                    tracks.forEach { track ->
                                        track.metadata?.let { metadata ->
                                            playlists.saveTrackMetadata(
                                                trackUri = track.uri,
                                                title = metadata.title,
                                                artist = metadata.artist,
                                                album = metadata.album,
                                                durationMs = metadata.durationMs,
                                                artworkPath = metadata.artworkPath,
                                            )
                                        }
                                        check(playlists.addTrack(id, track.uri)) {
                                            getString(R.string.playlist_track_failed)
                                        }
                                    }
                                    refreshPlaylists()
                                } catch (error: Exception) {
                                    showError(error.localizedMessage ?: getString(R.string.playlist_import_failed))
                                }
                            }.show()
                    },
                    onFailure = { error ->
                        showError(error.localizedMessage ?: getString(R.string.playlist_import_failed))
                    },
                )
            }
        }
    }

    private fun loadPlaylistAndPlay(id: Long) {
        val list = playlists.getPlaylists().firstOrNull { it.id == id } ?: return
        val resolved = list.tracks.mapNotNull(::resolvePlaylistTrack)
        val tracks = resolved.filter { it.uri.scheme != "http" && it.uri.scheme != "https" }
        if (tracks.isNotEmpty()) playTrackList(tracks, 0)
        else {
            val remoteUrls = resolved.map { it.uri.toString() }
            if (remoteUrls.isNotEmpty()) enqueueUrls(remoteUrls, showStatus = false)
            else Toast.makeText(this, getString(R.string.playlist_no_available_tracks), Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeletePlaylist(id: Long, name: String) {
        AlertDialog.Builder(this).setMessage(getString(R.string.delete_playlist_confirm, name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                try { playlists.deletePlaylist(id); refreshPlaylists() }
                catch (error: Exception) { showError(error.localizedMessage ?: getString(R.string.playlist_error, "")) }
            }.show()
    }

    private fun removeFromPlaylist(id: Long, uri: String) {
        try { playlists.removeTrack(id, uri); refreshPlaylists() }
        catch (error: Exception) { showError(error.localizedMessage ?: getString(R.string.playlist_track_failed)) }
    }

    private fun playTrackList(tracks: List<AudioTrack>, index: Int) {
        if (tracks.isEmpty() || index !in tracks.indices) {
            playerStatus.text = getString(R.string.player_empty); showTab(R.string.nav_player); return
        }
        val service = playbackService
        if (service == null) {
            playerStatus.text = getString(R.string.player_service_unavailable)
            return
        }
        service.playQueue(
            tracks.map {
                PlaybackTrack(
                    uri = it.uri.toString(),
                    title = it.title,
                    artist = it.artist,
                    album = it.album,
                    durationMs = it.durationMs,
                    artwork = it.artwork,
                )
            },
            index,
        )
        showTab(R.string.nav_player)
    }

    private fun togglePlayback() {
        if (playbackService == null) {
            if (libraryTracks.isNotEmpty()) playTrackList(libraryTracks, 0)
            else playerStatus.text = getString(R.string.library_empty)
            return
        }
        playbackService?.togglePlayback()
    }

    private fun playPrevious() = playbackService?.playPrevious() ?: run {
        playerStatus.text = getString(R.string.player_service_unavailable)
    }

    private fun playNext() = playbackService?.playNext() ?: run {
        playerStatus.text = getString(R.string.player_service_unavailable)
    }

    private fun renderPlaybackSnapshot(snapshot: PlaybackSnapshot) {
        playbackSnapshot = snapshot
        val track = snapshot.track
        if (track == null) {
            lastArtworkAnimationUri = null
            currentPlaybackTrack = null
            playerTitle.text = getString(R.string.nothing_playing)
            playerArtist.text = ""
            playerArtwork.setImageResource(android.R.drawable.ic_media_play)
            nowPlayingBar.animate().cancel()
            nowPlayingBar.visibility = View.GONE
        } else {
            val trackChanged = lastArtworkAnimationUri != track.uri
            lastArtworkAnimationUri = track.uri
            val showPlayerBar = nowPlayingBar.visibility != View.VISIBLE
            currentPlaybackTrack = AudioTrack(
                uri = Uri.parse(track.uri),
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs,
                artwork = track.artwork,
            )
            playerTitle.text = track.title
            playerArtist.text = "${track.artist} · ${track.album}"
            playerArtwork.contentDescription = getString(
                R.string.track_artwork_description,
                track.title,
                track.artist,
            )
            nowPlayingBar.contentDescription = getString(
                R.string.now_playing_accessibility,
                track.title,
                track.artist,
            )
            playerArtwork.setImageResource(android.R.drawable.ic_media_play)
            track.artwork?.let { playerArtwork.setImageBitmap(it) }
            if (trackChanged && animationsEnabled()) {
                playerArtwork.animate().cancel()
                playerArtwork.alpha = 0.72f
                playerArtwork.scaleX = 0.96f
                playerArtwork.scaleY = 0.96f
                playerArtwork.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(SUCCESS_ANIMATION_MS)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.08f))
                    .start()
            }
            nowPlayingTitle.text = track.title
            nowPlayingArtist.text = track.artist
            nowPlayingArtwork.setImageResource(android.R.drawable.ic_media_play)
            track.artwork?.let { nowPlayingArtwork.setImageBitmap(it) }
            nowPlayingBar.visibility = View.VISIBLE
            if (showPlayerBar && animationsEnabled()) {
                nowPlayingBar.alpha = 0f
                nowPlayingBar.translationY = dp(8).toFloat()
                nowPlayingBar.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(TAB_TRANSITION_MS)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
                    .start()
            } else {
                nowPlayingBar.alpha = 1f
                nowPlayingBar.translationY = 0f
            }
        }
        nowPlayingButton.text = if (snapshot.isPlaying) "Ⅱ" else "▶"
        nowPlayingButton.contentDescription = getString(
            if (snapshot.isPlaying) R.string.pause_playback else R.string.play,
        )
        playerSeek.max = snapshot.durationMs.coerceAtLeast(1)
        if (!seeking) playerSeek.progress = snapshot.positionMs.coerceIn(0, playerSeek.max)
        playerPosition.text = formatTime(snapshot.positionMs.toLong())
        playerDuration.text = formatTime(snapshot.durationMs.toLong())
        playerSeek.isEnabled = snapshot.isPrepared
        playButton.text = if (snapshot.isPlaying) "Ⅱ" else "▶"
        playButton.contentDescription = getString(
            if (snapshot.isPlaying) R.string.pause_playback else R.string.play,
        )
        playerStatus.text = snapshot.message ?: when {
            snapshot.isPlaying -> getString(R.string.player_playing)
            track != null && !snapshot.isPrepared -> getString(R.string.player_loading)
            track != null -> getString(R.string.player_paused)
            else -> getString(R.string.player_empty)
        }
        shuffleButton.isSelected = snapshot.shuffle
        shuffleButton.contentDescription = getString(
            if (snapshot.shuffle) R.string.shuffle_enabled else R.string.shuffle,
        )
        shuffleButton.background = roundedBackground(
            if (snapshot.shuffle) COLOR_ACCENT_SECONDARY else COLOR_SURFACE,
            dp(12),
        )
        shuffleButton.setTextColor(if (snapshot.shuffle) COLOR_BACKGROUND else COLOR_TEXT)
        repeatButton.isSelected = snapshot.repeatMode != 0
        repeatButton.contentDescription = getString(
            when (snapshot.repeatMode) {
                1 -> R.string.repeat_all
                2 -> R.string.repeat_one
                else -> R.string.repeat
            },
        )
        repeatButton.text = getString(
            when (snapshot.repeatMode) {
                1 -> R.string.repeat_all
                2 -> R.string.repeat_one
                else -> R.string.repeat
            },
        ).let { "↻  $it" }
        repeatButton.background = roundedBackground(
            if (snapshot.repeatMode != 0) COLOR_ACCENT_WARM else COLOR_SURFACE,
            dp(12),
        )
        repeatButton.setTextColor(if (snapshot.repeatMode != 0) COLOR_BACKGROUND else COLOR_TEXT)
        syncingPlaybackControls = true
        speedSpinner.setSelection(
            PLAYBACK_SPEEDS.indices.minByOrNull { kotlin.math.abs(PLAYBACK_SPEEDS[it] - snapshot.speed) } ?: DEFAULT_SPEED_INDEX,
        )
        equalizerToggle.isChecked = snapshot.equalizerEnabled
        equalizerLevelSeek.progress = snapshot.equalizerLevel
        equalizerLevelSeek.isEnabled = snapshot.equalizerEnabled
        sleepTimerButton.text = snapshot.sleepMinutesRemaining?.let {
            getString(R.string.sleep_timer_remaining, it)
        } ?: getString(R.string.sleep_timer)
        syncingPlaybackControls = false
    }

    private fun showSleepTimerOptions() {
        val service = playbackService
        if (service == null) {
            showError(getString(R.string.player_service_unavailable))
            return
        }
        val minutes = listOf(null, 15, 30, 60, 120, 240)
        val labels = minutes.map { value ->
            if (value == null) getString(R.string.sleep_timer_off)
            else getString(R.string.sleep_timer_minutes, value)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sleep_timer))
            .setItems(labels) { _, which -> service.setSleepTimerMinutes(minutes[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importResult(data: Intent?) {
        if (data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
        data.data?.let { uris.add(it) }
        var retained = importedUris.toMutableSet()
        var failed = 0
        uris.distinct().forEach { uri ->
            try {
                if (contentResolver.getType(uri)?.startsWith("audio/") != true) {
                    failed++
                    return@forEach
                }
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                retained.add(uri.toString())
            } catch (_: SecurityException) { failed++ }
        }
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putStringSet(KEY_IMPORTED, retained).apply()
        if (failed > 0) Toast.makeText(this, getString(R.string.import_permission_warning, failed), Toast.LENGTH_LONG).show()
        refreshLibrary()
    }

    @Deprecated("Deprecated in Android API; required for ACTION_OPEN_DOCUMENT callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMPORT_AUDIO && resultCode == RESULT_OK) importResult(data)
        if (requestCode == REQUEST_IMPORT_M3U && resultCode == RESULT_OK) {
            data?.data?.let(::importPlaylistM3u)
        }
    }

    private fun showError(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun emptyMessage(message: String) = TextView(this).apply {
        text = message; textSize = 14f; setTextColor(COLOR_MUTED); setPadding(dp(4), dp(10), dp(4), dp(10))
    }

    override fun onDestroy() {
        destroyed = true
        metadataExecutor.shutdownNow()
        thumbnailExecutor.shutdownNow()
        downloadService?.setListener(null)
        if (bound) unbindService(serviceConnection)
        playbackService?.setListener(null)
        if (playbackBound) unbindService(playbackConnection)
        super.onDestroy()
    }

    private fun formatTime(milliseconds: Long): String {
        val seconds = (milliseconds.coerceAtLeast(0) / 1000)
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun String.normalizedForDuplicate(): String =
        lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun Int.withAlpha(alpha: Int): Int =
        Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))

    private fun themeColor(attribute: Int) =
        if (attribute == android.R.attr.textColorSecondary) COLOR_MUTED else COLOR_TEXT

    private fun roundedBackground(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius.toFloat()
    }

    private fun styledSpinnerAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            items,
        ) {
            private fun styleItem(view: View): View = (view as? TextView)?.apply {
                setTextColor(COLOR_TEXT)
                textSize = 15f
                setPadding(dp(14), dp(12), dp(14), dp(12))
                minHeight = dp(48)
                setBackgroundColor(COLOR_SURFACE)
            } ?: view

            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                styleItem(super.getView(position, convertView, parent))

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                styleItem(super.getDropDownView(position, convertView, parent))
        }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    private fun sectionCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBackground(COLOR_CARD, dp(16))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        elevation = dp(2).toFloat()
    }

    private fun styleActionButton(button: Button, primary: Boolean) {
        button.isAllCaps = false; button.textSize = 14f
        button.setTextColor(if (primary) COLOR_BACKGROUND else COLOR_TEXT)
        button.background = roundedBackground(if (primary) COLOR_ACCENT else COLOR_SURFACE, dp(12))
        button.setPadding(dp(12), 0, dp(12), 0)
        button.minHeight = dp(48)
        button.minWidth = 0
        button.stateListAnimator = null
        button.setOnTouchListener { view, event ->
            if (animationsEnabled()) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> view.animate()
                        .scaleX(0.97f)
                        .scaleY(0.97f)
                        .setDuration(BUTTON_PRESS_MS)
                        .start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(BUTTON_PRESS_MS)
                        .start()
                }
            }
            false
        }
    }

    private fun animationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    private fun animateSuccess(view: View) {
        if (!animationsEnabled()) return
        view.animate().cancel()
        view.alpha = 0.75f
        view.scaleX = 0.97f
        view.scaleY = 0.97f
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(SUCCESS_ANIMATION_MS)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.15f))
            .start()
    }

    private fun animateHomeContent() {
        if (!animationsEnabled() || !::homeLibraryContent.isInitialized) return
        homeLibraryContent.animate().cancel()
        homeLibraryContent.alpha = 0f
        homeLibraryContent.translationY = dp(7).toFloat()
        homeLibraryContent.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(TAB_TRANSITION_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.3f))
            .start()
    }

    private fun sectionSpacing() = matchWidth().apply { topMargin = dp(20) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun matchWidth() = LinearLayout.LayoutParams(MATCH, WRAP)

    companion object {
        private const val REQUEST_NOTIFICATIONS = 10
        private const val REQUEST_AUDIO_LIBRARY = 13
        private const val SEARCH_PAGE_SIZE = 20
        private const val SEARCH_LOADING_TAG = "search-page-loading"
        private const val REQUEST_IMPORT_AUDIO = 11
        private const val REQUEST_IMPORT_M3U = 12
        private const val PREFERENCES = "downloader_preferences"
        private const val KEY_FORMAT = "format_mode"
        private const val KEY_IMPORTED = "imported_audio_uris"
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private const val MAX_THUMBNAIL_BYTES = 5 * 1024 * 1024
        private const val MAX_THUMBNAIL_PIXELS = 256
        private const val MAX_TRACK_ART_PIXELS = 640
        private const val MAX_M3U_BYTES = 1024 * 1024
        private const val MAX_CONCURRENT_DOWNLOADS = 10
        private const val MAX_CONTENT_WIDTH_DP = 760
        private const val TAB_TRANSITION_MS = 180L
        private const val BUTTON_PRESS_MS = 110L
        private const val SUCCESS_ANIMATION_MS = 260L
        private const val RECENT_HISTORY_PAGE_SIZE = 32
        private const val RECENT_HISTORY_PREVIEW_COUNT = 4
        private const val HOME_CATEGORY_MUSIC = 0
        private const val HOME_CATEGORY_ARTISTS = 1
        private const val HOME_CATEGORY_ALBUMS = 2
        private const val HOME_CATEGORY_PLAYLISTS = 3
        private const val HOME_CATEGORY_DISCOVER = 4
        private const val HOME_CATEGORY_RECENT_PLAYED = 5
        private const val DEFAULT_CONCURRENT_DOWNLOADS = 3
        private const val DEFAULT_EQUALIZER_LEVEL = 50
        private const val DEFAULT_SPEED_INDEX = 2
        private val PLAYBACK_SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        private val AUDIO_EXTENSIONS = setOf("aac", "aif", "aiff", "alac", "amr", "flac", "m4a", "mp3", "oga", "ogg", "opus", "wav", "wma")
        private val COLOR_BACKGROUND = Color.rgb(17, 20, 32)
        private val COLOR_NAVIGATION = Color.rgb(17, 20, 32)
        private val COLOR_SURFACE = Color.rgb(35, 41, 61)
        private val COLOR_CARD = Color.rgb(26, 31, 48)
        private val COLOR_PLAYER_BAR = Color.rgb(38, 45, 67)
        private val COLOR_TEXT = Color.rgb(247, 247, 255)
        private val COLOR_MUTED = Color.rgb(183, 190, 211)
        private val COLOR_ACCENT = Color.rgb(93, 230, 190)
        private val COLOR_ACCENT_SECONDARY = Color.rgb(193, 164, 255)
        private val COLOR_ACCENT_WARM = Color.rgb(255, 190, 122)
        private val COLOR_ACCENT_BLUE = Color.rgb(112, 186, 255)
        private val COLOR_ACCENT_PINK = Color.rgb(255, 137, 181)
        private val COLOR_HERO_START = Color.rgb(89, 52, 180)
        private val COLOR_HERO_MIDDLE = Color.rgb(126, 67, 189)
        private val COLOR_HERO_END = Color.rgb(34, 162, 160)
        private val COLOR_HERO_TEXT = Color.WHITE
    }
}
