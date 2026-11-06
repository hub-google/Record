package com.hubgoogle.record.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Crash-aware PCM -> WAV. Caller must close() to finalize the header.
 * Each session is limited to RIFF WAV's 4 GiB field and should be segmented before that.
 */
class WavPcmFile(private val destination: File, val sampleRate: Int, val channels: Int) : Closeable {
    private val temp = File(destination.parentFile, destination.name + ".partial")
    private val file: RandomAccessFile
    private var bytes = 0L
    private var finished = false
    init {
        require(sampleRate in 8000..192000 && channels in 1..2)
        check(!temp.exists()) { "Uncommitted segment exists: " + temp.name }
        destination.parentFile?.mkdirs()
        file = RandomAccessFile(temp, "rw")
        file.setLength(0)
        file.write(ByteArray(44))
    }
    fun append(pcm: ByteArray, off: Int = 0, length: Int = pcm.size - off) {
        check(!finished)
        require(length >= 0 && off >= 0 && off + length <= pcm.size)
        check(bytes + length <= 0xFFFFFFFFL - 36L) { "RIFF size limit exceeded: segment now" }
        file.write(pcm, off, length)
        bytes += length
    }
    val dataBytes get() = bytes
    val durationMs get() = bytes * 1000L / (sampleRate * channels * 2)
    override fun close() {
        if (finished) return
        finished = true
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt((bytes + 36).toInt())
        header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16).putShort(1).putShort(channels.toShort())
        header.putInt(sampleRate).putInt(sampleRate * channels * 2)
        header.putShort((channels * 2).toShort()).putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII)).putInt(bytes.toInt())
        try {
            file.seek(0)
            file.write(header.array())
            file.fd.sync()
        } finally {
            file.close()
        }
        if (!temp.renameTo(destination)) throw java.io.IOException("Cannot commit WAV " + destination.name)
    }
}

