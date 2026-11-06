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
        @Volatile var liveLevel = 0
            private set
        @Volatile var startedElapsedMs = 0L
            private set
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
            liveLevel = 0
            startedElapsedMs = android.os.SystemClock.elapsedRealtime()
            lastStatus = "正在擷取允許錄製的音樂／遊戲聲音"

            worker = Thread({
                val recordings = Recordings(this)
                val index = RecordingDatabase(this)
                var file = recordings.newFile("media")
                var startedNs = SystemClock.elapsedRealtimeNanos()
                var writer: WavPcmFile? = null
                var quality = QualityAccumulator()
                var digest = MessageDigest.getInstance("SHA-256")
                var segmentsSaved = 0
                var silentSegments = 0
                var interruptedByStorage = false
                try {
                    fun finish() {
                        val wav = writer ?: return
                        if (wav.dataBytes == 0L) {
                            wav.close()
                            file.delete()
                            writer = null
                            return
                        }
                        wav.commit()
                        writer = null
                        val result = quality.result()
                        index.save(file, "MEDIA_PLAYBACK", sampleRate, channels,
                            result, digest.digest().joinToString("") {
                                "%02x".format(it.toInt() and 255)
                            }, startedNs)
                        segmentsSaved++
                        if (result.isLikelySilent) silentSegments++
                    }
                    writer = WavPcmFile(file, sampleRate, channels)
                    val buffer = ByteArray(32768)
                    while (running.get()) {
                        val n = source.read(buffer, 0, buffer.size)
                        if (n < 0) {
                            // A stop may legitimately interrupt read.
                            if (running.get()) throw IllegalStateException("AudioRecord read error: " + n)
                            break
                        }
                        if (n == 0) continue
                        if (filesDir.usableSpace < 16L * 1024 * 1024) {
                            interruptedByStorage = true
                            break
                        }
                        val activeWriter = checkNotNull(writer)
                        if (activeWriter.dataBytes + n > 32L * 1024 * 1024) {
                            finish()
                            file = recordings.newFile("media")
                            startedNs = SystemClock.elapsedRealtimeNanos()
                            writer = WavPcmFile(file, sampleRate, channels)
                            quality = QualityAccumulator()
                            digest = MessageDigest.getInstance("SHA-256")
                        }
                        checkNotNull(writer).append(buffer, 0, n)
                        quality.add(buffer, n)
                        digest.update(buffer, 0, n)
                        var power = 0.0
                        var cursor = 0
                        while (cursor + 1 < n) {
                            val pcm = ((buffer[cursor + 1].toInt() shl 8) or
                                (buffer[cursor].toInt() and 255)).toShort().toInt()
                            power += pcm.toDouble() * pcm
                            cursor += 2
                        }
                        val rms = kotlin.math.sqrt(power / maxOf(1, cursor / 2))
                        liveLevel = if (rms < 1) 0 else
                            ((20 * kotlin.math.log10(rms / 32768.0) + 60) / 0.6)
                                .toInt().coerceIn(0, 100)
                    }
                    finish()
                    lastStatus = when {
                        interruptedByStorage -> "儲存空間過低，已安全保存 " + segmentsSaved + " 個片段"
                        segmentsSaved == 0 -> "媒體來源未提供可錄製音訊"
                        silentSegments == segmentsSaved -> "已保存 " + segmentsSaved + " 段音訊，但可能全部是靜音"
                        silentSegments > 0 -> "已保存 " + segmentsSaved + " 段；其中 " + silentSegments + " 段可能靜音"
                        else -> "媒體錄製已保存 " + segmentsSaved + " 個完整 WAV 片段"
                    }
                } catch (error: Exception) {
                    Log.e("RecordCapture", "Media recording failed", error)
                    lastStatus = "媒體錄製失敗；已保留 " + segmentsSaved +
                        " 個先前完整片段：" + (error.message ?: error.javaClass.simpleName)
                } finally {
                    writer?.close()
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
        liveLevel = 0
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

