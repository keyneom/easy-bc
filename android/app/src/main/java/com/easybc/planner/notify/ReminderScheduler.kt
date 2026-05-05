package com.easybc.planner.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Schedules a single, daily, **inexact** reminder alarm.
 *
 * Why inexact? We use [AlarmManager.setInexactRepeating] with
 * [AlarmManager.INTERVAL_DAY] so we don't need the `SCHEDULE_EXACT_ALARM`
 * permission (which user-hostile on recent Android versions — system settings
 * revoke it silently and we'd need to re-request). ±10 minutes of slack is
 * fine for an end-of-day nudge.
 *
 * The alarm fires [ReminderReceiver] which posts the notification. Tapping
 * the notification deep-links into the Reconcile screen via MainActivity.
 */
object ReminderScheduler {
    const val CHANNEL_ID = "easybc_reminder"
    const val CHANNEL_NAME = "Daily reconcile reminder"
    const val NOTIFICATION_ID = 1001
    const val REQUEST_CODE = 1002

    /** Intent extra set by the notification tap so MainActivity routes to Reconcile. */
    const val EXTRA_OPEN_RECONCILE = "open_reconcile"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Daily prompt to confirm what actually happened yesterday."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * Schedule the daily alarm for [hour]:[minute] local time. Cancels any
     * prior alarm first so the new schedule is clean. Idempotent.
     */
    fun schedule(context: Context, hour: Int, minute: Int) {
        cancel(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(context)
        val trigger = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // If the chosen time has already passed today, start tomorrow.
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            trigger.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            pi,
        )
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(buildPendingIntent(context))
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
