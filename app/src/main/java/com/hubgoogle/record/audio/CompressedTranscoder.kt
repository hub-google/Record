package com.hubgoogle.record.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Optional space-saving encoding. AAC and Opus are lossy and intentionally
 * do NOT replace the WAV/FLAC archival master. A failed conversion leaves
 * the original PCM file untouched.
 */
object CompressedTranscoder {
    enum class Codec(val mime: String, val extension: String, val bitrate: Int, val muxer: Int) {
        AAC(MediaFormat.MIMETYPE_AUDIO_AAC, "m4a", 128_000, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
        OPUS(MediaFormat.MIMETYPE_AUDIO_OPUS, "ogg", 96_000, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
    }
    fun convert(wav: File, format: Codec): File {
        require(wav.isFile && wav.extension.lowercase() == "wav")
        val output = File(wav.parentFile, wav.nameWithoutExtension + "." + format.extension)
        check(!output.exists()) { "Output already exists" }
        val pending = File(wav.parentFile, output.name + ".partial")
        check(!pending.exists()) { "An incomplete output already exists" }
        val bytes = wav.inputStream().use { source ->
            ByteArray(44).also { check(source.read(it) == 44) { "Bad WAV header" } }
        }
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE" &&
            String(bytes, 36, 4) == "data" && header.getShort(20).toInt() == 1 &&
            header.getShort(34).toInt() == 16) { "Only canonical PCM16 WAV supported" }
        val channels = header.getShort(22).toInt() and 0xffff
        val rate = header.getInt(24)
        val length = header.getInt(40).toLong() and 0xffffffffL
        require(channels in 1..2 && rate in 8000..192000 &&
            length == wav.length() - 44 && length % (2L * channels) == 0L)
        val codec = MediaCodec.createEncoderByType(format.mime)
        var encoderStarted = false
        val muxer = MediaMuxer(pending.absolutePath, format.muxer)
        var muxerStarted = false
        var success = false
        try {
            val config = MediaFormat.createAudioFormat(format.mime, rate, channels)
            config.setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 2) format.bitrate * 2 else format.bitrate)
            config.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
            if (format == Codec.AAC) config.setInteger(
                MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            codec.configure(config, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            encoderStarted = true
            FileInputStream(wav).use { input ->
                check(input.skip(44) == 44L)
                var fed = 0L
                var eosIn = false
                var eosOut = false
                var track = -1
                val frameBytes = channels * 2
                val info = MediaCodec.BufferInfo()
                val deadline = SystemClock.elapsedRealtime() + 600_000L
                while (!eosOut) {
                    check(SystemClock.elapsedRealtime() < deadline) { "Encoding timed out" }
                    if (!eosIn) {
                        val index = codec.dequeueInputBuffer(20_000)
                        if (index >= 0) {
                            val buffer = checkNotNull(codec.getInputBuffer(index))
                            buffer.clear()
                            val readBytes = (minOf((length - fed), buffer.capacity().toLong()) /
                                frameBytes * frameBytes).toInt()
                            if (readBytes == 0) {
                                codec.queueInputBuffer(index, 0, 0,
                                    fed * 1_000_000L / (rate * frameBytes), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                eosIn = true
                            } else {
                                val raw = ByteArray(readBytes)
                                var n = 0
                                while (n < raw.size) {
                                    val got = input.read(raw, n, raw.size - n)
                                    check(got > 0) { "Truncated PCM input" }
                                    n += got
                                }
                                buffer.put(raw)
                                codec.queueInputBuffer(index, 0, n,
                                    fed * 1_000_000L / (rate * frameBytes), 0)
                                fed += n
                            }
                        }
                    }
                    while (true) {
                        val index = codec.dequeueOutputBuffer(info, if (eosIn) 100_000 else 0)
                        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            check(!muxerStarted) { "Encoder changed format twice" }
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                            continue
                        }
                        if (index < 0) break
                        val buffer = checkNotNull(codec.getOutputBuffer(index))
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(track >= 0 && muxerStarted) { "No muxer output track" }
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosOut = true
                        codec.releaseOutputBuffer(index, false)
                        if (eosOut) break
                    }
                }
            }
            check(muxerStarted) { "No encoded samples" }
            muxer.stop()
            muxerStarted = false
            check(pending.length() > 100) { "Output is empty" }
            check(pending.renameTo(output)) { "Cannot publish encoded file" }
            success = true
            return output
        } finally {
            if (encoderStarted) runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
            if (!success) pending.delete()
        }
    }
}

