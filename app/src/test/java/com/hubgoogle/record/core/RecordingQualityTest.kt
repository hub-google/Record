package com.hubgoogle.record.core

import org.junit.Assert.*
import org.junit.Test

class RecordingQualityTest {
    @Test fun silenceIsDetected() {
        val quality = QualityAccumulator().apply { add(ByteArray(16000)) }.result()
        assertTrue(quality.isLikelySilent)
        assertEquals(1.0, quality.zeroRatio, 0.00001)
    }
    @Test fun speechLikePcmIsNotMarkedAsSilence() {
        val bytes = ByteArray(4000)
        for (i in bytes.indices step 2) {
            val sample = if (i % 8 == 0) 6000 else -6000
            bytes[i] = sample.toByte()
            bytes[i + 1] = (sample shr 8).toByte()
        }
        val quality = QualityAccumulator().apply { add(bytes) }.result()
        assertFalse(quality.isLikelySilent)
        assertEquals(6000, quality.peak)
        assertEquals(0.0, quality.clippingRatio, 0.001)
    }
    @Test fun clippingIsDetected() {
        val bytes = ByteArray(2000)
        for (i in bytes.indices step 2) {
            bytes[i] = 0xff.toByte()
            bytes[i + 1] = 0x7f.toByte()
        }
        assertTrue(QualityAccumulator().apply { add(bytes) }.result().hasClipping)
    }
    @Test(expected = IllegalArgumentException::class)
    fun overlongBufferIsRejected() {
        QualityAccumulator().add(ByteArray(2), 3)
    }
}

