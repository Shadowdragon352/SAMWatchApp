package au.org.deliriumwatch.mobile

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject
import java.util.concurrent.Executors

class WearReceiverService : WearableListenerService() {
    private val executor = Executors.newSingleThreadExecutor()
    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            PATH_BATCH -> try {
                val obj = JSONObject(String(event.data, Charsets.UTF_8))
                val rows = obj.getJSONArray("events")
                require(rows.length() in 1..100)
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    require(row.getString("event_id").length in 8..100)
                    require(row.getString("study_id").trim().length in 1..100)
                    require(row.getLong("ts_ms") > 0)
                    require(row.getString("type").length in 1..80)
                    row.getJSONObject("payload")
                }
                // Commit the entire batch before sending a receipt; retries deduplicate by UUID.
                PhoneDb(this).use { it.insertBatch(obj, PhonePrefs(this).studyId) }
                val id = obj.optString("batch_id")
                if (id.isNotBlank()) {
                    Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, PATH_ACK,
                        JSONObject().put("batch_id", id).toString().toByteArray(Charsets.UTF_8))
                        .addOnFailureListener { Log.w("DeliriumWatch", "Receipt delivery failed; watch will retry") }
                }
                executor.execute { Uploader.drain(applicationContext, 3) }
            } catch (e: Exception) { Log.e("DeliriumWatch", "Batch not acknowledged: save/validation failed", e) }
            PATH_CONFIG_REQUEST -> sendConfig(event.sourceNodeId)
        }
    }
    override fun onDestroy() { executor.shutdown(); super.onDestroy() }
    private fun sendConfig(nodeId: String) {
        val bytes = PhonePrefs(this).watchConfigJson().toString().toByteArray(Charsets.UTF_8)
        Wearable.getMessageClient(this).sendMessage(nodeId, PATH_CONFIG, bytes)
            .addOnFailureListener { Log.w("DeliriumWatch", "Watch configuration delivery failed") }
    }
    companion object {
        const val PATH_BATCH = "/delirium-watch/batch"
        const val PATH_ACK = "/delirium-watch/batch/ack"
        const val PATH_CONFIG = "/delirium-watch/config"
        const val PATH_CONFIG_REQUEST = "/delirium-watch/config/request"
    }
}
