package au.org.deliriumwatch.watch

/** Each response/timeout must belong to the currently open trial. */
class TrialGate {
    private var generation = 0
    private var open = false
    fun begin(): Int { generation++; open = true; return generation }
    fun finish(token: Int): Boolean {
        if (!open || token != generation) return false
        open = false
        return true
    }
    fun cancel() { generation++; open = false }
}
