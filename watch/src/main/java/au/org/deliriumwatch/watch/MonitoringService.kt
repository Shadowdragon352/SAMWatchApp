package au.org.deliriumwatch.watch

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.*
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
    @Volatile private var samsungAccel=false
    @Volatile private var samsungHr=false
    private var started=false
    private var usingLinear=true
    private val gravity=DoubleArray(3)

    override fun onCreate() {
        super.onCreate(); prefs=WatchPrefs(this); db=WatchDb(this); sensorManager=getSystemService(SensorManager::class.java)
        movement=MovementProcessor(5_000,{prefs.activeThreshold},{prefs.rawAccel}){ts,payload->
            prefs.lastMovement=payload.optDouble("rms",Double.NaN); store("movement_5s",ts,payload,if(samsungAccel)"samsung" else "android")
        }
        ppg=PpgProcessor({prefs.rawPpg}){ts,payload->store("ppg_summary",ts,payload,"samsung")}
        samsung=SamsungHealthSensorBridge(this,this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action==ACTION_STOP){ prefs.monitoring=false; cleanupSensors(); stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIFICATION_ID, notification("Monitoring active"))
        if(intent?.action==ACTION_FLUSH){ WatchTransport.flush(this); if(!prefs.monitoring) handler.postDelayed({stopSelf()},1500); return START_NOT_STICKY }
        if(intent?.action==ACTION_RESTART && started) cleanupSensors()
        prefs.monitoring=true
        startMonitoring(); return START_STICKY
    }

    private fun startMonitoring() {
        if(started) { scheduleFlush(); return }
        started=true; prefs.lastSensorStatus="Starting sensors"
        val linear=sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if(linear!=null){ usingLinear=true; sensorManager.registerListener(this,linear,40_000) }
        else { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { usingLinear=false; sensorManager.registerListener(this,it,40_000) } }
        val hr=sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if(hr!=null) sensorManager.registerListener(this,hr,SensorManager.SENSOR_DELAY_NORMAL)
        samsung.start(enablePpg = prefs.ppgEnabled, allPpgWavelengths = prefs.ppgAllWavelengths); scheduleFlush()
        store("monitoring_state",System.currentTimeMillis(),JSONObject().put("state","started").put("samsung_sdk_bundled",samsung.isBundled()))
    }

    private fun cleanupSensors() {
        if(!started) return
        handler.removeCallbacksAndMessages(null); sensorManager.unregisterListener(this); samsung.stop(); movement.flush(); ppg.flush(); started=false
        store("monitoring_state",System.currentTimeMillis(),JSONObject().put("state","stopped")); WatchTransport.flush(this)
    }

    private fun scheduleFlush() {
        handler.removeCallbacks(flushRunnable); handler.postDelayed(flushRunnable,prefs.sendIntervalSec*1000L)
    }
    private val flushRunnable=object:Runnable{override fun run(){flushToPhone();db.prune(prefs.retentionHours);handler.postDelayed(this,prefs.sendIntervalSec*1000L)}}

    private fun flushToPhone() = WatchTransport.flush(this)

    private fun store(type:String,ts:Long,payload:JSONObject,source:String="watch") {
        val e=EventFactory.create(this,type,ts,payload,source); db.insert(ts,e.getString("event_id"),type,e.toString())
    }

    override fun onSensorChanged(event: SensorEvent) {
        val ts=System.currentTimeMillis()
        when(event.sensor.type){
            Sensor.TYPE_LINEAR_ACCELERATION -> if(!samsungAccel && event.values.size>=3) movement.add(AccelSample(ts,event.values[0].toDouble(),event.values[1].toDouble(),event.values[2].toDouble()))
            Sensor.TYPE_ACCELEROMETER -> if(!samsungAccel && !usingLinear && event.values.size>=3) {
                val alpha=0.8; val linear=DoubleArray(3); for(i in 0..2){gravity[i]=alpha*gravity[i]+(1-alpha)*event.values[i];linear[i]=event.values[i]-gravity[i]}; movement.add(AccelSample(ts,linear[0],linear[1],linear[2]))
            }
            Sensor.TYPE_HEART_RATE -> if(!samsungHr && event.values.isNotEmpty()) {
                val bpm=event.values[0].roundToInt(); if(bpm>0){prefs.lastHeartRate=bpm;store("heart_rate",ts,JSONObject().put("bpm",bpm).put("status",JSONObject.NULL).put("ibi_ms",JSONArray()).put("ibi_status",JSONArray()),"android")}
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onConnected(){ prefs.lastSensorStatus="Samsung Health Sensor connected" }
    override fun onStatus(message:String){ prefs.lastSensorStatus=message }
    override fun onAcceleration(tsMs:Long,x:Double,y:Double,z:Double){ samsungAccel=true; movement.add(AccelSample(tsMs,x,y,z)) }
    override fun onHeartRate(tsMs:Long,bpm:Int?,status:Int?,ibiMs:List<Int>,ibiStatus:List<Int>){ samsungHr=true; if(bpm!=null&&bpm>0)prefs.lastHeartRate=bpm; store("heart_rate",tsMs,JSONObject().put("bpm",bpm).put("status",status).put("ibi_ms",EventFactory.numbers(ibiMs)).put("ibi_status",EventFactory.numbers(ibiStatus)),"samsung") }
    override fun onPpg(sample:PpgSample){ ppg.add(sample) }
    override fun onTemperature(tsMs:Long,skinC:Float?,ambientC:Float?,status:Int?){ if(skinC!=null)prefs.lastTemp=skinC.toDouble(); store("temperature",tsMs,JSONObject().put("skin_temp_c",skinC).put("ambient_temp_c",ambientC).put("status",status),"samsung") }

    override fun onDestroy(){ cleanupSensors(); super.onDestroy() }
    override fun onBind(intent:Intent?):IBinder?=null

    private fun createNotificationChannel(){ if(android.os.Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Research monitoring",NotificationManager.IMPORTANCE_LOW)) }
    private fun notification(text:String):Notification{
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,1,Intent(this,MonitoringService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,CHANNEL).setContentTitle("DeliriumWatch").setContentText(text).setSmallIcon(android.R.drawable.ic_menu_info_details).setContentIntent(open).addAction(Notification.Action.Builder(null,"Stop",stop).build()).setOngoing(true).build()
    }

    companion object { const val PATH_BATCH="/delirium-watch/batch"; const val ACTION_STOP="stop"; const val ACTION_FLUSH="flush"; const val ACTION_RESTART="restart"; const val CHANNEL="monitoring"; const val NOTIFICATION_ID=4201 }
}
