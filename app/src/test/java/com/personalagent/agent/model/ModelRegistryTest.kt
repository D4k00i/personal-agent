package com.personalagent.agent.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [ModelRegistry].
 *
 * Tests resolve(), getByName(), getResidentModels(), getOnDemandModels(), getAllModels().
 */
class ModelRegistryTest {

    // -------------------------------------------------------------------------
    // TC-01: resolve — all known subtypes
    // -------------------------------------------------------------------------

    @Test
    fun resolve_translate_returnsHyMT() {
        val payload = JSONObject().put("subtype", "translate").put("input", "hello").toString()
        val meta = ModelRegistry.resolve("AI", payload)
        assertNotNull("translate should resolve to a model", meta)
        assertEquals("hy-mt1.5", meta!!.name)
    }

    @Test
    fun resolve_vision_returnsFlorence() {
        val payload = JSONObject().put("subtype", "vision").put("input", "base64image").toString()
        val meta = ModelRegistry.resolve("AI", payload)
        assertNotNull("vision should resolve to a model", meta)
        assertEquals("florence-2", meta!!.name)
    }

    @Test
    fun resolve_sql_returnsCodeS() {
        val payload = JSONObject().put("subtype", "sql").put("input", "get all users").toString()
        val meta = ModelRegistry.resolve("AI", payload)
        assertNotNull("sql should resolve to a model", meta)
        assertEquals("codes-1b", meta!!.name)
    }

    @Test
    fun resolve_code_returnsQwenCoder() {
        val payload = JSONObject().put("subtype", "code").put("input", "fibonacci").toString()
        val meta = ModelRegistry.resolve("AI", payload)
        assertNotNull("code should resolve to a model", meta)
        assertEquals("qwen-coder-1.5b", meta!!.name)
    }

    @Test
    fun resolve_math_returnsQwenMath() {
        val payload = JSONObject().put("subtype", "math").put("input", "2+2").toString()
        val meta = ModelRegistry.resolve("AI", payload)
        assertNotNull("math should resolve to a model", meta)
        assertEquals("qwen-math-1.5b", meta!!.name)
    }

    @Test
    fun resolve_embedding_returnsBGESmall() {
        val payload = JSONObject().put("subtype", "embedding").put("input", "hello world").toString()
        val meta = ModelRegistry.resolve("AI", payload)
        assertNotNull("embedding should resolve to a model", meta)
        assertEquals("bge-small", meta!!.name)
    }

    @Test
    fun resolve_stt_returnsWhisper() {
        val payload = JSONObject().put("subtype", "stt").put("input", "base64audio").toString()
        val meta = ModelRegistry.resolve("AI", payload)
        assertNotNull("stt should resolve to a model", meta)
        assertEquals("whisper-small", meta!!.name)
    }

    @Test
    fun resolve_ocr_returnsFlorence() {
        val payload = JSONObject().put("subtype", "ocr").put("input", "base64doc").toString()
        val meta = ModelRegistry.resolve("AI", payload)
        assertNotNull("ocr should resolve to a model", meta)
        assertEquals("florence-2", meta!!.name)
    }

    // -------------------------------------------------------------------------
    // TC-02: resolve — unknown subtype → null
    // -------------------------------------------------------------------------

    @Test
    fun resolve_unknown_returnsNull() {
        val payload = JSONObject().put("subtype", "unknown_subtype").toString()
        val meta = ModelRegistry.resolve("AI", payload)
        assertNull("unknown subtype should return null", meta)
    }

    @Test
    fun resolve_emptySubtype_returnsNull() {
        val payload = JSONObject().put("subtype", "").toString()
        val meta = ModelRegistry.resolve("AI", payload)
        assertNull("empty subtype should return null", meta)
    }

    @Test
    fun resolve_missingSubtype_returnsNull() {
        val payload = JSONObject().put("input", "hello").toString()
        val meta = ModelRegistry.resolve("AI", payload)
        assertNull("missing subtype should return null", meta)
    }

    @Test
    fun resolve_caseInsensitive() {
        val subtypes = listOf("TRANSLATE", "Translate", "TrAnSlAtE")
        for (s in subtypes) {
            val payload = JSONObject().put("subtype", s).toString()
            assertNotNull("subtype '$s' should resolve (case-insensitive)", ModelRegistry.resolve("AI", payload))
        }
    }

    // -------------------------------------------------------------------------
    // TC-03: Meta properties match spec
    // -------------------------------------------------------------------------

    @Test
    fun resolve_translate_metaHasCorrectProperties() {
        val payload = JSONObject().put("subtype", "translate").put("input", "test").toString()
        val meta = ModelRegistry.resolve("AI", payload)!!

        assertEquals("Hy-MT1.5-1.8B", meta.displayName)
        assertEquals("TFLite", meta.engine)
        assertTrue("translate model should contain 'translate' subtype", meta.taskSubtypes.contains("translate"))
        assertFalse("translate model should not be resident", meta.resident)
        assertFalse("translate model should not require healthy device", meta.requiresHealthyDevice)
    }

