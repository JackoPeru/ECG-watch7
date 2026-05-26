package com.galaxywatch7.health.wear.sensors

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
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
                        listener.onStatus("Watch sensor service failed: ${args?.firstOrNull()}")
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

    fun disconnect() {
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
            val proxy = Proxy.newProxyInstance(eventClass.classLoader, arrayOf(eventClass)) { _, method, args ->
                when (method.name) {
                    "onDataReceived" -> {
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
                        val elapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000L).toInt()
                        val secondsLeft = (durationMillis / 1000L).toInt() - elapsedSeconds
                        if (secondsLeft != lastProgressSecond) {
                            lastProgressSecond = secondsLeft
                            listener.onEcgProgress(secondsLeft.coerceAtLeast(0), samples.size, leadOffSamples)
                        }
                    }
                    "onError" -> listener.onStatus("ECG tracker error: ${args?.firstOrNull()}")
                    "onFlushCompleted" -> listener.onStatus("ECG tracker flush complete.")
                }
                null
            }
            activeListener = proxy
            ecgTracker?.javaClass?.getMethod("setEventListener", eventClass)?.invoke(ecgTracker, proxy)
            listener.onStatus("ECG recording started.")
            main.postDelayed({
                runCatching { ecgTracker?.javaClass?.getMethod("unsetEventListener")?.invoke(ecgTracker) }
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
            }, durationMillis)
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
            val proxy = Proxy.newProxyInstance(eventClass.classLoader, arrayOf(eventClass)) { _, method, args ->
                when (method.name) {
                    "onDataReceived" -> {
                        val points = args?.firstOrNull() as? List<*> ?: emptyList<Any>()
                        points.forEach { point ->
                            val value = if (point == null) null else readValue(point, hrKey) as? Number
                            if (value != null && value.toInt() in 30..220) hrValues.add(value.toInt())
                        }
                    }
                    "onError" -> listener.onStatus("HR tracker error: ${args?.firstOrNull()}")
                }
                null
            }
            hrTracker?.javaClass?.getMethod("setEventListener", eventClass)?.invoke(hrTracker, proxy)
            listener.onStatus("BP research capture started.")
            main.postDelayed({
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
            }, durationMillis)
        }.onFailure {
            listener.onStatus("BP capture failed: ${it.message}")
        }
    }

    private fun supportedTrackerNames(): List<String> = runCatching {
        val capability = service?.javaClass?.getMethod("getTrackingCapability")?.invoke(service) ?: return emptyList()
        val trackers = capability.javaClass.getMethod("getSupportHealthTrackerTypes").invoke(capability) as? List<*>
        trackers.orEmpty().map { it.toString() }
    }.getOrDefault(emptyList())

    private fun enumValue(className: String, name: String): Any {
        val clazz = Class.forName(className)
        return clazz.getMethod("valueOf", String::class.java).invoke(null, name)
    }

    private fun readValue(point: Any, key: Any): Any? {
        val method = point.javaClass.methods.firstOrNull { it.name == "getValue" && it.parameterTypes.size == 1 }
        return method?.invoke(point, key)
    }
}
