package com.hubgoogle.record.core

import kotlin.math.abs
import kotlin.math.sqrt

data class QualityResult(val samples: Long, val rms: Double, val peak: Int, val zeroRatio: Double, val clippingRatio: Double) {
    val isLikelySilent: Boolean get() = samples == 0L || (rms < 30 && zeroRatio > 0.98)
    val hasClipping: Boolean get() = clippingRatio > 0.01
}
class QualityAccumulator {
    private var samples = 0L
    private var sumSquared = 0.0
    private var zeros = 0L
    private var clipped = 0L
    private var peak = 0
    /** PCM signed 16-bit, little-endian. Ignores an incomplete final sample. */
    fun add(pcm: ByteArray, length: Int = pcm.size) {
        require(length >= 0 && length <= pcm.size)
        var i = 0
        while (i + 1 < length) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toInt()
            val a = abs(sample)
            if (sample == 0) zeros++
            if (a >= 32760) clipped++
            if (a > peak) peak = a
            sumSquared += sample.toDouble() * sample
            samples++
            i += 2
        }
    }
    fun result() = QualityResult(
        samples = samples,
        rms = if (samples == 0L) 0.0 else sqrt(sumSquared / samples),
        peak = peak,
        zeroRatio = if (samples == 0L) 1.0 else zeros.toDouble() / samples,
        clippingRatio = if (samples == 0L) 0.0 else clipped.toDouble() / samples
    )
}

