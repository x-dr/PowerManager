package cn.tryxd.powermanager.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import cn.tryxd.powermanager.model.BatterySnapshot

/**
 * 电池状态读取工具类。
 */
object BatteryReader {
    /**
     * 读取当前电池状态快照。
     * 
     * @param context 上下文对象
     * @return 返回读取到的电池状态快照 [BatterySnapshot]，如果读取失败则返回 null。
     */
    fun read(context: Context): BatterySnapshot? {
        // 注册对 ACTION_BATTERY_CHANGED 广播的接收器，传入 null 接收器来获取最新的粘性广播 Intent。
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        
        // 获取当前电量及电量总刻度
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null

        // 计算当前电量百分比并限制在 0-100 之间
        val percent = (level * 100f / scale).toInt().coerceIn(0, 100)
        
        // 获取充电状态、供电类型、温度及电压值
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pluggedValue = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val voltageRaw = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)

        // 判断是否正在充电（充电中或电量已满状态均视为 charging）
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        return BatterySnapshot(
            percent = percent,
            charging = charging,
            plugged = pluggedValue != 0, // pluggedValue 不为 0 表示插入了电源(AC、USB或无线充电)
            // 系统返回的温度 tempRaw 是摄氏度的 10 倍（例如 350 表示 35.0℃），因此需要除以 10
            temperatureCelsius = if (tempRaw == Int.MIN_VALUE) null else tempRaw / 10f,
            voltageMv = if (voltageRaw == Int.MIN_VALUE) null else voltageRaw
        )
    }
}
