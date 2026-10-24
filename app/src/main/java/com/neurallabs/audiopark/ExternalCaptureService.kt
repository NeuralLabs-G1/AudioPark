package com.neurallabs.audiopark

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder

class ExternalCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private val tracks = mutableListOf<AudioTrack>()
    private var captureThread: Thread? = null
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY
        startForegroundWithNotification()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val deviceIds = intent?.getIntArrayExtra(EXTRA_DEVICE_IDS) ?: intArrayOf()
        if (resultCode < 0 || resultData == null) {
            reportStatus("Capture permission was not received")
            stopSelf()
            return START_NOT_STICKY
        }
        startCapture(resultCode, resultData, deviceIds)
        return START_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent, deviceIds: IntArray) {
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    reportStatus("Android stopped external audio capture")
                    stopCapture()
                    stopSelf()
                }
            }, null)

            val sampleRate = 48_000
            val channelMask = AudioFormat.CHANNEL_IN_STEREO
            val playbackChannelMask = AudioFormat.CHANNEL_OUT_STEREO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()
            val recordBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding).coerceAtLeast(sampleRate)
            recorder = AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build())
                .setBufferSizeInBytes(recordBuffer * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            val trackBuffer = AudioTrack.getMinBufferSize(sampleRate, playbackChannelMask, encoding).coerceAtLeast(sampleRate)
            val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            val devices = deviceIds.toList().mapNotNull { id ->
                audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
            }
            val trackCount = if (devices.isEmpty()) 1 else devices.size.coerceAtMost(2)
            repeat(trackCount) { index ->
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(playbackChannelMask)
                        .build())
                    .setBufferSizeInBytes(trackBuffer * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                if (devices.isNotEmpty()) track.setPreferredDevice(devices[index])
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    track.release()
                    throw IllegalStateException("Android could not initialize an output track")
                }
                tracks += track
            }

            recorder?.startRecording()
            tracks.forEach { it.play() }
            running = true
            reportStatus(if (tracks.size > 1) "Capturing external audio to ${tracks.size} outputs" else "Capturing external audio")
            captureThread = Thread({ captureLoop() }, "AudioParkExternalCapture").also { it.start() }
        } catch (error: Exception) {
            reportStatus("External capture unavailable: ${error.message ?: "Android rejected the capture"}")
            stopCapture()
            stopSelf()
        }
    }

    private fun captureLoop() {
        val buffer = ShortArray(4_800)
        while (running) {
            val count = recorder?.read(buffer, 0, buffer.size) ?: -1
            if (count > 0) {
                tracks.toList().forEach { it.write(buffer, 0, count) }
            } else if (count < 0) {
                reportStatus("External audio capture returned no audio")
                running = false
            }
        }
    }

    private fun stopCapture() {
        running = false
        recorder?.runCatching { stop() }
        recorder?.release()
        recorder = null
        tracks.forEach {
            it.runCatching { pause() }
            it.release()
        }
        tracks.clear()
        val activeProjection = projection
        projection = null
        activeProjection?.stop()
        captureThread = null
    }

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "External audio capture", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AudioPark external audio")
            .setContentText("Capturing another app's permitted audio")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun reportStatus(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.neurallabs.audiopark.START_EXTERNAL_CAPTURE"
        const val ACTION_STOP = "com.neurallabs.audiopark.STOP_EXTERNAL_CAPTURE"
        const val ACTION_STATUS = "com.neurallabs.audiopark.EXTERNAL_CAPTURE_STATUS"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_DEVICE_IDS = "device_ids"
        const val EXTRA_STATUS = "status"
        private const val CHANNEL_ID = "audiopark_external_capture"
        private const val NOTIFICATION_ID = 4101
    }
}
