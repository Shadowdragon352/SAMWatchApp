package au.org.deliriumwatch.watch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.android.gms.wearable.Wearable

class MainActivity:Activity(){
    private lateinit var prefs:WatchPrefs; private lateinit var status:TextView; private val handler=Handler(Looper.getMainLooper())
    private val refresh=object:Runnable{override fun run(){updateStatus();handler.postDelayed(this,2000)}}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);prefs=WatchPrefs(this);setContentView(buildUi());requestNeededPermissions();CognitionScheduler.scheduleAll(this,prefs.promptTimes);requestPhoneConfig();handler.post(refresh)}
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy()}

    private fun buildUi():View{
        val scroll=ScrollView(this);val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(16,20,16,28);setBackgroundColor(Color.BLACK)}
        root.addView(TextView(this).apply{text="DeliriumWatch";textSize=22f;setTextColor(Color.WHITE);gravity=Gravity.CENTER})
        root.addView(TextView(this).apply{text="Research only";textSize=12f;setTextColor(Color.LTGRAY);gravity=Gravity.CENTER})
        val start=Button(this).apply{text="Start monitoring";setOnClickListener{requestNeededPermissions();startForegroundService(Intent(this@MainActivity,MonitoringService::class.java));prefs.monitoring=true;updateStatus()}}
        val stop=Button(this).apply{text="Stop monitoring";setOnClickListener{startService(Intent(this@MainActivity,MonitoringService::class.java).setAction(MonitoringService.ACTION_STOP));prefs.monitoring=false;updateStatus()}}
        val cog=Button(this).apply{text="Cognitive check";setOnClickListener{startActivity(Intent(this@MainActivity,CognitiveActivity::class.java))}}
        val sync=Button(this).apply{text="Send queued data";setOnClickListener{startForegroundService(Intent(this@MainActivity,MonitoringService::class.java).setAction(MonitoringService.ACTION_FLUSH));Toast.makeText(this@MainActivity,"Transfer requested",Toast.LENGTH_SHORT).show()}}
        listOf(start,stop,cog,sync).forEach{root.addView(it,LinearLayout.LayoutParams(-1,-2))}
        status=TextView(this).apply{textSize=13f;setTextColor(Color.WHITE);gravity=Gravity.CENTER;setPadding(4,12,4,4)};root.addView(status)
        scroll.addView(root);return scroll
    }

    private fun requestPhoneConfig(){Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener{nodes->nodes.forEach{Wearable.getMessageClient(this).sendMessage(it.id,"/delirium-watch/config/request",byteArrayOf())}}}
    private fun updateStatus(){val db=WatchDb(this);val move=if(prefs.lastMovement.isNaN())"—" else "%.3f".format(prefs.lastMovement);val temp=if(prefs.lastTemp.isNaN())"—" else "%.2f".format(prefs.lastTemp);status.text="Study: ${prefs.studyId}\nMonitoring: ${if(prefs.monitoring)"ON" else "OFF"}\nHR: ${if(prefs.lastHeartRate>0)prefs.lastHeartRate else "—"}\nMovement RMS: $move\nSkin temp: $temp\nQueued: ${db.pendingCount()}\n${prefs.lastSensorStatus}"}

    private fun requestNeededPermissions(){
        val p=mutableListOf<String>(); fun addIfMissing(permission:String){if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED)p+=permission}
        addIfMissing(Manifest.permission.ACTIVITY_RECOGNITION)
        if(Build.VERSION.SDK_INT>=36){addIfMissing("android.permission.health.READ_HEART_RATE");addIfMissing("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND");addIfMissing("android.permission.health.READ_SKIN_TEMPERATURE")} else {addIfMissing(Manifest.permission.BODY_SENSORS);if(Build.VERSION.SDK_INT>=33)addIfMissing("android.permission.BODY_SENSORS_BACKGROUND")}
        addIfMissing("com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA")
        if(Build.VERSION.SDK_INT>=33)addIfMissing(Manifest.permission.POST_NOTIFICATIONS)
        if(p.isNotEmpty())requestPermissions(p.distinct().toTypedArray(),77)
    }
}
