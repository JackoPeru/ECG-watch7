package com.galaxywatch7.health.wear

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
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
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity(), SamsungHealthSensorBridge.Listener, DataClient.OnDataChangedListener {
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
    private val processedUpdateItems = mutableSetOf<String>()

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

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
        loadPendingWearUpdates()
    }

    override fun onPause() {
        Wearable.getDataClient(this).removeListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        bridge.disconnect()
        super.onDestroy()
    }

    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED &&
                event.dataItem.uri.path.orEmpty().startsWith(com.galaxywatch7.health.shared.WearPaths.DATA_WEAR_UPDATE_PREFIX)
            ) {
                receiveWearUpdateDataItem(event.dataItem.freeze())
            }
        }
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
            textSize = 12f
            setTextColor(Color.rgb(226, 232, 240))
            setPadding(12.dp(), 9.dp(), 12.dp(), 9.dp())
            minHeight = 56.dp()
            background = round(Color.rgb(15, 23, 42), 14.dp())
        }
        root.addView(status)
        sampleStatus = pill("No active recording", Color.rgb(8, 47, 73), 12f)
        root.addView(sampleStatus)
        root.addView(actionButton("ECG 30s").apply {
            setOnClickListener {
                if (ensureSensorPermissions()) bridge.startEcg(this@MainActivity)
            }
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
        root.addView(actionButton("Health Platform").apply {
            setOnClickListener { bridge.openHealthPlatformSettings(this@MainActivity) }
        })
        root.addView(actionButton("Policy info").apply {
            setOnClickListener {
                logPermissionStatus()
                bridge.sendPolicyDiagnostic(this@MainActivity)
            }
        })
        root.addView(actionButton("Permissions").apply {
            setOnClickListener {
                if (checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED) {
                    requestSensorPermission()
                    logPermissionStatus()
                } else {
                    requestSensorPermission()
                    main.postDelayed({
                        if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
                            openAppPermissionSettings()
                        }
                    }, 1200)
                }
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
            textSize = 11f
            minHeight = 34.dp()
            minWidth = 0
            setPadding(6.dp(), 0, 6.dp(), 0)
            setTextColor(Color.WHITE)
            background = round(Color.rgb(8, 145, 178), 14.dp())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                36.dp()
            ).apply {
                setMargins(0, 6.dp(), 0, 6.dp())
            }
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
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 4.dp(), 0, 4.dp())
            }
        }
    }

    private fun round(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    private fun requestSensorPermission() {
        val requested = dangerousSensorPermissions()
        val missing = requested
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            .toTypedArray()
        if (missing.isNotEmpty()) {
            onStatus("Requesting sensor permissions: ${missing.joinToString()}")
            requestPermissions(missing, 10)
        } else {
            logPermissionStatus()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) logPermissionStatus()
    }

    private fun ensureSensorPermissions(): Boolean {
        val missing = dangerousSensorPermissions()
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            onStatus("ECG needs permissions first: ${missing.joinToString()}")
            requestPermissions(missing.toTypedArray(), 10)
            if (missing.contains(Manifest.permission.BODY_SENSORS)) {
                main.postDelayed({
                    if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
                        openAppPermissionSettings()
                    }
                }, 1200)
            }
            return false
        }
        logPermissionStatus()
        return true
    }

    private fun dangerousSensorPermissions(): List<String> {
        val requested = mutableListOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION
        )
        if (Build.VERSION.SDK_INT >= 36) {
            requested.add("android.permission.health.READ_HEART_RATE")
        }
        return requested
    }

    private fun logPermissionStatus() {
        val names = listOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
            "android.permission.health.READ_HEART_RATE",
            "android.permission.HIGH_SAMPLING_RATE_SENSORS",
            "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
        )
        val summary = names.joinToString("; ") { permission ->
            val state = if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) "granted" else "denied"
            "${permission.substringAfterLast('.')}=$state"
        }
        onStatus("Permission status: $summary")
    }

    private fun openAppPermissionSettings() {
        runCatching {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            onStatus("BODY_SENSORS still denied. Opened app settings: Permissions > Sensors/Body sensors > Allow.")
        }.onFailure {
            onStatus("Open permission settings failed: ${it.message ?: it.javaClass.simpleName}")
        }
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

    private fun receiveWearUpdateDataItem(dataItem: com.google.android.gms.wearable.DataItem) {
        val uri = dataItem.uri
        if (!processedUpdateItems.add(uri.toString())) return
        io.execute {
            runCatching {
                val map = DataMapItem.fromDataItem(dataItem).dataMap
                val version = map.getString("version") ?: "unknown"
                val fileName = map.getString("fileName") ?: "Watch7Health-wear-$version.apk"
                val asset = map.getAsset("apk") ?: return@execute
                val bytes = readAsset(asset) ?: return@execute
                val dir = File(getExternalFilesDir(null) ?: cacheDir, "updates").apply { mkdirs() }
                val file = File(dir, fileName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                file.writeBytes(bytes)
                updateApk = file
                runOnUiThread { status.text = "Watch update received via phone: ${file.name}. Press Install." }
                sync.sendLog("INFO", "update", "Watch APK received via phone: ${file.name}, ${file.length()} bytes.")
                Tasks.await(Wearable.getDataClient(this).deleteDataItems(uri))
            }.onFailure {
                processedUpdateItems.remove(uri.toString())
                onStatus("Watch update receive failed: ${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun loadPendingWearUpdates() {
        io.execute {
            runCatching {
                val buffer = Tasks.await(Wearable.getDataClient(this).dataItems)
                try {
                    buffer.forEach { item ->
                        if (item.uri.path.orEmpty().startsWith(com.galaxywatch7.health.shared.WearPaths.DATA_WEAR_UPDATE_PREFIX)) {
                            receiveWearUpdateDataItem(item.freeze())
                        }
                    }
                } finally {
                    buffer.release()
                }
            }
        }
    }

    private fun readAsset(asset: Asset): ByteArray? = runCatching {
        val response = Tasks.await(Wearable.getDataClient(this).getFdForAsset(asset))
        response.inputStream.use { it.readBytes() }
    }.getOrNull()

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
