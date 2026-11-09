package com.hubgoogle.record.audio

import com.hubgoogle.record.data.RecordingDetails
import com.hubgoogle.record.data.RecordingFile
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class SessionBundleExporterTest {
    private fun create(folder: File, name: String, source: String, ns: Long,
                       status: String = "RECORDED_UNVERIFIED"): RecordingFile {
        val file = File(folder, name)
        WavPcmFile(file, 16000, 1).use { wav ->
            wav.append(ByteArray(32000) { i -> if (i % 2 == 0) 40 else 10 })
            wav.commit()
        }
        return RecordingFile(file, System.currentTimeMillis(), file.length(),
            RecordingDetails(source, status, 1000, 16000, 1, 100.0, 1000, 0.01,
                "a".repeat(64), ns))
    }

    @Test fun threeConcurrentSourcesAndMissingTracksReported() {
        val dir = Files.createTempDirectory("session-zip-test").toFile()
        try {
            val call = create(dir, "call.wav", "VOIP_LINE", 10_000_000_000L)
            val media = create(dir, "media.wav", "MEDIA_PLAYBACK", 10_250_000_000L)
            val mic = create(dir, "mic.wav", "MIC", 10_450_000_000L)
            val silent = create(dir, "silent.wav", "CALL_RX", 10_150_000_000L, "SILENT")
            val unrelated = create(dir, "other.wav", "CALL_TX", 15_000_000_000L)
            val candidates = listOf(call, media, mic, silent, unrelated)
            val plan = SessionBundleExporter.plan(call, candidates)
            assertEquals(3, plan.tracks.size)
            assertEquals(3, plan.sourceCount)
            assertTrue(plan.missing.contains("CALL_RX"))
            assertEquals("SILENT", plan.rejected["silent.wav"])
            val zipFile = SessionBundleExporter.export(call, candidates)
            ZipFile(zipFile).use { zip ->
                assertEquals(4, zip.size())
                val manifest = zip.getInputStream(zip.getEntry("session.json")).bufferedReader().readText()
                assertTrue(manifest.contains("VOIP_LINE"))
                assertTrue(manifest.contains("MEDIA_PLAYBACK"))
                assertTrue(manifest.contains("MIC"))
                assertTrue(manifest.contains("missingSources"))
                assertTrue(manifest.contains("UNVERIFIED_ON_DEVICE"))
                assertTrue(manifest.contains("allDeviceAudioCaptured"))
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun oneRecordingDoesNotPretendToContainOtherSources() {
        val dir = Files.createTempDirectory("session-one-test").toFile()
        try {
            val item = create(dir, "call.wav", "CALL_MIX", 10_000_000_000L)
            val plan = SessionBundleExporter.plan(item, listOf(item))
            assertEquals(1, plan.tracks.size)
            assertTrue(plan.missing.contains("MIC"))
            assertTrue(plan.missing.contains("MEDIA_PLAYBACK"))
        } finally { dir.deleteRecursively() }
    }
}
