package au.org.deliriumwatch.watch

import android.content.Intent
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

class WatchMessageService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if(messageEvent.path!=PATH_CONFIG) return
        try {
            val prefs=WatchPrefs(this)
            prefs.applyConfig(JSONObject(String(messageEvent.data,Charsets.UTF_8)))
            CognitionScheduler.scheduleAll(this,prefs.promptTimes)
            if(prefs.monitoring) startForegroundService(Intent(this,MonitoringService::class.java).setAction(MonitoringService.ACTION_RESTART))
        } catch (_:Exception) { }
    }
    companion object { const val PATH_CONFIG="/delirium-watch/config" }
}
