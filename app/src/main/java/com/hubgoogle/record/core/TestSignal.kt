package com.hubgoogle.record.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * Deterministic non-copyright test audio for playback-capture diagnostics.
 * 0–2s: 440 Hz; 2–4s: 880 Hz; 4–6s: 440 Hz; 6–8s: 880 Hz.
 */
object TestSignal {
    const val RATE = 48000
    const val TOTAL_SECONDS = 8
    fun frequency(sampleIndex: Long): Int =
        if ((sampleIndex / (RATE * 2L)) % 2L == 0L) 440 else 880
    fun sample(sampleIndex: Long): Short {
        val hz = frequency(sampleIndex)
        return (11000.0 * sin(2.0 * PI * hz * (sampleIndex % RATE) / RATE))
            .toInt().coerceIn(-32768, 32767).toShort()
    }
}

/**
 * Goertzel test-tone detection, not speech/call participant detection.
 * A normalized spectral peak above 0.18 marks an expected sine signal.
 */
object ToneDetector {
    fun evaluate(pcm: ShortArray, rate: Int, frequency: Int): Double {
        require(rate > 0 && frequency in 1..(rate / 2))
        if (pcm.size < 128) return 0.0
        val coefficient = 2.0 * cos(2.0 * PI * frequency / rate)
        var a = 0.0
        var b = 0.0
        var energy = 0.0
        for (x in pcm) {
            val value = x.toDouble() / 32768.0
            val s = value + coefficient * a - b
            b = a; a = s
            energy += value * value
        }
        val power = b * b + a * a - coefficient * a * b
        return if (energy < 1e-8) 0.0 else (power / (energy * pcm.size)).coerceAtLeast(0.0)
    }
}

