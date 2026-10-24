package com.neurallabs.audiopark

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

private data class Song(
    val uri: Uri,
    val title: String,
    val artist: String,
    val durationMs: Long
)

class MainActivity : Activity() {
    private lateinit var audioManager: AudioManager
    private lateinit var libraryList: LinearLayout
    private lateinit var outputList: LinearLayout
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var statusText: TextView
    private lateinit var timeText: TextView
    private lateinit var playButton: Button
    private lateinit var dualButton: Button
    private lateinit var seekBar: SeekBar

    private val songs = mutableListOf<Song>()
    private val outputDevices = mutableListOf<AudioDeviceInfo>()
    private val selectedOutputIds = linkedSetOf<Int>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var playerA: MediaPlayer? = null
    private var playerB: MediaPlayer? = null
    private var currentIndex = -1
    private var currentSong: Song? = null
    private var dualMode = false
    private var shuffle = false
    private var repeat = false
    private var isPrepared = false
    private var isLoading = false
    private var isSeeking = false
    private var preparedPlayers = 0
    private var playersToPrepare = 0
    private var completionHandled = false
    private var loadRequestId = 0

    private val progressTicker = object : Runnable {
        override fun run() {
            val active = playerA
            if (!isSeeking && active != null && isPrepared) {
                val duration = active.duration.coerceAtLeast(1)
                seekBar.max = duration
                seekBar.progress = active.currentPosition.coerceIn(0, duration)
                timeText.text = "${formatTime(active.currentPosition)} / ${formatTime(duration)}"
            }
            mainHandler.postDelayed(this, 500)
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = runOnUiThread { refreshOutputs() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = runOnUiThread { refreshOutputs() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = color(0x101614)
        window.navigationBarColor = color(0x101614)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        buildScreen()
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        mainHandler.post(progressTicker)
        refreshOutputs()
        requestAudioPermissionsOrScan()
    }

    private fun buildScreen() {
        val narrow = resources.configuration.screenWidthDp < 360
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = if (narrow) 18 else 24
            setPadding(dp(padding), dp(20), dp(padding), dp(24))
            setBackgroundColor(color(0x101614))
        }
        setContentView(ScrollView(this).apply { addView(page) })

        page.addView(label("AUDIOPARK", 13f, 0x69E0C1, true))
        page.addView(label("Your library.\nYour outputs.", if (narrow) 30f else 34f, 0xF3F7F5, true).apply {
            setPadding(0, dp(12), 0, dp(8))
        })
        page.addView(label("Play music from this device and test supported output combinations.", 15f, 0xA9B6B1))

        val playerCard = card()
        page.addView(playerCard, topParams())
        playerCard.addView(label("NOW PLAYING", 12f, 0x91A59D, true))
        trackTitle = label("Choose a song below", 20f, 0xF3F7F5, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(13), 0, dp(3))
        }
        playerCard.addView(trackTitle)
        trackArtist = label("No song selected", 14f, 0xA9B6B1).apply { setPadding(0, 0, 0, dp(14)) }
        playerCard.addView(trackArtist)
        seekBar = SeekBar(this).apply {
            max = 1
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar?) { isSeeking = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    isSeeking = false
                    seekTo(seekBar?.progress ?: 0)
                }
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) timeText.text = "${formatTime(progress)} / ${formatTime(playerA?.duration ?: 0)}"
                }
            })
        }
        playerCard.addView(seekBar)
        timeText = label("00:00 / 00:00", 12f, 0x91A59D).apply { gravity = Gravity.END }
        playerCard.addView(timeText)

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(13), 0, dp(4)) }
        playerCard.addView(controls)
        controls.addView(controlButton("Prev") { previousSong() }, controlParams())
        playButton = controlButton("Play") { togglePlayback() }
        controls.addView(playButton, controlParams())
        controls.addView(controlButton("Next") { nextSong() }, controlParams())

        val modes = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        playerCard.addView(modes)
        dualButton = button("Single output") { toggleDualMode() }
        modes.addView(dualButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        modes.addView(button("Shuffle") { shuffle = !shuffle; updateModeButtons() }.apply {
            setTextSize(12f)
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        modes.addView(button("Repeat") { repeat = !repeat; updateModeButtons() }.apply {
            setTextSize(12f)
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })

        statusText = label("Scan your device to build the library.", 13f, 0xA9B6B1).apply {
            setPadding(0, dp(12), 0, 0)
        }
        playerCard.addView(statusText)

        page.addView(label("OUTPUTS", 12f, 0x91A59D, true).apply { setPadding(0, dp(26), 0, dp(5)) })
        page.addView(label("Select one output for normal mode, or two for best-effort dual mode.", 13f, 0xA9B6B1))
        outputList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(color(0x1A2420))
        }
        page.addView(outputList, topParams())

        page.addView(label("LIBRARY", 12f, 0x91A59D, true).apply { setPadding(0, dp(26), 0, dp(5)) })
        val libraryActions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        libraryActions.addView(button("Scan device") { requestAudioPermissionsOrScan() }, LinearLayout.LayoutParams(0, dp(50), 1f))
        libraryActions.addView(button("Import file") { chooseAudioFile() }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(8) })
        page.addView(libraryActions)
        libraryList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(0x1A2420))
        }
        page.addView(libraryList, topParams())
    }

    private fun requestAudioPermissionsOrScan() {
        val permissions = mutableListOf<String>()
        val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(audioPermission) != PackageManager.PERMISSION_GRANTED) permissions.add(audioPermission)
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), PERMISSIONS_REQUEST)
        else scanSongs()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST) {
            val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
            if (checkSelfPermission(audioPermission) == PackageManager.PERMISSION_GRANTED) scanSongs()
            else statusText.text = "Music permission is needed to scan songs."
            refreshOutputs()
        }
    }

    private fun scanSongs() {
        songs.clear()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME
        )
        runCatching {
            contentResolver.query(
                collection,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val displayColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val title = cursor.getString(titleColumn)?.takeIf { it.isNotBlank() }
                        ?: cursor.getString(displayColumn)?.substringBeforeLast('.')
                        ?: "Unknown song"
                    val artist = cursor.getString(artistColumn)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown artist"
                    songs.add(Song(ContentUris.withAppendedId(collection, cursor.getLong(idColumn)), title, artist, cursor.getLong(durationColumn)))
                }
            }
        }.onFailure {
            statusText.text = "Could not scan device audio. Check media permission and try again."
        }
        renderLibrary()
        if (songs.isEmpty()) statusText.text = "No music files found. Use Import file or add music to the device."
        else statusText.text = "${songs.size} song${if (songs.size == 1) "" else "s"} ready."
    }

    private fun renderLibrary() {
        if (!::libraryList.isInitialized) return
        libraryList.removeAllViews()
        songs.forEachIndexed { index, song ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                setOnClickListener { playSong(index) }
                setBackgroundColor(if (index == currentIndex) color(0x243831) else color(0x1A2420))
            }
            row.addView(label(song.title, 16f, 0xF3F7F5, true))
            row.addView(label("${song.artist}  ·  ${formatTime(song.durationMs)}", 12f, 0xA9B6B1))
            libraryList.addView(row)
        }
        if (songs.isEmpty()) libraryList.addView(label("Your songs will appear here after scanning.", 14f, 0xA9B6B1).apply {
            setPadding(dp(14), dp(16), dp(14), dp(16))
        })
    }

    private fun refreshOutputs() {
        if (!::outputList.isInitialized) return
        outputDevices.clear()
        outputDevices += audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            .distinctBy { it.id }
        selectedOutputIds.retainAll(outputDevices.map { it.id }.toSet())
        outputList.removeAllViews()
        outputDevices.forEach { device ->
            val check = CheckBox(this).apply {
                text = "${device.productName}  ·  ${deviceType(device.type)}"
                setTextColor(color(0xF3F7F5))
                buttonTintList = android.content.res.ColorStateList.valueOf(color(0x69E0C1))
                isChecked = selectedOutputIds.contains(device.id)
                setPadding(dp(4), dp(6), dp(4), dp(6))
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedOutputIds.add(device.id) else selectedOutputIds.remove(device.id)
                    if (selectedOutputIds.size > 2) selectedOutputIds.remove(selectedOutputIds.first())
                    if (currentSong != null && !isLoading) restartCurrentSong()
                }
            }
            outputList.addView(check)
        }
        val paired = pairedBluetoothNames()
        if (outputDevices.isEmpty() && paired.isEmpty()) {
            outputList.addView(label("No connected audio outputs detected.", 14f, 0xA9B6B1).apply {
                setPadding(dp(8), dp(14), dp(8), dp(14))
            })
        } else if (paired.isNotEmpty() && outputDevices.none { isBluetoothType(it.type) }) {
            outputList.addView(label("Paired Bluetooth devices (connect one in Android settings): ${paired.joinToString()}", 12f, 0x91A59D).apply {
                setPadding(dp(8), dp(8), dp(8), dp(8))
            })
        }
        statusText.text = if (dualMode && selectedOutputIds.size >= 2) "Dual mode ready · two outputs selected" else "${outputDevices.size} connected output${if (outputDevices.size == 1) "" else "s"} detected"
    }

    private fun pairedBluetoothNames(): List<String> = runCatching {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return emptyList()
        getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.map { it.name ?: "Bluetooth device" }?.sorted() ?: emptyList()
    }.getOrDefault(emptyList())

    private fun toggleDualMode() {
        dualMode = !dualMode
        updateModeButtons()
        if (dualMode && selectedOutputIds.size < 2) statusText.text = "Select two connected outputs to try dual mode."
        if (currentSong != null && !isLoading) restartCurrentSong()
    }

    private fun updateModeButtons() {
        dualButton.text = if (dualMode) "Dual mode ON" else "Single output"
        dualButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(if (dualMode) 0xD2F7A8 else 0x69E0C1)))
    }

    private fun playSong(index: Int) {
        if (index !in songs.indices) return
        currentIndex = index
        currentSong = songs[index]
        renderLibrary()
        startCurrentSong()
    }

    private fun restartCurrentSong() {
        val position = playerA?.currentPosition ?: 0
        startCurrentSong(position)
    }

    private fun startCurrentSong(startPosition: Int = 0) {
        val song = currentSong ?: return
        releasePlayers()
        val requestId = ++loadRequestId
        isLoading = true
        isPrepared = false
        preparedPlayers = 0
        completionHandled = false
        trackTitle.text = song.title
        trackArtist.text = song.artist
        playButton.text = "Loading…"
        playButton.isEnabled = false
        val selected = outputDevices.filter { selectedOutputIds.contains(it.id) }
        val useDual = dualMode && selected.size >= 2
        playersToPrepare = if (useDual) 2 else 1
        statusText.text = if (useDual) "Preparing dual output playback…" else "Preparing ${song.title}…"
        playerA = createPlayer(song, selected.getOrNull(0), true, requestId, startPosition)
        if (useDual) playerB = createPlayer(song, selected[1], false, requestId, startPosition)
    }

    private fun createPlayer(song: Song, device: AudioDeviceInfo?, primary: Boolean, requestId: Int, startPosition: Int): MediaPlayer {
        val mediaPlayer = MediaPlayer()
        try {
            mediaPlayer.setDataSource(this, song.uri)
            device?.let { mediaPlayer.setPreferredDevice(it) }
            mediaPlayer.setVolume(0.82f, 0.82f)
            mediaPlayer.setOnPreparedListener {
                if (requestId != loadRequestId) return@setOnPreparedListener
                if (startPosition > 0) mediaPlayer.seekTo(startPosition)
                preparedPlayers++
                if (preparedPlayers >= playersToPrepare) {
                    isLoading = false
                    isPrepared = true
                    playButton.isEnabled = true
                    playButton.text = "Pause"
                    startPlayers()
                    statusText.text = if (playersToPrepare == 2) "Playing in best-effort dual mode" else routeStatus()
                }
            }
            mediaPlayer.setOnCompletionListener {
                if (primary) handleCompletion()
            }
            mediaPlayer.setOnErrorListener { _, _, _ ->
                if (requestId == loadRequestId) handlePlayerError()
                true
            }
            mediaPlayer.prepareAsync()
        } catch (_: Exception) {
            if (requestId == loadRequestId) handlePlayerError()
        }
        return mediaPlayer
    }

    private fun startPlayers() {
        playerA?.start()
        playerB?.start()
    }

    private fun togglePlayback() {
        if (isLoading) {
            Toast.makeText(this, "Audio is still loading", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isPrepared || playerA == null) {
            if (currentSong == null && songs.isNotEmpty()) playSong(0)
            else Toast.makeText(this, "Choose a song first", Toast.LENGTH_SHORT).show()
            return
        }
        if (playerA?.isPlaying == true) {
            playerA?.pause()
            playerB?.pause()
            playButton.text = "Play"
        } else {
            startPlayers()
            playButton.text = "Pause"
        }
    }

    private fun previousSong() {
        if (songs.isEmpty()) return
        val index = if (currentIndex <= 0) songs.lastIndex else currentIndex - 1
        playSong(index)
    }

    private fun nextSong() {
        if (songs.isEmpty()) return
        val index = if (shuffle) songs.indices.random() else if (currentIndex >= songs.lastIndex) 0 else currentIndex + 1
        playSong(index)
    }

    private fun handleCompletion() {
        if (completionHandled) return
        completionHandled = true
        if (repeat) {
            playerA?.seekTo(0)
            playerB?.seekTo(0)
            startPlayers()
            completionHandled = false
        } else nextSong()
    }

    private fun handlePlayerError() {
        releasePlayers()
        isLoading = false
        isPrepared = false
        playButton.isEnabled = true
        playButton.text = "Play"
        statusText.text = "Could not play this file. Try another audio file."
    }

    private fun seekTo(position: Int) {
        if (!isPrepared) return
        playerA?.seekTo(position)
        playerB?.seekTo(position)
    }

    private fun chooseAudioFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }, IMPORT_REQUEST)
    }

    @Deprecated("The system document picker remains supported for this prototype")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IMPORT_REQUEST && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                val imported = Song(uri, displayName(uri), "Imported file", 0)
                songs.add(imported)
                renderLibrary()
                playSong(songs.lastIndex)
            }
        }
    }

    private fun displayName(uri: Uri): String {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor: Cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        return name?.takeIf { it.isNotBlank() } ?: uri.path?.substringAfterLast('/') ?: "Imported audio"
    }

    private fun releasePlayers() {
        loadRequestId++
        playerA?.runCatching { stop() }
        playerB?.runCatching { stop() }
        playerA?.release()
        playerB?.release()
        playerA = null
        playerB = null
        isPrepared = false
        isLoading = false
    }

    private fun routeStatus() = if (selectedOutputIds.isEmpty()) "Playing through Android's default output" else "Playing through selected output"

    private fun isBluetoothType(type: Int) = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER

    private fun deviceType(type: Int): String = when {
        type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
        type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB audio"
        isBluetoothType(type) -> "Bluetooth audio"
        type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        type == AudioDeviceInfo.TYPE_HDMI || type == AudioDeviceInfo.TYPE_HDMI_ARC || type == AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI audio"
        else -> "Audio output"
    }

    private fun formatTime(milliseconds: Int): String = formatTime(milliseconds.toLong())
    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(16))
        setBackgroundColor(color(0x1A2420))
    }

    private fun controlButton(text: String, action: () -> Unit) = button(text, action).apply { setTextSize(12f) }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        minWidth = 0
        minHeight = 0
        setTextColor(color(0x101614))
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(0x69E0C1)))
        setOnClickListener { action() }
    }

    private fun label(text: String, size: Float, colorHex: Long, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color(colorHex))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun topParams() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
    private fun controlParams() = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4); marginEnd = dp(4) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun color(value: Long) = android.graphics.Color.rgb(((value shr 16) and 0xFF).toInt(), ((value shr 8) and 0xFF).toInt(), (value and 0xFF).toInt())

    override fun onDestroy() {
        mainHandler.removeCallbacks(progressTicker)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        releasePlayers()
        super.onDestroy()
    }

    companion object {
        private const val PERMISSIONS_REQUEST = 2001
        private const val IMPORT_REQUEST = 2002
    }
}
