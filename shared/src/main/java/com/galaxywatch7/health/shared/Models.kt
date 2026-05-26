package com.galaxywatch7.health.shared

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class HealthCommand {
    START_ECG,
    STOP_ECG,
    START_PPG_BP,
    SYNC_SESSION,
    EXPORT_DATA
}

object WearPaths {
    const val MESSAGE_STATUS = "/status"
    const val MESSAGE_COMMAND = "/command"
    const val MESSAGE_PPG_METRICS = "/ppg_metrics"
    const val DATA_ECG_SESSION_PREFIX = "/ecg_session"
    const val DATA_WATCH_LOG_PREFIX = "/watch_log"
    const val DATA_WEAR_UPDATE_PREFIX = "/wear_update"
}

data class WatchLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestampEpochMillis: Long = System.currentTimeMillis(),
    val level: String,
    val source: String,
    val message: String
)

data class EcgSessionMetadata(
    val id: String = UUID.randomUUID().toString(),
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val sampleRateHz: Int = 500,
    val sampleCount: Int,
    val leadOffSamples: Int,
    val deviceModel: String,
    val sampleFileName: String,
    val wellnessOnly: Boolean = true
)

data class BpCalibration(
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int,
    val timestampEpochMillis: Long,
    val watchWrist: String,
    val validUntilEpochMillis: Long = timestampEpochMillis + 28L * 24L * 60L * 60L * 1000L
) {
    fun isValid(nowEpochMillis: Long = System.currentTimeMillis()): Boolean = nowEpochMillis <= validUntilEpochMillis
}

data class PpgMetrics(
    val pulse: Int,
    val signalQuality: Float,
    val capturedAtEpochMillis: Long,
    val sourceSessionId: String = UUID.randomUUID().toString()
)

data class BpEstimate(
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int,
    val confidence: Float,
    val sourceSessionId: String,
    val timestampEpochMillis: Long,
    val wellnessOnly: Boolean = true
)

object BpEstimator {
    fun estimate(calibration: BpCalibration?, metrics: PpgMetrics): BpEstimate? {
        if (calibration == null || !calibration.isValid(metrics.capturedAtEpochMillis)) return null

        val pulseDelta = metrics.pulse - calibration.pulse
        val systolic = (calibration.systolic + pulseDelta * 0.25f).toInt().coerceIn(70, 180)
        val diastolic = (calibration.diastolic + pulseDelta * 0.15f).toInt().coerceIn(40, 120)
        val confidencePenalty = min(0.35f, abs(pulseDelta) / 100f)
        val confidence = max(0.05f, min(metrics.signalQuality, 0.70f) - confidencePenalty)

        return BpEstimate(
            systolic = systolic,
            diastolic = diastolic,
            pulse = metrics.pulse,
            confidence = confidence,
            sourceSessionId = metrics.sourceSessionId,
            timestampEpochMillis = metrics.capturedAtEpochMillis
        )
    }
}

object EcgBinaryCodec {
    fun encode(samples: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val values = FloatArray(bytes.size / 4)
        for (i in values.indices) values[i] = buffer.getFloat()
        return values
    }
}

object HealthJson {
    fun watchLogToJson(entry: WatchLogEntry): String = JSONObject()
        .put("id", entry.id)
        .put("timestampEpochMillis", entry.timestampEpochMillis)
        .put("level", entry.level)
        .put("source", entry.source)
        .put("message", entry.message)
        .toString()

    fun watchLogFromJson(raw: String): WatchLogEntry {
        val json = JSONObject(raw)
        return WatchLogEntry(
            id = json.getString("id"),
            timestampEpochMillis = json.getLong("timestampEpochMillis"),
            level = json.getString("level"),
            source = json.getString("source"),
            message = json.getString("message")
        )
    }

