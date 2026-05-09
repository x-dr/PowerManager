package cn.tryxd.powermanager.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import cn.tryxd.powermanager.model.BatterySnapshot

object BatteryReader {
    fun read(context: Context): BatterySnapshot? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null

        val percent = (level * 100f / scale).toInt().coerceIn(0, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pluggedValue = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val voltageRaw = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)

        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        return BatterySnapshot(
            percent = percent,
            charging = charging,
            plugged = pluggedValue != 0,
            temperatureCelsius = if (tempRaw == Int.MIN_VALUE) null else tempRaw / 10f,
            voltageMv = if (voltageRaw == Int.MIN_VALUE) null else voltageRaw
        )
    }
}
