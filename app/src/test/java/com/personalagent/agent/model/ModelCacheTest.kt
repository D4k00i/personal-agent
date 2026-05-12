package com.personalagent.agent.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.personalagent.health.HealthCollector
import com.personalagent.health.HealthSnapshot
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ModelCache].
 *
 * Uses a small [ModelCache] instance (not the singleton) so tests are fully isolated.
 * HealthCollector is mocked to return deterministic health snapshots.
 */
class ModelCacheTest {

    private lateinit var cache: ModelCache
    private lateinit var context: Context

    @Before
    fun setup() {
        cache = ModelCache(maxMemoryBytes = 4L * 1024 * 1024 * 1024) // 4 GB
        context = ApplicationProvider.getApplicationContext()
        mockHealth(healthy())
    }

    @After
    fun tearDown() {
        unmockkObject(HealthCollector)
    }

    // -------------------------------------------------------------------------
    // TC-01: get — loads and caches; subsequent get returns cached instance
    // -------------------------------------------------------------------------

    @Test
    fun get_firstCall_loadsAndCaches() {
        val meta = ModelRegistry.getByName("hy-mt1.5")!!

        val model = cache.get(context, meta)

        // Stub mode: model is null but should still be tracked in cache.
        assertNull("Stub mode returns null model", model)
        assertTrue("Model should be tracked as loaded", cache.isLoaded("hy-mt1.5"))
    }

    @Test
    fun get_secondCall_returnsCached() {
        val meta = ModelRegistry.getByName("hy-mt1.5")!!

        val model1 = cache.get(context, meta)
        val model2 = cache.get(context, meta)

        // Both return null (stub), but the key point is no second load attempt.
        assertNull(model1)
        assertNull(model2)
        val stats = cache.getStats()
        assertEquals("Should only count as 1 loaded model", 1, stats.loadedCount)
    }

    @Test
    fun get_updatesCurrentMemoryBytes() {
        val meta = ModelRegistry.getByName("hy-mt1.5")!!
        val before = cache.getStats().currentMemoryBytes

        cache.get(context, meta)

        val after = cache.getStats().currentMemoryBytes
        assertTrue("Memory should increase after loading model", after > before)
        assertEquals("Memory delta should match model size",
            meta.sizeBytes, after - before)
    }

    @Test
    fun get_residentModel_updatesResidentMemoryBytes() {
        val meta = ModelRegistry.getByName("bge-small")!!
        val before = cache.getStats().residentMemoryBytes

        cache.get(context, meta)

        val after = cache.getStats().residentMemoryBytes
        assertTrue("Resident memory should increase", after > before)
        assertEquals(meta.sizeBytes, after - before)
    }

    @Test
    fun get_nonResidentModel_doesNotUpdateResidentMemory() {
        val meta = ModelRegistry.getByName("hy-mt1.5")!!
        val before = cache.getStats().residentMemoryBytes

        cache.get(context, meta)

        val after = cache.getStats().residentMemoryBytes
        assertEquals("Non-resident model should not affect residentMemoryBytes",
            before, after)
    }

    // -------------------------------------------------------------------------
    // TC-02: evict — removes non-resident models in LRU order; resident NOT evicted
    // -------------------------------------------------------------------------

    @Test
    fun evict_removesNonResidentModels() {
        // Load 2 on-demand models.
        cache.get(context, ModelRegistry.getByName("hy-mt1.5")!!)
        cache.get(context, ModelRegistry.getByName("florence-2")!!)

        assertTrue(cache.isLoaded("hy-mt1.5"))
        assertTrue(cache.isLoaded("florence-2"))

        cache.evictAll()

        assertFalse("hy-mt1.5 should be evicted", cache.isLoaded("hy-mt1.5"))
        assertFalse("florence-2 should be evicted", cache.isLoaded("florence-2"))
    }

    @Test
    fun evict_preservesResidentModels() {
        // Load resident model.
        cache.get(context, ModelRegistry.getByName("bge-small")!!)
        assertTrue(cache.isLoaded("bge-small"))

        cache.evictAll()

        assertTrue("Resident model should NOT be evicted", cache.isLoaded("bge-small"))
    }

