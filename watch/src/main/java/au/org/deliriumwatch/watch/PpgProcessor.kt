package au.org.deliriumwatch.watch

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

data class PpgSample(val tsMs: Long, val green: Int?, val red: Int?, val ir: Int?,
    val greenStatus: Int?, val redStatus: Int?, val irStatus: Int?)

class PpgProcessor(private val includeRaw: () -> Boolean, private val onWindow: (Long, JSONObject) -> Unit) {
    private val samples = ArrayList<PpgSample>(160)
    @Synchronized fun add(sample: PpgSample) {
        if (samples.isNotEmpty() && sample.tsMs < samples.last().tsMs) flush()
        samples.add(sample)
        if (sample.tsMs - samples.first().tsMs >= 5000 || samples.size >= 250) flush()
    }
    @Synchronized fun flush() {
        if (samples.isEmpty()) return
        val window = samples.toList()
        samples.clear()
        val green = window.filter { it.greenStatus == 0 }.mapNotNull { it.green?.toDouble() }
        val red = window.filter { it.redStatus == 0 }.mapNotNull { it.red?.toDouble() }
        val ir = window.filter { it.irStatus == 0 }.mapNotNull { it.ir?.toDouble() }
        fun mean(values: List<Double>): Any = if (values.isEmpty()) JSONObject.NULL else values.average()
        fun sd(values: List<Double>): Any {
            if (values.isEmpty()) return JSONObject.NULL
            val center = values.average()
            return sqrt(values.sumOf { (it - center) * (it - center) } / values.size)
        }
        val valid = window.count { s ->
            val channels = listOf(s.green to s.greenStatus, s.red to s.redStatus, s.ir to s.irStatus)
                .filter { it.first != null }
            channels.isNotEmpty() && channels.all { it.second == 0 }
        }.toDouble() / window.size
        val payload = JSONObject().apply {
            put("green_mean", mean(green)); put("green_sd", sd(green))
            put("red_mean", mean(red)); put("red_sd", sd(red))
            put("ir_mean", mean(ir)); put("ir_sd", sd(ir))
            put("valid_fraction", valid); put("sample_count", window.size)
            if (includeRaw()) {
                val raw = JSONArray()
                for (s in window) {
                    val row = JSONArray().put(s.tsMs)
                    listOf(s.green, s.red, s.ir, s.greenStatus, s.redStatus, s.irStatus).forEach { v -> row.put(v ?: JSONObject.NULL) }
                    raw.put(row)
                }
                put("raw", raw)
            }
        }
        onWindow(window.last().tsMs, payload)
    }
}
