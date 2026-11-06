package com.hubgoogle.record.media

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.hubgoogle.record.audio.WavPcmFile
import com.hubgoogle.record.data.Recordings
import com.hubgoogle.record.data.RecordingDatabase
import com.hubgoogle.record.core.QualityAccumulator
import android.os.SystemClock
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** User-authorized media/game playback capture; never reads protected voice communications. */
class PlaybackCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.hubgoogle.record.PLAYBACK_START"
        const val ACTION_STOP = "com.hubgoogle.record.PLAYBACK_STOP"
        const val EXTRA_DATA = "projection_data"
        const val EXTRA_CODE = "projection_code"
        @Volatile var active = false
            private set
        @Volatile var lastStatus = "尚未錄製媒體聲音"
            private set
    }
    private val running = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var projection: MediaProjection? = null
    private var worker: Thread? = null
    private val sampleRate = 48_000
    private val channels = 2

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || running.get()) return START_NOT_STICKY
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            lastStatus = "麥克風權限尚未授予（MediaProjection 音訊擷取也需要此權限）"
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent.getIntExtra(EXTRA_CODE, android.app.Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            lastStatus = "使用者未授權 Android 系統播放音訊擷取"
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        val notification = Notification.Builder(this, "record.capture")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Record 正在錄製媒體聲音")
            .setContentText("由 Android 系統授權；點回 App 可停止")
            .setOngoing(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(54, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else startForeground(54, notification)
            // Android 14+: register callback before capture, and use the projection token only once.
            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = manager.getMediaProjection(resultCode, data)
            val proj = checkNotNull(projection)
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopCapture(); stopSelf() }
            }, Handler(Looper.getMainLooper()))
            val config = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0) { "Unsupported PCM format" }
            recorder = AudioRecord.Builder().setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min * 2, 16384))
                .setAudioPlaybackCaptureConfig(config).build()
            val source = checkNotNull(recorder)
            check(source.state == AudioRecord.STATE_INITIALIZED)
            source.startRecording()
            check(source.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            running.set(true); active = true
            lastStatus = "正在擷取允許錄製的音樂／遊戲聲音"
            val file = Recordings(this).newFile("media")
            val startNs = SystemClock.elapsedRealtimeNanos()
            worker = Thread({
                val quality = QualityAccumulator()
                val digest = MessageDigest.getInstance("SHA-256")
                try {
                    WavPcmFile(file, sampleRate, channels).use { wav ->
                        val buffer = ByteArray(32768)
                        while (running.get()) {
                            val n = source.read(buffer, 0, buffer.size)
                            if (n > 0) {
                                wav.append(buffer, 0, n)
                                quality.add(buffer, n)
                                digest.update(buffer, 0, n)
                            }
                            else if (n < 0) throw IllegalStateException("AudioRecord error: " + n)
                        }
                        wav.commit()
                    }
                    val stats = quality.result()
                    if (file.length() > 44) {
                        RecordingDatabase(this).save(file, "MEDIA_PLAYBACK", sampleRate, channels,
                            stats, digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) },
                            startNs)
                    } else file.delete()
                    lastStatus = when {
                        file.length() <= 44 -> "媒體來源未提供可錄製音訊"
                        stats.isLikelySilent -> "媒體錄音已儲存但可能是靜音：" + file.name
                        else -> "媒體錄音已儲存：" + file.name
                    }
                } catch (e: Exception) {
                    Log.e("RecordCapture", "Media playback capture failed", e)
                    lastStatus = "媒體錄音失敗：" + (e.message ?: e.javaClass.simpleName)
                } finally {
                    active = false
                    stopSelf()
                }
            }, "record-media-wav").also { it.start() }
        } catch (e: Exception) {
            Log.e("RecordCapture", "Cannot start", e)
            lastStatus = "媒體擷取無法啟動：" + (e.message ?: e.javaClass.simpleName)
            stopCapture(); stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("record.capture", "媒體錄音", NotificationManager.IMPORTANCE_LOW)
        )
    }
    private fun stopCapture() {
        running.set(false)
        active = false
        runCatching { recorder?.stop() }
    }
    override fun onDestroy() {
        stopCapture()
        worker?.let { if (it != Thread.currentThread()) runCatching { it.join(2500) } }
        runCatching { recorder?.release() }; recorder = null
        val p = projection; projection = null
        runCatching { p?.stop() }
        super.onDestroy()
    }
}

