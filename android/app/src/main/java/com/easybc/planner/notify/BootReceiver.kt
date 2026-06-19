package com.easybc.planner.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.easybc.planner.EasyBCApp
import kotlinx.coroutines.launch

/**
 * Re-arms the daily reminder alarm after a device reboot, since Android
 * clears all scheduled alarms on boot. No-op unless the user has the
 * reminder toggle on.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        val app = context.applicationContext as? EasyBCApp ?: return
        val pending = goAsync()
        app.appScope.launch {
            try {
                val settings = app.repository.getSettings() ?: return@launch
                if (settings.reminderEnabled) {
                    ReminderScheduler.ensureChannel(context)
                    ReminderScheduler.schedule(
                        context,
                        settings.reminderHour,
                        settings.reminderMinute,
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
