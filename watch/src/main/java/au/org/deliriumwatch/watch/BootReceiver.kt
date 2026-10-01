package au.org.deliriumwatch.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        val prefs=WatchPrefs(context); CognitionScheduler.scheduleAll(context,prefs.promptTimes)
        if(prefs.monitoring) context.startForegroundService(Intent(context,MonitoringService::class.java))
    }
}
