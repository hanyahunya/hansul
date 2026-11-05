package com.hansul.app.voice

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.hansul.app.MainActivity
import com.hansul.app.R
import com.hansul.app.model.CookingTimerDeadline
import java.time.Instant

enum class TimerReadiness {
    READY,
    NEEDS_NOTIFICATION_PERMISSION,
    NEEDS_EXACT_ALARM_PERMISSION,
}

sealed interface TimerScheduleResult {
    data object Scheduled : TimerScheduleResult

    data class PermissionRequired(val readiness: TimerReadiness) : TimerScheduleResult

    data class Failed(val message: String) : TimerScheduleResult
}

class CookingTimerScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val deadlines = context.getSharedPreferences("cooking-timer-deadlines", Context.MODE_PRIVATE)

    fun savedDeadline(sessionId: String, timerCueId: String): CookingTimerDeadline? {
        val key = "$sessionId:$timerCueId"
        val ends = deadlines.getLong("$key:end", 0)
        val started = deadlines.getLong("$key:start", 0)
        if (ends <= 0 || started <= 0) return null
        return CookingTimerDeadline(timerCueId, Instant.ofEpochMilli(started).toString(),
            Instant.ofEpochMilli(ends).toString(), null)
    }

    fun savedAcknowledgementId(sessionId: String, timerCueId: String): String? =
        deadlines.getString("$sessionId:$timerCueId:ack", null)

    fun readiness(): TimerReadiness {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return TimerReadiness.NEEDS_NOTIFICATION_PERMISSION
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !alarmManager.canScheduleExactAlarms()
        ) {
            return TimerReadiness.NEEDS_EXACT_ALARM_PERMISSION
        }
        return TimerReadiness.READY
    }

    fun exactAlarmPermissionIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                "package:${context.packageName}".toUri(),
            )
        } else {
            null
        }

    fun schedule(
        sessionId: String,
        timerCueId: String,
        durationSeconds: Long,
        clientAckId: String? = null,
    ): TimerScheduleResult {
        val readiness = readiness()
        if (readiness != TimerReadiness.READY) {
            return TimerScheduleResult.PermissionRequired(readiness)
        }
        if (durationSeconds <= 0) {
            return TimerScheduleResult.Failed("레시피의 타이머 시간이 올바르지 않아요.")
        }
        return runCatching {
            createNotificationChannel(context)
            val timerIdentity = "$sessionId:$timerCueId"
            // Re-arm the same absolute deadline after process/OS recovery, never restart its duration.
            savedDeadline(sessionId, timerCueId)?.let { saved ->
                val triggerAt = Instant.parse(saved.endsAt).toEpochMilli()
                if (triggerAt > System.currentTimeMillis()) arm(sessionId, timerCueId, triggerAt)
                return@runCatching TimerScheduleResult.Scheduled
            }
            val startedAtMillis = System.currentTimeMillis()
            val triggerAtMillis = Math.addExact(
                startedAtMillis,
                Math.multiplyExact(durationSeconds, 1_000L),
            )
            check(deadlines.edit().putLong("$timerIdentity:start", startedAtMillis)
                .putLong("$timerIdentity:end", triggerAtMillis)
                .putString("$timerIdentity:ack", clientAckId)
                .putString("$timerIdentity:session", sessionId)
                .putString("$timerIdentity:cue", timerCueId).commit())
            try {
                arm(sessionId, timerCueId, triggerAtMillis)
            } catch (error: Exception) {
                deadlines.edit().remove("$timerIdentity:start").remove("$timerIdentity:end")
                    .remove("$timerIdentity:ack").remove("$timerIdentity:session").remove("$timerIdentity:cue").commit()
                throw error
            }
            TimerScheduleResult.Scheduled
        }.getOrElse {
            TimerScheduleResult.Failed("기기에 타이머를 설정하지 못했어요.")
        }
    }

    fun restoreScheduledAlarms(): TimerScheduleResult {
        val future = deadlines.all.entries.filter {
            it.key.endsWith(":end") && (it.value as? Long ?: 0) > System.currentTimeMillis()
        }
        if (future.isEmpty()) return TimerScheduleResult.Scheduled
        val readiness = readiness()
        if (readiness != TimerReadiness.READY) return TimerScheduleResult.PermissionRequired(readiness)
        return runCatching {
            createNotificationChannel(context)
            future.forEach { (endKey, end) ->
                val identity = endKey.removeSuffix(":end")
                val session = deadlines.getString("$identity:session", null) ?: return@forEach
                val cue = deadlines.getString("$identity:cue", null) ?: return@forEach
                arm(session, cue, end as Long)
            }
            TimerScheduleResult.Scheduled
        }.getOrElse { TimerScheduleResult.Failed("저장된 타이머 알람을 복원하지 못했어요. 알람 권한을 확인해주세요.") }
    }

    private fun arm(sessionId: String, timerCueId: String, triggerAtMillis: Long) {
        val timerIdentity = "$sessionId:$timerCueId"
        val alarmIntent = Intent(context, CookingTimerAlarmReceiver::class.java).apply {
            action = "$ACTION_TIMER.$timerIdentity"
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_TIMER_CUE_ID, timerCueId)
        }
        val pendingIntent = PendingIntent.getBroadcast(context, timerIdentity.hashCode(), alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    }
}

class CookingTimerRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            CookingTimerScheduler(context).restoreScheduledAlarms()
        }
    }
}

class CookingTimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val timerCueId = intent.getStringExtra(EXTRA_TIMER_CUE_ID) ?: return
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val timerIdentity = "$sessionId:$timerCueId"
        createNotificationChannel(context)

        val openApp = PendingIntent.getActivity(
            context,
            timerIdentity.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, TIMER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("한술 타이머가 끝났어요")
            .setContentText("다음 조리 단계를 확인해주세요.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(timerIdentity.hashCode(), notification)
        }
    }
}

private fun createNotificationChannel(context: Context) {
    val channel = NotificationChannel(
        TIMER_CHANNEL_ID,
        "요리 타이머",
        NotificationManager.IMPORTANCE_HIGH,
    ).apply {
        description = "한술에서 시작한 요리 타이머 알림"
        enableVibration(true)
    }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}

private const val TIMER_CHANNEL_ID = "cooking_timers"
private const val ACTION_TIMER = "com.hansul.app.action.COOKING_TIMER"
private const val EXTRA_SESSION_ID = "cooking_session_id"
private const val EXTRA_TIMER_CUE_ID = "timer_cue_id"