    @Test
    fun resolve_vision_metaHasCorrectProperties() {
        val meta = ModelRegistry.resolve("AI", JSONObject().put("subtype", "vision").put("input", "x").toString())!!

        assertEquals("Florence-2-0.77B", meta.displayName)
        assertEquals("TFLite", meta.engine)
        assertTrue(meta.taskSubtypes.containsAll(listOf("vision", "ocr")))
        assertFalse(meta.resident)
        assertFalse(meta.requiresHealthyDevice)
    }

    @Test
    fun resolve_code_metaRequiresHealthyDevice() {
        val meta = ModelRegistry.resolve("AI", JSONObject().put("subtype", "code").put("input", "x").toString())!!

        assertTrue("code model should require healthy device (>2GB)", meta.requiresHealthyDevice)
        assertEquals("ONNX", meta.engine)
    }

    @Test
    fun resolve_math_metaRequiresHealthyDevice() {
        val meta = ModelRegistry.resolve("AI", JSONObject().put("subtype", "math").put("input", "x").toString())!!

        assertTrue("math model should require healthy device (>2GB)", meta.requiresHealthyDevice)
        assertEquals("ONNX", meta.engine)
    }

    @Test
    fun resolve_bgeSmall_isResident() {
        val meta = ModelRegistry.resolve("AI", JSONObject().put("subtype", "embedding").put("input", "x").toString())!!

        assertTrue("embedding model should be resident", meta.resident)
        assertFalse("resident model should not require healthy device", meta.requiresHealthyDevice)
        assertEquals("ONNX", meta.engine)
    }

    @Test
    fun resolve_whisperSmall_isResident() {
        val meta = ModelRegistry.resolve("AI", JSONObject().put("subtype", "stt").put("input", "x").toString())!!

        assertTrue("stt model should be resident", meta.resident)
        assertFalse(meta.requiresHealthyDevice)
    }

    @Test
    fun resolve_allModelsHaveValidSize() {
        for (meta in ModelRegistry.getAllModels()) {
            assertTrue("Model ${meta.name} sizeBytes must be > 0", meta.sizeBytes > 0)
            assertTrue("Model ${meta.name} should have taskSubtypes", meta.taskSubtypes.isNotEmpty())
            assertTrue("Model ${meta.name} should have valid engine", meta.engine in listOf("TFLite", "ONNX"))
        }
    }

    // -------------------------------------------------------------------------
    // TC-04: Other API methods
    // -------------------------------------------------------------------------

    @Test
    fun getByName_validName() {
        val meta = ModelRegistry.getByName("hy-mt1.5")
        assertNotNull("getByName should return model for valid name", meta)
        assertEquals("hy-mt1.5", meta!!.name)
    }

    @Test
    fun getByName_invalidName() {
        val meta = ModelRegistry.getByName("nonexistent-model")
        assertNull("getByName should return null for unknown name", meta)
    }

    @Test
    fun getResidentModels_returnsOnlyResident() {
        val residents = ModelRegistry.getResidentModels()
        assertEquals("Should have 2 resident models", 2, residents.size)
        assertTrue("bge-small should be resident", residents.any { it.name == "bge-small" })
        assertTrue("whisper-small should be resident", residents.any { it.name == "whisper-small" })
        for (m in residents) {
            assertTrue("All resident models must have resident=true: ${m.name}", m.resident)
        }
    }

    @Test
    fun getOnDemandModels_returnsOnlyNonResidentSortedByPriority() {
        val onDemand = ModelRegistry.getOnDemandModels()
        val residentNames = ModelRegistry.getResidentModels().map { it.name }.toSet()

        for (m in onDemand) {
            assertFalse("On-demand model ${m.name} should not be resident", m.resident)
            assertFalse("On-demand model ${m.name} should not be in resident list", residentNames.contains(m.name))
        }

        // Verify sorted by priority ascending
        val priorities = onDemand.map { it.priority }
        assertEquals("On-demand models should be sorted by priority", priorities.sorted(), priorities)
    }

    @Test
    fun getAllModels_allModelsPresent() {
        val all = ModelRegistry.getAllModels()
        assertEquals("Should have 7 total models", 7, all.size)

        val names = all.map { it.name }.toSet()
        val expected = setOf(
            "bge-small", "whisper-small", "florence-2",
            "hy-mt1.5", "codes-1b", "qwen-coder-1.5b", "qwen-math-1.5b"
        )
        assertEquals("All 7 models should be present", expected, names)
    }

    @Test
    fun getAllModels_returnsDefensiveCopy() {
        val all1 = ModelRegistry.getAllModels()
        val all2 = ModelRegistry.getAllModels()
        assertNotSame("getAllModels should return a copy", all1, all2)
        assertEquals(all1.map { it.name }.sorted(), all2.map { it.name }.sorted())
    }
}