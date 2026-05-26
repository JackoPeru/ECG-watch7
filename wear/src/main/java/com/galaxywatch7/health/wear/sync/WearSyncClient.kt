package com.galaxywatch7.health.wear.sync

import android.content.Context
import com.galaxywatch7.health.shared.EcgBinaryCodec
import com.galaxywatch7.health.shared.EcgSessionMetadata
import com.galaxywatch7.health.shared.HealthJson
import com.galaxywatch7.health.shared.PpgMetrics
import com.galaxywatch7.health.shared.WearPaths
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

class WearSyncClient(private val context: Context) {
    fun sendStatus(message: String) {
        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, WearPaths.MESSAGE_STATUS, message.toByteArray())
            }
        }
    }

    fun sendPpgMetrics(metrics: PpgMetrics) {
        val payload = HealthJson.ppgMetricsToJson(metrics).toByteArray()
        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { node ->
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, WearPaths.MESSAGE_PPG_METRICS, payload)
            }
        }
    }

    fun sendEcgSession(metadata: EcgSessionMetadata, samples: FloatArray) {
        val request = PutDataMapRequest.create("${WearPaths.DATA_ECG_SESSION_PREFIX}/${metadata.id}")
        request.dataMap.putString("metadata", HealthJson.ecgToJson(metadata))
        request.dataMap.putAsset("samples", Asset.createFromBytes(EcgBinaryCodec.encode(samples)))
        request.dataMap.putLong("createdAt", System.currentTimeMillis())
        Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent())
    }
}

