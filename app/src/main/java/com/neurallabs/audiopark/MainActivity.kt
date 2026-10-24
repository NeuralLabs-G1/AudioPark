package com.neurallabs.audiopark

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var audioManager: AudioManager
    private lateinit var deviceList: LinearLayout
    private lateinit var trackTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var playButton: Button
    private var player: MediaPlayer? = null
    private var selectedUri: Uri? = null
    private var selectedDeviceId: Int? = null
    private var selectedDeviceName: String? = null
    private var isPrepared = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshDevices()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (selectedDeviceId != null && audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .none { it.id == selectedDeviceId }) {
                selectedDeviceId = null
                selectedDeviceName = null
                player?.setPreferredDevice(null)
            }
            refreshDevices()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = color(0x101614)
        window.navigationBarColor = color(0x101614)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        buildScreen()
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        refreshDevices()
    }

    private fun buildScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(18))
            setBackgroundColor(color(0x101614))
        }
        val scroll = ScrollView(this).apply { addView(page) }
        setContentView(scroll)

        page.addView(label("AUDIOPARK", 13f, 0x69E0C1, true))
        page.addView(label("One player.\nYour outputs.", 34f, 0xF3F7F5, true).apply {
            setPadding(0, dp(12), 0, dp(8))
        })
        page.addView(label("A practical first check of what this phone can route.", 15f, 0xA9B6B1))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(color(0x1A2420))
        }
        val cardParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(28) }
        page.addView(card, cardParams)
        card.addView(label("NOW PLAYING", 12f, 0x91A59D, true))
        trackTitle = label("Choose an audio file", 19f, 0xF3F7F5, true).apply {
            setPadding(0, dp(14), 0, dp(16))
        }
        card.addView(trackTitle)
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        card.addView(actions)
        actions.addView(button("Choose audio") { chooseAudio() }, weightParams())
        playButton = button("Play") { togglePlayback() }
        actions.addView(playButton, weightParams().apply { leftMargin = dp(10) })
        val volumeLabel = label("PLAYER VOLUME", 12f, 0x91A59D, true).apply {
            setPadding(0, dp(20), 0, dp(4))
        }
        card.addView(volumeLabel)
        card.addView(SeekBar(this).apply {
            max = 100
            progress = 80
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) player?.setVolume(progress / 100f, progress / 100f)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })

        page.addView(label("CONNECTED OUTPUTS", 12f, 0x91A59D, true).apply {
            setPadding(0, dp(28), 0, dp(4))
        })
        statusText = label("Looking for audio devices…", 14f, 0xA9B6B1).apply {
            setPadding(0, dp(5), 0, dp(10))
        }
        page.addView(statusText)
        deviceList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setBackgroundColor(color(0x1A2420))
        }
        page.addView(deviceList, LinearLayout.LayoutParams(-1, -2))
        page.addView(label(
            "This prototype can select one preferred output. Android does not give ordinary apps a universal way to duplicate playback to wired headphones and TWS together. The phone may still support sharing through its own system feature.",
            13f, 0xA9B6B1
        ).apply { setPadding(0, dp(16), 0, 0) })
        page.addView(button("Refresh devices") { refreshDevices() }.apply {
            setPadding(0, dp(14), 0, 0)
        })
    }

    private fun loadAudio(uri: Uri) {
        releasePlayer()
        selectedUri = uri
        isPrepared = false
        val newPlayer = MediaPlayer()
        player = newPlayer
        try {
            newPlayer.setDataSource(this, uri)
            newPlayer.setVolume(0.8f, 0.8f)
            selectedDeviceId?.let { id ->
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.id == id }?.let { newPlayer.setPreferredDevice(it) }
            }
            newPlayer.setOnPreparedListener {
                isPrepared = true
                trackTitle.text = displayName(uri)
                playButton.text = "Play"
                statusText.text = routeMessage()
            }
            newPlayer.setOnCompletionListener {
                playButton.text = "Play"
            }
            newPlayer.setOnErrorListener { _, _, _ ->
                isPrepared = false
                playButton.text = "Play"
                statusText.text = "Could not play this file. Try another audio file."
                true
            }
            newPlayer.prepareAsync()
            trackTitle.text = "Loading audio…"
            statusText.text = "Preparing the selected file."
        } catch (error: Exception) {
            releasePlayer()
            Toast.makeText(this, "Could not open this audio file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun togglePlayback() {
        val activePlayer = player
        if (activePlayer == null || !isPrepared) {
            if (selectedUri == null) chooseAudio()
            else Toast.makeText(this, "Audio is still loading", Toast.LENGTH_SHORT).show()
            return
        }
        if (activePlayer.isPlaying) {
            activePlayer.pause()
            playButton.text = "Play"
        } else {
            activePlayer.start()
            playButton.text = "Pause"
            statusText.text = routeMessage()
        }
    }

    private fun chooseAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        startActivityForResult(intent, REQUEST_AUDIO)
    }

    @Deprecated("The system document picker remains supported for this prototype")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_AUDIO && resultCode == RESULT_OK) {
            data?.data?.let(::loadAudio)
        }
    }

    private fun refreshDevices() {
        if (!::deviceList.isInitialized) return
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        deviceList.removeAllViews()
        val buttonGroup = android.widget.RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        deviceList.addView(buttonGroup)
        outputs.forEach { device ->
            val radio = RadioButton(this).apply {
                text = "${device.productName}  ·  ${deviceType(device.type)}"
                setTextColor(color(0xF3F7F5))
                buttonTintList = android.content.res.ColorStateList.valueOf(color(0x69E0C1))
                isChecked = selectedDeviceId == device.id
                setPadding(dp(4), dp(6), dp(4), dp(6))
                setOnClickListener { chooseDevice(device) }
            }
            buttonGroup.addView(radio)
        }
        if (outputs.isEmpty()) {
            deviceList.addView(label("No external audio outputs detected.", 14f, 0xA9B6B1).apply {
                setPadding(dp(8), dp(14), dp(8), dp(14))
            })
        }
        statusText.text = if (selectedDeviceName == null) {
            "${outputs.size} output${if (outputs.size == 1) "" else "s"} detected · Android default route"
        } else routeMessage()
    }

    private fun chooseDevice(device: AudioDeviceInfo) {
        val accepted = player?.setPreferredDevice(device) ?: true
        if (!accepted) {
            Toast.makeText(this, "Android did not accept this output", Toast.LENGTH_SHORT).show()
            refreshDevices()
            return
        }
        selectedDeviceId = device.id
        selectedDeviceName = device.productName.toString()
        refreshDevices()
        statusText.text = routeMessage()
    }

    private fun routeMessage() = "Preferred output: ${selectedDeviceName ?: "Android default"}. This is one route, not simultaneous playback."

    private fun displayName(uri: Uri): String = uri.lastPathSegment?.substringAfterLast('/')
        ?.substringAfterLast(':')?.takeIf { it.isNotBlank() } ?: "Selected audio file"

    private fun deviceType(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth audio"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI audio"
        else -> "Audio output"
    }

    private fun releasePlayer() {
        player?.runCatching { stop() }
        player?.release()
        player = null
        isPrepared = false
    }

    override fun onDestroy() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        releasePlayer()
        super.onDestroy()
    }

    private fun label(text: String, size: Float, colorHex: Long, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color(colorHex))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(color(0x101614))
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(color(0x69E0C1)))
        setOnClickListener { action() }
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, dp(52), 1f)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun color(value: Long) = android.graphics.Color.rgb(
        ((value shr 16) and 0xFF).toInt(), ((value shr 8) and 0xFF).toInt(), (value and 0xFF).toInt()
    )

    companion object {
        private const val REQUEST_AUDIO = 1001
    }
}
