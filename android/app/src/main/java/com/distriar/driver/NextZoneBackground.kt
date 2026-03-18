package com.distriar.driver

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

object NextZoneNotifier {
    private const val CHANNEL_ID = "next_zone_notice"
    private const val NOTIFICATION_ID = 4101
    private const val PREFS_NAME = "driver_prefs"
    private const val KEY_LAST_ZONE_NOTICE = "last_zone_notice"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Zona siguiente",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Avisos sobre la zona asignada para el dia siguiente"
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    fun formatZoneDate(value: String): String {
        val parsed = parseApiDate(value) ?: return value
        val output = SimpleDateFormat("dd/MM", Locale.getDefault())
        return output.format(parsed.time)
    }

    fun maybeNotify(context: Context, notice: DriverNextZoneNotice?): Boolean {
        val zone = notice?.zone?.trim().orEmpty()
        val deliveryDate = notice?.deliveryDate?.trim().orEmpty()
        if (zone.isBlank() || deliveryDate.isBlank()) return false
        if (!isTomorrow(deliveryDate)) return false
        if (!notificationsAllowed(context)) return false

        val key = "$deliveryDate|$zone"
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastShown = prefs.getString(KEY_LAST_ZONE_NOTICE, null)
        if (key == lastShown) return false

        val body = notice?.message?.takeIf { it.isNotBlank() } ?: "Manana te toca la zona $zone."
        showNotification(context, deliveryDate, body)
        prefs.edit().putString(KEY_LAST_ZONE_NOTICE, key).apply()
        return true
    }

    private fun showNotification(context: Context, deliveryDate: String, body: String) {
        ensureChannel(context)
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Zona del dia siguiente")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$body Fecha: ${formatZoneDate(deliveryDate)}"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .apply {
                if (pendingIntent != null) {
                    setContentIntent(pendingIntent)
                }
            }
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun isTomorrow(value: String): Boolean {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val tomorrowKey = apiDateFormat().format(cal.time)
        return value == tomorrowKey
    }

    private fun parseApiDate(value: String): Calendar? {
        return try {
            val date = apiDateFormat().parse(value) ?: return null
            Calendar.getInstance().apply { time = date }
        } catch (_: Exception) {
            null
        }
    }

    private fun apiDateFormat(): SimpleDateFormat {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}

object NextZoneWorkScheduler {
    private const val WORK_NAME = "next_zone_background_poll"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<NextZoneWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}

class NextZoneWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val tokenStore = TokenStore(applicationContext)
        val token = tokenStore.getToken()
        if (token.isNullOrBlank()) return Result.success()

        val repo = DriverRepository(ApiClient.create { tokenStore.getToken() })
        val notice = try {
            repo.getNextZoneNotice(token)
        } catch (_: Exception) {
            return Result.success()
        }

        NextZoneNotifier.maybeNotify(applicationContext, notice)
        return Result.success()
    }
}
