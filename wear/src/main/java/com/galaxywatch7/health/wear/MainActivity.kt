package com.galaxywatch7.health.wear

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.galaxywatch7.health.shared.EcgSessionMetadata
import com.galaxywatch7.health.shared.PpgMetrics
import com.galaxywatch7.health.shared.updates.GitHubReleaseUpdater
import com.galaxywatch7.health.shared.updates.UpdateCheckResult
import com.galaxywatch7.health.wear.sensors.SamsungHealthSensorBridge
import com.galaxywatch7.health.wear.storage.WearSessionStore
import com.galaxywatch7.health.wear.sync.WearSyncClient
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity(), SamsungHealthSensorBridge.Listener {
    private lateinit var status: TextView
    private lateinit var nodeStatus: TextView
    private lateinit var sampleStatus: TextView
    private lateinit var logPreview: TextView
    private lateinit var bridge: SamsungHealthSensorBridge
    private lateinit var sync: WearSyncClient
    private lateinit var sessionStore: WearSessionStore
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val recentLogs = ArrayDeque<String>()
    private var updateCheck: UpdateCheckResult? = null
    private var updateApk: File? = null
    private var lastSyncCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bridge = SamsungHealthSensorBridge(this)
        sync = WearSyncClient(this)
        sessionStore = WearSessionStore(this)
        setContentView(buildUi())
        requestSensorPermission()
        onStatus("Boot app. Checking SDK...")
        bridge.connect(this)
        syncQueuedSessions()
        refreshConnectionStatus()
    }

    override fun onDestroy() {
        bridge.disconnect()
        super.onDestroy()
    }

    override fun onStatus(message: String) {
        addLog(message)
        runOnUiThread { status.text = message }
        sync.sendStatus(message)
        refreshConnectionStatus()
    }

    override fun onEcgProgress(secondsLeft: Int, sampleCount: Int, leadOffSamples: Int) {
        val text = "ECG ${secondsLeft}s left | $sampleCount samples | lead-off $leadOffSamples"
        runOnUiThread {
            sampleStatus.text = text
            status.text = "Recording ECG. Keep still, finger on top button."
        }
        sync.sendLog("INFO", "ecg", text)
    }

    override fun onEcgComplete(metadata: EcgSessionMetadata, samples: FloatArray) {
        sessionStore.save(metadata, samples)
        sync.sendEcgSession(metadata, samples)
        lastSyncCount++
        sampleStatus.text = "${metadata.sampleCount} samples saved. Queued/sent: $lastSyncCount"
        onStatus("ECG complete: ${metadata.sampleCount} samples, lead-off ${metadata.leadOffSamples}.")
    }

    override fun onPpgMetrics(metrics: PpgMetrics) {
        sync.sendPpgMetrics(metrics)
        onStatus("BP research metrics sent: pulse ${metrics.pulse}, quality ${"%.2f".format(metrics.signalQuality)}.")
    }

    private fun buildUi(): ScrollView {
        val dp = resources.displayMetrics.density
        fun Int.dp(): Int = (this * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22.dp(), 18.dp(), 22.dp(), 28.dp())
            setBackgroundColor(Color.rgb(5, 7, 10))
        }
        root.addView(TextView(this).apply {
            text = "ECG Watch7"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        nodeStatus = pill("Link: checking", Color.rgb(17, 24, 39), 12f)
        root.addView(nodeStatus)
        status = TextView(this).apply {
            text = "Starting..."
            textSize = 13f
            setTextColor(Color.rgb(226, 232, 240))
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
            minHeight = 76.dp()
            background = round(Color.rgb(15, 23, 42), 18.dp())
        }
        root.addView(status)
        sampleStatus = pill("No active recording", Color.rgb(8, 47, 73), 12f)
        root.addView(sampleStatus)
        root.addView(actionButton("ECG 30s").apply {
            setOnClickListener { bridge.startEcg(this@MainActivity) }
        })
        root.addView(actionButton("BP research").apply {
            setOnClickListener { bridge.startBpResearchCapture(this@MainActivity) }
        })
        root.addView(actionButton("Sync logs").apply {
            setOnClickListener {
                syncQueuedSessions()
                sync.sendLog("INFO", "manual", "Manual sync/log flush requested.")
                onStatus("Manual sync requested.")
            }
        })
        logPreview = TextView(this).apply {
            text = "Logs will appear here."
            textSize = 10f
            setTextColor(Color.rgb(148, 163, 184))
            setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())
            maxLines = 4
        }
        root.addView(logPreview)
        root.addView(actionButton("Update check").apply {
            setOnClickListener { checkForUpdate() }
        })
        root.addView(actionButton("Download").apply {
            setOnClickListener { downloadUpdate() }
        })
        root.addView(actionButton("Install").apply {
            setOnClickListener { installUpdate() }
        })
        root.addView(TextView(this).apply {
            text = "Research only - v${BuildConfig.VERSION_NAME}"
            textSize = 10f
            setTextColor(Color.rgb(148, 163, 184))
            gravity = Gravity.CENTER
        })
        return ScrollView(this).apply { addView(root) }
    }

    private fun actionButton(label: String): Button {
        val dp = resources.displayMetrics.density
        fun Int.dp(): Int = (this * dp).toInt()
        return Button(this).apply {
            text = label
            textSize = 12f
            minHeight = 44.dp()
            minWidth = 0
            setPadding(8.dp(), 0, 8.dp(), 0)
            setTextColor(Color.WHITE)
            background = round(Color.rgb(14, 116, 144), 22.dp())
        }
    }

    private fun pill(textValue: String, color: Int, size: Float): TextView {
        val dp = resources.displayMetrics.density
        fun Int.dp(): Int = (this * dp).toInt()
        return TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(Color.rgb(226, 232, 240))
            gravity = Gravity.CENTER
            setPadding(8.dp(), 7.dp(), 8.dp(), 7.dp())
            background = round(color, 18.dp())
        }
    }

    private fun round(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
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

    private fun checkForUpdate() {
        onStatus("Checking GitHub releases...")
        io.execute {
            val result = GitHubReleaseUpdater.check(BuildConfig.VERSION_NAME, "wear")
            updateCheck = result
            updateApk = result.latestVersion?.let { GitHubReleaseUpdater.findDownloadedApk(this, it, "wear") }
            val asset = result.assetName?.let { " Asset: $it." } ?: ""
            val downloaded = if (updateApk != null) " APK downloaded." else ""
            runOnUiThread { status.text = result.message + asset + downloaded }
        }
    }

    private fun downloadUpdate() {
        val result = updateCheck ?: run {
            onStatus("Check release first.")
            return
        }
        val assetUrl = result.assetUrl ?: run {
            onStatus("No wear APK asset in release.")
            return
        }
        val version = result.latestVersion ?: return
        onStatus("Downloading wear update...")
        io.execute {
            val file = GitHubReleaseUpdater.downloadApk(this, assetUrl, version, "wear") { progress ->
                runOnUiThread { status.text = "${progress.message} ${progress.sizeLabel}" }
            }
            updateApk = file
            runOnUiThread { status.text = if (file == null) "Download failed." else "Downloaded ${file.name}. Install update." }
        }
    }

    private fun installUpdate() {
        val file = updateApk ?: updateCheck?.latestVersion?.let { GitHubReleaseUpdater.findDownloadedApk(this, it, "wear") }
        onStatus(if (file == null) "APK not found. Download first." else GitHubReleaseUpdater.installDownloadedApk(this, file))
    }

    private fun syncQueuedSessions() {
        io.execute {
            val sessions = sessionStore.list()
            sessions.forEach { (metadata, samples) -> sync.sendEcgSession(metadata, samples) }
            if (sessions.isNotEmpty()) {
                sync.sendLog("INFO", "sync", "Queued ECG sessions offered to Data Layer: ${sessions.size}")
            }
        }
    }

    private fun refreshConnectionStatus() {
        sync.getConnectedNodeCount { count ->
            runOnUiThread {
                nodeStatus.text = if (count > 0) "Link: phone connected ($count)" else "Link: offline - data queued"
            }
        }
    }

    private fun addLog(message: String) {
        val line = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $message"
        if (recentLogs.size >= 4) recentLogs.removeFirst()
        recentLogs.addLast(line)
        runOnUiThread { if (::logPreview.isInitialized) logPreview.text = recentLogs.joinToString("\n") }
    }
}
