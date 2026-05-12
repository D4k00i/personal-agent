package com.personalagent.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Unit tests for [PersonalTaskDao] and [AppDatabase].
 *
 * Uses Room inMemoryDatabaseBuilder for fast, isolated tests.
 * All DB operations are suspend functions — use runBlocking { }.
 */
@RunWith(AndroidJUnit4::class)
class PersonalTaskDaoTest {

    private lateinit var dao: PersonalTaskDao
    private lateinit var db: TestAppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder<TestAppDatabase>(
            context,
            TestAppDatabase::class.java
        ).build()
        dao = db.personalTaskDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -------------------------------------------------------------------------
    // TC-01: Insert a task and retrieve by ID
    // -------------------------------------------------------------------------

    @Test
    fun insert_and_getById() = runBlocking<Unit> {
        val task = makeTask(id = "task-001", type = "IMAGE", priority = 5)

        dao.insert(task)

        val retrieved = dao.getById("task-001")
        assertNotNull("Task should be retrievable by ID", retrieved)
        assertEquals("task-001", retrieved!!.id)
        assertEquals("IMAGE", retrieved.type)
        assertEquals(5, retrieved.priority)
        assertEquals("PENDING", retrieved.status)
    }

    @Test
    fun insert_replaceOnConflict() = runBlocking<Unit> {
        val task1 = makeTask(id = "task-001", type = "IMAGE", priority = 5)
        val task2 = makeTask(id = "task-001", type = "TEXT", priority = 10)

        dao.insert(task1)
        dao.insert(task2) // REPLACE strategy

        val retrieved = dao.getById("task-001")
        assertEquals("TEXT", retrieved!!.type)
        assertEquals(10, retrieved.priority)
    }

    @Test
    fun getById_returnsNullForNonexistent() = runBlocking<Unit> {
        val result = dao.getById("nonexistent-id")
        assertNull("Nonexistent task should return null", result)
    }

    // -------------------------------------------------------------------------
    // TC-02: getPendingTasksSortedByPriority — correct order
    // -------------------------------------------------------------------------

    @Test
    fun getPendingTasksSortedByPriority_highPriorityFirst() = runBlocking<Unit> {
        // Insert tasks out of priority order.
        dao.insert(makeTask(id = "t-low",   type = "TEXT", priority = 1,  createdAt = 1000L))
        dao.insert(makeTask(id = "t-mid",   type = "TEXT", priority = 5,  createdAt = 2000L))
        dao.insert(makeTask(id = "t-high",  type = "IMAGE", priority = 10, createdAt = 3000L))

        val pending = dao.getPendingTasksSortedByPriority()

        assertEquals(3, pending.size)
        assertEquals("t-high",  pending[0].id) // priority 10
        assertEquals("t-mid",   pending[1].id) // priority 5
        assertEquals("t-low",   pending[2].id) // priority 1
    }

    @Test
    fun getPendingTasksSortedByPriority_samePriority_byCreatedAt() = runBlocking<Unit> {
        dao.insert(makeTask(id = "t-newer",  type = "IMAGE", priority = 5, createdAt = 3000L))
        dao.insert(makeTask(id = "t-older",  type = "IMAGE", priority = 5, createdAt = 1000L))
        dao.insert(makeTask(id = "t-middle", type = "IMAGE", priority = 5, createdAt = 2000L))

        val pending = dao.getPendingTasksSortedByPriority()

        assertEquals(3, pending.size)
        assertEquals("t-older",  pending[0].id) // createdAt 1000
        assertEquals("t-middle", pending[1].id) // createdAt 2000
        assertEquals("t-newer",  pending[2].id) // createdAt 3000
    }

    @Test
    fun getPendingTasksSortedByPriority_excludesNonPending() = runBlocking<Unit> {
        dao.insert(makeTask(id = "t-pending", type = "IMAGE", priority = 5, status = "PENDING"))
        dao.insert(makeTask(id = "t-running", type = "IMAGE", priority = 10, status = "RUNNING"))
        dao.insert(makeTask(id = "t-done",    type = "IMAGE", priority = 10, status = "DONE"))

        val pending = dao.getPendingTasksSortedByPriority()

        assertEquals(1, pending.size)
        assertEquals("t-pending", pending[0].id)
    }

    @Test
    fun getPendingTasksSortedByPriority_emptyWhenAllDone() = runBlocking<Unit> {
        dao.insert(makeTask(id = "t1", status = "DONE"))
        dao.insert(makeTask(id = "t2", status = "FAILED"))

        val pending = dao.getPendingTasksSortedByPriority()

        assertTrue("No pending tasks expected", pending.isEmpty())
    }

