package com.relay.agents

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.util.UUID

/**
 * عامل التشغيل المجدول (v28 — الميزة 15): ينفّذ مهمة فريق كاملة
 * في الخلفية عبر WorkManager حتى لو أُغلق التطبيق، ثم يحفظها
 * مشروعاً وينبّه المستخدم بالنتيجة. بلا واجهة وبلا تدخل يدوي
 * (الوكيل اليدوي يُتخطى تلقائياً لأنه يحتاج إنساناً).
 */
class TeamRunWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val runId = inputData.getString("runId") ?: return Result.failure()
        val goal = inputData.getString("goal").orEmpty()
        if (goal.isBlank()) return Result.failure()
        val coding = inputData.getBoolean("coding", false)
        val thinking = inputData.getBoolean("thinking", false)

        val storage = Storage(applicationContext)
        val store = ProjectStore(applicationContext)
        val schedule = ScheduleStore(applicationContext)
        val agents = storage.loadAgents()
        if (agents.none { it.enabled }) {
            schedule.update(runId) { it.copy(status = "failed") }
            return Result.failure()
        }
        schedule.update(runId) { it.copy(status = "running") }

        val now = System.currentTimeMillis()
        var project = Project(UUID.randomUUID().toString(), goal.take(50), now, now, Checkpoint(goal, coding = coding, thinking = thinking))
        store.save(project)
        storage.setCurrentProjectId(project.id)

        return try {
            val orch = Orchestrator(storage, { _, _ -> null }) { cp, _ ->
                project = project.copy(checkpoint = cp, updatedAt = System.currentTimeMillis())
                store.save(project)
            }
            orch.run(goal, agents, null, "", AttachmentBundle(), coding, thinking)
            val done = project.checkpoint
            if (done.finalAnswer.isNotEmpty()) {
                schedule.update(runId) { it.copy(status = "done", resultProjectId = project.id) }
                notify(applicationContext, "✅ " + project.name, done.finalAnswer.take(200))
                Result.success()
            } else {
                schedule.update(runId) { it.copy(status = "failed", resultProjectId = project.id) }
                notify(applicationContext, "⚠️ " + project.name, "انتهى التشغيل المجدول بلا نتيجة — افتح المشروع لترى السجل")
                Result.failure()
            }
        } catch (e: Exception) {
            schedule.update(runId) { it.copy(status = "failed", resultProjectId = project.id) }
            notify(applicationContext, "⚠️ " + project.name, "تعذّر التشغيل المجدول: ${e.message?.take(120)}")
            Result.failure()
        }
    }

    companion object {
        const val CHANNEL_ID = "team_runs"

        fun notify(ctx: Context, title: String, text: String) {
            runCatching {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "Team runs", NotificationManager.IMPORTANCE_DEFAULT)
                    )
                }
                val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(ctx, CHANNEL_ID)
                } else {
                    @Suppress("DEPRECATION")
                    Notification.Builder(ctx)
                }.setContentTitle(title).setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setAutoCancel(true).build()
                nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
            }
        }
    }
}
