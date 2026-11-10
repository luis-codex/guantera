package app.guantera.sonda.domain

import app.guantera.sonda.domain.model.ClockSample
import app.guantera.sonda.domain.rule.IntervalSamplingPolicy
import app.guantera.sonda.domain.rule.SuspensionDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuspensionDetectorTest {
    @Test fun detectsSleepGapSeparatelyFromRuntime() {
        assertEquals(120L, SuspensionDetector.suspendedSeconds(ClockSample(1000, 500), ClockSample(131000, 10500)))
    }

    @Test fun noSleepWhenBothClocksAdvanceTogether() {
        assertEquals(0L, SuspensionDetector.suspendedSeconds(ClockSample(1000, 500), ClockSample(11000, 10500)))
    }

    @Test fun negativeGapIsClampedToZero() {
        assertEquals(0L, SuspensionDetector.suspendedSeconds(ClockSample(0, 0), ClockSample(1000, 5000)))
    }

    @Test fun gapBelowThresholdIsNotReported() {
        assertNull(SuspensionDetector.detect(ClockSample(0, 0), ClockSample(14_000, 10_000)))
    }

    @Test fun gapAtThresholdIsReported() {
        assertEquals(5L, SuspensionDetector.detect(ClockSample(0, 0), ClockSample(15_000, 10_000)))
    }
}

class IntervalSamplingPolicyTest {
    private val policy = IntervalSamplingPolicy(intervalMillis = 30_000)

    @Test fun recordsFirstFix() = assertTrue(policy.shouldRecord(null, 1))

    @Test fun skipsFixInsideInterval() = assertFalse(policy.shouldRecord(1_000, 30_999))

    @Test fun recordsFixAtInterval() = assertTrue(policy.shouldRecord(1_000, 31_000))
}
