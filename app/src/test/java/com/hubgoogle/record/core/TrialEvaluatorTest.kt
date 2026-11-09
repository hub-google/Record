package com.hubgoogle.record.core

import org.junit.Assert.*
import org.junit.Test

class TrialEvaluatorTest {
    private fun attempt(n: Int, both: Boolean = true,
                        source: String = "CALL_MIX"): CallTrial =
        CallTrial("$n", n.toLong(), "buildA", source,
            if (n % 2 == 0) "INCOMING" else "OUTGOING",
            "EARPIECE", true, both, 10_000, n.toString().padStart(64, '0'))

    @Test fun tenUniqueCallsNineWithTwoPartiesMeetReportedBaseline() {
        val data = (0 until 10).map { attempt(it, both = it != 9) }
        val result = TrialEvaluator.evaluate(data, "buildA")
        assertEquals(10, result.attempts)
        assertEquals(9, result.bothAudible)
        assertTrue(result.meetsSelfReportedBaseline)
    }

    @Test fun nineCallsCannotMeetBaseline() {
        assertFalse(TrialEvaluator.evaluate((0 until 9).map { attempt(it) },
            "buildA").meetsSelfReportedBaseline)
    }

    @Test fun duplicateAudioAndDifferentBuildDoNotCount() {
        val data = (0 until 10).map { attempt(it) }
        assertEquals(10, TrialEvaluator.evaluate(data + attempt(1), "buildA").attempts)
        assertFalse(TrialEvaluator.evaluate(data, "buildB").meetsSelfReportedBaseline)
    }

    @Test fun txOnlyCannotProveBothCallParticipants() {
        val data = (0 until 12).map { attempt(it, source = "CALL_TX") }
        assertEquals(0, TrialEvaluator.evaluate(data, "buildA").attempts)
    }
}
