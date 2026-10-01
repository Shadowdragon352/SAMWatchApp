package au.org.deliriumwatch.watch

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

class WatchMessageService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        try {
            val obj = JSONObject(String(event.data, Charsets.UTF_8))
            when (event.path) {
                WatchTransport.PATH_ACK -> WatchTransport.acknowledge(this, event.sourceNodeId, obj.getString("batch_id"))
                PATH_CONFIG -> {
                    val prefs = WatchPrefs(this)
                    MonitoringService.applyConfiguration(applicationContext, obj)
                }
            }
        } catch (e: Exception) { Log.w("DeliriumWatch", "Invalid phone message", e) }
    }
    companion object { const val PATH_CONFIG = "/delirium-watch/config" }
}
