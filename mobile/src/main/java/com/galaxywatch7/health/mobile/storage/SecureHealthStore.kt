package com.galaxywatch7.health.mobile.storage

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.galaxywatch7.health.shared.EcgBinaryCodec
import com.galaxywatch7.health.shared.EcgSessionMetadata
import com.galaxywatch7.health.shared.HealthJson
import com.galaxywatch7.health.shared.BpCalibration
import java.io.File

class SecureHealthStore(private val context: Context) {
    private val root = File(context.filesDir, "health_store").apply { mkdirs() }
    private val key by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    fun saveCalibration(calibration: BpCalibration) {
        writeEncrypted(File(root, "bp_calibration.json"), HealthJson.calibrationToJson(calibration).toByteArray())
    }

    fun readCalibration(): BpCalibration? {
        val raw = readEncrypted(File(root, "bp_calibration.json")) ?: return null
        return runCatching { HealthJson.calibrationFromJson(raw.decodeToString()) }.getOrNull()
    }

    fun saveEcgSession(metadata: EcgSessionMetadata, samples: FloatArray) {
        writeEncrypted(File(root, metadata.sampleFileName), EcgBinaryCodec.encode(samples))
        val sessions = listEcgSessions().filterNot { it.id == metadata.id } + metadata
        writeEncrypted(File(root, "ecg_sessions.json"), HealthJson.ecgListToJson(sessions).toByteArray())
    }

    fun listEcgSessions(): List<EcgSessionMetadata> {
        val raw = readEncrypted(File(root, "ecg_sessions.json")) ?: return emptyList()
        return runCatching { HealthJson.ecgListFromJson(raw.decodeToString()) }.getOrDefault(emptyList())
            .sortedByDescending { it.startedAtEpochMillis }
    }

    fun readEcgSamples(session: EcgSessionMetadata): FloatArray {
        val bytes = readEncrypted(File(root, session.sampleFileName)) ?: return FloatArray(0)
        return EcgBinaryCodec.decode(bytes)
    }

    private fun encryptedFile(file: File): EncryptedFile =
        EncryptedFile.Builder(
            context,
            file,
            key,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()

    private fun writeEncrypted(file: File, bytes: ByteArray) {
        if (file.exists()) file.delete()
        encryptedFile(file).openFileOutput().use { it.write(bytes) }
    }

    private fun readEncrypted(file: File): ByteArray? {
        if (!file.exists()) return null
        return encryptedFile(file).openFileInput().use { it.readBytes() }
    }
}

