package au.org.deliriumwatch.watch

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

data class AccelSample(val tsMs: Long, val x: Double, val y: Double, val z: Double)

class MovementProcessor(private val windowMs: Long = 5_000, private val thresholdProvider: () -> Double, private val includeRaw: () -> Boolean, private val onWindow: (Long, JSONObject) -> Unit) {
    private val samples=ArrayList<AccelSample>(160)
    private var windowStart=0L

    @Synchronized
    fun add(sample: AccelSample) {
        if (!sample.x.isFinite() || !sample.y.isFinite() || !sample.z.isFinite()) return
        if (samples.isNotEmpty() && sample.tsMs < samples.last().tsMs) flush()
        if(windowStart==0L) windowStart=sample.tsMs
        samples+=sample
        if(sample.tsMs-windowStart>=windowMs || samples.size >= 250) flush(sample.tsMs)
    }

    @Synchronized
    fun flush(endMs: Long=System.currentTimeMillis()) {
        if(samples.size<2) { samples.clear(); windowStart=0; return }
        val mags=samples.map { sqrt(it.x*it.x+it.y*it.y+it.z*it.z) }
        val mean=mags.average()
        val rms=sqrt(mags.sumOf { it*it }/mags.size)
        val mad=mags.sumOf { abs(it-mean) }/mags.size
        val sd=sqrt(mags.sumOf { (it-mean)*(it-mean) }/mags.size)
        val peak=mags.maxOrNull() ?: 0.0
        var jerkSq=0.0; var jerkN=0
        for(i in 1 until samples.size) {
            val dt=(samples[i].tsMs-samples[i-1].tsMs)/1000.0
            if(dt>0 && dt<1) { val j=(mags[i]-mags[i-1])/dt; jerkSq+=j*j; jerkN++ }
        }
        val threshold=thresholdProvider()
        val active=mags.count { it>=threshold }
        var transitions=0; var prev=mags.first()>=threshold
        for(m in mags.drop(1)) { val now=m>=threshold; if(now!=prev) transitions++; prev=now }
        val p=JSONObject().apply {
            put("rms",rms); put("mad",mad); put("sd",sd); put("peak",peak)
            put("jerk_rms", if(jerkN>0) sqrt(jerkSq/jerkN) else 0.0)
            put("active_fraction", active.toDouble()/mags.size)
            put("transitions",transitions); put("sample_count",samples.size)
            put("duration_ms",samples.last().tsMs-samples.first().tsMs)
            put("active_threshold_ms2",threshold)
            if(includeRaw()) put("raw", JSONArray().apply { samples.forEach { put(JSONArray().put(it.tsMs).put(it.x).put(it.y).put(it.z)) } })
        }
        onWindow(samples.last().tsMs,p)
        samples.clear(); windowStart=0
    }
}
