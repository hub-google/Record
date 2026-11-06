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
}

