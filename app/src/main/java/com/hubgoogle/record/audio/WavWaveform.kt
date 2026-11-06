package com.hubgoogle.record.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/** Computes a fixed number of normalized peaks for a canonical PCM16 WAV file. */
object WavWaveform {
    fun peaks(file: File, buckets: Int = 96): List<Float> {
        require(buckets in 8..1024)
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < 44) return emptyList()
            val headerBytes = ByteArray(44)
            input.readFully(headerBytes)
            val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            if (String(headerBytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(headerBytes, 8, 4, Charsets.US_ASCII) != "WAVE" ||
                String(headerBytes, 36, 4, Charsets.US_ASCII) != "data" ||
                header.getShort(20).toInt() != 1 ||
                header.getShort(34).toInt() != 16) return emptyList()
            val count = (input.length() - 44L) / 2
            if (count <= 0L) return emptyList()
            val peaks = IntArray(buckets)
            val buffer = ByteArray(32768)
            var index = 0L
            while (true) {
                val size = input.read(buffer)
                if (size <= 0) break
                var offset = 0
                while (offset + 1 < size) {
                    val value = (((buffer[offset + 1].toInt() shl 8)
                        or (buffer[offset].toInt() and 0xff)).toShort()).toInt()
                    val bucket = ((index * buckets) / count).toInt().coerceIn(0, buckets - 1)
                    peaks[bucket] = max(peaks[bucket], abs(value))
                    index++
                    offset += 2
                }
            }
            return peaks.map { it / 32768f }
        }
    }
}