    fun ecgToJson(session: EcgSessionMetadata): String = JSONObject()
        .put("id", session.id)
        .put("startedAtEpochMillis", session.startedAtEpochMillis)
        .put("endedAtEpochMillis", session.endedAtEpochMillis)
        .put("sampleRateHz", session.sampleRateHz)
        .put("sampleCount", session.sampleCount)
        .put("leadOffSamples", session.leadOffSamples)
        .put("deviceModel", session.deviceModel)
        .put("sampleFileName", session.sampleFileName)
        .put("wellnessOnly", session.wellnessOnly)
        .toString()

    fun ecgFromJson(raw: String): EcgSessionMetadata {
        val json = JSONObject(raw)
        return EcgSessionMetadata(
            id = json.getString("id"),
            startedAtEpochMillis = json.getLong("startedAtEpochMillis"),
            endedAtEpochMillis = json.getLong("endedAtEpochMillis"),
            sampleRateHz = json.getInt("sampleRateHz"),
            sampleCount = json.getInt("sampleCount"),
            leadOffSamples = json.getInt("leadOffSamples"),
            deviceModel = json.getString("deviceModel"),
            sampleFileName = json.getString("sampleFileName"),
            wellnessOnly = json.optBoolean("wellnessOnly", true)
        )
    }

    fun ecgListToJson(sessions: List<EcgSessionMetadata>): String {
        val array = JSONArray()
        sessions.forEach { array.put(JSONObject(ecgToJson(it))) }
        return array.toString()
    }

    fun ecgListFromJson(raw: String): List<EcgSessionMetadata> {
        if (raw.isBlank()) return emptyList()
        val array = JSONArray(raw)
        return List(array.length()) { index -> ecgFromJson(array.getJSONObject(index).toString()) }
    }

    fun calibrationToJson(calibration: BpCalibration): String = JSONObject()
        .put("systolic", calibration.systolic)
        .put("diastolic", calibration.diastolic)
        .put("pulse", calibration.pulse)
        .put("timestampEpochMillis", calibration.timestampEpochMillis)
        .put("watchWrist", calibration.watchWrist)
        .put("validUntilEpochMillis", calibration.validUntilEpochMillis)
        .toString()

    fun calibrationFromJson(raw: String): BpCalibration {
        val json = JSONObject(raw)
        return BpCalibration(
            systolic = json.getInt("systolic"),
            diastolic = json.getInt("diastolic"),
            pulse = json.getInt("pulse"),
            timestampEpochMillis = json.getLong("timestampEpochMillis"),
            watchWrist = json.getString("watchWrist"),
            validUntilEpochMillis = json.getLong("validUntilEpochMillis")
        )
    }

    fun ppgMetricsToJson(metrics: PpgMetrics): String = JSONObject()
        .put("pulse", metrics.pulse)
        .put("signalQuality", metrics.signalQuality.toDouble())
        .put("capturedAtEpochMillis", metrics.capturedAtEpochMillis)
        .put("sourceSessionId", metrics.sourceSessionId)
        .toString()

    fun ppgMetricsFromJson(raw: String): PpgMetrics {
        val json = JSONObject(raw)
        return PpgMetrics(
            pulse = json.getInt("pulse"),
            signalQuality = json.getDouble("signalQuality").toFloat(),
            capturedAtEpochMillis = json.getLong("capturedAtEpochMillis"),
            sourceSessionId = json.getString("sourceSessionId")
        )
    }
}

object CsvExport {
    fun ecg(session: EcgSessionMetadata, samples: FloatArray): String {
        val builder = StringBuilder("session_id,timestamp_ms,sample_index,ecg_mv,wellness_only\n")
        samples.forEachIndexed { index, value ->
            val offsetMs = (index * 1000L) / session.sampleRateHz
            builder.append(session.id).append(',')
                .append(session.startedAtEpochMillis + offsetMs).append(',')
                .append(index).append(',')
                .append(value).append(',')
                .append(session.wellnessOnly)
                .append('\n')
        }
        return builder.toString()
    }
}
