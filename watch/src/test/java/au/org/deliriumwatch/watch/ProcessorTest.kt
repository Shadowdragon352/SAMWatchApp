package au.org.deliriumwatch.watch

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProcessorTest {
    @Test fun greenOnlyPpgDoesNotRequireUnusedWavelengths() {
        var result: JSONObject? = null
        val processor = PpgProcessor({true}) { _, p -> result = p }
        processor.add(PpgSample(1000, 100, null, null, 0, null, null))
        processor.add(PpgSample(6000, 200, null, null, 0, null, null))
        assertEquals(1.0, result!!.getDouble("valid_fraction"), 0.0)
        assertEquals(150.0, result!!.getDouble("green_mean"), 0.0)
        assertTrue(result!!.isNull("red_mean"))
        assertTrue(result!!.getJSONArray("raw").getJSONArray(0).isNull(2))
    }
    @Test fun missingAndInvalidStatusesAreNotNormalPpg() {
        var result: JSONObject? = null
        val processor = PpgProcessor({false}) { _, p -> result = p }
        processor.add(PpgSample(1000, 100, null, null, null, null, null))
        processor.add(PpgSample(6000, 900, null, null, -1, null, null))
        assertEquals(0.0, result!!.getDouble("valid_fraction"), 0.0)
        assertTrue(result!!.isNull("green_mean"))
    }
    @Test fun timestampResetSplitsPpgWindow() {
        val results = mutableListOf<JSONObject>()
        val processor = PpgProcessor({false}) { _, p -> results.add(p) }
        processor.add(PpgSample(5000, 100, null, null, 0, null, null))
        processor.add(PpgSample(1000, 200, null, null, 0, null, null))
        processor.flush()
        assertEquals(2, results.size)
        assertEquals(100.0, results[0].getDouble("green_mean"), 0.0)
        assertEquals(200.0, results[1].getDouble("green_mean"), 0.0)
    }
    @Test fun emptyPpgIsUnknownAndWindowStorageIsBounded() {
        val results = mutableListOf<JSONObject>()
        val processor = PpgProcessor({false}) { _, p -> results.add(p) }
        repeat(250) { processor.add(PpgSample(1000, null, null, null, null, null, null)) }
        assertEquals(1, results.size)
        assertEquals(0.0, results[0].getDouble("valid_fraction"), 0.0)
    }
    @Test fun previousTimeoutCannotFinishNextTrial() {
        val gate = TrialGate()
        val previous = gate.begin()
        assertTrue(gate.finish(previous))
        val next = gate.begin()
        assertFalse(gate.finish(previous))
        assertTrue(gate.finish(next))
        assertFalse(gate.finish(next))
    }
    @Test fun interruptedTrialCannotAcceptAnswer() {
        val gate = TrialGate(); val token = gate.begin(); gate.cancel()
        assertFalse(gate.finish(token))
    }
    @Test fun immobilityProducesZeroMovementAndIgnoresNonfiniteSamples() {
        var result: JSONObject? = null
        val processor = MovementProcessor(5000, {0.12}, {true}) { _, p -> result = p }
        processor.add(AccelSample(1000, 0.0, 0.0, 0.0))
        processor.add(AccelSample(2000, Double.NaN, 0.0, 0.0))
        processor.add(AccelSample(6000, 0.0, 0.0, 0.0))
        assertEquals(2, result!!.getInt("sample_count"))
        assertEquals(0.0, result!!.getDouble("rms"), 0.0)
        assertEquals(0.0, result!!.getDouble("active_fraction"), 0.0)
    }
    @Test fun constantMovementAndJerkAreCalculated() {
        var result: JSONObject? = null
        val processor = MovementProcessor(1000, {0.12}, {false}) { _, p -> result = p }
        processor.add(AccelSample(1000, 0.2, 0.0, 0.0))
        processor.add(AccelSample(1040, 0.4, 0.0, 0.0))
        processor.flush()
        assertEquals(1.0, result!!.getDouble("active_fraction"), 0.0)
        assertEquals(5.0, result!!.getDouble("jerk_rms"), 1e-9)
    }
}
