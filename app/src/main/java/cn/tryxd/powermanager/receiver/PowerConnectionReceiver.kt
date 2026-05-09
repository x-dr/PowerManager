package cn.tryxd.powermanager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.tryxd.powermanager.worker.BatteryAlertProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PowerConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_POWER_CONNECTED || action == Intent.ACTION_POWER_DISCONNECTED) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.Default).launch {
                runCatching {
                    BatteryAlertProcessor.process(context.applicationContext, markChecked = true)
                }
                pendingResult.finish()
            }
        }
    }
}
