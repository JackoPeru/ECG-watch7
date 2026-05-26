package com.galaxywatch7.health.shared

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SharedModelTest {
    @Test
    fun ecgBinaryCodecRoundTripsSamples() {
        val samples = floatArrayOf(-0.12f, 0.0f, 0.45f, 1.25f)
        val decoded = EcgBinaryCodec.decode(EcgBinaryCodec.encode(samples))
        assertArrayEquals(samples, decoded, 0.0001f)
    }

    @Test
    fun bpEstimateRequiresCalibration() {
        val metrics = PpgMetrics(
            pulse = 72,
            signalQuality = 0.8f,
            capturedAtEpochMillis = 1_000L
        )
        assertNull(BpEstimator.estimate(null, metrics))
    }

    @Test
    fun bpEstimateUsesValidCalibration() {
        val calibration = BpCalibration(
            systolic = 120,
            diastolic = 80,
            pulse = 70,
            timestampEpochMillis = 1_000L,
            watchWrist = "left",
            validUntilEpochMillis = 10_000L
        )
        val metrics = PpgMetrics(
            pulse = 78,
            signalQuality = 0.8f,
            capturedAtEpochMillis = 2_000L,
            sourceSessionId = "ppg-1"
        )
        val estimate = BpEstimator.estimate(calibration, metrics)
        assertNotNull(estimate)
        assertEquals("ppg-1", estimate!!.sourceSessionId)
        assertEquals(122, estimate.systolic)
        assertEquals(81, estimate.diastolic)
    }

    @Test
    fun ecgAnalyzerDetectsSyntheticRhythm() {
        val rate = 500
        val samples = FloatArray(rate * 20)
        for (i in samples.indices) {
            val t = i.toFloat() / rate
            samples[i] = (0.03f * sin(2f * PI.toFloat() * 1.2f * t))
            if (i % rate in 0..8) {
                samples[i] += 1.0f - (i % rate) * 0.08f
            }
        }

        val analysis = EcgAnalyzer.analyze(samples, rate, 1_000L)
        assertNotNull(analysis.heartRateBpm)
        assertEquals(60.0, analysis.heartRateBpm!!.toDouble(), 8.0)
        assertEquals(true, analysis.signalQuality > 0.2f)
    }
}
