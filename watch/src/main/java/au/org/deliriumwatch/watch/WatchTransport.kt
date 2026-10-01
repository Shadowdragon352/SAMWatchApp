package au.org.deliriumwatch.watch

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object WatchTransport {
    const val PATH_ACK = "/delirium-watch/batch/ack"
    private val handler = Handler(Looper.getMainLooper())
    private data class Transfer(val batchId: String, val ids: List<Long>, val count: Int,
        val callback: ((Boolean, Int) -> Unit)?, val nodes: MutableSet<String> = mutableSetOf())
    private var transfer: Transfer? = null

    fun flush(context: Context, onResult: ((Boolean, Int) -> Unit)? = null) {
        val app = context.applicationContext
        handler.post {
            if (transfer != null) { onResult?.invoke(false, 0); return@post }
            try {
                val queued = WatchDb(app).use { it.pendingForMessage() }
                if (queued.isEmpty()) { onResult?.invoke(true, 0); return@post }
                val batchId = UUID.randomUUID().toString()
                val body = JSONObject().put("batch_id", batchId)
                    .put("events", JSONArray().apply { queued.forEach { put(JSONObject(it.payload)) } })
                    .toString().toByteArray(Charsets.UTF_8)
                if (body.size > 90_000) { onResult?.invoke(false, 0); return@post }
                val current = Transfer(batchId, queued.map { it.id }, queued.size, onResult)
                transfer = current
                handler.postDelayed({ if (transfer === current) complete(false) }, 20_000)
                Wearable.getNodeClient(app).connectedNodes.addOnSuccessListener { nodes ->
                    if (transfer !== current) return@addOnSuccessListener
                    if (nodes.isEmpty()) { complete(false); return@addOnSuccessListener }
                    nodes.forEach { node ->
                        current.nodes.add(node.id)
                        Wearable.getMessageClient(app).sendMessage(node.id, MonitoringService.PATH_BATCH, body)
                            .addOnFailureListener { /* leave queued; timeout reports failure */ }
                    }
                    // sendMessage success is transport delivery, not durable storage.
                    // Only the phone's receipt can mark these records sent.
                }.addOnFailureListener { if (transfer === current) complete(false) }
            } catch (_: Exception) { if (transfer != null) complete(false) else onResult?.invoke(false, 0) }
        }
    }
    fun acknowledge(context: Context, nodeId: String, batchId: String) {
        val app = context.applicationContext
        handler.post {
            val current = transfer ?: return@post
            if (current.batchId != batchId || nodeId !in current.nodes) return@post
            try {
                WatchDb(app).use { it.markSent(current.ids) }
                complete(true)
            } catch (_: Exception) { complete(false) }
        }
    }
    private fun complete(ok: Boolean) {
        val current = transfer ?: return
        transfer = null
        current.callback?.invoke(ok, if (ok) current.count else 0)
    }
}
