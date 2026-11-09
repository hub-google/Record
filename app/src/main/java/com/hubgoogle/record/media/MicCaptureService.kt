package com.hubgoogle.record.media

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.hubgoogle.record.audio.WavPcmFile
import com.hubgoogle.record.core.QualityAccumulator
import com.hubgoogle.record.core.RecordingAlert
import com.hubgoogle.record.data.RecordingDatabase
import com.hubgoogle.record.data.Recordings
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Separate opt-in microphone experiment: the OS may mute MIC while a
 * cellular or VoIP call has higher capture priority. This does not pretend
 * to record both call parties, nor run without an active foreground notice.
 */
class MicCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.hubgoogle.record.MIC_START"
        const val ACTION_STOP = "com.hubgoogle.record.MIC_STOP"
        @Volatile var active = false
            private set
        @Volatile var level = 0
            private set
        @Volatile var startedElapsedMs = 0L
            private set
        @Volatile var status = "麥克風音軌未啟用"
            private set
    }
    private val running = AtomicBoolean(false)
    private var input: AudioRecord? = null
    private var worker: Thread? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running.set(false)
            runCatching { input?.stop() }
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || running.get()) return START_NOT_STICKY
        if (!getSharedPreferences("record", MODE_PRIVATE).getBoolean("consent", false) ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "未取得麥克風權限或錄音合規確認"
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("record.microphone", "獨立麥克風音軌", NotificationManager.IMPORTANCE_LOW)
            )
            val notification = Notification.Builder(this, "record.microphone")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Record 正在錄製獨立麥克風音軌")
                .setContentText("只作使用者啟動的實驗，通話期間可能被 Android 靜音")
                .setOngoing(true).build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(59, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(59, notification)
            }
            val rate = 48_000
            val channels = 1
            val buffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(buffer > 0) { "This device does not support MIC PCM16 at 48kHz" }
            input = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(buffer * 2, 16384)).build()
            val mic = checkNotNull(input)
            check(mic.state == AudioRecord.STATE_INITIALIZED) { "MIC could not be initialized" }
            mic.startRecording()
            check(mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "MIC access denied" }
            running.set(true)
            active = true
            level = 0
            startedElapsedMs = SystemClock.elapsedRealtime()
            status = "獨立麥克風音軌錄製中；實際音量須檢查"
            worker = Thread({
                val library = Recordings(this)
                val index = RecordingDatabase(this)
                var file = library.newFile("mic")
                var startNs = SystemClock.elapsedRealtimeNanos()
                var writer: WavPcmFile? = null
                var quality = QualityAccumulator()
                var digest = MessageDigest.getInstance("SHA-256")
                var saved = 0
                var silent = 0
                var lowSpace = false
                try {
                    fun finish() {
                        val out = writer ?: return
                        if (out.dataBytes == 0L) {
                            out.close()
                            writer = null
                            return
                        }
                        out.commit()
                        writer = null
                        val result = quality.result()
                        index.save(file, "MIC", rate, channels, result,
                            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) },
                            startNs)
                        saved++
                        if (result.isLikelySilent) silent++
                    }
                    writer = WavPcmFile(file, rate, channels)
                    val pcm = ByteArray(32768)
                    while (running.get()) {
                        val n = mic.read(pcm, 0, pcm.size)
                        if (n < 0) {
                            if (running.get()) error("MIC read error: " + n)
                            break
                        }
                        if (n == 0) continue
                        if (filesDir.usableSpace < 16L * 1024 * 1024) {
                            lowSpace = true
                            break
                        }
                        if (checkNotNull(writer).dataBytes + n > 32L * 1024 * 1024) {
                            finish()
                            file = library.newFile("mic")
                            startNs = SystemClock.elapsedRealtimeNanos()
                            writer = WavPcmFile(file, rate, channels)
                            quality = QualityAccumulator()
                            digest = MessageDigest.getInstance("SHA-256")
                        }
                        checkNotNull(writer).append(pcm, 0, n)
                        quality.add(pcm, n)
                        digest.update(pcm, 0, n)
                        var sq = 0.0
                        var i = 0
                        while (i + 1 < n) {
                            val sample = ((pcm[i+1].toInt() shl 8) or
                                (pcm[i].toInt() and 255)).toShort().toDouble()
                            sq += sample * sample
                            i += 2
                        }
                        val rms = sqrt(sq / maxOf(1, i/2))
                        level = if (rms < 1.0) 0 else
                            ((20.0 * log10(rms / 32768.0) + 60) / 0.6).toInt().coerceIn(0, 100)
                    }
                    finish()
                    status = when {
                        lowSpace -> "磁碟空間不足，已保存 $saved 個完整麥克風音軌片段"
                        saved == 0 -> "麥克風沒有輸出有效 PCM 音訊"
                        silent == saved -> "麥克風已保存 $saved 段，但全部可能靜音"
                        silent > 0 -> "麥克風已保存 $saved 段，其中 $silent 段可能靜音"
                        else -> "獨立麥克風錄音已保存 $saved 段，仍須回放"
                    }
                    if (lowSpace || saved == 0 || silent > 0) RecordingAlert.show(this, "麥克風錄音需檢查", status)
                } catch (e: Exception) {
                    status = "麥克風錄音中斷，先前已提交 $saved 段：" + e.message
                    RecordingAlert.show(this, "麥克風錄音異常", status)
                } finally {
                    runCatching { writer?.close() }
                    running.set(false)
                    active = false
                    level = 0
                    stopSelf()
                }
            }, "record-mic-pcm").also { it.start() }
        } catch (e: Exception) {
            status = "無法啟動麥克風音軌：" + e.message
            RecordingAlert.show(this, "麥克風錄音無法開始", status)
            running.set(false)
            active = false
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        running.set(false)
        runCatching { input?.stop() }
        worker?.let { if (it != Thread.currentThread()) runCatching { it.join(4000) } }
        runCatching { input?.release() }
        input = null
        super.onDestroy()
    }
}
