package com.hubgoogle.record.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WavWaveformTest {
    @Test fun peaksReflectActualWaveform() {
        val dir = Files.createTempDirectory("record-wave").toFile()
        val file = File(dir, "signal.wav")
        WavPcmFile(file, 16000, 1).use { writer ->
            val pcm = ByteArray(320)
            for (i in pcm.indices step 2) {
                val sample = if (i < 160) 0 else 16000
                pcm[i] = sample.toByte()
                pcm[i + 1] = (sample shr 8).toByte()
            }
            writer.append(pcm)
            writer.commit()
        }
        val result = WavWaveform.peaks(file, 8)
        assertEquals(8, result.size)
        assertEquals(0f, result[0], 0.01f)
        assertTrue(result[7] > 0.48f)
        dir.deleteRecursively()
    }
}

