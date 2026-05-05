package com.easybc.planner.notify

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.easybc.planner.EasyBCApp
import com.easybc.planner.MainActivity
import com.easybc.planner.R
import com.easybc.planner.data.RecommendedAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Fires each day at the user's chosen reminder time.
 *
 * Posts a notification only when yesterday was a planned non-abstinence day
 * and has not already been reconciled. Quick actions let the user mark the
 * day as planned or open the reconciliation screen for anything different.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ReminderScheduler.ACTION_MARK_AS_PLANNED) {
            markAsPlanned(context, intent)
            return
        }

        // Respect runtime permission on API 33+. If POST_NOTIFICATIONS isn't
        // granted we silently drop — the user will see the toggle reflect
        // reality and can grant it later.
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val app = context.applicationContext as? EasyBCApp ?: return
        val pendingResult = goAsync()
        app.appScope.launch {
            try {
                val reminder = reminderForYesterday(app) ?: return@launch
                ReminderScheduler.ensureChannel(context)

                val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Reconcile yesterday?")
                    .setContentText("${reminder.label} was planned. Did it go as planned?")
                    .setContentIntent(openReconcilePendingIntent(context))
                    .addAction(
                        R.drawable.ic_notification,
                        "As planned",
                        markAsPlannedPendingIntent(context, reminder),
                    )
                    .addAction(
                        R.drawable.ic_notification,
                        "Not as planned",
                        openReconcilePendingIntent(context),
                    )
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()

                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(ReminderScheduler.NOTIFICATION_ID, notification)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun markAsPlanned(context: Context, intent: Intent) {
        val dateEpochDay = intent.getLongExtra(ReminderScheduler.EXTRA_RECONCILE_DATE, Long.MIN_VALUE)
        val action = intent.getStringExtra(ReminderScheduler.EXTRA_RECONCILE_ACTION)
        if (dateEpochDay == Long.MIN_VALUE || action.isNullOrBlank()) return

        val app = context.applicationContext as? EasyBCApp ?: return
        val pendingResult = goAsync()
        app.appScope.launch {
            try {
                app.repository.reconcileDay(LocalDate.ofEpochDay(dateEpochDay), action)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(ReminderScheduler.NOTIFICATION_ID)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun reminderForYesterday(
        app: EasyBCApp,
    ): ReminderInfo? {
        val settings = app.repository.getSettings() ?: return null
        if (!settings.onboardingComplete || !settings.reminderEnabled) return null

        val date = LocalDate.now().minusDays(1)
        val logs = app.repository.dayLogsFlow.first()
        val existing = logs.firstOrNull { it.date == date.toEpochDay() }
        if (existing?.reconciled == true) return null

        val periods = app.repository.periodsFlow.first()
        val plan = app.repository.calendarPlannerResultFlow.first() ?: return null
        val cycles = app.cycleCalculator.buildCoverageCycles(periods, settings)
        val (cycleIdx, dayInCycle) = app.cycleCalculator.dateToCycleDay(date, cycles) ?: return null
        val dayWeight = plan.years.getOrNull(cycleIdx)?.dayWeights?.getOrNull(dayInCycle - 1)
            ?: return null
        val action = dayWeight.recommendedAction
        if (action == RecommendedAction.A) return null

        return ReminderInfo(
            dateEpochDay = date.toEpochDay(),
            plannedAction = action.shortLabel,
            label = labelFor(action),
        )
    }

    private fun openReconcilePendingIntent(context: Context): PendingIntent {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ReminderScheduler.EXTRA_OPEN_RECONCILE, true)
        }
        return PendingIntent.getActivity(
            context,
            ReminderScheduler.REQUEST_CODE_OPEN_RECONCILE,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun markAsPlannedPendingIntent(
        context: Context,
        reminder: ReminderInfo,
    ): PendingIntent {
        val actionIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderScheduler.ACTION_MARK_AS_PLANNED
            putExtra(ReminderScheduler.EXTRA_RECONCILE_DATE, reminder.dateEpochDay)
            putExtra(ReminderScheduler.EXTRA_RECONCILE_ACTION, reminder.plannedAction)
        }
        return PendingIntent.getBroadcast(
            context,
            ReminderScheduler.REQUEST_CODE_MARK_AS_PLANNED,
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun labelFor(action: RecommendedAction): String = when (action) {
        RecommendedAction.U -> "Unprotected sex"
        RecommendedAction.C -> "Protected sex"
        RecommendedAction.W -> "Withdrawal"
        RecommendedAction.A -> "Abstinence"
    }

    private data class ReminderInfo(
        val dateEpochDay: Long,
        val plannedAction: String,
        val label: String,
    )
}
