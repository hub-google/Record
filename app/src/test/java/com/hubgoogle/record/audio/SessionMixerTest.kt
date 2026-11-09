package com.hubgoogle.record.audio

import com.hubgoogle.record.data.RecordingDetails
import com.hubgoogle.record.data.RecordingFile
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class SessionMixerTest {
    private fun create(folder: File, name: String, source: String, startNs: Long,
                       level: Int): RecordingFile {
        val file = File(folder, name)
        WavPcmFile(file, 16000, 1).use { out ->
            val pcm = ByteArray(32000)
            for (n in pcm.indices step 2) {
                pcm[n] = level.toByte()
                pcm[n + 1] = (level shr 8).toByte()
            }
            out.append(pcm)
            out.commit()
        }
        return RecordingFile(file, System.currentTimeMillis(), file.length(),
            RecordingDetails(source, "RECORDED_UNVERIFIED", 1000, 16000, 1, 100.0,
                level, 0.0, "a".repeat(64), startNs))
    }
    private fun read(file: File, frame: Int): Int =
        RandomAccessFile(file, "r").use {
            it.seek(44 + frame.toLong() * 4)
            java.lang.Short.reverseBytes(it.readShort()).toInt()
        }

    @Test fun sumsThreeSourcesOnlyWhenTheirTimesOverlap() {
        val dir = Files.createTempDirectory("session-mix").toFile()
        try {
            val call = create(dir,"call.wav","CALL_MIX",10_000_000_000L,9000)
            val media = create(dir,"media.wav","MEDIA_PLAYBACK",10_200_000_000L,18000)
            val mic = create(dir,"mic.wav","MIC",10_400_000_000L,3000)
            val plan = SessionBundleExporter.plan(call,listOf(call,media,mic))
            val mix = SessionMixer.mix(plan)
            assertEquals(9000,read(mix,4800))   // 100 ms: call only
            assertEquals(13500,read(mix,14400)) // 300 ms: call + media
            assertEquals(10000,read(mix,24000)) // 500 ms: all three
            assertEquals(10500,read(mix,52800)) // 1100 ms: media + mic
        } finally { dir.deleteRecursively() }
    }
}
