package com.galaxywatch7.health.shared

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class RPeak(
    val sampleIndex: Int,
    val timestampMillis: Long,
    val amplitudeMv: Float
)

data class EcgAnalysis(
    val sampleRateHz: Int,
    val durationSeconds: Float,
    val rPeaks: List<RPeak>,
    val heartRateBpm: Int?,
    val meanRrMillis: Int?,
    val rrStdDevMillis: Int?,
    val signalQuality: Float,
    val status: String
)

object EcgAnalyzer {
    fun analyze(samples: FloatArray, sampleRateHz: Int = 500, startedAtEpochMillis: Long = 0L): EcgAnalysis {
        if (samples.size < sampleRateHz * 5) {
            return EcgAnalysis(
                sampleRateHz = sampleRateHz,
                durationSeconds = samples.size.toFloat() / sampleRateHz,
                rPeaks = emptyList(),
                heartRateBpm = null,
                meanRrMillis = null,
                rrStdDevMillis = null,
                signalQuality = 0f,
                status = "ECG troppo corto per analisi affidabile."
            )
        }

        val filtered = removeBaseline(samples, max(1, sampleRateHz / 2))
        val energy = qrsEnergy(filtered, sampleRateHz)
        val candidatePeaks = detectPeaks(energy, filtered, sampleRateHz)
        val rrMillis = candidatePeaks.zipWithNext { a, b -> ((b.sampleIndex - a.sampleIndex) * 1000f / sampleRateHz).toInt() }
            .filter { it in 300..2_000 }
        val meanRr = rrMillis.takeIf { it.isNotEmpty() }?.average()?.toInt()
        val rrStdDev = if (rrMillis.size > 1 && meanRr != null) {
            sqrt(rrMillis.map { (it - meanRr) * (it - meanRr) }.average()).toInt()
        } else {
            null
        }
        val bpm = meanRr?.let { (60_000f / it).toInt().coerceIn(25, 220) }
        val quality = qualityScore(samples, candidatePeaks.size, rrMillis, sampleRateHz)
        val status = when {
            candidatePeaks.size < 3 -> "Nessun ritmo ECG stabile rilevato."
            quality < 0.35f -> "Segnale debole/rumoroso. Ripeti misura con contatto stabile."
            bpm == null -> "R-peaks rilevati, HR non affidabile."
            else -> "Analisi ECG raw completata localmente."
        }

        return EcgAnalysis(
            sampleRateHz = sampleRateHz,
            durationSeconds = samples.size.toFloat() / sampleRateHz,
            rPeaks = candidatePeaks.map {
                it.copy(timestampMillis = startedAtEpochMillis + (it.sampleIndex * 1000L / sampleRateHz))
            },
            heartRateBpm = bpm,
            meanRrMillis = meanRr,
            rrStdDevMillis = rrStdDev,
            signalQuality = quality,
            status = status
        )
    }

    private fun removeBaseline(samples: FloatArray, window: Int): FloatArray {
        val out = FloatArray(samples.size)
        var sum = 0f
        for (i in samples.indices) {
            sum += samples[i]
            if (i >= window) sum -= samples[i - window]
            val count = min(i + 1, window)
            out[i] = samples[i] - (sum / count)
        }
        return movingAverage(out, max(1, window / 50))
    }

    private fun qrsEnergy(filtered: FloatArray, sampleRateHz: Int): FloatArray {
        val derivative = FloatArray(filtered.size)
        for (i in 2 until filtered.size - 2) {
            derivative[i] = (2 * filtered[i + 1] + filtered[i + 2] - 2 * filtered[i - 1] - filtered[i - 2]) / 8f
        }
        val squared = FloatArray(derivative.size) { derivative[it] * derivative[it] }
        return movingAverage(squared, max(1, (0.15f * sampleRateHz).toInt()))
    }

    private fun detectPeaks(energy: FloatArray, filtered: FloatArray, sampleRateHz: Int): List<RPeak> {
        val mean = energy.average().toFloat()
        val std = sqrt(energy.map { (it - mean) * (it - mean) }.average()).toFloat()
        val threshold = mean + std * 0.65f
        val refractory = (0.25f * sampleRateHz).toInt()
        val searchRadius = max(1, (0.08f * sampleRateHz).toInt())
        val peaks = mutableListOf<RPeak>()
        var lastPeak = -refractory
        var i = 1
        while (i < energy.lastIndex) {
            if (energy[i] > threshold && energy[i] >= energy[i - 1] && energy[i] >= energy[i + 1] && i - lastPeak >= refractory) {
                val start = max(0, i - searchRadius)
                val end = min(filtered.lastIndex, i + searchRadius)
                var best = start
                for (j in start..end) {
                    if (abs(filtered[j]) > abs(filtered[best])) best = j
                }
                peaks.add(RPeak(best, 0L, filtered[best]))
                lastPeak = best
                i += refractory
            } else {
                i++
            }
        }
        return peaks
    }

    private fun movingAverage(values: FloatArray, window: Int): FloatArray {
        if (window <= 1) return values.copyOf()
        val out = FloatArray(values.size)
        var sum = 0f
        for (i in values.indices) {
            sum += values[i]
            if (i >= window) sum -= values[i - window]
            out[i] = sum / min(i + 1, window)
        }
        return out
    }

    private fun qualityScore(samples: FloatArray, peakCount: Int, rrMillis: List<Int>, sampleRateHz: Int): Float {
        val duration = samples.size.toFloat() / sampleRateHz
        val peakDensity = (peakCount / max(1f, duration)).coerceIn(0f, 3f) / 3f
        val amplitude = percentileRange(samples).coerceIn(0.05f, 4f)
        val amplitudeScore = ((amplitude - 0.05f) / 0.95f).coerceIn(0f, 1f)
        val rrScore = if (rrMillis.size > 1) {
            val mean = rrMillis.average()
            val std = sqrt(rrMillis.map { (it - mean) * (it - mean) }.average())
            (1.0 - (std / max(1.0, mean))).toFloat().coerceIn(0f, 1f)
        } else {
            0.2f
        }
        return (0.35f * peakDensity + 0.35f * amplitudeScore + 0.30f * rrScore).coerceIn(0f, 1f)
    }

    private fun percentileRange(samples: FloatArray): Float {
        val sorted = samples.copyOf().also { it.sort() }
        val p05 = sorted[(sorted.lastIndex * 0.05f).toInt()]
        val p95 = sorted[(sorted.lastIndex * 0.95f).toInt()]
        return p95 - p05
    }
}