    // -------------------------------------------------------------------------
    // TC-03: updateStatus — correct transitions
    // -------------------------------------------------------------------------

    @Test
    fun updateStatus_pendingToRunning() = runBlocking<Unit> {
        dao.insert(makeTask(id = "t1", status = "PENDING"))

        dao.updateStatus("t1", "RUNNING")

        val task = dao.getById("t1")
        assertEquals("RUNNING", task!!.status)
    }

    @Test
    fun updateStatus_runningToFailed() = runBlocking<Unit> {
        dao.insert(makeTask(id = "t1", status = "RUNNING"))

        dao.updateStatus("t1", "FAILED")

        val task = dao.getById("t1")
        assertEquals("FAILED", task!!.status)
    }

    @Test
    fun updateStatus_idempotent() = runBlocking<Unit> {
        dao.insert(makeTask(id = "t1", status = "PENDING"))

        dao.updateStatus("t1", "RUNNING")
        dao.updateStatus("t1", "RUNNING") // second call — no-op

        val task = dao.getById("t1")
        assertEquals("RUNNING", task!!.status)
    }

    @Test
    fun updateStatus_nonexistentTask_noCrash() = runBlocking<Unit> {
        // Should not throw — Room UPDATE affects 0 rows silently.
        dao.updateStatus("nonexistent", "RUNNING")

        val result = dao.getById("nonexistent")
        assertNull("Nonexistent task should still not exist", result)
    }

    // -------------------------------------------------------------------------
    // TC-04: getOldestPending(limit) — correct count and order
    // -------------------------------------------------------------------------

    @Test
    fun getOldestPending_returnsOldestFirst() = runBlocking<Unit> {
        dao.insert(makeTask(id = "t-new",     type = "IMAGE", priority = 5, createdAt = 3000L))
        dao.insert(makeTask(id = "t-oldest",  type = "IMAGE", priority = 5, createdAt = 1000L))
        dao.insert(makeTask(id = "t-middle",  type = "IMAGE", priority = 5, createdAt = 2000L))

        val oldest = dao.getOldestPending(1)

        assertEquals(1, oldest.size)
        assertEquals("t-oldest", oldest[0].id)
    }

    @Test
    fun getOldestPending_respectsLimit() = runBlocking<Unit> {
        dao.insert(makeTask(id = "t1", priority = 1, createdAt = 1000L))
        dao.insert(makeTask(id = "t2", priority = 1, createdAt = 2000L))
        dao.insert(makeTask(id = "t3", priority = 1, createdAt = 3000L))

        val oldest = dao.getOldestPending(2)

        assertEquals(2, oldest.size)
        assertEquals("t1", oldest[0].id)
        assertEquals("t2", oldest[1].id)
    }

    @Test
    fun getOldestPending_excludesNonPending() = runBlocking<Unit> {
        dao.insert(makeTask(id = "t-pending", priority = 1, createdAt = 1000L, status = "PENDING"))
        dao.insert(makeTask(id = "t-running", priority = 1, createdAt = 500L,   status = "RUNNING"))

        val oldest = dao.getOldestPending(1)

        assertEquals(1, oldest.size)
        assertEquals("t-pending", oldest[0].id)
    }

    @Test
    fun getOldestPending_emptyWhenNoPending() = runBlocking<Unit> {
        val oldest = dao.getOldestPending(5)
        assertTrue("No oldest tasks when none pending", oldest.isEmpty())
    }

    // -------------------------------------------------------------------------
    // TC-05: deleteCompleted(cutoff) — removes DONE tasks older than threshold
    // -------------------------------------------------------------------------

    @Test
    fun deleteCompleted_removesOldDoneTasks() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val cutoff = now - 60_000L // 1 minute ago

        dao.insert(makeTask(
            id = "t-old-done",
            status = "DONE",
            completedAt = now - 2 * 60_000L // 2 minutes ago — older than cutoff
        ))
        dao.insert(makeTask(
            id = "t-recent-done",
            status = "DONE",
            completedAt = now - 30_000L // 30 seconds ago — within cutoff
        ))
        dao.insert(makeTask(
            id = "t-still-running",
            status = "RUNNING",
        ))

        val deleted = dao.deleteCompleted(cutoff)

