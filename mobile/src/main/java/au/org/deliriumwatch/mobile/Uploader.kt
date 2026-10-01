package au.org.deliriumwatch.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

object Uploader {
    data class Result(val ok: Boolean, val message: String, val uploaded: Int = 0)

    @Synchronized
    fun uploadOnce(context: Context): Result {
        val prefs = PhonePrefs(context)
        val base = prefs.serverUrl
        if (base.isBlank()) return Result(false, "Server URL is not configured")
        val db = PhoneDb(context)
        var conn: HttpURLConnection? = null
        return try {
            val pending = db.pending(100)
            if (pending.isEmpty()) return Result(true, "Queue empty", 0)
            val body = JSONObject().put("events", JSONArray().apply { pending.forEach { put(it.second) } }).toString()
            conn = (URL("$base/api/v1/ingest").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (prefs.token.isNotBlank()) setRequestProperty("Authorization", "Bearer ${prefs.token}")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val response = try { conn.inputStream.bufferedReader().use { it.readText() } } catch (_: Exception) { conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "" }
            if (code in 200..299) {
                db.markUploaded(pending.map { it.first })
                db.pruneUploaded()
                prefs.lastUploadMessage = "${Instant.now()} — ${pending.size} events"
                Result(true, response.ifBlank { "Uploaded" }, pending.size)
            } else Result(false, "HTTP $code: $response")
        } catch (e: Exception) {
            Result(false, e.javaClass.simpleName + ": " + (e.message ?: "upload failed"))
        } finally { conn?.disconnect(); db.close() }
    }

    fun drain(context: Context, maxBatches: Int = 10): Result {
        var total = 0
        var last = Result(true, "Queue empty", 0)
        repeat(maxBatches) {
            if (Thread.currentThread().isInterrupted) return Result(false, "Upload interrupted", total)
            last = uploadOnce(context)
            total += last.uploaded
            if (!last.ok || last.uploaded == 0) return Result(last.ok, last.message, total)
        }
        return Result(true, "Uploaded $total events; more may remain queued", total)
    }
}
