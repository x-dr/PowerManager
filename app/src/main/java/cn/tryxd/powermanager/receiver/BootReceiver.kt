package cn.tryxd.powermanager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.tryxd.powermanager.data.SettingsRepository
import cn.tryxd.powermanager.service.BatteryForegroundService
import cn.tryxd.powermanager.worker.BatteryCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机广播接收器，用于在系统启动完成或应用更新后，自动恢复电量监控与前台常驻服务。
 */
class BootReceiver : BroadcastReceiver() {
    /**
     * 当接收到广播时的回调函数。
     */
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // 监听系统启动完成广播，以及本应用自身被覆盖安装/更新成功的广播
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // 调用 goAsync() 获取 PendingResult，允许在广播接收器内异步执行挂起任务，避免阻塞主线程或被系统终止
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.Default).launch {
                runCatching {
                    val appContext = context.applicationContext
                    val settings = SettingsRepository(appContext).getSettings()
                    
                    // 如果用户开启了电量监控，重新注册后台定时检查任务并立即执行一次检查
                    if (settings.monitorEnabled) {
                        BatteryCheckWorker.start(appContext)
                        BatteryCheckWorker.checkNow(appContext)
                    }
                    // 如果用户启用了常驻通知，则启动前台电量监听服务
                    if (settings.persistentNotificationEnabled) {
                        BatteryForegroundService.start(appContext)
                    }
                }
                // 必须调用 finish() 以通知系统该广播接收器已处理完毕，以便释放系统持有的唤醒锁
                pendingResult.finish()
            }
        }
    }
}