        assertEquals("Should delete 1 task", 1, deleted)
        assertNull("Old done task should be deleted", dao.getById("t-old-done"))
        assertNotNull("Recent done task should remain", dao.getById("t-recent-done"))
        assertNotNull("Running task should remain", dao.getById("t-still-running"))
    }

    @Test
    fun deleteCompleted_noTasksInRange() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val cutoff = now + 60_000L // all tasks are older than this — none qualify

        dao.insert(makeTask(id = "t1", status = "DONE", completedAt = now - 30_000L))
        dao.insert(makeTask(id = "t2", status = "DONE", completedAt = now - 30_000L))

        val deleted = dao.deleteCompleted(cutoff)

        assertEquals("Should delete 0 tasks", 0, deleted)
        assertNotNull(dao.getById("t1"))
        assertNotNull(dao.getById("t2"))
    }

    @Test
    fun deleteCompleted_onlyAffectsDoneStatus() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val cutoff = now - 60_000L

        dao.insert(makeTask(id = "t-failed", status = "FAILED", completedAt = now - 120_000L))
        dao.insert(makeTask(id = "t-pending", status = "PENDING"))

        dao.deleteCompleted(cutoff)

        assertNotNull("FAILED tasks should not be deleted", dao.getById("t-failed"))
        assertNotNull("PENDING tasks should not be deleted", dao.getById("t-pending"))
    }

    // -------------------------------------------------------------------------
    // TC-06: completeTask — sets status + completedAt atomically
    // -------------------------------------------------------------------------

    @Test
    fun completeTask_setsStatusAndTimestamp() = runBlocking<Unit> {
        dao.insert(makeTask(id = "t1", status = "RUNNING"))
        val now = System.currentTimeMillis()

        dao.completeTask("t1", "DONE", now)

        val task = dao.getById("t1")
        assertEquals("DONE", task!!.status)
        assertEquals(now, task.completedAt)
    }

    @Test
    fun completeTask_idempotent() = runBlocking<Unit> {
        dao.insert(makeTask(id = "t1", status = "RUNNING"))
        val ts1 = 1000L
        val ts2 = 2000L

        dao.completeTask("t1", "DONE", ts1)
        dao.completeTask("t1", "DONE", ts2) // second call — overwrites timestamp

        val task = dao.getById("t1")
        assertEquals("DONE", task!!.status)
        assertEquals(ts2, task.completedAt) // last call wins
    }

    // -------------------------------------------------------------------------
    // TC-07: updatePayload — persists runner output path
    // -------------------------------------------------------------------------

    @Test
    fun updatePayload_persistsNewPayload() = runBlocking<Unit> {
        val originalPayload = """{"imageId":123,"path":"/storage/photo.jpg"}"""
        dao.insert(makeTask(id = "t1", payloadJson = originalPayload))

        val updatedPayload = """{"imageId":123,"path":"/storage/photo.jpg","outputPath":"/Pictures/PPA/IMG_123.jpg","outputSizeBytes":45678}"""

        dao.updatePayload("t1", updatedPayload)

        val task = dao.getById("t1")
        assertEquals(updatedPayload, task!!.payloadJson)
    }

    @Test
    fun updatePayload_nonexistentTask_noCrash() = runBlocking<Unit> {
        // Should not throw — no rows affected.
        dao.updatePayload("nonexistent", """{"outputPath":"/test.jpg"}""")
    }

    // -------------------------------------------------------------------------
    // TC-08: DB migration — version 1 → 2 placeholder
    // -------------------------------------------------------------------------

    @Test
    fun database_versionIsOne() {
        assertEquals("DB version should be 1", 1, db.version)
    }

    @Test
    fun database_hasPersonalTaskDao() {
        assertNotNull("Database should expose PersonalTaskDao", db.personalTaskDao())
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeTask(
        id: String = UUID.randomUUID().toString(),
        type: String = "IMAGE",
        payloadJson: String = "{}",
        priority: Int = 0,
        status: String = "PENDING",
        createdAt: Long = System.currentTimeMillis(),
        scheduledAt: Long? = null,
        completedAt: Long? = null,
    ) = PersonalTask(
        id = id,
        type = type,
        payloadJson = payloadJson,
        priority = priority,
        status = status,
        createdAt = createdAt,
        scheduledAt = scheduledAt,
        completedAt = completedAt,
    )
}

/**
 * Test variant of [com.personalagent.config.AppDatabase] that uses
 * inMemoryDatabaseBuilder so each test gets a fresh, empty DB.
 */
@androidx.room.Database(
    entities = [PersonalTask::class],
    version = 1,
    exportSchema = false,
)
abstract class TestAppDatabase : androidx.room.RoomDatabase() {
    abstract fun personalTaskDao(): PersonalTaskDao
}