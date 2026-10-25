package com.example.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.AppCurrency
import com.example.data.network.MarketRepository
import com.example.data.repository.PortfolioRepository
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class TriggeredPriceAlert(
    val symbol: String,
    val companyName: String,
    val livePriceUsd: Double,
    val targetPriceUsd: Double,
    val sharesOwned: Double,
    val triggeredAtMs: Long = System.currentTimeMillis()
)

class PriceTargetNotificationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifiedTargets = ConcurrentHashMap<String, Double>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        PriceAlertNotificationManager.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startMonitorForeground()
                observePortfolioTargets()
            }
        }
        return START_STICKY
    }

    private fun startMonitorForeground() {
        PriceAlertNotificationManager.ensureChannels(this)
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, PriceTargetNotificationService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, PriceAlertNotificationManager.CHANNEL_MONITOR_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.alert_service_running_title))
            .setContentText(getString(R.string.alert_service_running_body))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.alert_service_stop_action),
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start foreground service notification", e)
        }
    }

    private fun observePortfolioTargets() {
        val currentUser = Firebase.auth.currentUser ?: return
        val portfolioRepo = PortfolioRepository(applicationContext)
        val marketRepo = MarketRepository()

        serviceScope.launch {
            try {
                combine(
                    portfolioRepo.observeHoldings(currentUser.uid),
                    portfolioRepo.observeWatchlist(currentUser.uid),
                    marketRepo.streamRealTimeQuotes()
                ) { holdings, watchlist, quotes ->
                    val quoteMap = quotes.associateBy { it.symbol.uppercase() }
                    val holdingMap = holdings.associateBy { it.symbol.uppercase() }

                    watchlist.mapNotNull { item ->
                        val sym = item.symbol.uppercase()
                        val quote = quoteMap[sym]
                        val livePrice = quote?.price ?: return@mapNotNull null
                        val target = item.targetAlertPrice
                        if (target > 0.0 && livePrice >= target) {
                            val previousNotifiedTarget = notifiedTargets[sym]
                            if (previousNotifiedTarget == null || previousNotifiedTarget != target) {
                                notifiedTargets[sym] = target
                                val ownedShares = holdingMap[sym]?.shares ?: 0.0
                                TriggeredPriceAlert(
                                    symbol = sym,
                                    companyName = item.companyName,
                                    livePriceUsd = livePrice,
                                    targetPriceUsd = target,
                                    sharesOwned = ownedShares
                                )
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    }
                }.collect { newlyTriggered ->
                    newlyTriggered.forEach { alert ->
                        PriceAlertNotificationManager.sendPriceTargetHitNotification(
                            context = applicationContext,
                            alert = alert,
                            currency = AppCurrency.USD
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Portfolio target monitor stopped", e)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PriceTargetService"
        private const val FOREGROUND_NOTIFICATION_ID = 4100
        const val ACTION_START_SERVICE = "com.example.action.START_PRICE_ALERT_SERVICE"
        const val ACTION_STOP_SERVICE = "com.example.action.STOP_PRICE_ALERT_SERVICE"
    }
}

object PriceAlertNotificationManager {

    const val CHANNEL_ALERTS_ID = "pulsefolio_price_target_alerts"
    const val CHANNEL_MONITOR_ID = "pulsefolio_price_monitor_service"

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS_ID,
                context.getString(R.string.alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.alert_channel_desc)
                enableVibration(true)
            }

            val monitorChannel = NotificationChannel(
                CHANNEL_MONITOR_ID,
                context.getString(R.string.monitor_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.monitor_channel_desc)
            }

            manager.createNotificationChannel(alertChannel)
            manager.createNotificationChannel(monitorChannel)
        }
    }

    fun sendPriceTargetHitNotification(
        context: Context,
        alert: TriggeredPriceAlert,
        currency: AppCurrency
    ): Boolean {
        if (!hasNotificationPermission(context)) {
            return false
        }
        ensureChannels(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            alert.symbol.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val liveFormatted = currency.format(alert.livePriceUsd)
        val targetFormatted = currency.format(alert.targetPriceUsd)
        val portfolioImpactText = if (alert.sharesOwned > 0.0) {
            val positionValue = currency.format(alert.sharesOwned * alert.livePriceUsd)
            " • Portfolio Position (${alert.sharesOwned} sh): $positionValue"
        } else {
            " • Watchlist Target Reached"
        }

        val title = "🎯 ${alert.symbol} Hit Price Target ($targetFormatted)"
        val body = "${alert.companyName} is now trading at $liveFormatted (Target: $targetFormatted)$portfolioImpactText"

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(
                4200 + (alert.symbol.hashCode() and 0x7FFFFFFF) % 10000,
                notification
            )
            true
        } catch (e: SecurityException) {
            Log.w("PriceAlertManager", "SecurityException posting notification", e)
            false
        }
    }

    fun startMonitoringService(context: Context) {
        val intent = Intent(context, PriceTargetNotificationService::class.java).apply {
            action = PriceTargetNotificationService.ACTION_START_SERVICE
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.w("PriceAlertManager", "Failed to start PriceTargetNotificationService", e)
        }
    }

    fun stopMonitoringService(context: Context) {
        val intent = Intent(context, PriceTargetNotificationService::class.java).apply {
            action = PriceTargetNotificationService.ACTION_STOP_SERVICE
        }
        try {
            context.stopService(intent)
        } catch (e: Exception) {
            Log.w("PriceAlertManager", "Failed to stop PriceTargetNotificationService", e)
        }
    }
}
