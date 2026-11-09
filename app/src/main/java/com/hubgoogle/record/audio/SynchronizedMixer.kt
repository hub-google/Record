package com.hubgoogle.record.audio

import com.hubgoogle.record.data.RecordingFile
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Explicit offline mix for two time-overlapping 16-bit PCM WAV tracks.
 *
 * Uses monotonic capture timestamps and a fixed 48 kHz stereo output.
 * Resampling is deterministic nearest-neighbour; no clock-drift correction.
 * Does not imply the call contains both people or that either input is valid.
 * WAV sources remain independent; a failed mix is not published.
 */
object SynchronizedMixer {
    const val OUTPUT_RATE = 48_000
    const val OUTPUT_CHANNELS = 2
    const val MAX_OUTPUT_SECONDS = 7_200

    fun mix(pair: MultiTrackExporter.TrackPair): File {
        require(pair.overlapMs > 0) { "Tracks must overlap" }
        val a = pair.call.details ?: error("Call recording metadata missing")
        val b = pair.media.details ?: error("Media recording metadata missing")
        require(a.source in setOf("CALL_MIX", "CALL_TX", "CALL_RX"))
        require(b.source == "MEDIA_PLAYBACK")
        require(a.status !in setOf("SILENT", "EMPTY") && b.status !in setOf("SILENT", "EMPTY"))
        require(a.startedElapsedNs > 0 && b.startedElapsedNs > 0)
        require(pair.call.file.extension.equals("wav", true) && pair.media.file.extension.equals("wav", true)) {
            "Mixing requires original PCM WAV tracks (convert sources before encrypting)"
        }
        val folder = pair.call.file.canonicalFile.parentFile
        require(pair.media.file.canonicalFile.parentFile == folder) { "Tracks must share the library folder" }

        PcmReader(pair.call.file).use { call ->
            PcmReader(pair.media.file).use { media ->
                check(call.rate == a.sampleRate && call.channels == a.channels &&
                    media.rate == b.sampleRate && media.channels == b.channels) { "WAV metadata mismatch" }
                val originNs = minOf(a.startedElapsedNs, b.startedElapsedNs)
                val callStartNs = a.startedElapsedNs - originNs
                val mediaStartNs = b.startedElapsedNs - originNs
                val durationNs = maxOf(
                    callStartNs + call.frames * 1_000_000_000L / call.rate,
                    mediaStartNs + media.frames * 1_000_000_000L / media.rate
                )
                val totalFrames = (durationNs * OUTPUT_RATE / 1_000_000_000L) + 1
                require(totalFrames in 1..(MAX_OUTPUT_SECONDS.toLong() * OUTPUT_RATE)) {
                    "Mix duration outside supported range"
                }
                val estimated = totalFrames * OUTPUT_CHANNELS * 2L + 44
                check(folder.usableSpace > estimated + 16L * 1024 * 1024) {
                    "Not enough free storage to create an independent lossless mix"
                }
                val dest = File(folder, "mixed_" + UUID.randomUUID().toString().take(12) + ".wav")
                WavPcmFile(dest, OUTPUT_RATE, OUTPUT_CHANNELS).use { output ->
                    val block = ByteArray(8192 * OUTPUT_CHANNELS * 2)
                    var position = 0L
                    while (position < totalFrames) {
                        val frames = minOf(8192L, totalFrames - position).toInt()
                        for (i in 0 until frames) {
                            val frame = position + i
                            val tNs = frame * 1_000_000_000L / OUTPUT_RATE
                            val callSample = if (tNs >= callStartNs) {
                                (tNs - callStartNs) * call.rate / 1_000_000_000L
                            } else -1L
                            val mediaSample = if (tNs >= mediaStartNs) {
                                (tNs - mediaStartNs) * media.rate / 1_000_000_000L
                            } else -1L
                            for (channel in 0..1) {
                                // Gain=0.5 for each source to avoid routine clipping.
                                val aValue = call.sample(callSample, channel)
                                val bValue = media.sample(mediaSample, channel)
                                val combined = ((aValue.toInt() + bValue.toInt()) / 2)
                                    .coerceIn(-32768, 32767)
                                val offset = (i * OUTPUT_CHANNELS + channel) * 2
                                block[offset] = combined.toByte()
                                block[offset + 1] = (combined shr 8).toByte()
                            }
                        }
                        output.append(block, 0, frames * OUTPUT_CHANNELS * 2)
                        position += frames
                    }
                    output.commit()
                }
                return dest
            }
        }
    }

    /** Fixed-window buffered reader avoids disk seeks for every output sample. */
    private class PcmReader(file: File) : AutoCloseable {
        private val input = RandomAccessFile(file, "r")
        val rate: Int
        val channels: Int
        val frames: Long
        private val cache = ByteArray(65_536)
        private var firstCachedFrame = -1L
        private var cachedFrames = 0

        init {
            try {
                require(input.length() >= 44) { "WAV too short" }
                val hdr = ByteArray(44)
                input.readFully(hdr)
                val h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                require(String(hdr, 0, 4, Charsets.US_ASCII) == "RIFF")
                require(String(hdr, 8, 4, Charsets.US_ASCII) == "WAVE")
                require(String(hdr, 12, 4, Charsets.US_ASCII) == "fmt ")
                require(String(hdr, 36, 4, Charsets.US_ASCII) == "data")
                require(h.getShort(20).toInt() == 1 && h.getShort(34).toInt() == 16)
                val chan = h.getShort(22).toInt() and 65535
                val frequency = h.getInt(24)
                require(chan in 1..2 && frequency in 8000..192000)
                val size = h.getInt(40).toLong() and 0xffffffffL
                require(size == input.length() - 44 && size % (2L * chan) == 0L) {
                    "Incomplete or malformed WAV PCM"
                }
                channels = chan
                rate = frequency
                frames = size / (2L * chan)
            } catch (error: Throwable) {
                input.close()
                throw error
            }
        }

        fun sample(index: Long, channel: Int): Short {
            if (index < 0 || index >= frames) return 0
            if (index < firstCachedFrame || index >= firstCachedFrame + cachedFrames) {
                val framesPerCache = cache.size / (channels * 2)
                firstCachedFrame = (index / framesPerCache) * framesPerCache
                cachedFrames = minOf(frames - firstCachedFrame, framesPerCache.toLong()).toInt()
                input.seek(44 + firstCachedFrame * channels * 2L)
                input.readFully(cache, 0, cachedFrames * channels * 2)
            }
            val offset = ((index - firstCachedFrame).toInt() * channels +
                if (channels == 1) 0 else channel) * 2
            return ((cache[offset + 1].toInt() shl 8) or
                (cache[offset].toInt() and 255)).toShort()
        }
        override fun close() { input.close() }
    }
}
