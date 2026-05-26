package com.galaxywatch7.health.mobile

import android.app.Activity
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.Environment
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
import com.galaxywatch7.health.shared.EcgBinaryCodec
import com.galaxywatch7.health.shared.EcgSessionMetadata
import com.galaxywatch7.health.shared.HealthJson
import com.galaxywatch7.health.shared.PpgMetrics
import com.galaxywatch7.health.shared.WearPaths
import com.galaxywatch7.health.shared.updates.GitHubReleaseUpdater
import com.galaxywatch7.health.shared.updates.UpdateCheckResult
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity(), DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener {
    private lateinit var store: SecureHealthStore
    private lateinit var status: TextView
    private lateinit var sessions: TextView
    private lateinit var bp: TextView
    private lateinit var updateStatus: TextView
    private lateinit var chart: EcgChartView
    private val io = Executors.newSingleThreadExecutor()
    private var selectedSession: EcgSessionMetadata? = null
    private var selectedSamples: FloatArray = FloatArray(0)
    private var updateCheck: UpdateCheckResult? = null
    private var updateApk: File? = null

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
    }

    override fun onPause() {
        Wearable.getDataClient(this).removeListener(this)
        Wearable.getMessageClient(this).removeListener(this)
        super.onPause()
    }

    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED &&
                event.dataItem.uri.path?.startsWith(WearPaths.DATA_ECG_SESSION_PREFIX) == true
            ) {
                receiveEcgSession(event)
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }
        fun label(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.rgb(35, 39, 45))
            setPadding(0, 12, 0, 8)
        }

        root.addView(TextView(this).apply {
            text = "Watch7 Health"
            textSize = 26f
            setTextColor(Color.rgb(11, 122, 117))
        })
        root.addView(label("Wellness/research only. Not for diagnosis or treatment."))
        status = label("Waiting for watch data.")
        root.addView(status)

        val systolic = EditText(this).apply { hint = "Cuff systolic"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val diastolic = EditText(this).apply { hint = "Cuff diastolic"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val pulse = EditText(this).apply { hint = "Cuff pulse"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val wrist = EditText(this).apply { hint = "Watch wrist: left/right"; setText("left") }
        root.addView(systolic)
        root.addView(diastolic)
        root.addView(pulse)
        root.addView(wrist)
        root.addView(Button(this).apply {
            text = "Save cuff calibration"
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 420)
        }
        root.addView(chart)
        sessions = label("")
        root.addView(sessions)
        root.addView(Button(this).apply {
            text = "Export selected CSV/PDF"
            setOnClickListener { exportSelected() }
        })
        root.addView(label("Updates"))
        updateStatus = label("Version ${BuildConfig.VERSION_NAME}. Release GitHub non controllata.")
        root.addView(updateStatus)
        root.addView(Button(this).apply {
            text = "Check GitHub release"
            setOnClickListener { checkForUpdate() }
        })
        root.addView(Button(this).apply {
            text = "Download update"
            setOnClickListener { downloadUpdate() }
        })
        root.addView(Button(this).apply {
            text = "Install update"
            setOnClickListener { installUpdate() }
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun receiveEcgSession(event: DataEvent) {
        io.execute {
            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            val metadata = HealthJson.ecgFromJson(map.getString("metadata") ?: return@execute)
            val asset = map.getAsset("samples") ?: return@execute
            val bytes = readAsset(asset) ?: return@execute
            val samples = EcgBinaryCodec.decode(bytes)
            store.saveEcgSession(metadata, samples)
            selectedSession = metadata
            selectedSamples = samples
            runOnUiThread {
                status.text = "Received ECG ${metadata.sampleCount} samples."
                refresh()
            }
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
}
