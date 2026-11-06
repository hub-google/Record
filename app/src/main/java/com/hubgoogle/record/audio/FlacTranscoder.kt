package com.hubgoogle.record.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lossless PCM WAV -> FLAC via Android's MediaCodec.
 * Hardware/software FLAC encoder availability depends on the device.
 * Failed transcodes keep the original WAV and remove the .partial file.
 */
object FlacTranscoder {
    fun convert(source: File): File {
        require(source.extension.equals("wav", ignoreCase = true)) { "Requires a PCM WAV recording" }
        val parent = requireNotNull(source.parentFile)
        val destination = File(parent, source.nameWithoutExtension + ".flac")
        check(!destination.exists()) { "FLAC already exists" }
        val temp = File(parent, destination.name + ".partial")
        check(!temp.exists()) { "Another FLAC conversion is pending" }

        val header = RandomAccessFile(source, "r").use { wav ->
            require(wav.length() >= 44L)
            val bytes = ByteArray(44)
            wav.readFully(bytes)
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF")
            require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
            require(String(bytes, 12, 4, Charsets.US_ASCII) == "fmt ")
            require(String(bytes, 36, 4, Charsets.US_ASCII) == "data")
            val encoding = b.getShort(20).toInt() and 0xffff
            val channels = b.getShort(22).toInt() and 0xffff
            val rate = b.getInt(24)
            val bits = b.getShort(34).toInt() and 0xffff
            val bytesInHeader = b.getInt(40).toLong() and 0xffffffffL
            require(encoding == 1 && bits == 16 && channels in 1..2 && rate in 8000..192000)
            require(bytesInHeader == wav.length() - 44L) { "WAV header does not match PCM length" }
            WavSpec(rate, channels, bytesInHeader)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        var started = false
        var committed = false
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, header.rate, header.channels)
            format.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            started = true
            FileInputStream(source).use { wav ->
                check(wav.skip(44) == 44L)
                FileOutputStream(temp).use { output ->
                    val info = MediaCodec.BufferInfo()
                    var bytesFed = 0L
                    var eosInput = false
                    var eosOutput = false
                    var wroteFlacMarker = false
                    val deadline = SystemClock.elapsedRealtime() + 10 * 60_000L
                    while (!eosOutput) {
                        check(SystemClock.elapsedRealtime() < deadline) { "FLAC encoding timeout" }
                        if (!eosInput) {
                            val index = codec.dequeueInputBuffer(20_000)
                            if (index >= 0) {
                                val input = checkNotNull(codec.getInputBuffer(index))
                                input.clear()
                                val remaining = header.pcmBytes - bytesFed
                                if (remaining <= 0L) {
                                    codec.queueInputBuffer(index, 0, 0, header.durationUs(),
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    eosInput = true
                                } else {
                                    val maxRead = (minOf(input.remaining().toLong(), remaining) / (2 * header.channels)) *
                                        (2 * header.channels)
                                    check(maxRead > 0) { "Encoder input too small" }
                                    val chunk = ByteArray(maxRead.toInt())
                                    var count = 0
                                    while (count < chunk.size) {
                                        val n = wav.read(chunk, count, chunk.size - count)
                                        check(n > 0) { "Unexpected end of PCM" }
                                        count += n
                                    }
                                    input.put(chunk)
                                    val pts = bytesFed * 1_000_000L / (header.rate * header.channels * 2L)
                                    codec.queueInputBuffer(index, 0, chunk.size, pts, 0)
                                    bytesFed += count
                                }
                            }
                        }
                        while (true) {
                            val outIndex = codec.dequeueOutputBuffer(info, if (eosInput) 100_000 else 0)
                            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                val csd = codec.outputFormat.getByteBuffer("csd-0")
                                if (csd != null && csd.remaining() > 0 && !wroteFlacMarker) {
                                    val prefix = ByteArray(csd.remaining())
                                    csd.get(prefix)
                                    if (prefix.size >= 4 && String(prefix, 0, 4, Charsets.US_ASCII) == "fLaC") {
                                        output.write(prefix)
                                        wroteFlacMarker = true
                                    }
                                }
                                continue
                            }
                            if (outIndex < 0) break
                            val out = checkNotNull(codec.getOutputBuffer(outIndex))
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
                            out.get(chunk)
                            if (chunk.size >= 4 && String(chunk, 0, 4, Charsets.US_ASCII) == "fLaC") {
                                // Some encoders write the stream header in-band rather than as csd-0.
                                if (!wroteFlacMarker) {
                                    output.write(chunk); wroteFlacMarker = true
                                } else if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    // A whole stream header should never occur mid-stream.
                                    throw IllegalStateException("Duplicate FLAC stream header")
                                }
                            } else if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(wroteFlacMarker) { "FLAC STREAMINFO missing from encoder" }
                                if (chunk.isNotEmpty()) output.write(chunk)
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosOutput = true
                            codec.releaseOutputBuffer(outIndex, false)
                            if (eosOutput) break
                        }
                    }
                    check(wroteFlacMarker && temp.length() > 42L) { "Invalid FLAC stream" }
                    output.fd.sync()
                }
            }
            check(temp.renameTo(destination)) { "Cannot commit FLAC file" }
            committed = true
            return destination
        } finally {
            if (started) runCatching { codec.stop() }
            codec.release()
            if (!committed) temp.delete()
        }
    }
    private data class WavSpec(val rate: Int, val channels: Int, val pcmBytes: Long) {
        fun durationUs() = pcmBytes * 1_000_000L / (rate * channels * 2L)
    }
}

