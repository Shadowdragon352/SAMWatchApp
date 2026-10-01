package au.org.deliriumwatch.watch

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.*
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.PowerManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

class MonitoringService : Service(), SensorEventListener, SamsungHealthSensorBridge.Callback {
    private lateinit var prefs: WatchPrefs
    private lateinit var db: WatchDb
    private lateinit var sensorManager: SensorManager
    private lateinit var movement: MovementProcessor
    private lateinit var ppg: PpgProcessor
    private lateinit var samsung: SamsungHealthSensorBridge
    private val handler=Handler(Looper.getMainLooper())
    private var lastSamsungHr = 0L
    private var gravityReady = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var started=false
    private var usingLinear=true
    private val gravity=DoubleArray(3)

    override fun onCreate() {
        super.onCreate(); prefs=WatchPrefs(this); db=WatchDb(this); sensorManager=getSystemService(SensorManager::class.java)
        movement=MovementProcessor(5_000,{prefs.activeThreshold},{prefs.rawAccel}){ts,payload->
            prefs.lastMovement=payload.optDouble("rms",Double.NaN); store("movement_5s",ts,payload,"android")
        }
        ppg=PpgProcessor({prefs.rawPpg}){ts,payload->store("ppg_summary",ts,payload,"samsung")}
        samsung=SamsungHealthSensorBridge(this,this)
        active = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.monitoring = false; cleanupSensors(); stopSelf(); return START_NOT_STICKY
        }
        // Transfers do not need a health service or body-sensor permissions.
        if (intent?.action == ACTION_FLUSH) {
            WatchTransport.flush(this); if (!started) stopSelf(); return START_NOT_STICKY
        }
        if (!WatchPermissions.canMonitor(this)) {
            prefs.monitoring = false
            prefs.lastSensorStatus = "Monitoring stopped: sensor permission is missing"
            stopSelf(); return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, notification("Monitoring active"))
            if (intent?.action == ACTION_RESTART && started) cleanupSensors()
            startMonitoring()
            prefs.monitoring = true
            return START_STICKY
        } catch (e: Exception) {
            Log.e("DeliriumWatch", "Monitoring startup failed", e)
            prefs.monitoring = false
            prefs.lastSensorStatus = "Cannot start monitoring: ${e.message}"
            cleanupSensors(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
    }

    private fun startMonitoring() {
        if(started) { scheduleFlush(); return }
        started=true; prefs.lastSensorStatus="Starting sensors"
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeliriumWatch:monitoring").apply {
            setReferenceCounted(false); acquire(10 * 60_000L)
        }
        val linear=sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if(linear!=null){ usingLinear=true; sensorManager.registerListener(this,linear,40_000) }
        else { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { usingLinear=false; sensorManager.registerListener(this,it,40_000) } }
        val hr = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (hr != null && WatchPermissions.heartRate(this)) {
            try { sensorManager.registerListener(this, hr, SensorManager.SENSOR_DELAY_NORMAL) }
            catch (e: SecurityException) { prefs.lastSensorStatus = "Heart rate unavailable; movement recording continues" }
        }
        samsung.start(enablePpg = prefs.ppgEnabled, allPpgWavelengths = prefs.ppgAllWavelengths); scheduleFlush()
        store("monitoring_state",System.currentTimeMillis(),JSONObject().put("state","started").put("samsung_sdk_bundled",samsung.isBundled()))
    }

    private fun cleanupSensors() {
        if(!started) return
        started = false
        handler.removeCallbacksAndMessages(null)
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        sensorManager.unregisterListener(this); samsung.stop(); movement.flush(); ppg.flush()
        lastSamsungHr = 0
        gravityReady = false
        gravity.fill(0.0)
        store("monitoring_state",System.currentTimeMillis(),JSONObject().put("state","stopped")); WatchTransport.flush(this)
    }

    private fun scheduleFlush() {
        handler.removeCallbacks(flushRunnable); handler.postDelayed(flushRunnable,prefs.sendIntervalSec*1000L)
    }
    private val flushRunnable=object:Runnable{override fun run(){
        if (!started) return
        try {
            wakeLock?.acquire(10 * 60_000L)
            flushToPhone();db.prune(prefs.retentionHours)
        } catch (e: Exception) { Log.e("DeliriumWatch", "Queue maintenance failed", e); prefs.lastSensorStatus="Queue error: ${e.message}" }
        handler.postDelayed(this,prefs.sendIntervalSec*1000L)
    }}

    private fun flushToPhone() = WatchTransport.flush(this)

    private fun store(type:String,ts:Long,payload:JSONObject,source:String="watch") {
        val e=EventFactory.create(this,type,ts,payload,source); db.insert(ts,e.getString("event_id"),type,e.toString())
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!started || event.values.any { !it.isFinite() }) return
        // Android sensor timestamps use elapsed realtime, not callback arrival time.
        val ts = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000
        val samsungHr = lastSamsungHr > 0 && SystemClock.elapsedRealtime() - lastSamsungHr < 15_000
        when(event.sensor.type){
            Sensor.TYPE_LINEAR_ACCELERATION -> if(event.values.size>=3) movement.add(AccelSample(ts,event.values[0].toDouble(),event.values[1].toDouble(),event.values[2].toDouble()))
            Sensor.TYPE_ACCELEROMETER -> if(!usingLinear && event.values.size>=3) {
                if (!gravityReady) { for (i in 0..2) gravity[i] = event.values[i].toDouble(); gravityReady = true }
                val alpha=0.8; val linear=DoubleArray(3); for(i in 0..2){gravity[i]=alpha*gravity[i]+(1-alpha)*event.values[i];linear[i]=event.values[i]-gravity[i]}; movement.add(AccelSample(ts,linear[0],linear[1],linear[2]))
            }
            Sensor.TYPE_HEART_RATE -> if(!samsungHr && event.accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE && event.values.isNotEmpty()) {
                val bpm=event.values[0].roundToInt(); if(bpm>0){prefs.lastHeartRate=bpm;store("heart_rate",ts,JSONObject().put("bpm",bpm).put("status",JSONObject.NULL).put("ibi_ms",JSONArray()).put("ibi_status",JSONArray()),"android")}
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun onMain(action: () -> Unit) {
        handler.post {
            if (started) try { action() } catch (e: Exception) {
                Log.e("DeliriumWatch", "Sensor callback failed", e)
                prefs.lastSensorStatus = "Sensor data error: ${e.message}"
            }
        }
    }
    override fun onConnected() = onMain { prefs.lastSensorStatus = "Samsung Health Sensor connected" }
    override fun onStatus(message: String) = onMain { prefs.lastSensorStatus = message }
    // Keep movement on Android's linear-acceleration stream. Samsung acceleration
    // contains gravity and has different calibration/units; mixing it into the
    // same feature windows makes immobility and baseline comparisons invalid.
    override fun onAcceleration(tsMs: Long, x: Double, y: Double, z: Double) = Unit
    override fun onHeartRate(tsMs: Long, bpm: Int?, status: Int?, ibiMs: List<Int>, ibiStatus: List<Int>) = onMain {
        if (bpm != null && bpm > 0 && status == 1) {
            lastSamsungHr = SystemClock.elapsedRealtime()
            prefs.lastHeartRate = bpm
        }
        store("heart_rate", tsMs, JSONObject().put("bpm", if (status == 1) bpm else JSONObject.NULL)
            .put("status", status ?: JSONObject.NULL).put("ibi_ms", EventFactory.numbers(ibiMs))
            .put("ibi_status", EventFactory.numbers(ibiStatus)), "samsung")
    }
    override fun onPpg(sample: PpgSample) = onMain { ppg.add(sample) }
    override fun onTemperature(tsMs: Long, skinC: Float?, ambientC: Float?, status: Int?) = onMain {
        val valid = status == 0 && skinC?.isFinite() == true
        if (valid) prefs.lastTemp = skinC!!.toDouble()
        store("temperature", tsMs, JSONObject().put("skin_temp_c", if (valid) skinC else JSONObject.NULL)
            .put("ambient_temp_c", if (ambientC?.isFinite() == true) ambientC else JSONObject.NULL)
            .put("status", status ?: JSONObject.NULL), "samsung")
    }
    override fun onDestroy() {
        active = null
        prefs.monitoring = false
        try { cleanupSensors() } finally { db.close(); super.onDestroy() }
    }

    override fun onBind(intent:Intent?):IBinder?=null

    private fun createNotificationChannel(){ if(android.os.Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Research monitoring",NotificationManager.IMPORTANCE_LOW)) }
    private fun notification(text:String):Notification{
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,1,Intent(this,MonitoringService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,CHANNEL).setContentTitle("DeliriumWatch").setContentText(text).setSmallIcon(android.R.drawable.ic_menu_info_details).setContentIntent(open).addAction(Notification.Action.Builder(null,"Stop",stop).build()).setOngoing(true).build()
    }

    companion object {
        private var active: MonitoringService? = null
        fun applyConfiguration(context: Context, config: JSONObject) {
            Handler(Looper.getMainLooper()).post {
                val svc = active
                val restart = svc?.started == true
                if (restart) svc!!.cleanupSensors()
                val prefs = WatchPrefs(context)
                prefs.applyConfig(config)
                CognitionScheduler.scheduleAll(context, prefs.promptTimes)
                if (restart) {
                    try { svc!!.startMonitoring() } catch (e: Exception) {
                        prefs.monitoring = false
                        prefs.lastSensorStatus = "Restart failed: ${e.message}"
                        svc!!.stopSelf()
                    }
                }
            }
        }
        const val PATH_BATCH="/delirium-watch/batch"; const val ACTION_STOP="stop"; const val ACTION_FLUSH="flush"; const val ACTION_RESTART="restart"; const val CHANNEL="monitoring"; const val NOTIFICATION_ID=4201 }
}
