package cn.tryxd.powermanager.rule

import cn.tryxd.powermanager.model.AppSettings
import cn.tryxd.powermanager.model.BatteryLevelState
import cn.tryxd.powermanager.model.BatterySnapshot
import cn.tryxd.powermanager.model.NotifyEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 电池告警规则引擎，用于评估电池状态、判定是否达到推送冷却条件并构建通知事件。
 */
object BatteryRuleEngine {
    /**
     * 根据当前电量百分比和设置的各个阈值，评估并决定新的电池状态。
     * 
     * @param snapshot 电池状态快照
     * @param settings 应用配置信息
     * @return 评估出的最新电量/工作状态 [BatteryLevelState]
     */
    fun evaluate(snapshot: BatterySnapshot, settings: AppSettings): BatteryLevelState {
        // 确保上一个状态如果是充电或充满电，将其安全归一化为 NORMAL 状态以防逻辑混乱
        val safeLastState = when (settings.lastState) {
            BatteryLevelState.CHARGING,
            BatteryLevelState.FULL_CHARGE -> BatteryLevelState.NORMAL
            else -> settings.lastState
        }
        // 按优先级从高到低对阈值进行判断
        return when {
            snapshot.percent <= settings.dangerThreshold -> BatteryLevelState.DANGER
            snapshot.percent <= settings.criticalThreshold -> BatteryLevelState.CRITICAL
            snapshot.percent <= settings.lowThreshold -> BatteryLevelState.LOW
            snapshot.percent >= settings.recoverThreshold -> BatteryLevelState.RECOVERED
            else -> safeLastState
        }
    }

    /**
     * 判断是否需要发送通知（包括防重复推送和冷却时间校验）。
     * 
     * @param newState 待评估的新状态
     * @param settings 应用配置信息
     * @param now 当前时间戳
     * @return 返回 true 表示符合发送条件，否则返回 false
     */
    fun shouldNotify(newState: BatteryLevelState, settings: AppSettings, now: Long = System.currentTimeMillis()): Boolean {
        // 1. 如果是 NORMAL 正常状态，默认不推送
        if (newState == BatteryLevelState.NORMAL) return false
        // 2. 如果状态没有发生改变，且新状态不是 RECOVERED（电量恢复通常是一次性的过渡动作），则不重复推送
        if (newState == settings.lastState && newState != BatteryLevelState.RECOVERED) return false

        // 3. 计算冷却时间（以毫秒为单位，最小为 1 分钟）
        val cooldownMs = settings.cooldownMinutes.coerceAtLeast(1) * 60_000L
        
        // 4. 获取该状态上次推送的时间戳
        val lastAt = when (newState) {
            BatteryLevelState.LOW -> settings.lastLowNotifyAt
            BatteryLevelState.CRITICAL -> settings.lastCriticalNotifyAt
            BatteryLevelState.DANGER -> settings.lastDangerNotifyAt
            BatteryLevelState.RECOVERED -> settings.lastRecoverNotifyAt
            BatteryLevelState.CHARGING -> settings.lastChargingNotifyAt
            BatteryLevelState.FULL_CHARGE -> settings.lastFullChargeNotifyAt
            BatteryLevelState.NORMAL -> settings.lastNotifyAt
        }
        // 5. 校验当前距离上次发送该状态通知的时间是否已超过冷却时长
        return now - lastAt >= cooldownMs
    }

    /**
     * 组装通知事件 [NotifyEvent]，生成对应语言（中文）的标题与详细内容体。
     * 
     * @param state 评估确定的电池状态
     * @param snapshot 电池状态快照
     * @param settings 配置信息
     * @return 组装好的通知事件
     */
    fun buildEvent(state: BatteryLevelState, snapshot: BatterySnapshot, settings: AppSettings): NotifyEvent {
        val title = when (state) {
            BatteryLevelState.LOW -> "设备电量低"
            BatteryLevelState.CRITICAL -> "设备电量严重不足"
            BatteryLevelState.DANGER -> "设备即将关机"
            BatteryLevelState.RECOVERED -> "设备电量已恢复"
            BatteryLevelState.CHARGING -> "设备开始充电"
            BatteryLevelState.FULL_CHARGE -> "设备电量已充满"
            BatteryLevelState.NORMAL -> "设备电量正常"
        }
        val chargingText = when {
            snapshot.charging -> "充电中"
            snapshot.plugged -> "已接入电源"
            else -> "未充电"
        }
        // 格式化输出时间和温度，如果温度不存在则忽略
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(snapshot.timestamp))
        val temp = snapshot.temperatureCelsius?.let { "\n电池温度：${String.format(Locale.getDefault(), "%.1f", it)}℃" } ?: ""
        
        val body = "设备：${settings.deviceName}\n当前电量：${snapshot.percent}%\n充电状态：$chargingText\n时间：$time\n低电量阈值：${settings.lowThreshold}%\n满电阈值：${settings.fullChargeThreshold}%$temp"
        return NotifyEvent(state = state, title = title, body = body)
    }
}
