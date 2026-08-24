package com.smartad.display

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : FragmentActivity() {

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private lateinit var playerView: PlayerView
    private lateinit var loadingOverlay: View
    private var player: ExoPlayer? = null

    private lateinit var firestore: FirebaseFirestore
    private var firestoreListener: ListenerRegistration? = null

    /** Ordered list of (docId, url) pairs — reflects the current Firestore state. */
    private var currentPlaylistDocs: List<Pair<String, String>> = emptyList()

    /**
     * Local media folder: getExternalFilesDir(null)/SmartDisplay_Media
     * No WRITE_EXTERNAL_STORAGE permission needed on API 29+.
     * Files are named {firestoreDocId}.mp4 for unambiguous matching.
     */
    private val mediaDir: File by lazy {
        File(getExternalFilesDir(null), "SmartDisplay_Media").also { it.mkdirs() }
    }

    /** Coroutine scope for background downloads — cancelled when activity is destroyed. */
    private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Tracks active download jobs by docId so they can be cancelled on delete. */
    private val downloadJobs = mutableMapOf<String, Job>()

    // Double-click detection for DPAD_CENTER
    private val dpadHandler = Handler(Looper.getMainLooper())
    private var dpadClickCount = 0
    private val DOUBLE_CLICK_WINDOW_MS = 500L
    private val dpadResetRunnable = Runnable {
        if (dpadClickCount == 1) togglePlayPause()
        dpadClickCount = 0
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView     = findViewById(R.id.playerView)
        loadingOverlay = findViewById(R.id.loadingOverlay)

        initPlayer()
        startLocalPlayback()   // Play cached files immediately
        initFirestore()        // Sync in background
    }

    override fun onStart() {
        super.onStart()
        player?.play()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        dpadHandler.removeCallbacks(dpadResetRunnable)
        firestoreListener?.remove()
        downloadScope.cancel()
        releasePlayer()
    }

    // -------------------------------------------------------------------------
    // ExoPlayer
    // -------------------------------------------------------------------------

    private fun initPlayer() {
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this)))
            .build()
            .also { exoPlayer ->
                playerView.player = exoPlayer
                exoPlayer.repeatMode   = Player.REPEAT_MODE_ALL
                exoPlayer.playWhenReady = true

                exoPlayer.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) hideOverlay()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "Playback error [item ${exoPlayer.currentMediaItemIndex}]: ${error.message}")
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (exoPlayer.mediaItemCount > 1) exoPlayer.seekToNextMediaItem()
                            else exoPlayer.prepare()
                        }, 2_000)
                    }
                })
            }
    }

    /**
     * Scans SmartDisplay_Media immediately on startup and begins playback
     * without waiting for the Firestore sync. Files are sorted by name
     * (alphabetical by docId) as a best-effort order until Firestore fires.
     */
    private fun startLocalPlayback() {
        val files = mediaDir
            .listFiles { f -> f.extension == "mp4" && !f.name.endsWith(".tmp") }
            ?.sortedBy { it.name }
            ?: return
        if (files.isEmpty()) return

        Log.d(TAG, "Startup: playing ${files.size} local files before Firestore sync")
        player?.run {
            setMediaItems(files.map { MediaItem.fromUri(Uri.fromFile(it)) })
            prepare()
            play()
        }
    }

    /**
     * Rebuilds the ExoPlayer queue from [docs] (in Firestore timestamp order).
     * For each doc: prefers local SmartDisplay_Media/{docId}.mp4 if it exists,
     * falls back to the Firebase Storage URL otherwise.
     * Preserves the currently playing item and seek position where possible.
     */
    private fun rebuildPlaylist(docs: List<Pair<String, String>>) {
        if (docs.isEmpty()) {
            Log.w(TAG, "Playlist empty — stopping")
            player?.stop()
            player?.clearMediaItems()
            showOverlay()
            return
        }

        val mediaItems = docs.map { (docId, url) ->
            val local = File(mediaDir, "$docId.mp4")
            if (local.exists()) {
                Log.d(TAG, "  [$docId] → local file")
                MediaItem.fromUri(Uri.fromFile(local))
            } else {
                Log.d(TAG, "  [$docId] → URL (not yet downloaded)")
                MediaItem.fromUri(url)
            }
        }

        val currentUri  = player?.currentMediaItem?.localConfiguration?.uri?.toString()
        val startIndex  = mediaItems.indexOfFirst {
            it.localConfiguration?.uri?.toString() == currentUri
        }.coerceAtLeast(0)
        val startPos    = if (currentUri != null && startIndex > 0)
            player?.currentPosition ?: 0L else 0L

        Log.d(TAG, "Rebuilding playlist: ${docs.size} items (startIndex=$startIndex)")
        player?.run {
            setMediaItems(mediaItems, startIndex, startPos)
            prepare()
            play()
        }
    }

    // -------------------------------------------------------------------------
    // Smart Local Storage — SmartDisplay_Media
    // -------------------------------------------------------------------------

    /**
     * Downloads the video at [url] into SmartDisplay_Media/{docId}.mp4.
     * - Skips silently if already present.
     * - Skips with a warning if free space < 200 MB.
     * - Uses a .tmp file to prevent partial downloads from being played.
     * - After a successful download, rebuilds the playlist on the main thread
     *   so ExoPlayer switches the item from URL to local URI automatically.
     */
    private fun downloadVideo(docId: String, url: String) {
        val localFile = File(mediaDir, "$docId.mp4")
        if (localFile.exists()) return

        val freeMB = mediaDir.freeSpace / 1_048_576
        if (freeMB < 200) {
            Log.w(TAG, "Only ${freeMB}MB free — skipping download of $docId")
            return
        }

        val job = downloadScope.launch {
            val tmp = File(mediaDir, "$docId.mp4.tmp")
            try {
                Log.d(TAG, "Downloading $docId.mp4 …")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout    = 60_000
                conn.connect()
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                }
                tmp.renameTo(localFile)
                Log.d(TAG, "Download complete: $docId.mp4 (${localFile.length() / 1_048_576} MB)")

                withContext(Dispatchers.Main) {
                    rebuildPlaylist(currentPlaylistDocs)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for $docId: ${e.message}")
                tmp.delete()
            } finally {
                downloadJobs.remove(docId)
            }
        }
        downloadJobs[docId] = job
    }

    /**
     * Cancels any in-progress download for [docId] and deletes both the
     * completed file and any leftover .tmp file from the local folder.
     */
    private fun deleteLocalFile(docId: String) {
        downloadJobs.remove(docId)?.cancel()
        File(mediaDir, "$docId.mp4").delete().also {
            if (it) Log.d(TAG, "Deleted local: $docId.mp4")
        }
        File(mediaDir, "$docId.mp4.tmp").delete()
    }

    private fun togglePlayPause() {
        player?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    // -------------------------------------------------------------------------
    // Firestore — playlist collection listener
    // -------------------------------------------------------------------------

    private fun initFirestore() {
        firestore = FirebaseFirestore.getInstance()

        firestoreListener = firestore
            .collection("playlist")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "Firestore error: ${error.message}", error)
                    return@addSnapshotListener
                }
                if (snapshots == null) return@addSnapshotListener

                // Handle per-document changes for local storage management
                snapshots.documentChanges.forEach { change ->
                    val docId = change.document.id
                    val url   = change.document.getString("url") ?: return@forEach
                    when (change.type) {
                        DocumentChange.Type.ADDED    -> downloadVideo(docId, url)
                        DocumentChange.Type.REMOVED  -> deleteLocalFile(docId)
                        DocumentChange.Type.MODIFIED -> { deleteLocalFile(docId); downloadVideo(docId, url) }
                    }
                }

                // Rebuild ExoPlayer queue from the full current Firestore state
                val newDocs = snapshots.documents.mapNotNull { doc ->
                    val url = doc.getString("url") ?: return@mapNotNull null
                    doc.id to url
                }
                if (newDocs != currentPlaylistDocs) {
                    currentPlaylistDocs = newDocs
                    rebuildPlaylist(newDocs)
                }
            }
    }

    // -------------------------------------------------------------------------
    // Loading overlay
    // -------------------------------------------------------------------------

    private fun showOverlay() {
        loadingOverlay.alpha = 1f
        loadingOverlay.visibility = View.VISIBLE
    }

    private fun hideOverlay() {
        if (loadingOverlay.visibility != View.VISIBLE) return
        loadingOverlay.animate()
            .alpha(0f)
            .setDuration(700)
            .withEndAction { loadingOverlay.visibility = View.GONE }
            .start()
    }

    // -------------------------------------------------------------------------
    // Remote control — DPAD_CENTER single / double click
    // -------------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            dpadClickCount++
            dpadHandler.removeCallbacks(dpadResetRunnable)
            if (dpadClickCount >= 2) {
                dpadClickCount = 0
                Toast.makeText(this, "Updating…", Toast.LENGTH_SHORT).show()
            } else {
                dpadHandler.postDelayed(dpadResetRunnable, DOUBLE_CLICK_WINDOW_MS)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps    = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    companion object {
        private const val TAG = "TVMainActivity"
    }
}
