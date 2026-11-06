package com.hubgoogle.record.audio

import com.hubgoogle.record.data.RecordingDetails
import com.hubgoogle.record.data.RecordingFile
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MultiTrackExporterTest {
    private fun entry(name: String, source: String, start: Long, duration: Long): RecordingFile =
        RecordingFile(
            File("/tmp/$name"), System.currentTimeMillis(), 1024,
            RecordingDetails(source, "RECORDED_UNVERIFIED", duration, 16000, 1,
                100.0, 9000, 0.05, "checksum", start)
        )

    @Test fun overlappingCallAndMediaArePairable() {
        val call = entry("call.wav", "CALL_MIX", 100_000_000_000L, 20_000)
        val media = entry("media.wav", "MEDIA_PLAYBACK", 105_000_000_000L, 10_000)
        val result = MultiTrackExporter.findBestPair(call, listOf(call, media))
        assertNotNull(result)
        assertEquals(10_000L, result!!.overlapMs)
    }

    @Test fun unrelatedNonOverlappingMediaCannotBePaired() {
        val call = entry("call.wav", "CALL_MIX", 100_000_000_000L, 10_000)
        val media = entry("media.wav", "MEDIA_PLAYBACK", 140_000_000_000L, 10_000)
        assertNull(MultiTrackExporter.findBestPair(call, listOf(media)))
    }

    @Test fun encryptedSourcesAreNotMisreportedAsAudioTracks() {
        val call = entry("call.wav.enc", "CALL_MIX", 100_000_000_000L, 10_000)
        val media = entry("media.wav", "MEDIA_PLAYBACK", 101_000_000_000L, 10_000)
        assertNull(MultiTrackExporter.findBestPair(call, listOf(media)))
    }

    @Test fun archiveContainsIndependentAudioAndSynchronizationManifest() {
        val dir = java.nio.file.Files.createTempDirectory("record-tracks").toFile()
        val callFile = File(dir, "call.wav")
        val mediaFile = File(dir, "media.wav")
        WavPcmFile(callFile, 16000, 1).use { it.append(byteArrayOf(0, 1, 2, 3)); it.commit() }
        WavPcmFile(mediaFile, 48000, 2).use { it.append(byteArrayOf(4, 5, 6, 7)); it.commit() }
        val call = RecordingFile(callFile, System.currentTimeMillis(), callFile.length(),
            RecordingDetails("CALL_MIX", "RECORDED_UNVERIFIED", 2000, 16000, 1, 100.0, 500,
                0.0, "testhash", 10000000000L))
        val media = RecordingFile(mediaFile, System.currentTimeMillis(), mediaFile.length(),
            RecordingDetails("MEDIA_PLAYBACK", "RECORDED_UNVERIFIED", 2000, 48000, 2, 100.0, 500,
                0.0, "testhash2", 10001000000L))
        val result = MultiTrackExporter.export(
            MultiTrackExporter.TrackPair(call, media, 1999))
        java.util.zip.ZipFile(result).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue(names.contains("call/call.wav"))
            assertTrue(names.contains("media/media.wav"))
            assertTrue(names.contains("session.json"))
            val manifest = zip.getInputStream(zip.getEntry("session.json"))
                .bufferedReader().readText()
            assertTrue(manifest.contains("record.multitrack/1"))
            assertTrue(manifest.contains("relativeStartUs"))
            assertTrue(manifest.contains("UNVERIFIED_ON_DEVICE"))
            assertEquals(callFile.readBytes().size.toLong(),
                zip.getInputStream(zip.getEntry("call/call.wav")).readBytes().size.toLong())
        }
        dir.deleteRecursively()
    }
}

