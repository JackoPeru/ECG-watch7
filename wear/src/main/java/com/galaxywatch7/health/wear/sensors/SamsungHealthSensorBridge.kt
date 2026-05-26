package com.galaxywatch7.health.wear.sensors

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.galaxywatch7.health.shared.EcgSessionMetadata
import com.galaxywatch7.health.shared.PpgMetrics
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

class SamsungHealthSensorBridge(private val context: Context) {
    interface Listener {
        fun onStatus(message: String)
        fun onEcgProgress(secondsLeft: Int, sampleCount: Int, leadOffSamples: Int)
        fun onEcgComplete(metadata: EcgSessionMetadata, samples: FloatArray)
        fun onPpgMetrics(metrics: PpgMetrics)
    }

    private val main = Handler(Looper.getMainLooper())
    private var service: Any? = null
    private var ecgTracker: Any? = null
    private var hrTracker: Any? = null
    private var activeListener: Any? = null
    private var androidHrListener: SensorEventListener? = null
    private val hrValues = CopyOnWriteArrayList<Int>()

    fun isSdkPresent(): Boolean = runCatching {
        Class.forName("com.samsung.android.service.health.tracking.HealthTrackingService")
    }.isSuccess

    fun connect(listener: Listener) {
        if (!isSdkPresent()) {
            listener.onStatus("ECG blocked: watch APK missing Samsung sensor SDK AAR. Phone app already replaces Samsung phone companion, but ECG electrodes must be read on watch.")
            return
        }
        runCatching {
            val connectionClass = Class.forName("com.samsung.android.service.health.tracking.ConnectionListener")
            val serviceClass = Class.forName("com.samsung.android.service.health.tracking.HealthTrackingService")
            val proxy = Proxy.newProxyInstance(
                connectionClass.classLoader,
                arrayOf(connectionClass)
            ) { _, method, args ->
                when (method.name) {
                    "onConnectionSuccess" -> {
                        listener.onStatus("Watch sensor service ready. Trackers: ${supportedTrackerNames().joinToString()}")
                    }
                    "onConnectionFailed" -> {
                        handleConnectionFailure(args?.firstOrNull(), listener)
                    }
                    "onConnectionEnded" -> listener.onStatus("Watch sensor service ended.")
                }
                null
            }
            service = serviceClass.getConstructor(connectionClass, Context::class.java).newInstance(proxy, context)
            serviceClass.getMethod("connectService").invoke(service)
            listener.onStatus("Connecting watch sensor service...")
        }.onFailure {
            listener.onStatus("SDK connect error: ${it.message}")
        }
    }

    fun openHealthPlatformSettings(listener: Listener) {
        runCatching {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:com.samsung.android.service.health")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            listener.onStatus("Opened Health Platform app settings. If visible, use Settings > Apps > Health Platform and tap title 10 times for [Dev mode].")
        }.onFailure {
            listener.onStatus("Cannot open Health Platform settings: ${it.message}")
        }
    }

    fun disconnect() {
        unregisterAndroidHrFallback()
        runCatching { service?.javaClass?.getMethod("disconnectService")?.invoke(service) }
    }