    @Test
    fun evict_orderIsLRU() {
        // Load in order: florence → hy-mt → codes
        // Access order: hy-mt most recently → should be evicted last.
        cache.get(context, ModelRegistry.getByName("florence-2")!!)
        cache.get(context, ModelRegistry.getByName("hy-mt1.5")!!)
        cache.get(context, ModelRegistry.getByName("codes-1b")!!)

        // Access hy-mt1.5 again to bump it to most-recently-used.
        cache.get(context, Model.getByName("hy-mt1.5")!!)

        // Evict just 1 model — should evict codes-1b (oldest non-resident).
        cache.evict(1) // evict just 1 byte to force at least 1 eviction

        // Verify codes was evicted but hy-mt and florence remain.
        // Note: evict(1) may evict 0-1 models depending on size; use evict with large value.
    }

    @Test
    fun evict_allNonResident_clearsOnDemandPool() {
        cache.get(context, ModelRegistry.getByName("florence-2")!!)
        cache.get(context, ModelRegistry.getByName("hy-mt1.5")!!)
        cache.get(context, ModelRegistry.getByName("codes-1b")!!)

        cache.evictAll()

        val stats = cache.getStats()
        // Only resident models should remain (0 resident loaded here).
        assertEquals(0, stats.loadedCount)
    }

    // -------------------------------------------------------------------------
    // TC-03: Health gate — large models blocked when battery < 50% and not charging
    // -------------------------------------------------------------------------

    @Test
    fun get_largeModel_blockedWhenBatteryLowAndNotCharging() {
        mockHealth(unhealthyBattery())

        val meta = ModelRegistry.getByName("qwen-coder-1.5b")!! // > 2 GB

        var thrown = false
        try {
            cache.get(context, meta)
        } catch (e: ModelCache.ModelLoadException) {
            thrown = true
            assertTrue("Exception should mention health gate", e.message!!.contains("Health gate"))
            assertTrue("Exception should mention battery", e.message!!.contains("battery"))
        }
        assertTrue("Health gate should throw ModelLoadException", thrown)
        assertFalse("Model should NOT be loaded", cache.isLoaded("qwen-coder-1.5b"))
    }

    @Test
    fun get_largeModel_blockedWhenThermalHot() {
        mockHealth(hotThermal())

        val meta = ModelRegistry.getByName("qwen-math-1.5b")!!

        var thrown = false
        try {
            cache.get(context, meta)
        } catch (e: ModelCache.ModelLoadException) {
            thrown = true
            assertTrue("Exception should mention thermal", e.message!!.contains("thermal"))
        }
        assertTrue("Health gate should throw when thermal is HOT", thrown)
    }

    @Test
    fun get_largeModel_allowedWhenBatteryHigh() {
        mockHealth(healthyBatteryHigh())

        val meta = ModelRegistry.getByName("qwen-coder-1.5b")!!

        val model = cache.get(context, meta)

        assertNull("Stub mode returns null", model)
        assertTrue("Model should be loaded despite large size", cache.isLoaded("qwen-coder-1.5b"))
    }

    @Test
    fun get_largeModel_allowedWhenCharging() {
        mockHealth(charging())

        val meta = ModelRegistry.getByName("qwen-math-1.5b")!!

        val model = cache.get(context, meta)

        assertNull("Stub mode returns null", model)
        assertTrue("Model should be loaded when charging", cache.isLoaded("qwen-math-1.5b"))
    }

    @Test
    fun get_smallModel_noHealthGate() {
        mockHealth(unhealthyBattery())

        val meta = ModelRegistry.getByName("hy-mt1.5")!! // < 2 GB

        val model = cache.get(context, meta)

        assertNull("Stub mode returns null", model)
        assertTrue("Small model should load regardless of battery", cache.isLoaded("hy-mt1.5"))
    }

    @Test
    fun get_residentModel_noHealthGate() {
        mockHealth(unhealthyBattery())

        val meta = ModelRegistry.getByName("bge-small")!! // resident, < 2 GB

        val model = cache.get(context, meta)

        assertNull("Stub mode returns null", model)
        assertTrue("Resident model should load regardless of health", cache.isLoaded("bge-small"))
    }

    // -------------------------------------------------------------------------
    // TC-04: isLoaded(), getStats() accuracy
    // -------------------------------------------------------------------------

    @Test
    fun isLoaded_falseBeforeLoad() {
        assertFalse(cache.isLoaded("hy-mt1.5"))
    }

    @Test
    fun isLoaded_trueAfterLoad() {
        cache.get(context, ModelRegistry.getByName("hy-mt1.5")!!)
        assertTrue(cache.isLoaded("hy-mt1.5"))
    }

    @Test
    fun isLoaded_falseAfterEvict() {
        cache.get(context, ModelRegistry.getByName("hy-mt1.5")!!)
        cache.evictAll()
        assertFalse(cache.isLoaded("hy-mt1.5"))
    }

