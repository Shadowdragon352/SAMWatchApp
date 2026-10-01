package au.org.deliriumwatch.watch

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

data class PpgSample(
    val tsMs: Long,
    val green: Int?,
    val red: Int?,
    val ir: Int?,
    val greenStatus: Int?,
    val redStatus: Int?,
    val irStatus: Int?
)

class PpgProcessor(
    private val includeRaw: () -> Boolean,
    private val onWindow: (Long, JSONObject) -> Unit
) {
    private val data = ArrayList<PpgSample>(160)
    private var start = 0L

    @Synchronized
    fun add(s: PpgSample) {
        if (start == 0L) start = s.tsMs
        data += s
        if (s.tsMs - start >= 5000) flush()
    }

    @Synchronized
    fun flush() {
        if (data.isEmpty()) {
            start = 0
            return
        }

        val g = data.mapNotNull { it.green?.toDouble() }
        val r = data.mapNotNull { it.red?.toDouble() }
        val ir = data.mapNotNull { it.ir?.toDouble() }

        fun mean(v: List<Double>): Double? = if (v.isEmpty()) null else v.average()

        fun sd(v: List<Double>): Double? {
            val m = mean(v) ?: return null
            return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
        }

        val valid = data.count {
            (it.greenStatus ?: 0) == 0 && (it.redStatus ?: 0) == 0 && (it.irStatus ?: 0) == 0
        }.toDouble() / data.size

        val p = JSONObject().apply {
            put("green_mean", mean(g))
            put("green_sd", sd(g))
            put("red_mean", mean(r))
            put("red_sd", sd(r))
            put("ir_mean", mean(ir))
            put("ir_sd", sd(ir))
            put("valid_fraction", valid)
            put("sample_count", data.size)
            if (includeRaw()) {
                put("raw", JSONArray().apply {
                    data.forEach {
                        put(
                            JSONArray()
                                .put(it.tsMs)
                                .put(it.green)
                                .put(it.red)
                                .put(it.ir)
                                .put(it.greenStatus)
                                .put(it.redStatus)
                                .put(it.irStatus)
                        )
                    }
                })
            }
        }

        onWindow(data.last().tsMs, p)
        data.clear()
        start = 0
    }
}

