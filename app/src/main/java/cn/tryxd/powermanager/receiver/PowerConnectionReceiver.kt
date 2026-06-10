package cn.tryxd.powermanager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.tryxd.powermanager.worker.BatteryAlertProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 电源连接与断开广播接收器，用于在充电状态改变时，立即执行一次电量检查和告警分发。
 */
class PowerConnectionReceiver : BroadcastReceiver() {
    /**
     * 当接收到充电线插入或拔出的系统广播时被回调。
     */
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // 监听电源接通和电源断开的广播
        if (action == Intent.ACTION_POWER_CONNECTED || action == Intent.ACTION_POWER_DISCONNECTED) {
            // 使用 goAsync() 允许在广播接收器中以协程异步执行耗时逻辑，防止主线程 ANR
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.Default).launch {
                runCatching {
                    // 立即触发电量告警核心处理器逻辑，并将最近检查标记置为 true (更新检查时间戳)
                    BatteryAlertProcessor.process(context.applicationContext, markChecked = true)
                }
                // 完成异步执行，释放系统唤醒锁
                pendingResult.finish()
            }
        }
    }
}
