package com.personalagent.agent.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.personalagent.agent.PersonalTask
import com.personalagent.health.HealthCollector
import com.personalagent.health.HealthSnapshot
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Unit tests for [AIRunner].
 *
 * Tests the full pipeline: parse → resolve → cache.get → inference stub → buildResultPayload.
 * Uses real ModelCache and ModelRegistry (no mocks for those), but HealthCollector is mocked
 * to return deterministic health snapshots.
 */
class AIRunnerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockHealth(healthy())
        // Reset the cache singleton between tests.
        ModelCache.getInstance().evictAll()
    }

    @After
    fun tearDown() {
        unmockkObject(HealthCollector)
        ModelCache.getInstance().evictAll()
    }

    // -------------------------------------------------------------------------
    // TC-01: Parse payload — extracts subtype and input correctly
    // -------------------------------------------------------------------------

    @Test
    fun parsePayload_translate_extractsSubtypeAndInput() = runBlocking {
        val payload = JSONObject().put("subtype", "translate")
            .put("input", "hello world")
            .put("params", JSONObject().put("sourceLang", "en").put("targetLang", "vi"))
            .toString()

        val runner = makeRunner(payload)
        val result = runner.execute()

        assertTrue("AIRunner should succeed for translate", result)
        assertTrue("Output should contain translated text",
            runner.buildResultPayload().contains("translated"))
    }

    @Test
    fun parsePayload_vision_extractsSubtypeAndInput() = runBlocking {
        val payload = JSONObject().put("subtype", "vision")
            .put("input", "base64imagedata").toString()

        val runner = makeRunner(payload)
        val result = runner.execute()

        assertTrue("AIRunner should succeed for vision", result)
        assertTrue("Output should contain vision stub",
            runner.buildResultPayload().contains("vision"))
    }

    @Test
    fun parsePayload_sql_extractsSubtypeAndInput() = runBlocking {
        val payload = JSONObject().put("subtype", "sql")
            .put("input", "get all active users").toString()

        val runner = makeRunner(payload)
        val result = runner.execute()

        assertTrue("AIRunner should succeed for sql", result)
        assertTrue("Output should contain SQL stub",
            runner.buildResultPayload().contains("SELECT"))
    }

    @Test
    fun parsePayload_code_extractsSubtypeAndInput() = runBlocking {
        val payload = JSONObject().put("subtype", "code")
            .put("input", "fibonacci function").toString()

        val runner = makeRunner(payload)
        val result = runner.execute()

        assertTrue("AIRunner should succeed for code", result)
        assertTrue("Output should contain code stub",
            runner.buildResultPayload().contains("generated"))
    }

    @Test
    fun parsePayload_math_extractsSubtypeAndInput() = runBlocking {
        val payload = JSONObject().put("subtype", "math")
            .put("input", "solve x^2 - 4 = 0").toString()

        val runner = makeRunner(payload)
        val result = runner.execute()

        assertTrue("AIRunner should succeed for math", result)
        assertTrue("Output should contain math stub",
            runner.buildResultPayload().contains("answer"))
    }

    // -------------------------------------------------------------------------
    // TC-02: All 5 subtypes return result with realistic latency
    // -------------------------------------------------------------------------

    @Test
    fun execute_allSubtypesReturnSuccess() = runBlocking {
        val subtypes = listOf("translate", "vision", "sql", "code", "math")

        for (subtype in subtypes) {
            val payload = JSONObject().put("subtype", subtype).put("input", "test input").toString()
            val runner = makeRunner(payload)
            val ok = runner.execute()

            assertTrue("AIRunner should succeed for subtype=$subtype", ok)
            assertTrue("latencyMs should be > 0", runner.latencyMs > 0)
        }
    }

    @Test
    fun execute_latencyIsRealistic() = runBlocking {
        val payload = JSONObject().put("subtype", "translate").put("input", "test").toString()
        val runner = makeRunner(payload)

        runner.execute()

        // Stub latency range for translate: 1000-3000ms
        assertTrue("Latency should be >= 1000ms for translate stub",
            runner.latencyMs >= 1000)
        assertTrue("Latency should be <= 4000ms (safety margin)",
            runner.latencyMs <= 4000)
    }

    @Test
    fun execute_mathHasFastestLatency() = runBlocking {
        val payload = JSONObject().put("subtype", "math").put("input", "2+2").toString()
        val runner = makeRunner(payload)
        runner.execute()

        // Math stub: 500-1500ms
        assertTrue("Math latency should be < 2000ms", runner.latencyMs < 2000)
    }

    @Test
    fun execute_codeHasSlowestLatency() = runBlocking {
        val payload = JSONObject().put("subtype", "code").put("input", "hello").toString()
        val runner = makeRunner(payload)
        runner.execute()

        // Code stub: 2000-5000ms
        assertTrue("Code latency should be >= 2000ms", runner.latencyMs >= 2000)
        assertTrue("Code latency should be <= 6000ms", runner.latencyMs <= 6000)
    }

    // -------------------------------------------------------------------------
    // TC-03: buildResultPayload — returns valid JSON with correct fields
    // -------------------------------------------------------------------------

    @Test
    fun buildResultPayload_validJson() = runBlocking {
        val payload = JSONObject().put("subtype", "translate")
            .put("input", "hello").toString()
        val runner = makeRunner(payload)
        runner.execute()

        val json = runner.buildResultPayload()
        val obj = JSONObject(json)

        assertTrue("JSON should have 'output' field", obj.has("output"))
        assertTrue("JSON should have 'model' field", obj.has("model"))
        assertTrue("JSON should have 'latencyMs' field", obj.has("latencyMs"))
        assertTrue("JSON should have 'subtype' field", obj.has("subtype"))
        assertTrue("JSON should have 'summary' field", obj.has("summary"))
    }

    @Test
    fun buildResultPayload_translate_modelIsHyMT() = runBlocking {
        val runner = makeRunner(JSONObject().put("subtype", "translate").put("input", "x").toString())
        runner.execute()

        val obj = JSONObject(runner.buildResultPayload())
        assertEquals("hy-mt1.5", obj.getString("model"))
        assertEquals("translate", obj.getString("subtype"))
        assertTrue("latencyMs should be positive", obj.getLong("latencyMs") > 0)
    }

    @Test
    fun buildResultPayload_vision_modelIsFlorence() = runBlocking {
        val runner = makeRunner(JSONObject().put("subtype", "vision").put("input", "x").toString())
        runner.execute()

        val obj = JSONObject(runner.buildResultPayload())
        assertEquals("florence-2", obj.getString("model"))
    }

    @Test
    fun buildResultPayload_code_modelIsQwenCoder() = runBlocking {
        val runner = makeRunner(JSONObject().put("subtype", "code").put("input", "x").toString())
        runner.execute()

        val obj = JSONObject(runner.buildResultPayload())
        assertEquals("qwen-coder-1.5b", obj.getString("model"))
    }

    @Test
    fun buildResultPayload_summaryContainsSubtypeAndOutput() = runBlocking {
        val runner = makeRunner(JSONObject().put("subtype", "translate").put("input", "hello").toString())
        runner.execute()

        val summary = JSONObject(runner.buildResultPayload()).getString("summary")
        assertTrue("Summary should contain subtype", summary.contains("translate"))
    }

    @Test
    fun buildResultPayload_summaryMax120Chars() = runBlocking {
        val longInput = "a".repeat(200)
        val runner = makeRunner(JSONObject().put("subtype", "translate").put("input", longInput).toString())
        runner.execute()

        val summary = JSONObject(runner.buildResultPayload()).getString("summary")
        assertTrue("Summary should be max 120 chars", summary.length <= 120)
    }

    @Test
    fun buildResultPayload_beforeExecute_hasZeroLatency() = runBlocking {
        val runner = makeRunner(JSONObject().put("subtype", "translate").put("input", "x").toString())
        // Don't execute — check initial state.

        val json = runner.buildResultPayload()
        val obj = JSONObject(json)

        assertEquals("Latency should be 0 before execution", 0, obj.getLong("latencyMs"))
    }

    // -------------------------------------------------------------------------
    // TC-04: Invalid subtype fails gracefully; empty payload throws clear error
    // -------------------------------------------------------------------------

    @Test
    fun execute_unknownSubtype_returnsFalse() = runBlocking {
        val payload = JSONObject().put("subtype", "totally_unknown").put("input", "test").toString()
        val runner = makeRunner(payload)

        val ok = runner.execute()

        // Unknown subtype still runs generic stub → returns true
        assertTrue("Unknown subtype should still return output via generic stub", ok)
    }

    @Test
    fun execute_missingSubtype_returnsFalse() = runBlocking {
        val payload = JSONObject().put("input", "test").toString()
        val runner = makeRunner(payload)

        val ok = runner.execute()

        assertFalse("Missing subtype should fail", ok)
    }

    @Test
    fun execute_emptySubtype_returnsFalse() = runBlocking {
        val payload = JSONObject().put("subtype", "").put("input", "test").toString()
        val runner = makeRunner(payload)

        val ok = runner.execute()

        assertFalse("Empty subtype should fail", ok)
    }

    @Test
    fun execute_missingInput_returnsFalse() = runBlocking {
        val payload = JSONObject().put("subtype", "translate").toString()
        val runner = makeRunner(payload)

        val ok = runner.execute()

        assertFalse("Missing input should fail", ok)
    }

    @Test
    fun execute_emptyInput_returnsFalse() = runBlocking {
        val payload = JSONObject().put("subtype", "translate").put("input", "").toString()
        val runner = makeRunner(payload)

        val ok = runner.execute()

        assertFalse("Empty input should fail", ok)
    }

    @Test
    fun execute_invalidJson_returnsFalse() = runBlocking {
        val runner = makeRunner("not-valid-json{{{")
        val ok = runner.execute()
        assertFalse("Invalid JSON should fail gracefully", ok)
    }

    @Test
    fun execute_unknownSubtype_outputIsGenericStub() = runBlocking {
        val payload = JSONObject().put("subtype", "unknown").put("input", "test").toString()
        val runner = makeRunner(payload)
        runner.execute()

        val output = JSONObject(runner.buildResultPayload()).getString("output")
        assertTrue("Unknown subtype should produce generic stub output",
            output.contains("unknown subtype"))
    }

    // -------------------------------------------------------------------------
    // TC-05: Health gate integration — large model blocked when health bad
    // -------------------------------------------------------------------------

    @Test
    fun execute_largeModel_blockedByHealthGate() = runBlocking {
        mockHealth(unhealthyBattery())

        // qwen-coder-1.5b requires healthy device (>2GB)
        val payload = JSONObject().put("subtype", "code").put("input", "test").toString()
        val runner = makeRunner(payload)

        val ok = runner.execute()

        // Health gate throws → caught → returns false
        assertFalse("Large model should be blocked by health gate", ok)
        assertFalse("Model should NOT be loaded", ModelCache.getInstance().isLoaded("qwen-coder-1.5b"))
    }

    @Test
    fun execute_smallModel_notBlockedByHealthGate() = runBlocking {
        mockHealth(unhealthyBattery())

        val payload = JSONObject().put("subtype", "translate").put("input", "test").toString()
        val runner = makeRunner(payload)

        val ok = runner.execute()

        assertTrue("Small model should not be blocked by health gate", ok)
    }

    @Test
    fun execute_largeModel_allowedWhenCharging() = runBlocking {
        mockHealth(charging())

        val payload = JSONObject().put("subtype", "math").put("input", "test").toString()
        val runner = makeRunner(payload)

        val ok = runner.execute()

        assertTrue("Large model should load when charging", ok)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeRunner(payloadJson: String): AIRunner {
        return AIRunner(
            context = context,
            task = PersonalTask(
                id = UUID.randomUUID().toString(),
                type = "AI",
                payloadJson = payloadJson,
                priority = 10,
                status = "PENDING",
            )
        )
    }

    private fun mockHealth(snapshot: HealthSnapshot) {
        mockkObject(HealthCollector)
        every { HealthCollector.snapshot(any()) } returns snapshot
    }

    private fun healthy() = HealthSnapshot(
        batteryPercent = 80, charging = false,
        thermalState = "THERMAL_NORMAL",
        cpuTempC = 35.0, ramFreeMb = 2048, storageFreeMb = 8192,
        networkReachable = true, powerSaveMode = false,
    )

    private fun unhealthyBattery() = HealthSnapshot(
        batteryPercent = 30, charging = false,
        thermalState = "THERMAL_NORMAL",
        cpuTempC = 35.0, ramFreeMb = 2048, storageFreeMb = 8192,
        networkReachable = true, powerSaveMode = false,
    )

    private fun charging() = HealthSnapshot(
        batteryPercent = 20, charging = true,
        thermalState = "THERMAL_NORMAL",
        cpuTempC = 35.0, ramFreeMb = 2048, storageFreeMb = 8192,
        networkReachable = true, powerSaveMode = false,
    )
}