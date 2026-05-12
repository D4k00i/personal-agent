package com.personalagent

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.personalagent.agent.PersonalTask
import com.personalagent.agent.PersonalTaskDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Dashboard activity showing agent status, pending task queue, and recent history.
 *
 * Uses Room Flow queries for reactive auto-update — the UI refreshes whenever
 * the database changes (new task inserted, task completed, etc.).
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectJob: Job? = null

    private var isAgentRunning = false

    private lateinit var statusDot: TextView
    private lateinit var statusLabel: TextView
    private lateinit var pendingContainer: LinearLayout
    private lateinit var recentContainer: LinearLayout
    private lateinit var pendingHeader: TextView
    private lateinit var recentHeader: TextView
    private lateinit var dao: PersonalTaskDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dao = WorkerApp.db.personalTaskDao()
        setContentView(buildLayout())
    }

    override fun onResume() {
        super.onResume()
        startCollecting()
    }

    override fun onPause() {
        collectJob?.cancel()
        collectJob = null
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // =========================================================================
    // Layout
    // =========================================================================

    private fun buildLayout(): ScrollView {
        return ScrollView(this).apply {
            setPadding(32, 48, 32, 48)

            val root = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }

            // -- Title --
            root.addView(TextView(this@MainActivity).apply {
                text = "Personal Phone Agent"
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, 8)
            })

            // -- Agent Status Bar --
            val statusRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 24)
            }
            statusDot = TextView(this@MainActivity).apply {
                text = "●"
                textSize = 18f
                setPadding(0, 0, 8, 0)
            }
            statusLabel = TextView(this@MainActivity).apply {
                text = "Agent: stopped"
                textSize = 15f
            }
            statusRow.addView(statusDot)
            statusRow.addView(statusLabel)
            root.addView(statusRow)

            // -- Pending Tasks Section --
            pendingHeader = sectionHeader("── Pending Tasks ──")
            root.addView(pendingHeader)

            pendingContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, 16)
            }
            root.addView(pendingContainer)

            // -- Recent History Section --
            recentHeader = sectionHeader("── Recent History ──")
            root.addView(recentHeader)

            recentContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, 24)
            }
            root.addView(recentContainer)

            // -- Buttons --
            root.addView(Button(this@MainActivity).apply {
                text = "Start Agent"
                setOnClickListener { startAgent() }
            })
            root.addView(Button(this@MainActivity).apply {
                text = "Stop Agent"
                setOnClickListener { stopAgent() }
            })

            addView(root)
        }
    }

    private fun sectionHeader(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 16, 0, 8)
        }
    }

    // =========================================================================
    // Reactive data collection
    // =========================================================================

    private fun startCollecting() {
        collectJob = scope.launch {
            // Collect pending tasks
            launch {
                dao.getPendingTasksFlow().collectLatest { tasks ->
                    renderPendingTasks(tasks)
                }
            }
            // Collect recent history
            launch {
                dao.getRecentTasksFlow().collectLatest { tasks ->
                    renderRecentTasks(tasks)
                }
            }
        }
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    private fun renderPendingTasks(tasks: List<PersonalTask>) {
        pendingContainer.removeAllViews()

        if (tasks.isEmpty()) {
            pendingContainer.addView(emptyPlaceholder("No pending tasks"))
            return
        }

        for (task in tasks) {
            pendingContainer.addView(taskItemView(
                emoji = typeEmoji(task.type),
                type = task.type,
                priority = task.priority,
                timeAgo = relativeTime(task.createdAt),
                isPending = true
            ))
        }
    }

    private fun renderRecentTasks(tasks: List<PersonalTask>) {
        recentContainer.removeAllViews()

        if (tasks.isEmpty()) {
            recentContainer.addView(emptyPlaceholder("No completed tasks yet"))
            return
        }

        for (task in tasks) {
            val statusEmoji = when (task.status) {
                "DONE" -> "✅"
                "FAILED" -> "❌"
                else -> "⬜"
            }
            val timeAgo = if (task.completedAt != null) relativeTime(task.completedAt!!) else ""
            recentContainer.addView(taskItemView(
                emoji = "$statusEmoji ${typeEmoji(task.type)}",
                type = task.type,
                priority = task.priority,
                timeAgo = timeAgo,
                isPending = false
            ))
        }
    }

    private fun taskItemView(emoji: String, type: String, priority: Int, timeAgo: String, isPending: Boolean): LinearLayout {
        val dp8 = (8 * resources.displayMetrics.density).toInt()
        val dp4 = (4 * resources.displayMetrics.density).toInt()

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp4, 0, dp4)

            // Emoji + type
            addView(TextView(this@MainActivity).apply {
                text = "$emoji  $type"
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            // Priority badge (only for pending)
            if (isPending) {
                addView(TextView(this@MainActivity).apply {
                    text = "  P$priority  "
                    textSize = 11f
                    setBackgroundColor(0xFFE0E0E0.toInt())
                    setPadding(dp8, dp4 / 2, dp8, dp4 / 2)
                })
            }

            // Time ago
            addView(TextView(this@MainActivity).apply {
                text = timeAgo
                textSize = 11f
                setPadding(dp8 * 2, 0, 0, 0)
                setTextColor(0xFF888888.toInt())
            })
        }
    }

    private fun emptyPlaceholder(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 8, 0, 8)
        }
    }

    // =========================================================================
    // Agent control
    // =========================================================================

    private fun startAgent() {
        val intent = Intent(this, WorkerForegroundService::class.java)
        intent.action = "START"
        startForegroundService(intent)
        updateAgentStatus(true)
    }

    private fun stopAgent() {
        val intent = Intent(this, WorkerForegroundService::class.java)
        intent.action = "STOP"
        stopService(intent)
        updateAgentStatus(false)
    }

    private fun updateAgentStatus(running: Boolean) {
        isAgentRunning = running
        if (running) {
            statusDot.text = "●"
            statusDot.setTextColor(0xFF4CAF50.toInt()) // green
            statusLabel.text = "Agent: running"
        } else {
            statusDot.text = "●"
            statusDot.setTextColor(0xFFF44336.toInt()) // red
            statusLabel.text = "Agent: stopped"
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun typeEmoji(type: String): String = when (type.uppercase()) {
        "IMAGE" -> "📷"
        "TEXT" -> "📝"
        "AI" -> "🧠"
        "AUTO" -> "⚙️"
        else -> "📋"
    }

    private fun relativeTime(epochMs: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - epochMs

        return when {
            diff < 60_000 -> "just now"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            else -> "${diff / 86_400_000}d ago"
        }
    }
}
