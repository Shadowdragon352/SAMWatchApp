package au.org.deliriumwatch.watch

import android.content.Context
import java.lang.reflect.Proxy

/**
 * Optional runtime bridge to Samsung Health Sensor SDK.
 *
 * The project intentionally does not redistribute Samsung's AAR. If the researcher
 * places samsung-health-sensor-api.aar in watch/libs, these classes are packaged and
 * this bridge activates at runtime. Otherwise the app uses Android sensor fallbacks.
 */
class SamsungHealthSensorBridge(
    private val context: Context,
    private val callback: Callback
) {
    interface Callback {
        fun onConnected()
        fun onStatus(message: String)
        fun onAcceleration(tsMs: Long, x: Double, y: Double, z: Double)
        fun onHeartRate(tsMs: Long, bpm: Int?, status: Int?, ibiMs: List<Int>, ibiStatus: List<Int>)
        fun onPpg(sample: PpgSample)
        fun onTemperature(tsMs: Long, skinC: Float?, ambientC: Float?, status: Int?)
    }

    private var service: Any? = null
    @Volatile private var generation = 0
    private val trackerListeners = mutableListOf<Pair<Any, Any>>()

    fun isBundled(): Boolean = try { Class.forName(SERVICE); true } catch (_: Throwable) { false }

    private var enablePpg = false
    private var allPpgWavelengths = false

    @Synchronized
    fun start(enablePpg: Boolean = false, allPpgWavelengths: Boolean = false) {
        stop()
        val current = generation
        this.enablePpg = enablePpg
        this.allPpgWavelengths = allPpgWavelengths
        if (!isBundled()) { callback.onStatus("Samsung SDK not bundled; Android sensor fallback active"); return }
        try {
            val listenerClass = Class.forName(CONNECTION_LISTENER)
            val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                    "equals" -> return@newProxyInstance proxy === args?.firstOrNull()
                    "toString" -> return@newProxyInstance "DeliriumWatchSamsungConnection"
                }
                if (current != generation) return@newProxyInstance null
                when (method.name) {
                    "onConnectionSuccess" -> {
                        callback.onConnected()
                        callback.onStatus("Samsung Health Sensor Service connected")
                        startTrackers()
                    }
                    "onConnectionEnded" -> callback.onStatus("Samsung Health Sensor Service disconnected")
                    "onConnectionFailed" -> callback.onStatus("Samsung Health Sensor connection failed: ${args?.firstOrNull()}")
                }
                null
            }
            val serviceClass = Class.forName(SERVICE)
            service = serviceClass.getConstructor(listenerClass, Context::class.java).newInstance(listener, context)
            serviceClass.getMethod("connectService").invoke(service)
        } catch (t: Throwable) {
            callback.onStatus("Samsung SDK unavailable: ${rootMessage(t)}")
        }
    }

    @Synchronized
    fun stop() {
        generation++
        trackerListeners.toList().forEach { (tracker, listener) ->
            try {
                val listenerClass = Class.forName(TRACKER_LISTENER)
                tracker.javaClass.getMethod("unsetEventListener").invoke(tracker)
            } catch (_: Throwable) { }
        }
        trackerListeners.clear()
        try { service?.javaClass?.getMethod("disconnectService")?.invoke(service) } catch (_: Throwable) { }
        service = null
    }

    @Synchronized
    private fun startTrackers() {
        if (WatchPermissions.heartRate(context)) startTracker("HEART_RATE_CONTINUOUS", ::handleHeartRate)
        if (enablePpg) startPpgTracker(::handlePpg)
        if (android.os.Build.VERSION.SDK_INT < 36 || WatchPermissions.granted(context, "android.permission.health.READ_SKIN_TEMPERATURE")) startTracker("SKIN_TEMPERATURE_CONTINUOUS", ::handleTemperature)
    }

    private fun startTracker(typeName: String, consumer: (List<*>) -> Unit) {
        val svc = service ?: return
        try {
            val typeClass = Class.forName(TRACKER_TYPE)
            val type = typeClass.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == typeName }
                ?: return callback.onStatus("Samsung tracker $typeName not present in this SDK")
            val getTracker = svc.javaClass.methods.first { it.name == "getHealthTracker" && it.parameterCount == 1 }
            val tracker = getTracker.invoke(svc, type) ?: return callback.onStatus("Samsung tracker unavailable: $typeName")
            attachListener(tracker, typeName, consumer)
        } catch (t: Throwable) {
            callback.onStatus("Samsung $typeName unavailable: ${rootMessage(t)}")
        }
    }

    /**
     * PPG_CONTINUOUS in Samsung Health Sensor SDK 1.3+ requires the two-argument
     * getHealthTracker(HealthTrackerType, Set<PpgType>) overload. Request all
     * three wavelengths; researchers can disable raw PPG storage separately.
     */
    private fun startPpgTracker(consumer: (List<*>) -> Unit) {
        val svc = service ?: return
        val typeName = "PPG_CONTINUOUS"
        try {
            val typeClass = Class.forName(TRACKER_TYPE)
            val type = typeClass.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == typeName }
                ?: return callback.onStatus("Samsung tracker $typeName not present in this SDK")
            val ppgTypeClass = Class.forName(PPG_TYPE)
            val requested = linkedSetOf<Any>()
            val wantedTypes = if (allPpgWavelengths) listOf("GREEN", "RED", "IR") else listOf("GREEN")
            wantedTypes.forEach { wanted ->
                ppgTypeClass.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == wanted }?.let(requested::add)
            }
            if (requested.isEmpty()) return callback.onStatus("Samsung PpgType values unavailable")
            val getTracker = svc.javaClass.methods.firstOrNull {
                it.name == "getHealthTracker" && it.parameterCount == 2 && java.util.Set::class.java.isAssignableFrom(it.parameterTypes[1])
            } ?: return callback.onStatus("Samsung PPG tracker overload unavailable")
            val tracker = getTracker.invoke(svc, type, requested) ?: return callback.onStatus("Samsung tracker unavailable: $typeName")
            attachListener(tracker, typeName, consumer)
        } catch (t: Throwable) {
            callback.onStatus("Samsung $typeName unavailable: ${rootMessage(t)}")
        }
    }

    private fun attachListener(tracker: Any, typeName: String, consumer: (List<*>) -> Unit) {
        val listenerClass = Class.forName(TRACKER_LISTENER)
        val current = generation
        val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                "equals" -> return@newProxyInstance proxy === args?.firstOrNull()
                "toString" -> return@newProxyInstance "DeliriumWatchSamsungTracker:$typeName"
            }
            if (current != generation) return@newProxyInstance null
            try { when (method.name) {
                "onDataReceived" -> (args?.firstOrNull() as? List<*>)?.let(consumer)
                "onError" -> callback.onStatus("Samsung $typeName tracker error: ${args?.firstOrNull()}")
                "onFlushCompleted" -> Unit
            }
            } catch (t: Throwable) { callback.onStatus("Samsung $typeName data error: ${rootMessage(t)}") }
            null
        }
        tracker.javaClass.getMethod("setEventListener", listenerClass).invoke(tracker, listener)
        trackerListeners += tracker to listener
        callback.onStatus("Samsung tracker active: $typeName")
    }

    private fun handleAccelerometer(points: List<*>) {
        val factor = 9.81 / (16383.75 / 4.0)
        points.filterNotNull().forEach { p ->
            val ts = timestamp(p)
            val x = (value(p, ACCEL_SET, "ACCELEROMETER_X") as? Number)?.toDouble()?.times(factor)
            val y = (value(p, ACCEL_SET, "ACCELEROMETER_Y") as? Number)?.toDouble()?.times(factor)
            val z = (value(p, ACCEL_SET, "ACCELEROMETER_Z") as? Number)?.toDouble()?.times(factor)
            if (x != null && y != null && z != null) callback.onAcceleration(ts, x, y, z)
        }
    }

    private fun handleHeartRate(points: List<*>) {
        points.filterNotNull().forEach { p ->
            val bpm = (value(p, HR_SET, "HEART_RATE") as? Number)?.toInt()
            val status = (value(p, HR_SET, "HEART_RATE_STATUS") as? Number)?.toInt()
            val ibi = (value(p, HR_SET, "IBI_LIST") as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
            val ibiStatus = (value(p, HR_SET, "IBI_STATUS_LIST") as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
            callback.onHeartRate(timestamp(p), bpm, status, ibi, ibiStatus)
        }
    }

    private fun handlePpg(points: List<*>) {
        points.filterNotNull().forEach { p ->
            callback.onPpg(PpgSample(
                timestamp(p),
                (value(p, PPG_SET, "PPG_GREEN") as? Number)?.toInt(),
                (value(p, PPG_SET, "PPG_RED") as? Number)?.toInt(),
                (value(p, PPG_SET, "PPG_IR") as? Number)?.toInt(),
                (value(p, PPG_SET, "GREEN_STATUS") as? Number)?.toInt(),
                (value(p, PPG_SET, "RED_STATUS") as? Number)?.toInt(),
                (value(p, PPG_SET, "IR_STATUS") as? Number)?.toInt()
            ))
        }
    }

    private fun handleTemperature(points: List<*>) {
        points.filterNotNull().forEach { p ->
            callback.onTemperature(
                timestamp(p),
                (value(p, TEMP_SET, "OBJECT_TEMPERATURE") as? Number)?.toFloat(),
                (value(p, TEMP_SET, "AMBIENT_TEMPERATURE") as? Number)?.toFloat(),
                (value(p, TEMP_SET, "STATUS") as? Number)?.toInt()
            )
        }
    }

    private fun timestamp(point: Any): Long = try { (point.javaClass.getMethod("getTimestamp").invoke(point) as Number).toLong() } catch (_: Throwable) { System.currentTimeMillis() }

    private fun value(point: Any, setClass: String, field: String): Any? = try {
        val key = Class.forName(setClass).getField(field).get(null)
        val getter = point.javaClass.methods.first { it.name == "getValue" && it.parameterCount == 1 }
        getter.invoke(point, key)
    } catch (_: Throwable) { null }

    private fun rootMessage(t: Throwable): String {
        var x=t; while(x.cause!=null) x=x.cause!!
        return x.javaClass.simpleName + (x.message?.let { ": $it" } ?: "")
    }

    companion object {
        private const val SERVICE = "com.samsung.android.service.health.tracking.HealthTrackingService"
        private const val CONNECTION_LISTENER = "com.samsung.android.service.health.tracking.ConnectionListener"
        private const val TRACKER_TYPE = "com.samsung.android.service.health.tracking.data.HealthTrackerType"
        private const val PPG_TYPE = "com.samsung.android.service.health.tracking.data.PpgType"
        private const val TRACKER_LISTENER = "com.samsung.android.service.health.tracking.HealthTracker\$TrackerEventListener"
        private const val ACCEL_SET = "com.samsung.android.service.health.tracking.data.ValueKey\$AccelerometerSet"
        private const val HR_SET = "com.samsung.android.service.health.tracking.data.ValueKey\$HeartRateSet"
        private const val PPG_SET = "com.samsung.android.service.health.tracking.data.ValueKey\$PpgSet"
        private const val TEMP_SET = "com.samsung.android.service.health.tracking.data.ValueKey\$SkinTemperatureSet"
    }
}
