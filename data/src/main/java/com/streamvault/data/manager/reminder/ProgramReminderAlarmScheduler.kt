package com.streamvault.data.manager.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.AlarmManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProgramReminderAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val alarmManager: AlarmManager? by lazy {
        context.getSystemService(AlarmManager::class.java)
    }

    fun schedule(reminderId: Long, whenMs: Long) {
        val manager = alarmManager ?: return
        val pendingIntent = buildPendingIntent(
            reminderId = reminderId,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            AlarmManagerCompat.setExactAndAllowWhileIdle(
                manager,
                AlarmManager.RTC_WAKEUP,
                whenMs,
                pendingIntent
            )
        }.getOrElse {
            AlarmManagerCompat.setAndAllowWhileIdle(
                manager,
                AlarmManager.RTC_WAKEUP,
                whenMs,
                pendingIntent
            )
        }
    }

    fun cancel(reminderId: Long) {
        alarmManager?.cancel(
            buildPendingIntent(
                reminderId = reminderId,
                flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
    }

    private fun buildPendingIntent(reminderId: Long, flags: Int): PendingIntent {
        val intent = Intent(context, ProgramReminderAlarmReceiver::class.java)
            .setAction(ProgramReminderAlarmReceiver.ACTION_NOTIFY_REMINDER)
            .putExtra(ProgramReminderAlarmReceiver.EXTRA_REMINDER_ID, reminderId)
        return PendingIntent.getBroadcast(
            context,
            reminderId.hashCode(),
            intent,
            flags
        )
    }
}
