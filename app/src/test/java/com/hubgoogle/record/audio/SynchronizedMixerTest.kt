package com.hubgoogle.record.audio

import com.hubgoogle.record.data.RecordingDetails
import com.hubgoogle.record.data.RecordingFile
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class SynchronizedMixerTest {
    private fun create(dir: File, name: String, rate: Int, channels: Int, value: Int): File {
        val file = File(dir, name)
        val frame = byteArrayOf(value.toByte(), (value shr 8).toByte())
        WavPcmFile(file, rate, channels).use { wav ->
            val bytes = ByteArray(rate / 5 * channels * 2) // 200ms per input.
            for (i in bytes.indices step 2) {
                bytes[i] = frame[0]; bytes[i + 1] = frame[1]
            }
            wav.append(bytes); wav.commit()
        }
        return file
    }
    private fun item(file: File, source: String, start: Long, rate: Int, channels: Int) =
        RecordingFile(file, System.currentTimeMillis(), file.length(),
            RecordingDetails(source, "RECORDED_UNVERIFIED", 200, rate, channels,
                100.0, 10000, 0.0, "", start))
    private fun pcm(file: File, frame: Int, channel: Int): Int =
        java.io.RandomAccessFile(file, "r").use {
            it.seek(44 + frame.toLong() * 4 + channel * 2)
            java.lang.Short.reverseBytes(it.readShort()).toInt()
        }

    @Test fun monoCallAndStereoMediaAreMixedUsingClockOffsets() {
        val dir = Files.createTempDirectory("mixer-test").toFile()
        try {
            val call = create(dir, "call.wav", 16000, 1, 10000)
            val media = create(dir, "media.wav", 48000, 2, 20000)
            // Media starts 100 ms after call.
            val pair = MultiTrackExporter.TrackPair(
                item(call, "CALL_MIX", 10_000_000_000, 16000, 1),
                item(media, "MEDIA_PLAYBACK", 10_100_000_000, 48000, 2),
                100)
            val mixed = SynchronizedMixer.mix(pair)
            assertTrue(mixed.length() > 44)
            val header = mixed.readBytes().copyOfRange(0,44)
            val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", String(header, 0, 4))
            assertEquals(48000, h.getInt(24))
            assertEquals(2, h.getShort(22).toInt())
            assertEquals(5000, pcm(mixed, 500, 0)) // Only call at 10ms
            assertEquals(15000, pcm(mixed, 7200, 0)) // Both at 150ms
            assertEquals(10000, pcm(mixed, 12000, 0)) // Only media at 250ms
        } finally { dir.deleteRecursively() }
    }

    @Test fun encryptedSourcesCannotBeMixed() {
        val dir = Files.createTempDirectory("mixer-reject").toFile()
        try {
            val call = create(dir,"call.wav",16000,1,1000)
            val media = create(dir,"media.wav",48000,2,1000)
            val pair = MultiTrackExporter.TrackPair(
                item(call,"CALL_MIX",1_000_000_000,16000,1),
                item(media,"MEDIA_PLAYBACK",1_100_000_000,48000,2),100)
            val encrypted = File(dir,"call.wav.enc")
            call.copyTo(encrypted)
            val encryptedPair = pair.copy(call=pair.call.copy(file=encrypted))
            assertThrows(IllegalArgumentException::class.java) {
                SynchronizedMixer.mix(encryptedPair)
            }
        } finally { dir.deleteRecursively() }
    }
}
