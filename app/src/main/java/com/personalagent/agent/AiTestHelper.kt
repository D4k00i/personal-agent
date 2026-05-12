package com.personalagent.agent

import org.json.JSONObject
import timber.log.Timber
import java.util.UUID

/**
 * Development utility for inserting AI test tasks into the queue.
 *
 * All methods are disabled by default. To enable, uncomment the `enabled` flag
 * or call individual methods directly from a test/debug context.
 *
 * ## Usage (development only)
 * ```
 * val dao = WorkerApp.db.personalTaskDao()
 * AiTestHelper.insertTranslateTask(dao, "xin chào", "vi", "en")
 * AiTestHelper.insertVisionTask(dao, "classify_cat.jpg")
 * AiTestHelper.insertSqlTask(dao, "all active users")
 * AiTestHelper.insertCodeTask(dao, "sort list descending")
 * AiTestHelper.insertMathTask(dao, "2+2*3")
 * ```
 */
object AiTestHelper {

    /**
     * Set to true to allow test task insertion. Keep false in production builds.
     */
    @Volatile
    var enabled: Boolean = false

    /**
     * Inserts a translation test task (AI → translate).
     *
     * @param dao The PersonalTaskDao from the database.
     * @param text Text to translate.
     * @param sourceLang Source language code (default "vi").
     * @param targetLang Target language code (default "en").
     */
    fun insertTranslateTask(
        dao: PersonalTaskDao,
        text: String,
        sourceLang: String = "vi",
        targetLang: String = "en",
    ) {
        if (!enabled) {
            Timber.d("AiTestHelper: disabled — skipping translate task")
            return
        }
        val payload = JSONObject().apply {
            put("subtype", "translate")
            put("input", text)
            put("params", JSONObject().apply {
                put("sourceLang", sourceLang)
                put("targetLang", targetLang)
            })
        }
        insert(dao, payload)
    }

    /**
     * Inserts a vision/classification test task (AI → vision).
     *
     * @param dao The PersonalTaskDao from the database.
     * @param imagePath Path or identifier for the image to classify.
     */
    fun insertVisionTask(dao: PersonalTaskDao, imagePath: String) {
        if (!enabled) {
            Timber.d("AiTestHelper: disabled — skipping vision task")
            return
        }
        val payload = JSONObject().apply {
            put("subtype", "vision")
            put("input", imagePath)
        }
        insert(dao, payload)
    }

    /**
     * Inserts a text-to-SQL test task (AI → sql).
     *
     * @param dao The PersonalTaskDao from the database.
     * @param query Natural language query to convert to SQL.
     */
    fun insertSqlTask(dao: PersonalTaskDao, query: String) {
        if (!enabled) {
            Timber.d("AiTestHelper: disabled — skipping sql task")
            return
        }
        val payload = JSONObject().apply {
            put("subtype", "sql")
            put("input", query)
        }
        insert(dao, payload)
    }

    /**
     * Inserts a code generation test task (AI → code).
     *
     * @param dao The PersonalTaskDao from the database.
     * @param prompt Code generation prompt.
     */
    fun insertCodeTask(dao: PersonalTaskDao, prompt: String) {
        if (!enabled) {
            Timber.d("AiTestHelper: disabled — skipping code task")
            return
        }
        val payload = JSONObject().apply {
            put("subtype", "code")
            put("input", prompt)
        }
        insert(dao, payload)
    }

    /**
     * Inserts a math solving test task (AI → math).
     *
     * @param dao The PersonalTaskDao from the database.
     * @param expression Math expression to solve.
     */
    fun insertMathTask(dao: PersonalTaskDao, expression: String) {
        if (!enabled) {
            Timber.d("AiTestHelper: disabled — skipping math task")
            return
        }
        val payload = JSONObject().apply {
            put("subtype", "math")
            put("input", expression)
        }
        insert(dao, payload)
    }

    // ---- Private ----

    private fun insert(dao: PersonalTaskDao, payload: JSONObject) {
        val task = PersonalTask(
            id = UUID.randomUUID().toString(),
            type = "AI",
            payloadJson = payload.toString(),
            priority = 5,
        )
        kotlinx.coroutines.runBlocking {
            dao.insert(task)
        }
        Timber.i("AiTestHelper: inserted AI test task id=%s subtype=%s", task.id,
            payload.optString("subtype", "?"))
    }
}