    fun startEcg(listener: Listener, durationMillis: Long = 30_000L) {
        val connectedService = service ?: run {
            listener.onStatus("ECG blocked: watch sensor service not connected. Phone app can store/manage ECG, but cannot read watch electrodes directly.")
            return
        }
        runCatching {
            val trackerType = enumValue("com.samsung.android.service.health.tracking.data.HealthTrackerType", "ECG_ON_DEMAND")
            ecgTracker = connectedService.javaClass
                .getMethod("getHealthTracker", trackerType.javaClass)
                .invoke(connectedService, trackerType)

            val ecgSet = Class.forName("com.samsung.android.service.health.tracking.data.ValueKey\$EcgSet")
            val ecgKey = ecgSet.getField("ECG_MV").get(null)
            val leadOffKey = ecgSet.getField("LEAD_OFF").get(null)
            val eventClass = Class.forName("com.samsung.android.service.health.tracking.HealthTracker\$TrackerEventListener")
            val samples = ArrayList<Float>(16_000)
            val startedAt = System.currentTimeMillis()
            var leadOffSamples = 0
            var lastProgressSecond = -1
            var firstDataLogged = false
            var stopped = false
            var finishRunnable: Runnable? = null
            val proxy = Proxy.newProxyInstance(eventClass.classLoader, arrayOf(eventClass)) { _, method, args ->
                when (method.name) {
                    "onDataReceived" -> {
                        if (stopped) return@newProxyInstance null
                        val points = args?.firstOrNull() as? List<*> ?: emptyList<Any>()
                        points.forEach { point ->
                            if (point != null) {
                                val lead = readValue(point, leadOffKey) as? Number
                                if (lead?.toInt() == 5) {
                                    leadOffSamples++
                                } else {
                                    val value = readValue(point, ecgKey) as? Number
                                    if (value != null) samples.add(value.toFloat())
                                }
                            }
                        }
                        if (!firstDataLogged && (samples.isNotEmpty() || leadOffSamples > 0)) {
                            firstDataLogged = true
                            listener.onStatus("ECG recording active. Keep still, finger on top button.")
                        }
                        val elapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000L).toInt()
                        val secondsLeft = (durationMillis / 1000L).toInt() - elapsedSeconds
                        if (secondsLeft != lastProgressSecond) {
                            lastProgressSecond = secondsLeft
                            listener.onEcgProgress(secondsLeft.coerceAtLeast(0), samples.size, leadOffSamples)
                        }
                    }
                    "onError" -> {
                        if (!stopped) {
                            stopped = true
                            finishRunnable?.let { main.removeCallbacks(it) }
                            runCatching { ecgTracker?.javaClass?.getMethod("unsetEventListener")?.invoke(ecgTracker) }
                            listener.onStatus(
                                "ECG blocked by Samsung policy/service: ${args?.firstOrNull()}. " +
                                    "No public Android ECG fallback exists; enable Health Platform Dev mode or register package/signature with Samsung."
                            )
                        }
                    }
                    "onFlushCompleted" -> listener.onStatus("ECG tracker flush complete.")
                }
                null
            }
            activeListener = proxy
            ecgTracker?.javaClass?.getMethod("setEventListener", eventClass)?.invoke(ecgTracker, proxy)
            listener.onStatus("ECG request sent. Waiting first raw samples...")
            finishRunnable = Runnable {
                if (stopped) return@Runnable
                stopped = true
                runCatching { ecgTracker?.javaClass?.getMethod("unsetEventListener")?.invoke(ecgTracker) }
                if (samples.isEmpty()) {
                    listener.onStatus("ECG ended with 0 samples. Session not saved.")
                    return@Runnable
                }
                val endedAt = System.currentTimeMillis()
                val metadata = EcgSessionMetadata(
                    id = UUID.randomUUID().toString(),
                    startedAtEpochMillis = startedAt,
                    endedAtEpochMillis = endedAt,
                    sampleCount = samples.size,
                    leadOffSamples = leadOffSamples,
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                    sampleFileName = "ecg_${startedAt}.bin"
                )
                listener.onEcgComplete(metadata, samples.toFloatArray())
            }
            main.postDelayed(finishRunnable!!, durationMillis)
        }.onFailure {
            listener.onStatus("ECG start failed: ${it.message}")
        }
    }

    fun startBpResearchCapture(listener: Listener, durationMillis: Long = 20_000L) {
        val connectedService = service ?: run {
            listener.onStatus("BP capture unavailable: watch sensor service not connected.")
            return
        }
        runCatching {
            val trackerType = enumValue("com.samsung.android.service.health.tracking.data.HealthTrackerType", "HEART_RATE_CONTINUOUS")
            hrTracker = connectedService.javaClass
                .getMethod("getHealthTracker", trackerType.javaClass)
                .invoke(connectedService, trackerType)
            val hrSet = Class.forName("com.samsung.android.service.health.tracking.data.ValueKey\$HeartRateSet")
            val hrKey = hrSet.getField("HEART_RATE").get(null)
            val eventClass = Class.forName("com.samsung.android.service.health.tracking.HealthTracker\$TrackerEventListener")
            hrValues.clear()
            var failed = false
            var firstDataLogged = false
            var finishRunnable: Runnable? = null
            val proxy = Proxy.newProxyInstance(eventClass.classLoader, arrayOf(eventClass)) { _, method, args ->
                when (method.name) {
                    "onDataReceived" -> {
                        if (failed) return@newProxyInstance null
                        val points = args?.firstOrNull() as? List<*> ?: emptyList<Any>()
                        points.forEach { point ->
                            val value = if (point == null) null else readValue(point, hrKey) as? Number
                            if (value != null && value.toInt() in 30..220) hrValues.add(value.toInt())
                        }
                        if (!firstDataLogged && hrValues.isNotEmpty()) {
                            firstDataLogged = true
                            listener.onStatus("BP research capture active via Samsung HR tracker.")
                        }
                    }
                    "onError" -> {
                        if (!failed) {
                            failed = true
                            finishRunnable?.let { main.removeCallbacks(it) }
                            runCatching { hrTracker?.javaClass?.getMethod("unsetEventListener")?.invoke(hrTracker) }
                            listener.onStatus("HR tracker blocked by Samsung policy/service: ${args?.firstOrNull()}. Trying Android heart-rate fallback.")
                            main.post { startAndroidHeartRateFallback(listener, durationMillis) }
                        }
                    }
                }
                null
            }
            hrTracker?.javaClass?.getMethod("setEventListener", eventClass)?.invoke(hrTracker, proxy)
            listener.onStatus("BP research request sent. Waiting HR samples...")
            finishRunnable = Runnable {
                if (failed) return@Runnable
                runCatching { hrTracker?.javaClass?.getMethod("unsetEventListener")?.invoke(hrTracker) }
                val pulse = hrValues.average().takeIf { !it.isNaN() }?.roundToInt()
                if (pulse == null) {
                    listener.onStatus("No valid HR/PPG metrics captured.")
                } else {
                    val quality = (hrValues.size / 20f).coerceIn(0.05f, 0.9f)
                    listener.onPpgMetrics(
                        PpgMetrics(
                            pulse = pulse,
                            signalQuality = quality,
                            capturedAtEpochMillis = System.currentTimeMillis()
                        )
                    )
                }
            }
            main.postDelayed(finishRunnable!!, durationMillis)
        }.onFailure {
            listener.onStatus("BP capture failed: ${it.message}")
        }
    }

    private fun startAndroidHeartRateFallback(listener: Listener, durationMillis: Long) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val heartRateSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (sensorManager == null || heartRateSensor == null) {
            listener.onStatus("Android HR fallback unavailable: no public heart-rate sensor exposed.")
            return
        }

        val values = CopyOnWriteArrayList<Int>()
        unregisterAndroidHrFallback()
        val fallbackListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val value = event.values.firstOrNull()?.roundToInt() ?: return
                if (value in 30..220) values.add(value)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        androidHrListener = fallbackListener
        val registered = sensorManager.registerListener(
            fallbackListener,
            heartRateSensor,
            SensorManager.SENSOR_DELAY_NORMAL
        )
        if (!registered) {
            androidHrListener = null
            listener.onStatus("Android HR fallback unavailable: sensor listener rejected.")
            return
        }

        listener.onStatus("Android HR fallback active. BP remains research-only; no raw ECG/PPG fallback.")
        main.postDelayed({
            unregisterAndroidHrFallback()
            val pulse = values.average().takeIf { !it.isNaN() }?.roundToInt()
            if (pulse == null) {
                listener.onStatus("Android HR fallback captured no valid pulse.")
            } else {
                listener.onPpgMetrics(
                    PpgMetrics(
                        pulse = pulse,
                        signalQuality = (values.size / 20f).coerceIn(0.05f, 0.75f),
                        capturedAtEpochMillis = System.currentTimeMillis()
                    )
                )
            }
        }, durationMillis)
    }

    private fun unregisterAndroidHrFallback() {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        androidHrListener?.let { sensorManager?.unregisterListener(it) }
        androidHrListener = null
    }

    private fun supportedTrackerNames(): List<String> = runCatching {
        val capability = service?.javaClass?.getMethod("getTrackingCapability")?.invoke(service) ?: return emptyList()
        val trackers = capability.javaClass.getMethod("getSupportHealthTrackerTypes").invoke(capability) as? List<*>
        trackers.orEmpty().map { it.toString() }
    }.getOrDefault(emptyList())

    private fun handleConnectionFailure(error: Any?, listener: Listener) {
        val message = error?.toString().orEmpty()
        val code = runCatching { error?.javaClass?.getMethod("getErrorCode")?.invoke(error) as? Int }.getOrNull()
        val hasResolution = runCatching { error?.javaClass?.getMethod("hasResolution")?.invoke(error) as? Boolean }.getOrDefault(false)
        if (hasResolution == true && context is Activity) {
            runCatching {
                error?.javaClass?.getMethod("resolve", Activity::class.java)?.invoke(error, context)
                listener.onStatus("Watch sensor service needs install/update. Opened Samsung resolution screen.")
                return
            }.onFailure {
                listener.onStatus("Sensor service resolution failed: ${it.message}")
            }
        }

        val policyHint = if (message.contains("POLICY", ignoreCase = true) || message.contains("SDK_POLICY", ignoreCase = true) || code == -1) {
            " SDK policy blocked app. Android Developer options is not enough: enable Health Platform Dev mode: Watch Settings > Apps > Health Platform > tap title about 10 times until [Dev mode]."
        } else {
            ""
        }
        listener.onStatus("Watch sensor service failed: ${message.ifBlank { "error code $code" }}.$policyHint")
    }

    private fun enumValue(className: String, name: String): Any {
        val clazz = Class.forName(className)
        return clazz.getMethod("valueOf", String::class.java).invoke(null, name)
    }

    private fun readValue(point: Any, key: Any): Any? {
        val method = point.javaClass.methods.firstOrNull { it.name == "getValue" && it.parameterTypes.size == 1 }
        return method?.invoke(point, key)
    }
}
