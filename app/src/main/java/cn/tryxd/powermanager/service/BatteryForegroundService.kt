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
import cn.tryxd.powermanager.worker.BatteryAlertProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台常驻电量监控服务。
 * 通过在状态栏维护一个持续显示的通知，保证应用进程不被系统轻易杀掉，并进行高频（每分钟）的电量监测。
 */
class BatteryForegroundService : Service() {
    // 实例化一个前台服务私有的协程作用域，绑定 SupervisorJob 和 Default 调度器
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var refreshJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // 创建用于前台通知的渠道
        createChannel()
    }

    /**
     * 当外部通过 startService 发送启动命令时回调。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 如果收到的 Action 是停止服务，则关闭服务并更新配置
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                SettingsRepository(applicationContext).setPersistentNotificationEnabled(false)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        // 绑定前台通知，启动前台服务进程
        startForeground(NOTIFICATION_ID, buildNotification("正在读取电量", "PowerManager 常驻监控已启动"))
        // 开启每分钟循环刷新的后台任务
        startRefreshLoop()
        
        // 设为 START_STICKY，如果服务被系统杀掉，在资源允许时会尝试重新启动服务，但不会重新传递最后的 Intent
        return START_STICKY
    }

    override fun onDestroy() {
        // 服务销毁时取消轮询 Job 和协程作用域，防止内存泄漏
        refreshJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 开启定时刷新回路，每隔 60 秒触发一次电量检测，并更新前台通知内容。
     */
    private fun startRefreshLoop() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            while (isActive) {
                // 执行电量状态判断及告警分发，标记已检查
                BatteryAlertProcessor.process(applicationContext, markChecked = true)
                // 刷新前台通知的显示状态（显示最新百分比及充电信息）
                refreshNotification()
                // 等待 60 秒
                delay(60_000L)
            }
        }
    }

    /**
     * 刷新前台通知栏文本。
     */
    private suspend fun refreshNotification() {
        val settings = SettingsRepository(applicationContext).getSettings()
        // 如果在外部配置中关闭了常驻通知，则主动关闭该服务
        if (!settings.persistentNotificationEnabled) {
            stopSelf()
            return
        }

        // 读取最新电池快照信息
        val snapshot = BatteryReader.read(applicationContext)
        val title: String
        val content: String
        if (snapshot == null) {
            title = "电量读取失败"
            content = "${settings.deviceName} · 常驻监控运行中"
        } else {
            val charging = when {
                snapshot.charging -> "充电中"
                snapshot.plugged -> "已接入电源"
                else -> "未充电"
            }
            title = "${settings.deviceName} · ${snapshot.percent}%"
            content = "$charging · 满电阈值 ${settings.fullChargeThreshold}% · 每分钟刷新"
        }

        // 获取通知管理器，并使用相同的通知 ID 覆盖更新前台通知内容
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(title, content))
    }

    /**
     * 构造并配置前台通知 [Notification]。
     * 
     * @param title 通知标题
     * @param content 通知内容
     * @return 返回配置完毕的 Notification 对象
     */
    private fun buildNotification(title: String, content: String): Notification {
        // 点击通知主体时打开 MainActivity 的 PendingIntent
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            3001,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 点击通知上的“停止常驻”按钮时停止前台服务的 PendingIntent
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
            .setOngoing(true) // 设置为常驻通知，用户无法通过侧滑清除
            .setShowWhen(false) // 不需要显示通知时间戳
            .setOnlyAlertOnce(true) // 仅在第一次创建时发出声音/震动提示（虽然 IMPORTANCE_LOW 本身不会打扰）
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止常驻", stopPendingIntent)
            .build()
    }

    /**
     * 创建前台服务通知渠道（Android 8.0 Oreo 以上系统适用）。
     * 使用 IMPORTANCE_LOW（低优先级）防止通知刷新的同时产生打扰声音。
     */
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Persistent Battery Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "PowerManager 常驻电量状态通知"
                setShowBadge(false) // 状态栏图标处不显示角标
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "persistent_battery_monitor"
        private const val NOTIFICATION_ID = 10001
        private const val ACTION_STOP = "cn.tryxd.powermanager.action.STOP_FOREGROUND"

        /**
         * 启动前台常驻服务的快捷助手函数。
         */
        fun start(context: Context) {
            val intent = Intent(context, BatteryForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 停止前台常驻服务的快捷助手函数。
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, BatteryForegroundService::class.java))
        }
    }
}
