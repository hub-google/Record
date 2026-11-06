package com.hubgoogle.record.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.hubgoogle.record.core.TestSignal
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit user-started, 8-second 440/880 Hz sine test fixture.
 * This plays through normal USAGE_MEDIA, permitting a legitimate
 * MediaProjection capture test when the OS and capture policy allow it.
 */
object TestTonePlayer {
    private val running = AtomicBoolean(false)
    @Volatile var lastResult = "尚未播放測試訊號"
        private set
    fun play(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        Thread({
            var track: AudioTrack? = null
            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                    .build()
                val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(TestSignal.RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                val bufferSize = maxOf(AudioTrack.getMinBufferSize(TestSignal.RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), 8192)
                check(bufferSize > 0) { "Unsupported audio output format" }
                track = AudioTrack.Builder().setAudioAttributes(attrs)
                    .setAudioFormat(format).setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                check(track.state == AudioTrack.STATE_INITIALIZED)
                track.play()
                val buffer = ByteArray(4096)
                var frame = 0L
                val total = TestSignal.TOTAL_SECONDS.toLong() * TestSignal.RATE
                while (running.get() && frame < total) {
                    val frames = minOf(total - frame, buffer.size / 2L).toInt()
                    for (i in 0 until frames) {
                        val value = TestSignal.sample(frame + i).toInt()
                        buffer[2*i] = value.toByte()
                        buffer[2*i+1] = (value shr 8).toByte()
                    }
                    var offset = 0
                    val length = frames * 2
                    while (offset < length && running.get()) {
                        val n = track.write(buffer, offset, length - offset, AudioTrack.WRITE_BLOCKING)
                        check(n > 0) { "AudioTrack output rejected sample" }
                        offset += n
                    }
                    frame += frames
                }
                lastResult = if (frame >= total) "已完成 8 秒受控測試聲" else "已停止測試聲"
            } catch (error: Exception) {
                lastResult = "播放測試聲失敗：" + error.message
            } finally {
                runCatching { track?.stop() }
                track?.release()
                running.set(false)
            }
        }, "record-test-signal").start()
        return true
    }
    fun stop() { running.set(false) }
}

