package au.org.deliriumwatch.mobile

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject
import java.util.concurrent.Executors

class WearReceiverService : WearableListenerService() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val prefs = PhonePrefs(this)
        when (messageEvent.path) {
            PATH_BATCH -> {
                try {
                    val obj = JSONObject(String(messageEvent.data, Charsets.UTF_8))
                    PhoneDb(this).insertBatch(obj, prefs.studyId)
                    executor.execute { Uploader.drain(this, 3) }
                } catch (_: Exception) { }
            }
            PATH_CONFIG_REQUEST -> sendConfig(messageEvent.sourceNodeId)
        }
    }

    private fun sendConfig(nodeId: String) {
        val bytes = PhonePrefs(this).watchConfigJson().toString().toByteArray(Charsets.UTF_8)
        Wearable.getMessageClient(this).sendMessage(nodeId, PATH_CONFIG, bytes)
    }

    companion object {
        const val PATH_BATCH = "/delirium-watch/batch"
        const val PATH_CONFIG = "/delirium-watch/config"
        const val PATH_CONFIG_REQUEST = "/delirium-watch/config/request"
    }
}
