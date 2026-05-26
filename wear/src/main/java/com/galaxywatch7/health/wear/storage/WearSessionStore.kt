package com.galaxywatch7.health.wear.storage

import android.content.Context
import com.galaxywatch7.health.shared.EcgBinaryCodec
import com.galaxywatch7.health.shared.EcgSessionMetadata
import com.galaxywatch7.health.shared.HealthJson
import java.io.File

class WearSessionStore(context: Context) {
    private val root = File(context.filesDir, "queued_sessions").apply { mkdirs() }

    fun save(metadata: EcgSessionMetadata, samples: FloatArray) {
        File(root, "${metadata.id}.json").writeText(HealthJson.ecgToJson(metadata))
        File(root, "${metadata.id}.bin").writeBytes(EcgBinaryCodec.encode(samples))
    }

    fun list(): List<Pair<EcgSessionMetadata, FloatArray>> {
        return root.listFiles { file -> file.extension == "json" }
            .orEmpty()
            .mapNotNull { jsonFile ->
                runCatching {
                    val metadata = HealthJson.ecgFromJson(jsonFile.readText())
                    val samples = EcgBinaryCodec.decode(File(root, "${metadata.id}.bin").readBytes())
                    metadata to samples
                }.getOrNull()
            }
    }
}

