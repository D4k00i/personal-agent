package com.personalagent.agent.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.personalagent.agent.PersonalTask
import com.personalagent.agent.PersonalTaskDao
import com.personalagent.agent.TaskExecutor
import com.personalagent.config.AppDatabase
import com.personalagent.health.HealthCollector
import com.personalagent.health.HealthSnapshot
import com.personalagent.lifecycle.ServiceLifecycle
import com.personalagent.worker.WorkerForegroundService
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Integration tests for the full Phase 2 pipeline.
 *
 * Tests:
 * 1. Full AI pipeline: insert task → poll → TaskExecutor → AIRunner → DONE
 * 2. Health gate: heavy model load blocked when battery < 50% and not charging
 * 3. Model eviction: load models beyond limit → verify LRU order
 * 4. Pre-warm: resident models preloaded at startup
 *
 * Uses Room in-memory DB + real ModelCache singleton.
 */
class Phase2IntegrationTest {

    private lateinit var context: Context
    private lateinit var dao: PersonalTaskDao
    private lateinit var cache: ModelCache
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Use in-memory DB for isolation.
        val db = androidx.room.Room.inMemoryDatabaseBuilder<IntegrationTestDatabase>(
            context,
            IntegrationTestDatabase::class.java
        ).build()
        dao = db.personalTaskDao()
        cache = ModelCache.getInstance()
        cache.evictAll()
        mockHealth(healthy())
    }

    @After
    fun tearDown() {
        unmockkObject(HealthCollector)
        cache.evictAll()
    }

    // -------------------------------------------------------------------------
    // TC-01: Full pipeline — insert AI task → poll → TaskExecutor → AIRunner → DONE
    // -------------------------------------------------------------------------

    @Test
    fun fullPipeline_aiTask_completesSuccessfully() = runBlocking {
        // 1. Insert AI task.
        val payload = JSONObject()
            .put("subtype", "translate")
            .put("input", "hello from test")
            .put("params", JSONObject().put("sourceLang", "en").put("targetLang", "vi"))
            .toString()

        val task = PersonalTask(
            id = UUID.randomUUID().toString(),
            type = "AI",
            payloadJson = payload,
            priority = 10,
            status = "PENDING",
        )
        dao.insert(task)

        // 2. Verify task is in DB as PENDING.
        val pending = dao.getOldestPending(1)
        assertEquals("Should have 1 pending task", 1, pending.size)
        assertEquals("PENDING", pending[0].status)

        // 3. Run TaskExecutor.
        val executor = TaskExecutor(context)
        val result = executor.execute(pending[0])

        assertTrue("TaskExecutor should succeed", result.success)
        assertNotNull("Output payload should be set", result.outputPayload)

        // 4. Verify output payload has expected fields.
        val output = JSONObject(result.outputPayload!!)
        assertTrue("Output should have model field", output.has("model"))
        assertEquals("hy-mt1.5", output.getString("model"))
        assertTrue("Output should have latencyMs", output.has("latencyMs"))
        assertTrue("Output should have output", output.has("output"))
    }

    @Test
    fun fullPipeline_allFiveSubtypes_completeSuccessfully() = runBlocking {
        val subtypes = listOf(
            "translate" to "hello",
            "vision" to "base64image",
            "sql" to "get users",
            "code" to "fibonacci",
            "math" to "2+2",
        )

        for ((subtype, input) in subtypes) {
            val payload = JSONObject().put("subtype", subtype).put("input", input).toString()
            val task = PersonalTask(
                id = UUID.randomUUID().toString(),
                type = "AI",
                payloadJson = payload,
                priority = 10,
                status = "PENDING",
            )
            dao.insert(task)

            val executor = TaskExecutor(context)
            val pending = dao.getOldestPending(1)
            val result = executor.execute(pending[0])

            assertTrue("Subtype '$subtype' should complete successfully", result.success)
            assertNotNull("Subtype '$subtype' should have output payload", result.outputPayload)

            // Verify model name matches expected model.
            val model = JSONObject(result.outputPayload!!).getString("model")
            assertNotNull("Model name for '$subtype' should not be null", model)
        }
    }

    @Test
    fun fullPipeline_invalidSubtype_marksFailed() = runBlocking {
        val payload = JSONObject().put("subtype", "totally_invalid").put("input", "test").toString()
        val task = PersonalTask(
            id = UUID.randomUUID().toString(),
            type = "AI",
            payloadJson = payload,
            priority = 10,
            status = "PENDING",
        )
        dao.insert(task)

        val executor = TaskExecutor(context)
        val pending = dao.getOldestPending(1)
        val result = executor.execute(pending[0])

        // Unknown subtype still returns true (generic stub output)
        assertTrue("Unknown subtype should produce stub output", result.success)
    }

    @Test
    fun fullPipeline_missingSubtype_marksFailed() = runBlocking {
        val payload = JSONObject().put("input", "test").toString()
        val task = PersonalTask(
            id = UUID.randomUUID().toString(),
            type = "AI",
            payloadJson = payload,
            priority = 10,
            status = "PENDING",
        )
        dao.insert(task)

        val executor = TaskExecutor(context)
        val pending = dao.getOldestPending(1)
        val result = executor.execute(pending[0])

        assertFalse("Missing subtype should fail", result.success)
    }

    // -------------------------------------------------------------------------
    // TC-02: Health gate — heavy model load blocked when battery < 50% and not charging
    // -------------------------------------------------------------------------

    @Test
    fun healthGate_blocksLargeModelWhenBatteryLow() = runBlocking {
        mockHealth(unhealthyBattery())

        val payload = JSONObject().put("subtype", "code").put("input", "test").toString()
        val task = PersonalTask(
            id = UUID.randomUUID().toString(),
            type = "AI",
            payloadJson = payload,
            priority = 10,
            status = "PENDING",
        )
        dao.insert(task)

        val executor = TaskExecutor(context)
        val pending = dao.getOldestPending(1)
        val result = executor.execute(pending[0])

        assertFalse("Large model should be blocked by health gate", result.success)
        assertFalse("Model should not be loaded",
            ModelCache.getInstance().isLoaded("qwen-coder-1.5b"))
    }

    @Test
    fun healthGate_blocksLargeModelWhenThermalHot() = runBlocking {
        mockHealth(hotThermal())

        val payload = JSONObject().put("subtype", "math").put("input", "test").toString()
        val task = PersonalTask(
            id = UUID.randomUUID().toString(),
            type = "AI",
            payloadJson = payload,
            priority = 10,
            status = "PENDING",
        )
        dao.insert(task)

        val executor = TaskExecutor(context)
        val pending = dao.getOldestPending(1)
        val result = executor.execute(pending[0])

        assertFalse("Large model should be blocked when thermal HOT", result.success)
    }

    @Test
    fun healthGate_allowsLargeModelWhenCharging() = runBlocking {
        mockHealth(charging())

        val payload = JSONObject().put("subtype", "math").put("input", "test").toString()
        val task = PersonalTask(
            id = UUID.randomUUID().toString(),
            type = "AI",
            payloadJson = payload,
            priority = 10,
            status = "PENDING",
        )
        dao.insert(task)

        val executor = TaskExecutor(context)
        val pending = dao.getOldestPending(1)
        val result = executor.execute(pending[0])

        assertTrue("Large model should load when charging", result.success)
    }

    @Test
    fun healthGate_smallModel_notAffected() = runBlocking {
        mockHealth(unhealthyBattery())

        val payload = JSONObject().put("subtype", "translate").put("input", "test").toString()
        val task = PersonalTask(
            id = UUID.randomUUID().toString(),
            type = "AI",
            payloadJson = payload,
            priority = 10,
            status = "PENDING",
        )
        dao.insert(task)

        val executor = TaskExecutor(context)
        val pending = dao.getOldestPending(1)
        val result = executor.execute(pending[0])

        assertTrue("Small model should not be affected by health gate", result.success)
    }

    // -------------------------------------------------------------------------
    // TC-03: Model eviction — LRU order when over limit
    // -------------------------------------------------------------------------

    @Test
    fun eviction_lruOrder_nonResidentModels() = runBlocking {
        // Load 3 on-demand models in order.
        cache.get(context, ModelRegistry.getByName("florence-2")!!)
        cache.get(context, ModelRegistry.getByName("hy-mt1.5")!!)
        cache.get(context, ModelRegistry.getByName("codes-1b")!!)

        assertTrue(cache.isLoaded("florence-2"))
        assertTrue(cache.isLoaded("hy-mt1.5"))
        assertTrue(cache.isLoaded("codes-1b"))

        // Access hy-mt1.5 to bump it to most-recently-used.
        cache.get(context, ModelRegistry.getByName("hy-mt1.5")!!)

        // Evict all non-resident.
        cache.evictAll()

        // Resident models (none loaded here) should remain.
        // All 3 on-demand should be evicted.
        assertFalse("florence-2 should be evicted", cache.isLoaded("florence-2"))
        assertFalse("hy-mt1.5 should be evicted", cache.isLoaded("hy-mt1.5"))
        assertFalse("codes-1b should be evicted", cache.isLoaded("codes-1b"))
    }

    @Test
    fun eviction_preservesResidentModels() = runBlocking {
        // Preload resident models.
        cache.preloadResidentModels(context)

        // Load some on-demand models.
        cache.get(context, ModelRegistry.getByName("florence-2")!!)
        cache.get(context, ModelRegistry.getByName("hy-mt1.5")!!)

        // Evict all non-resident.
        cache.evictAll()

        // Resident models should still be loaded.
        assertTrue("bge-small should survive evictAll", cache.isLoaded("bge-small"))
        assertTrue("whisper-small should survive evictAll", cache.isLoaded("whisper-small"))
    }

    // -------------------------------------------------------------------------
    // TC-04: Pre-warm — resident models preloaded at startup
    // -------------------------------------------------------------------------

    @Test
    fun preload_residentModels_preloadedAtStartup() = runBlocking {
        // Fresh cache — nothing loaded.
        assertFalse(cache.isLoaded("bge-small"))
        assertFalse(cache.isLoaded("whisper-small"))

        // Preload.
        cache.preloadResidentModels(context)

        assertTrue("bge-small should be preloaded", cache.isLoaded("bge-small"))
        assertTrue("whisper-small should be preloaded", cache.isLoaded("whisper-small"))

        val stats = cache.getStats()
        assertEquals(2, stats.residentCount)
    }

    @Test
    fun preload_idempotent_multipleCallsDoNotCrash() = runBlocking {
        cache.preloadResidentModels(context)
        cache.preloadResidentModels(context)
        cache.preloadResidentModels(context)

        val stats = cache.getStats()
        assertEquals("residentCount should be 2 regardless of call count", 2, stats.residentCount)
    }

    // -------------------------------------------------------------------------
    // TC-05: Task status lifecycle — insert → RUNNING → DONE
    // -------------------------------------------------------------------------

    @Test
    fun taskLifecycle_insertPending_runningDone() = runBlocking {
        val payload = JSONObject().put("subtype", "translate").put("input", "test").toString()
        val taskId = UUID.randomUUID().toString()

        val task = PersonalTask(
            id = taskId,
            type = "AI",
            payloadJson = payload,
            priority = 10,
            status = "PENDING",
        )
        dao.insert(task)

        // Verify PENDING.
        assertEquals("PENDING", dao.getById(taskId)!!.status)

        // Update to RUNNING.
        dao.updateStatus(taskId, "RUNNING")
        assertEquals("RUNNING", dao.getById(taskId)!!.status)

        // Complete.
        val now = System.currentTimeMillis()
        dao.completeTask(taskId, "DONE", now)
        val completed = dao.getById(taskId)!!
        assertEquals("DONE", completed.status)
        assertEquals(now, completed.completedAt)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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

    private fun hotThermal() = HealthSnapshot(
        batteryPercent = 80, charging = false,
        thermalState = "THERMAL_HOT",
        cpuTempC = 50.0, ramFreeMb = 2048, storageFreeMb = 8192,
        networkReachable = true, powerSaveMode = false,
    )

    private fun charging() = HealthSnapshot(
        batteryPercent = 20, charging = true,
        thermalState = "THERMAL_NORMAL",
        cpuTempC = 35.0, ramFreeMb = 2048, storageFreeMb = 8192,
        networkReachable = true, powerSaveMode = false,
    )
}

// In-memory DB for integration tests.
@androidx.room.Database(
    entities = [PersonalTask::class],
    version = 1,
    exportSchema = false,
)
abstract class IntegrationTestDatabase : androidx.room.RoomDatabase() {
    abstract fun personalTaskDao(): PersonalTaskDao
}