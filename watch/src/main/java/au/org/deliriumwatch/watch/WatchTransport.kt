package au.org.deliriumwatch.watch

import android.content.Context
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray
import org.json.JSONObject

object WatchTransport {
    fun flush(context: Context, onResult: ((Boolean, Int)->Unit)? = null) {
        val db=WatchDb(context)
        val queued=db.pendingForMessage()
        if(queued.isEmpty()){onResult?.invoke(true,0);return}
        val body=JSONObject().put("events",JSONArray().apply{queued.forEach{put(JSONObject(it.payload))}}).toString().toByteArray(Charsets.UTF_8)
        Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes ->
            if(nodes.isEmpty()){onResult?.invoke(false,0);return@addOnSuccessListener}
            val ids=queued.map{it.id}; var completed=false
            nodes.forEach { node ->
                Wearable.getMessageClient(context).sendMessage(node.id,MonitoringService.PATH_BATCH,body)
                    .addOnSuccessListener { if(!completed){completed=true;db.markSent(ids);onResult?.invoke(true,ids.size)} }
            }
        }.addOnFailureListener { onResult?.invoke(false,0) }
    }
}
