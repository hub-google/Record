package com.hubgoogle.record.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class WavPcmFileTest {
    @Test fun writesLittleEndianWavHeaderAndData() {
        val dir = Files.createTempDirectory("record-wav").toFile()
        val target = File(dir, "capture.wav")
        val data = byteArrayOf(0, 0, 0xff.toByte(), 0x7f, 1, 0)
        WavPcmFile(target, 16000, 1).use { it.append(data); it.commit() }
        val file = target.readBytes()
        assertEquals("RIFF", file.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", file.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals("fmt ", file.copyOfRange(12, 16).toString(Charsets.US_ASCII))
        val header = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(data.size + 36, header.getInt(4))
        assertEquals(16000, header.getInt(24))
        assertEquals(data.size, header.getInt(40))
        assertArrayEquals(data, file.copyOfRange(44, file.size))
        assertFalse(File(dir, "capture.wav.partial").exists())
        dir.deleteRecursively()
    }

    @Test fun failedPartialFileNeverBecomesPublishedWav() {
        val dir = Files.createTempDirectory("record-incomplete").toFile()
        val target = File(dir, "bad.wav")
        WavPcmFile(target, 16000, 1).use { it.append(byteArrayOf(1, 2, 3, 4)) }
        assertFalse(target.exists())
        assertFalse(File(dir, "bad.wav.partial").exists())
        dir.deleteRecursively()
    }
    @Test(expected = IllegalArgumentException::class)
    fun unsupportedChannelsRejected() {
        WavPcmFile(File("bad.wav"), 16000, 3)
    }
}

