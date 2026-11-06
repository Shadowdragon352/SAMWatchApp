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
    private val uploader = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var transfer: Transfer? = null

    fun flush(context: Context, onResult: ((Boolean, Int) -> Unit)? = null) {
        val app = context.applicationContext
        handler.post {
            if (transfer != null) { onResult?.invoke(false, 0); return@post }
            try {
                val config = app.getSharedPreferences("direct_upload", Context.MODE_PRIVATE)
                val token = config.getString("token", "").orEmpty()
                val studyId = if (token.isNotBlank()) config.getString("study_id", null) else null
                if (token.isNotBlank() && studyId.isNullOrBlank()) {
                    onResult?.invoke(false, 0)
                    return@post
                }
                val queued = WatchDb(app).use { it.pendingForMessage(studyId = studyId) }
                if (queued.isEmpty()) { onResult?.invoke(true, 0); return@post }
                val batchId = UUID.randomUUID().toString()
                val body = JSONObject().put("batch_id", batchId)
                    .put("events", JSONArray().apply { queued.forEach { put(JSONObject(it.payload)) } })
                    .toString().toByteArray(Charsets.UTF_8)
                if (body.size > 90_000) { onResult?.invoke(false, 0); return@post }
                val current = Transfer(batchId, queued.map { it.id }, queued.size, onResult)
                transfer = current
                if (token.isNotBlank()) {
                    uploader.execute {
                        val ok = runCatching {
                            val connection = java.net.URL("https://sam-dash.onrender.com/api/v1/ingest")
                                .openConnection() as javax.net.ssl.HttpsURLConnection
                            try {
                                connection.instanceFollowRedirects = false
                                connection.connectTimeout = 10_000
                                connection.readTimeout = 10_000
                                connection.requestMethod = "POST"
                                connection.doOutput = true
                                connection.setRequestProperty("Authorization", "Bearer $token")
                                connection.setRequestProperty("Content-Type", "application/json")
                                connection.outputStream.use { it.write(body) }
                                val status = connection.responseCode
                                android.util.Log.i("SamUpload", "HTTP $status")
                                if (status == 403) {
                                    val reason = runCatching {
                                        connection.errorStream?.bufferedReader()?.use { reader ->
                                            val buffer = CharArray(2048)
                                            val count = reader.read(buffer)
                                            if (count > 0) JSONObject(String(buffer, 0, count)).optString("message") else ""
                                        }
                                    }.getOrNull()
                                    val known = setOf(
                                        "Live device integration is disabled in this demo",
                                        "Device is not enabled for live demo uploads",
                                        "Events must match the active volunteer assignment",
                                        "Live demo device is not registered"
                                    )
                                    android.util.Log.w("SamUpload", if (reason in known) reason.orEmpty() else "Unrecognized 403 response")
                                }
                                check(status in 200..299)
                                val ack = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                                val ids = ack.getJSONArray("acknowledged_event_ids")
                                val expected = queued.map { JSONObject(it.payload).getString("event_id") }.toSet()
                                check(ack.getInt("accepted") == queued.size && ack.getInt("unassigned") == 0 &&
                                    ids.length() == queued.size && (0 until ids.length()).map { ids.getString(it) }.toSet() == expected)
                                WatchDb(app).use { it.markSent(current.ids) }
                            } finally {
                                connection.disconnect()
                            }
                        }.onFailure { android.util.Log.w("SamUpload", "Upload failed: ${it.javaClass.simpleName}") }.isSuccess
                        handler.post { if (transfer === current) complete(ok) }
                    }
                    return@post
                }
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