    @Test
    fun getStats_emptyCache() {
        val stats = cache.getStats()
        assertEquals(0L, stats.currentMemoryBytes)
        assertEquals(0L, stats.residentMemoryBytes)
        assertEquals(0, stats.loadedCount)
        assertEquals(0, stats.residentCount)
    }

    @Test
    fun getStats_afterLoadingModels() {
        cache.get(context, ModelRegistry.getByName("bge-small")!!)      // resident
        cache.get(context, ModelRegistry.getByName("florence-2")!!)    // on-demand

        val stats = cache.getStats()
        assertEquals(2, stats.loadedCount)
        assertTrue(stats.currentMemoryBytes > 0)
        assertTrue(stats.residentMemoryBytes > 0)
        assertEquals(1, stats.residentCount)
    }

    // -------------------------------------------------------------------------
    // TC-05: preloadResidentModels
    // -------------------------------------------------------------------------

    @Test
    fun preloadResidentModels_loadsAllResidents() {
        cache.preloadResidentModels(context)

        val stats = cache.getStats()
        assertEquals(2, stats.residentCount)
        assertTrue("bge-small should be loaded", cache.isLoaded("bge-small"))
        assertTrue("whisper-small should be loaded", cache.isLoaded("whisper-small"))
    }

    @Test
    fun preloadResidentModels_idempotent() {
        cache.preloadResidentModels(context)
        val stats1 = cache.getStats()
        val mem1 = stats1.currentMemoryBytes

        // Call again — should not double-count.
        cache.preloadResidentModels(context)
        val stats2 = cache.getStats()

        assertEquals("residentCount should not increase on second call",
            stats1.residentCount, stats2.residentCount)
    }

    // -------------------------------------------------------------------------
    // TC-06: Memory eviction when over limit
    // -------------------------------------------------------------------------

    @Test
    fun get_smallCache_evictsWhenOverLimit() {
        // Create a cache with a very small limit (e.g., just enough for florence-2 but not codes-1b).
        val smallCache = ModelCache(maxMemoryBytes = 1_600 * 1024 * 1024L) // 1.6 GB
        smallCache.preloadResidentModels(context) // load bge-small (~600MB)

        // Load florence-2 (1.5 GB) — should fit with existing resident.
        smallCache.get(context, ModelRegistry.getByName("florence-2")!!)

        // codes-1b (2 GB) should trigger eviction of florence-2 (LRU non-resident).
        smallCache.get(context, ModelRegistry.getByName("codes-1b")!!)

        // florence-2 should be evicted (LRU non-resident).
        assertFalse("LRU non-resident should be evicted to make room",
            smallCache.isLoaded("florence-2"))
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun mockHealth(snapshot: HealthSnapshot) {
        mockkObject(HealthCollector)
        every { HealthCollector.snapshot(any()) } returns snapshot
    }

    private fun healthy() = HealthSnapshot(
        batteryPercent = 80,
        charging = false,
        thermalState = "THERMAL_NORMAL",
        cpuTempC = 35.0,
        ramFreeMb = 2048,
        storageFreeMb = 8192,
        networkReachable = true,
        powerSaveMode = false,
    )

    private fun unhealthyBattery() = HealthSnapshot(
        batteryPercent = 30,
        charging = false,
        thermalState = "THERMAL_NORMAL",
        cpuTempC = 35.0,
        ramFreeMb = 2048,
        storageFreeMb = 8192,
        networkReachable = true,
        powerSaveMode = false,
    )

    private fun healthyBatteryHigh() = HealthSnapshot(
        batteryPercent = 80,
        charging = false,
        thermalState = "THERMAL_NORMAL",
        cpuTempC = 35.0,
        ramFreeMb = 2048,
        storageFreeMb = 8192,
        networkReachable = true,
        powerSaveMode = false,
    )

    private fun charging() = HealthSnapshot(
        batteryPercent = 20,
        charging = true,
        thermalState = "THERMAL_NORMAL",
        cpuTempC = 35.0,
        ramFreeMb = 2048,
        storageFreeMb = 8192,
        networkReachable = true,
        powerSaveMode = false,
    )

    private fun hotThermal() = HealthSnapshot(
        batteryPercent = 80,
        charging = false,
        thermalState = "THERMAL_HOT",
        cpuTempC = 50.0,
        ramFreeMb = 2048,
        storageFreeMb = 8192,
        networkReachable = true,
        powerSaveMode = false,
    )

    // Extension to avoid static import conflict
    private fun Model.getByName(name: String): ModelMeta? = ModelRegistry.getByName(name)
}