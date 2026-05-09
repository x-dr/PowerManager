package cn.tryxd.powermanager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.tryxd.powermanager.worker.BatteryCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.Default).launch {
                runCatching {
                    BatteryCheckWorker.start(context.applicationContext)
                    BatteryCheckWorker.checkNow(context.applicationContext)
                }
                pendingResult.finish()
            }
        }
    }
}
