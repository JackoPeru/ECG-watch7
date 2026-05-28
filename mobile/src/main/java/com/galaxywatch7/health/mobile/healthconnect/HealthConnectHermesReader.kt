package com.galaxywatch7.health.mobile.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

class HealthConnectHermesReader(private val context: Context) {
    fun statusLabel(): String = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> "available"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "provider update required"
        else -> "unavailable"
    }

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun grantedPermissions(): Set<String> {
        if (!isAvailable()) return emptySet()
        return HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
    }

    suspend fun readHermesPayload(days: Long = 30): JSONObject {
        val status = statusLabel()
        val root = JSONObject()
            .put("schema", "hermes.healthconnect.snapshot.v1")
            .put("capturedAtEpochMillis", System.currentTimeMillis())
            .put("status", status)

        if (!isAvailable()) return root

        val client = HealthConnectClient.getOrCreate(context)
        val end = Instant.now()
        val start = end.minus(Duration.ofDays(days))
        val range = TimeRangeFilter.between(start, end)
        val granted = client.permissionController.getGrantedPermissions()

        root.put("rangeDays", days)
            .put("grantedPermissions", JSONArray(granted.sorted()))
            .put("sleep", readSleep(client, range))
            .put("exercise", readExercise(client, range))
            .put("activity", readActivity(client, range))
            .put("bodyComposition", readBodyComposition(client, range))
            .put("vitals", readVitals(client, range))
        return root
    }

    private suspend fun readSleep(client: HealthConnectClient, range: TimeRangeFilter): JSONObject {
        val sessions = JSONArray()
        runCatching {
            client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range)).records
        }.getOrDefault(emptyList()).take(40).forEach { record ->
            val stages = JSONArray()
            record.stages.forEach { stage ->
                stages.put(
                    JSONObject()
                        .put("start", stage.startTime.toString())
                        .put("end", stage.endTime.toString())
                        .put("stage", stage.stage)
                )
            }
            sessions.put(
                JSONObject()
                    .put("start", record.startTime.toString())
                    .put("end", record.endTime.toString())
                    .put("title", record.title)
                    .put("notes", record.notes)
                    .put("dataOrigin", record.metadata.dataOrigin.packageName)
                    .put("stages", stages)
            )
        }
        return JSONObject().put("sessions", sessions)
    }

    private suspend fun readExercise(client: HealthConnectClient, range: TimeRangeFilter): JSONObject {
        val sessions = JSONArray()
        runCatching {
            client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range)).records
        }.getOrDefault(emptyList()).take(80).forEach { record ->
            sessions.put(
                JSONObject()
                    .put("start", record.startTime.toString())
                    .put("end", record.endTime.toString())
                    .put("exerciseType", record.exerciseType)
                    .put("title", record.title)
                    .put("notes", record.notes)
                    .put("dataOrigin", record.metadata.dataOrigin.packageName)
            )
        }
        return JSONObject().put("sessions", sessions)
    }

    private suspend fun readActivity(client: HealthConnectClient, range: TimeRangeFilter): JSONObject {
        val steps = JSONArray()
        runCatching {
            client.readRecords(ReadRecordsRequest(StepsRecord::class, range)).records
        }.getOrDefault(emptyList()).take(120).forEach { record ->
            steps.put(interval("count", record.count, record.startTime, record.endTime, record.metadata.dataOrigin.packageName))
        }

        val distance = JSONArray()
        runCatching {
            client.readRecords(ReadRecordsRequest(DistanceRecord::class, range)).records
        }.getOrDefault(emptyList()).take(120).forEach { record ->
            distance.put(interval("meters", record.distance.inMeters, record.startTime, record.endTime, record.metadata.dataOrigin.packageName))
        }

        val calories = JSONArray()
        runCatching {
            client.readRecords(ReadRecordsRequest(TotalCaloriesBurnedRecord::class, range)).records
        }.getOrDefault(emptyList()).take(120).forEach { record ->
            calories.put(interval("kilocalories", record.energy.inKilocalories, record.startTime, record.endTime, record.metadata.dataOrigin.packageName))
        }

        return JSONObject()
            .put("steps", steps)
            .put("distance", distance)
            .put("totalCaloriesBurned", calories)
    }

    private suspend fun readBodyComposition(client: HealthConnectClient, range: TimeRangeFilter): JSONObject {
        val weight = JSONArray()
        runCatching {
            client.readRecords(ReadRecordsRequest(WeightRecord::class, range)).records
        }.getOrDefault(emptyList()).take(80).forEach { record ->
            weight.put(instant("kilograms", record.weight.inKilograms, record.time, record.metadata.dataOrigin.packageName))
        }

        val bodyFat = JSONArray()
        runCatching {
            client.readRecords(ReadRecordsRequest(BodyFatRecord::class, range)).records
        }.getOrDefault(emptyList()).take(80).forEach { record ->
            bodyFat.put(instant("percent", record.percentage.value, record.time, record.metadata.dataOrigin.packageName))
        }

        val boneMass = JSONArray()
        runCatching {
            client.readRecords(ReadRecordsRequest(BoneMassRecord::class, range)).records
        }.getOrDefault(emptyList()).take(80).forEach { record ->
            boneMass.put(instant("kilograms", record.mass.inKilograms, record.time, record.metadata.dataOrigin.packageName))
        }

        val leanMass = JSONArray()
        runCatching {
            client.readRecords(ReadRecordsRequest(LeanBodyMassRecord::class, range)).records
        }.getOrDefault(emptyList()).take(80).forEach { record ->
            leanMass.put(instant("kilograms", record.mass.inKilograms, record.time, record.metadata.dataOrigin.packageName))
        }

        val waterMass = JSONArray()
        runCatching {
            client.readRecords(ReadRecordsRequest(BodyWaterMassRecord::class, range)).records
        }.getOrDefault(emptyList()).take(80).forEach { record ->
            waterMass.put(instant("kilograms", record.mass.inKilograms, record.time, record.metadata.dataOrigin.packageName))
        }

        return JSONObject()
            .put("weight", weight)
            .put("bodyFat", bodyFat)
            .put("boneMass", boneMass)
            .put("leanBodyMass", leanMass)
            .put("bodyWaterMass", waterMass)
    }

    private suspend fun readVitals(client: HealthConnectClient, range: TimeRangeFilter): JSONObject {
        val heartRate = JSONArray()
        runCatching {
            client.readRecords(ReadRecordsRequest(HeartRateRecord::class, range)).records
        }.getOrDefault(emptyList()).take(120).forEach { record ->
            val samples = JSONArray()
            record.samples.take(200).forEach {
                samples.put(JSONObject().put("time", it.time.toString()).put("bpm", it.beatsPerMinute))
            }
            heartRate.put(
                JSONObject()
                    .put("start", record.startTime.toString())
                    .put("end", record.endTime.toString())
                    .put("dataOrigin", record.metadata.dataOrigin.packageName)
                    .put("samples", samples)
            )
        }

        val oxygen = JSONArray()
        runCatching {
            client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, range)).records
        }.getOrDefault(emptyList()).take(120).forEach { record ->
            oxygen.put(instant("percent", record.percentage.value, record.time, record.metadata.dataOrigin.packageName))
        }

        return JSONObject()
            .put("heartRate", heartRate)
            .put("oxygenSaturation", oxygen)
    }

    private fun interval(key: String, value: Any, start: Instant, end: Instant, origin: String): JSONObject =
        JSONObject()
            .put("start", start.toString())
            .put("end", end.toString())
            .put(key, value)
            .put("dataOrigin", origin)

    private fun instant(key: String, value: Any, time: Instant, origin: String): JSONObject =
        JSONObject()
            .put("time", time.toString())
            .put(key, value)
            .put("dataOrigin", origin)

    companion object {
        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
            HealthPermission.getReadPermission(BoneMassRecord::class),
            HealthPermission.getReadPermission(LeanBodyMassRecord::class),
            HealthPermission.getReadPermission(BodyWaterMassRecord::class)
        )
    }
}
