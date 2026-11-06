package com.hubgoogle.record.core

import org.junit.Assert.*
import org.junit.Test

class TestSignalTest {
    @Test fun toneFixtureAlternatesFrequency() {
        assertEquals(440, TestSignal.frequency(0))
        assertEquals(880, TestSignal.frequency(2L * TestSignal.RATE))
        assertEquals(440, TestSignal.frequency(4L * TestSignal.RATE))
    }
    @Test fun detectsTargetAndRejectsUnrelatedTone() {
        val samples = ShortArray(8192) { TestSignal.sample(it.toLong()) }
        assertTrue(ToneDetector.evaluate(samples, TestSignal.RATE, 440) > 0.18)
        assertTrue(ToneDetector.evaluate(samples, TestSignal.RATE, 880) < 0.03)
        assertTrue(ToneDetector.evaluate(ShortArray(8192), TestSignal.RATE, 440) == 0.0)
    }
}

