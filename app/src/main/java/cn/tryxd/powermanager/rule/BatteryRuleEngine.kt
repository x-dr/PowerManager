package cn.tryxd.powermanager.rule

import cn.tryxd.powermanager.model.AppSettings
import cn.tryxd.powermanager.model.BatteryLevelState
import cn.tryxd.powermanager.model.BatterySnapshot
import cn.tryxd.powermanager.model.NotifyEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BatteryRuleEngine {
    fun evaluate(snapshot: BatterySnapshot, settings: AppSettings): BatteryLevelState {
        return when {
            snapshot.percent <= settings.dangerThreshold -> BatteryLevelState.DANGER
            snapshot.percent <= settings.criticalThreshold -> BatteryLevelState.CRITICAL
            snapshot.percent <= settings.lowThreshold -> BatteryLevelState.LOW
            snapshot.percent >= settings.recoverThreshold -> BatteryLevelState.RECOVERED
            else -> settings.lastState
        }
    }

    fun shouldNotify(newState: BatteryLevelState, settings: AppSettings, now: Long = System.currentTimeMillis()): Boolean {
        if (newState == BatteryLevelState.NORMAL) return false
        if (newState == settings.lastState && newState != BatteryLevelState.RECOVERED) return false

        val cooldownMs = settings.cooldownMinutes.coerceAtLeast(1) * 60_000L
        val lastAt = when (newState) {
            BatteryLevelState.LOW -> settings.lastLowNotifyAt
            BatteryLevelState.CRITICAL -> settings.lastCriticalNotifyAt
            BatteryLevelState.DANGER -> settings.lastDangerNotifyAt
            BatteryLevelState.RECOVERED -> settings.lastRecoverNotifyAt
            BatteryLevelState.NORMAL -> settings.lastNotifyAt
        }
        return now - lastAt >= cooldownMs
    }

    fun buildEvent(state: BatteryLevelState, snapshot: BatterySnapshot, settings: AppSettings): NotifyEvent {
        val title = when (state) {
            BatteryLevelState.LOW -> "设备电量低"
            BatteryLevelState.CRITICAL -> "设备电量严重不足"
            BatteryLevelState.DANGER -> "设备即将关机"
            BatteryLevelState.RECOVERED -> "设备电量已恢复"
            BatteryLevelState.NORMAL -> "设备电量正常"
        }
        val chargingText = if (snapshot.charging) "充电中" else "未充电"
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(snapshot.timestamp))
        val temp = snapshot.temperatureCelsius?.let { "\n电池温度：${String.format(Locale.getDefault(), "%.1f", it)}℃" } ?: ""
        val body = "设备：${settings.deviceName}\n当前电量：${snapshot.percent}%\n充电状态：$chargingText\n时间：$time\n低电量阈值：${settings.lowThreshold}%$temp"
        return NotifyEvent(state = state, title = title, body = body)
    }
}
