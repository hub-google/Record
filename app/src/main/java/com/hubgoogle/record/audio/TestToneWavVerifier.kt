package com.hubgoogle.record.audio

import com.hubgoogle.record.core.ToneDetector
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class TestToneResult(val tone440: Boolean, val tone880: Boolean, val max440: Double, val max880: Double)

/** Inspect up to 45 seconds of a PCM16 WAV. Results prove test tones only. */
object TestToneWavVerifier {
    fun check(wav: File): TestToneResult {
        RandomAccessFile(wav, "r").use { input ->
            val raw = ByteArray(44)
            input.readFully(raw)
            val header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            require(String(raw, 0, 4) == "RIFF" && String(raw, 8, 4) == "WAVE" &&
                String(raw, 36, 4) == "data" &&
                header.getShort(20).toInt() == 1 && header.getShort(34).toInt() == 16)
            val rate = header.getInt(24)
            val channels = header.getShort(22).toInt() and 0xffff
            require(rate in 8000..192000 && channels in 1..2)
            val window = 8192
            val rawBuffer = ByteArray(window * channels * 2)
            val samples = ShortArray(window)
            var max440 = 0.0
            var max880 = 0.0
            val maxWindows = maxOf(1, (45L * rate / window).toInt())
            for (i in 0 until maxWindows) {
                val amount = input.read(rawBuffer)
                if (amount < rawBuffer.size) break
                for (j in 0 until window) {
                    val position = j * channels * 2
                    samples[j] = ((rawBuffer[position + 1].toInt() shl 8) or
                        (rawBuffer[position].toInt() and 255)).toShort()
                }
                max440 = maxOf(max440, ToneDetector.evaluate(samples, rate, 440))
                max880 = maxOf(max880, ToneDetector.evaluate(samples, rate, 880))
            }
            return TestToneResult(max440 >= 0.18, max880 >= 0.18, max440, max880)
        }
    }
}

