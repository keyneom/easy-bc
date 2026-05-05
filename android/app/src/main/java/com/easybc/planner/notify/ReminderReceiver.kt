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
import com.easybc.planner.MainActivity
import com.easybc.planner.R

/**
 * Fires each day at the user's chosen reminder time.
 *
 * Posts a single notification asking the user to reconcile. Tapping routes
 * into MainActivity with an extra that navigates to the Reconcile screen.
 * Does *not* pre-fetch or bundle the unreconciled count — that info can
 * change between schedule time and when the user sees it.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
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

        ReminderScheduler.ensureChannel(context)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ReminderScheduler.EXTRA_OPEN_RECONCILE, true)
        }
        val pending = PendingIntent.getActivity(
            context,
            ReminderScheduler.REQUEST_CODE,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Reconcile yesterday?")
            .setContentText("Confirm what actually happened so your plan stays accurate.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ReminderScheduler.NOTIFICATION_ID, notification)
    }
}
