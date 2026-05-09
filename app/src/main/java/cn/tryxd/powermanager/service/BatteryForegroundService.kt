package cn.tryxd.powermanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import cn.tryxd.powermanager.MainActivity
import cn.tryxd.powermanager.data.SettingsRepository
import cn.tryxd.powermanager.monitor.BatteryReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BatteryForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var refreshJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                SettingsRepository(applicationContext).setPersistentNotificationEnabled(false)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("正在读取电量", "PowerManager 常驻监控已启动"))
        startRefreshLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRefreshLoop() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            while (isActive) {
                refreshNotification()
                delay(60_000L)
            }
        }
    }

    private suspend fun refreshNotification() {
        val settings = SettingsRepository(applicationContext).getSettings()
        if (!settings.persistentNotificationEnabled) {
            stopSelf()
            return
        }

        val snapshot = BatteryReader.read(applicationContext)
        val title: String
        val content: String
        if (snapshot == null) {
            title = "电量读取失败"
            content = "${settings.deviceName} · 常驻监控运行中"
        } else {
            val charging = if (snapshot.charging) "充电中" else "未充电"
            title = "${settings.deviceName} · ${snapshot.percent}%"
            content = "$charging · 低电量阈值 ${settings.lowThreshold}% · 每分钟刷新"
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(title, content))
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            3001,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, BatteryForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            3002,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止常驻", stopPendingIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Persistent Battery Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "PowerManager 常驻电量状态通知"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "persistent_battery_monitor"
        private const val NOTIFICATION_ID = 10001
        private const val ACTION_STOP = "cn.tryxd.powermanager.action.STOP_FOREGROUND"

        fun start(context: Context) {
            val intent = Intent(context, BatteryForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BatteryForegroundService::class.java))
        }
    }
}
