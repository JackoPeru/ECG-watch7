package com.galaxywatch7.health.mobile

import android.app.Activity
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.galaxywatch7.health.mobile.storage.SecureHealthStore
import com.galaxywatch7.health.mobile.ui.EcgChartView
import com.galaxywatch7.health.shared.BpCalibration
import com.galaxywatch7.health.shared.BpEstimator
import com.galaxywatch7.health.shared.CsvExport
import com.galaxywatch7.health.shared.EcgAnalyzer
import com.galaxywatch7.health.shared.EcgBinaryCodec
import com.galaxywatch7.health.shared.EcgSessionMetadata
import com.galaxywatch7.health.shared.HealthJson
import com.galaxywatch7.health.shared.PpgMetrics
import com.galaxywatch7.health.shared.WatchLogEntry
import com.galaxywatch7.health.shared.WearPaths
import com.galaxywatch7.health.shared.updates.GitHubReleaseUpdater
import com.galaxywatch7.health.shared.updates.UpdateCheckResult
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity(), DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener {
    private lateinit var store: SecureHealthStore
    private lateinit var status: TextView
    private lateinit var sessions: TextView
    private lateinit var bp: TextView
    private lateinit var updateStatus: TextView
    private lateinit var linkStatus: TextView
    private lateinit var logs: TextView
    private lateinit var ecgAnalysis: TextView
    private lateinit var chart: EcgChartView
    private lateinit var hermesUrl: EditText
    private lateinit var hermesToken: EditText
    private lateinit var hermesStatus: TextView
    private val io = Executors.newSingleThreadExecutor()
    private var selectedSession: EcgSessionMetadata? = null
    private var selectedSamples: FloatArray = FloatArray(0)
    private var updateCheck: UpdateCheckResult? = null
    private var updateApk: File? = null
    private var wearUpdateCheck: UpdateCheckResult? = null
    private var wearUpdateApk: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureHealthStore(this)
        setContentView(buildUi())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
        Wearable.getMessageClient(this).addListener(this)
        refreshLinkStatus()
        loadExistingWearData()
    }

    override fun onPause() {
        Wearable.getDataClient(this).removeListener(this)
        Wearable.getMessageClient(this).removeListener(this)
        super.onPause()
    }

    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            runCatching {
                if (event.type == DataEvent.TYPE_CHANGED &&
                    event.dataItem.uri.path?.startsWith(WearPaths.DATA_ECG_SESSION_PREFIX) == true
                ) {
                    receiveEcgDataItem(event.dataItem)
                }
                if (event.type == DataEvent.TYPE_CHANGED &&
                    event.dataItem.uri.path?.startsWith(WearPaths.DATA_WATCH_LOG_PREFIX) == true
                ) {
                    receiveWatchLog(event)
                }
                if (event.type == DataEvent.TYPE_CHANGED &&
                    event.dataItem.uri.path?.startsWith(WearPaths.DATA_HERMES_SNAPSHOT_PREFIX) == true
                ) {
                    receiveHermesSnapshot(event.dataItem)
                }
            }.onFailure {
                runOnUiThread { status.text = "Data Layer event ignored: ${it.message ?: it.javaClass.simpleName}" }
            }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearPaths.MESSAGE_STATUS -> runOnUiThread { status.text = event.data.decodeToString() }
            WearPaths.MESSAGE_PPG_METRICS -> handlePpgMetrics(event.data.decodeToString())
        }
    }

    private fun buildUi(): ScrollView {
        val dp = resources.displayMetrics.density
        fun Int.dp(): Int = (this * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 24.dp(), 24.dp(), 32.dp())
            setBackgroundColor(Color.rgb(5, 7, 10))
        }
        fun label(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.rgb(226, 232, 240))
            setPadding(0, 10.dp(), 0, 6.dp())
        }

        root.addView(TextView(this).apply {
            text = "ECG Watch7"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Wellness/research only. Not for diagnosis."
            textSize = 13f
            setTextColor(Color.rgb(148, 163, 184))
        })
        linkStatus = cardText("Link watch: checking...")
        root.addView(linkStatus)
        status = cardText("Waiting for watch data.")
        root.addView(status)

        root.addView(sectionTitle("Hermes"))
        val hermesPrefs = getSharedPreferences("hermes", MODE_PRIVATE)
        hermesUrl = input("Hermes endpoint URL", numeric = false).apply {
            setText(hermesPrefs.getString("url", "").orEmpty())
        }
        hermesToken = input("Bearer token optional", numeric = false).apply {
            setText(hermesPrefs.getString("token", "").orEmpty())
        }
        root.addView(hermesUrl)
        root.addView(hermesToken)
        hermesStatus = cardText(hermesPrefs.getString("last_status", "No Hermes sync yet.").orEmpty())
        root.addView(hermesStatus)
        root.addView(primaryButton("Save Hermes config").apply {
            setOnClickListener { saveHermesConfig() }
        })
        root.addView(primaryButton("Send Hermes packet").apply {
            setOnClickListener { sendHermesPacket() }
        })

        root.addView(sectionTitle("BP calibration"))
        val systolic = input("Cuff systolic")
        val diastolic = input("Cuff diastolic")
        val pulse = input("Cuff pulse")
        val wrist = input("Watch wrist: left/right", numeric = false).apply { setText("left") }
        root.addView(systolic)
        root.addView(diastolic)
        root.addView(pulse)
        root.addView(wrist)
        root.addView(primaryButton("Save cuff calibration").apply {
            setOnClickListener {
                val calibration = BpCalibration(
                    systolic = systolic.text.toString().toIntOrNull() ?: return@setOnClickListener,
                    diastolic = diastolic.text.toString().toIntOrNull() ?: return@setOnClickListener,
                    pulse = pulse.text.toString().toIntOrNull() ?: return@setOnClickListener,
                    timestampEpochMillis = System.currentTimeMillis(),
                    watchWrist = wrist.text.toString().ifBlank { "left" }
                )
                store.saveCalibration(calibration)
                refresh()
            }
        })

        bp = label("No BP estimate yet.")
        root.addView(bp)
        chart = EcgChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 360.dp())
            background = round(Color.rgb(15, 23, 42), 18.dp())
        }
        root.addView(sectionTitle("ECG sessions"))
        root.addView(chart)
        ecgAnalysis = cardText("No ECG raw data to analyze yet.")
        root.addView(ecgAnalysis)
        sessions = label("")
        root.addView(sessions)
        root.addView(primaryButton("Export selected CSV/PDF").apply {
            setOnClickListener { exportSelected() }
        })
        root.addView(sectionTitle("Watch logs"))
        logs = cardText(readLogs().ifBlank { "No watch logs yet." })
        root.addView(logs)
        root.addView(primaryButton("Refresh link/logs").apply {
            setOnClickListener {
                refreshLinkStatus()
                loadExistingWearData()
            }
        })
        root.addView(primaryButton("Clear logs").apply {
            setOnClickListener {
                getSharedPreferences("watch_logs", MODE_PRIVATE).edit().clear().apply()
                logs.text = "No watch logs yet."
                clearWatchLogDataItems()
            }
        })
        root.addView(sectionTitle("Updates"))
        updateStatus = cardText("Version ${BuildConfig.VERSION_NAME}. Release GitHub non controllata.")
        root.addView(updateStatus)
        root.addView(primaryButton("Check GitHub release").apply {
            setOnClickListener { checkForUpdate() }
        })
        root.addView(primaryButton("Download update").apply {
            setOnClickListener { downloadUpdate() }
        })
        root.addView(primaryButton("Install update").apply {
            setOnClickListener { installUpdate() }
        })
        root.addView(primaryButton("Download watch update").apply {
            setOnClickListener { downloadWearUpdateOnPhone() }
        })
        root.addView(primaryButton("Send watch update").apply {
            setOnClickListener { sendWearUpdateToWatch() }
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun sectionTitle(textValue: String): TextView = TextView(this).apply {
        text = textValue
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        setPadding(0, 22, 0, 8)
    }

    private fun cardText(textValue: String): TextView = TextView(this).apply {
        val dp = resources.displayMetrics.density
        fun Int.dp(): Int = (this * dp).toInt()
        text = textValue
        textSize = 14f
        setTextColor(Color.rgb(226, 232, 240))
        setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
        background = round(Color.rgb(15, 23, 42), 18.dp())
    }

    private fun input(hintValue: String, numeric: Boolean = true): EditText = EditText(this).apply {
        val dp = resources.displayMetrics.density
        fun Int.dp(): Int = (this * dp).toInt()
        hint = hintValue
        inputType = if (numeric) android.text.InputType.TYPE_CLASS_NUMBER else android.text.InputType.TYPE_CLASS_TEXT
        textSize = 15f
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(148, 163, 184))
        setPadding(14.dp(), 6.dp(), 14.dp(), 6.dp())
        background = round(Color.rgb(15, 23, 42), 14.dp())
    }

    private fun primaryButton(textValue: String): Button = Button(this).apply {
        val dp = resources.displayMetrics.density
        fun Int.dp(): Int = (this * dp).toInt()
        text = textValue
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = round(Color.rgb(8, 145, 178), 16)
        minHeight = 46.dp()
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            48.dp()
        ).apply {
            setMargins(0, 6.dp(), 0, 6.dp())
        }
    }

    private fun round(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    private fun receiveEcgDataItem(dataItem: DataItem) {
        io.execute {
            runCatching {
                val map = DataMapItem.fromDataItem(dataItem).dataMap
                val metadata = HealthJson.ecgFromJson(map.getString("metadata") ?: return@execute)
                val asset = map.getAsset("samples") ?: return@execute
                val bytes = readAsset(asset) ?: return@execute
                val samples = EcgBinaryCodec.decode(bytes)
                store.saveEcgSession(metadata, samples)
                selectedSession = metadata
                selectedSamples = samples
                val analysis = EcgAnalyzer.analyze(samples, metadata.sampleRateHz, metadata.startedAtEpochMillis)
                runOnUiThread {
                    status.text = "Received ECG ${metadata.sampleCount} samples."
                    ecgAnalysis.text = analysisText(analysis)
                    refresh()
                }
            }.onFailure {
                runOnUiThread { status.text = "ECG receive failed: ${it.message ?: it.javaClass.simpleName}" }
            }
        }
    }

    private fun receiveWatchLog(event: DataEvent) {
        runCatching {
            val raw = DataMapItem.fromDataItem(event.dataItem).dataMap.getString("entry") ?: return
            val entry = runCatching { HealthJson.watchLogFromJson(raw) }.getOrNull() ?: return
            saveLog(entry)
            runOnUiThread {
                logs.text = readLogs()
                status.text = "Watch log received: ${entry.message}"
            }
        }.onFailure {
            runOnUiThread { status.text = "Watch log receive failed: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    private fun receiveHermesSnapshot(dataItem: DataItem) {
        runCatching {
            val raw = DataMapItem.fromDataItem(dataItem).dataMap.getString("payload") ?: return
            getSharedPreferences("hermes", MODE_PRIVATE)
                .edit()
                .putString("latest_watch_snapshot", raw)
                .putLong("latest_watch_snapshot_at", System.currentTimeMillis())
                .apply()
            runOnUiThread {
                if (::hermesStatus.isInitialized) hermesStatus.text = "Watch snapshot received. Ready to send Hermes packet."
                status.text = "Hermes watch snapshot received."
            }
        }.onFailure {
            runOnUiThread { status.text = "Hermes snapshot receive failed: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    private fun readAsset(asset: Asset): ByteArray? = runCatching {
        val response = Tasks.await(Wearable.getDataClient(this).getFdForAsset(asset))
        response.inputStream.use { it.readBytes() }
    }.getOrNull()

    private fun handlePpgMetrics(raw: String) {
        val metrics = runCatching { HealthJson.ppgMetricsFromJson(raw) }.getOrNull() ?: return
        val estimate = BpEstimator.estimate(store.readCalibration(), metrics)
        runOnUiThread {
            bp.text = if (estimate == null) {
                "BP estimate blocked: save valid cuff calibration first."
            } else {
                "BP estimate: ${estimate.systolic}/${estimate.diastolic} mmHg, pulse ${estimate.pulse}, confidence ${"%.2f".format(estimate.confidence)}. Research only."
            }
        }
    }

    private fun refresh() {
        val calibration = store.readCalibration()
        val allSessions = store.listEcgSessions()
        if (selectedSession == null && allSessions.isNotEmpty()) {
            selectedSession = allSessions.first()
            selectedSamples = store.readEcgSamples(allSessions.first())
        }
        chart.samples = selectedSamples
        if (::ecgAnalysis.isInitialized && selectedSession != null && selectedSamples.isNotEmpty()) {
            ecgAnalysis.text = analysisText(EcgAnalyzer.analyze(selectedSamples, selectedSession!!.sampleRateHz, selectedSession!!.startedAtEpochMillis))
        }
        bp.text = if (calibration == null) {
            "No cuff calibration saved."
        } else {
            "Calibration: ${calibration.systolic}/${calibration.diastolic}, pulse ${calibration.pulse}, valid until ${java.util.Date(calibration.validUntilEpochMillis)}."
        }
        sessions.text = allSessions.joinToString(separator = "\n") {
            "${java.util.Date(it.startedAtEpochMillis)} | ${it.sampleCount} samples | lead-off ${it.leadOffSamples}"
        }.ifBlank { "No ECG sessions yet." }
    }

    private fun exportSelected() {
        val session = selectedSession ?: return
        val samples = selectedSamples
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        val csv = File(dir, "ecg_${session.id}.csv")
        csv.writeText(CsvExport.ecg(session, samples))
        val pdf = File(dir, "ecg_${session.id}.pdf")
        writePdf(pdf, session, samples)
        status.text = "Exported ${csv.absolutePath} and ${pdf.absolutePath}"
    }

    private fun writePdf(file: File, session: EcgSessionMetadata, samples: FloatArray) {
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas = page.canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 16f }
        canvas.drawText("Watch7 ECG session", 40f, 50f, paint)
        canvas.drawText("Research/wellness only. Not diagnostic.", 40f, 75f, paint)
        canvas.drawText("Samples: ${session.sampleCount}, lead-off: ${session.leadOffSamples}", 40f, 100f, paint)
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(11, 122, 117); strokeWidth = 1.5f }
        val top = 140f
        val height = 220f
        val stride = maxOf(1, samples.size / 500)
        var previousX = 40f
        var previousY = top + height / 2
        for ((point, sampleIndex) in (samples.indices step stride).withIndex()) {
            val x = 40f + point / 500f * 515f
            val y = top + height / 2 - samples[sampleIndex].coerceIn(-2f, 2f) * 45f
            canvas.drawLine(previousX, previousY, x, y, linePaint)
            previousX = x
            previousY = y
        }
        document.finishPage(page)
        file.outputStream().use { document.writeTo(it) }
        document.close()
    }

    private fun analysisText(analysis: com.galaxywatch7.health.shared.EcgAnalysis): String {
        val hr = analysis.heartRateBpm?.let { "$it bpm" } ?: "n/a"
        val rr = analysis.meanRrMillis?.let { "$it ms" } ?: "n/a"
        val peaks = analysis.rPeaks.size
        val quality = "%.2f".format(analysis.signalQuality)
        return "Raw ECG analysis\nHR: $hr\nR-peaks: $peaks\nMean RR: $rr\nQuality: $quality\n${analysis.status}\nResearch only."
    }

    private fun checkForUpdate() {
        updateStatus.text = "Checking GitHub releases..."
        io.execute {
            val result = GitHubReleaseUpdater.check(BuildConfig.VERSION_NAME, "mobile")
            updateCheck = result
            updateApk = result.latestVersion?.let { GitHubReleaseUpdater.findDownloadedApk(this, it, "mobile") }
            runOnUiThread {
                val notes = if (result.releaseNotes.isBlank()) "" else "\n\nNovita':\n${result.releaseNotes}"
                val asset = result.assetName?.let { "\nAsset: $it" } ?: ""
                val downloaded = if (updateApk != null) "\nAPK gia' scaricato." else ""
                updateStatus.text = result.message + asset + downloaded + notes
            }
        }
    }

    private fun downloadUpdate() {
        val result = updateCheck ?: run {
            updateStatus.text = "Controlla release prima di scaricare."
            return
        }
        val assetUrl = result.assetUrl ?: run {
            updateStatus.text = "Nessun asset mobile APK nella release."
            return
        }
        val version = result.latestVersion ?: return
        updateStatus.text = "Download update mobile..."
        io.execute {
            val file = GitHubReleaseUpdater.downloadApk(this, assetUrl, version, "mobile") { progress ->
                runOnUiThread {
                    updateStatus.text = "${progress.message}\n${progress.sizeLabel}"
                }
            }
            updateApk = file
            runOnUiThread {
                updateStatus.text = if (file == null) {
                    "Download update fallito."
                } else {
                    "Download completato: ${file.name}. Premi Install update."
                }
            }
        }
    }

    private fun installUpdate() {
        val file = updateApk ?: updateCheck?.latestVersion?.let { GitHubReleaseUpdater.findDownloadedApk(this, it, "mobile") }
        updateStatus.text = if (file == null) {
            "APK update non trovato. Scaricalo prima."
        } else {
            GitHubReleaseUpdater.installDownloadedApk(this, file)
        }
    }

    private fun downloadWearUpdateOnPhone() {
        updateStatus.text = "Checking wear release..."
        io.execute {
            val result = GitHubReleaseUpdater.check(BuildConfig.VERSION_NAME, "wear")
            wearUpdateCheck = result
            val assetUrl = result.assetUrl
            val version = result.latestVersion
            if (assetUrl == null || version == null) {
                runOnUiThread { updateStatus.text = result.message + "\nNessun APK watch da scaricare." }
                return@execute
            }
            runOnUiThread { updateStatus.text = "Telefono scarica APK watch $version..." }
            val file = GitHubReleaseUpdater.downloadApk(this, assetUrl, version, "wear") { progress ->
                runOnUiThread {
                    updateStatus.text = "Watch APK via phone\n${progress.message}\n${progress.sizeLabel}"
                }
            }
            wearUpdateApk = file
            runOnUiThread {
                updateStatus.text = if (file == null) {
                    "Download APK watch fallito."
                } else {
                    "APK watch scaricato sul telefono: ${file.name}. Premi Send watch update."
                }
            }
        }
    }

    private fun sendWearUpdateToWatch() {
        val version = wearUpdateCheck?.latestVersion
        val file = wearUpdateApk ?: version?.let { GitHubReleaseUpdater.findDownloadedApk(this, it, "wear") }
        if (version == null || file == null || !file.exists()) {
            updateStatus.text = "APK watch non trovato. Premi prima Download watch update."
            return
        }
        updateStatus.text = "Invio APK watch via Data Layer..."
        io.execute {
            runCatching {
                val request = PutDataMapRequest.create("${WearPaths.DATA_WEAR_UPDATE_PREFIX}/$version/${System.currentTimeMillis()}")
                request.dataMap.putString("version", version)
                request.dataMap.putString("fileName", file.name)
                request.dataMap.putLong("size", file.length())
                request.dataMap.putAsset("apk", Asset.createFromBytes(file.readBytes()))
                Tasks.await(Wearable.getDataClient(this).putDataItem(request.asPutDataRequest().setUrgent()))
                runOnUiThread { updateStatus.text = "APK watch inviato. Sul watch premi Install quando compare ricevuto." }
            }.onFailure {
                runOnUiThread { updateStatus.text = "Invio APK watch fallito: ${it.message ?: it.javaClass.simpleName}" }
            }
        }
    }

    private fun saveHermesConfig() {
        getSharedPreferences("hermes", MODE_PRIVATE)
            .edit()
            .putString("url", hermesUrl.text.toString().trim())
            .putString("token", hermesToken.text.toString().trim())
            .apply()
        hermesStatus.text = "Hermes config saved."
    }

    private fun sendHermesPacket() {
        saveHermesConfig()
        val url = hermesUrl.text.toString().trim()
        if (url.isBlank()) {
            hermesStatus.text = "Hermes URL missing."
            return
        }
        hermesStatus.text = "Sending Hermes packet..."
        io.execute {
            val payload = buildHermesPayload()
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "Watch7HealthHermes")
                    val token = getSharedPreferences("hermes", MODE_PRIVATE).getString("token", "").orEmpty()
                    if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                }
                connection.outputStream.use { it.write(payload.toString().toByteArray()) }
                val code = connection.responseCode
                val responseText = runCatching {
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    stream?.bufferedReader()?.use { it.readText().take(500) }.orEmpty()
                }.getOrDefault("")
                connection.disconnect()
                val message = "Hermes POST $code ${responseText.ifBlank { "" }}".trim()
                getSharedPreferences("hermes", MODE_PRIVATE)
                    .edit()
                    .putString("last_status", message)
                    .putString("last_payload", payload.toString())
                    .apply()
                runOnUiThread { hermesStatus.text = message }
            }.onFailure {
                val message = "Hermes send failed: ${it.message ?: it.javaClass.simpleName}"
                getSharedPreferences("hermes", MODE_PRIVATE)
                    .edit()
                    .putString("last_status", message)
                    .putString("last_payload", payload.toString())
                    .apply()
                runOnUiThread { hermesStatus.text = message }
            }
        }
    }

    private fun buildHermesPayload(): JSONObject {
        val prefs = getSharedPreferences("hermes", MODE_PRIVATE)
        val calibration = store.readCalibration()
        val sessionArray = JSONArray()
        store.listEcgSessions().take(10).forEach { session ->
            sessionArray.put(
                JSONObject()
                    .put("id", session.id)
                    .put("startedAtEpochMillis", session.startedAtEpochMillis)
                    .put("endedAtEpochMillis", session.endedAtEpochMillis)
                    .put("sampleRateHz", session.sampleRateHz)
                    .put("sampleCount", session.sampleCount)
                    .put("deviceModel", session.deviceModel)
                    .put("wellnessOnly", session.wellnessOnly)
            )
        }
        val logArray = JSONArray()
        readLogs().lines().filter { it.isNotBlank() }.take(40).forEach { logArray.put(it) }
        val watchSnapshot = prefs.getString("latest_watch_snapshot", "").orEmpty()
        return JSONObject()
            .put("schema", "hermes.health.packet.v1")
            .put("createdAtEpochMillis", System.currentTimeMillis())
            .put("source", "phone")
            .put("packageName", packageName)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put(
                "device",
                JSONObject()
                    .put("manufacturer", android.os.Build.MANUFACTURER)
                    .put("model", android.os.Build.MODEL)
                    .put("sdk", android.os.Build.VERSION.SDK_INT)
            )
            .put("watchSnapshot", if (watchSnapshot.isBlank()) JSONObject.NULL else JSONObject(watchSnapshot))
            .put("bpCalibration", calibration?.let { JSONObject(HealthJson.calibrationToJson(it)) } ?: JSONObject.NULL)
            .put("ecgSessions", sessionArray)
            .put("watchLogs", logArray)
            .put("blockedSources", JSONArray().put("ecg_raw_samsung_policy").put("bp_official_samsung_policy"))
            .put("notes", "Wellness/research data only. Not diagnostic.")
    }

    private fun refreshLinkStatus() {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                linkStatus.text = if (nodes.isEmpty()) {
                    "Watch link: offline. Data Layer queue will sync when connected."
                } else {
                    "Watch link: connected (${nodes.joinToString { it.displayName }})"
                }
            }
            .addOnFailureListener { linkStatus.text = "Watch link check failed: ${it.message}" }
    }

    private fun loadExistingWearData() {
        io.execute {
            runCatching {
                val buffer = Tasks.await(Wearable.getDataClient(this).dataItems)
                try {
                    buffer.forEach { item ->
                        val path = item.uri.path.orEmpty()
                        when {
                            path.startsWith(WearPaths.DATA_ECG_SESSION_PREFIX) -> receiveEcgDataItem(item.freeze())
                            path.startsWith(WearPaths.DATA_HERMES_SNAPSHOT_PREFIX) -> receiveHermesSnapshot(item.freeze())
                            path.startsWith(WearPaths.DATA_WATCH_LOG_PREFIX) -> {
                                val raw = DataMapItem.fromDataItem(item).dataMap.getString("entry")
                                val entry = raw?.let { json -> runCatching { HealthJson.watchLogFromJson(json) }.getOrNull() }
                                if (entry != null) saveLog(entry)
                            }
                        }
                    }
                } finally {
                    buffer.release()
                }
                runOnUiThread { logs.text = readLogs().ifBlank { "No watch logs yet." } }
            }
        }
    }

    private fun saveLog(entry: WatchLogEntry) {
        val prefs = getSharedPreferences("watch_logs", MODE_PRIVATE)
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(entry.timestampEpochMillis))
        val line = "$time [${entry.level}] ${entry.source}: ${entry.message}"
        val existing = prefs.getString("lines", "").orEmpty().lines().filter { it.isNotBlank() }
        val next = (listOf(line) + existing.filterNot { it.contains(entry.id) }).take(80)
        prefs.edit().putString("lines", next.joinToString("\n")).apply()
    }

    private fun readLogs(): String = getSharedPreferences("watch_logs", MODE_PRIVATE)
        .getString("lines", "")
        .orEmpty()

    private fun clearWatchLogDataItems() {
        io.execute {
            runCatching {
                val buffer = Tasks.await(Wearable.getDataClient(this).dataItems)
                try {
                    buffer.forEach { item ->
                        if (item.uri.path.orEmpty().startsWith(WearPaths.DATA_WATCH_LOG_PREFIX)) {
                            Tasks.await(Wearable.getDataClient(this).deleteDataItems(item.uri))
                        }
                    }
                } finally {
                    buffer.release()
                }
                runOnUiThread {
                    logs.text = "No watch logs yet."
                    status.text = "Watch logs cleared from phone and Wear Data Layer."
                }
            }.onFailure {
                runOnUiThread {
                    status.text = "Clear logs failed: ${it.message ?: it.javaClass.simpleName}"
                }
            }
        }
    }
}
