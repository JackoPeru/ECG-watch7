package com.galaxywatch7.health.wear

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.galaxywatch7.health.shared.EcgSessionMetadata
import com.galaxywatch7.health.shared.PpgMetrics
import com.galaxywatch7.health.wear.sensors.SamsungHealthSensorBridge
import com.galaxywatch7.health.wear.sync.WearSyncClient

class MainActivity : Activity(), SamsungHealthSensorBridge.Listener {
    private lateinit var status: TextView
    private lateinit var bridge: SamsungHealthSensorBridge
    private lateinit var sync: WearSyncClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bridge = SamsungHealthSensorBridge(this)
        sync = WearSyncClient(this)
        setContentView(buildUi())
        requestSensorPermission()
        bridge.connect(this)
    }

    override fun onDestroy() {
        bridge.disconnect()
        super.onDestroy()
    }

    override fun onStatus(message: String) {
        runOnUiThread { status.text = message }
        sync.sendStatus(message)
    }

    override fun onEcgComplete(metadata: EcgSessionMetadata, samples: FloatArray) {
        sync.sendEcgSession(metadata, samples)
        onStatus("ECG complete: ${metadata.sampleCount} samples, lead-off ${metadata.leadOffSamples}.")
    }

    override fun onPpgMetrics(metrics: PpgMetrics) {
        sync.sendPpgMetrics(metrics)
        onStatus("BP research metrics sent: pulse ${metrics.pulse}, quality ${"%.2f".format(metrics.signalQuality)}.")
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
        }
        root.addView(TextView(this).apply {
            text = "Watch7 Health"
            textSize = 20f
            setTextColor(Color.WHITE)
        })
        status = TextView(this).apply {
            text = "Starting..."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 10, 0, 12)
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Record ECG 30s"
            setOnClickListener { bridge.startEcg(this@MainActivity) }
        })
        root.addView(Button(this).apply {
            text = "BP research capture"
            setOnClickListener { bridge.startBpResearchCapture(this@MainActivity) }
        })
        root.addView(TextView(this).apply {
            text = "Wellness/research only. Not diagnostic."
            textSize = 12f
            setTextColor(Color.LTGRAY)
        })
        return ScrollView(this).apply { addView(root) }
    }

    private fun requestSensorPermission() {
        val requested = mutableListOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
            "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
        )
        if (Build.VERSION.SDK_INT >= 36) {
            requested.add("android.permission.health.READ_HEART_RATE")
        }
        val missing = requested
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            .toTypedArray()
        if (missing.isNotEmpty()) requestPermissions(missing, 10)
    }
}
