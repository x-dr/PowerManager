package cn.tryxd.powermanager.worker

import android.content.Context
import cn.tryxd.powermanager.data.SettingsRepository
import cn.tryxd.powermanager.model.BatteryLevelState
import cn.tryxd.powermanager.monitor.BatteryReader
import cn.tryxd.powermanager.notifier.NotifyDispatcher
import cn.tryxd.powermanager.rule.BatteryRuleEngine

/**
 * 电池告警核心处理器。
 * 负责结合电量状态、预设阈值、冷却时间以及历史状态，来触发充电通知、满电通知、低电量通知及电量恢复通知，并保存运行时状态。
 */
object BatteryAlertProcessor {
    /**
     * 执行电量分析与告警分发的主要挂起方法。
     * 
     * @param context 上下文对象
     * @param markChecked 是否需要将最近检查时间记为当前时间
     * @return 返回 true 表示处理成功，返回 false 表示无法读取电池状态
     */
    suspend fun process(context: Context, markChecked: Boolean = true): Boolean {
        val appContext = context.applicationContext
        val repository = SettingsRepository(appContext)
        val settings = repository.getSettings()
        
        // 1. 如果需要，标记本次检查时间戳
        if (markChecked) repository.markChecked()

        // 2. 如果已禁用电量监控，则不继续执行后续逻辑
        if (!settings.monitorEnabled) return true

        // 3. 读取电池快照，如果失败则返回 false
        val snapshot = BatteryReader.read(appContext) ?: return false
        val dispatcher = NotifyDispatcher(appContext)
        val now = System.currentTimeMillis()
        val cooldownMs = settings.cooldownMinutes.coerceAtLeast(1) * 60_000L

        // 4. 判定是否达到满电通知阈值（满电阈值强制限制在 50-100% 之间）
        val fullThreshold = settings.fullChargeThreshold.coerceIn(50, 100)
        val fullReached = snapshot.plugged && snapshot.percent >= fullThreshold

        // 5. 校验并触发「开始充电」通知：开启了充电通知 + 当前插入电源 + 上一次未插入电源
        if (settings.chargingNotifyEnabled && snapshot.plugged && !settings.lastPlugged) {
            if (now - settings.lastChargingNotifyAt >= cooldownMs) {
                val event = BatteryRuleEngine.buildEvent(BatteryLevelState.CHARGING, snapshot, settings)
                dispatcher.send(event, settings)
                repository.updateNotifyState(BatteryLevelState.CHARGING, now)
            }
        }

        // 6. 校验并触发「充满电」通知：开启了充满电通知 + 达到了电量阈值 + 上一次没到达充满状态
        if (settings.fullChargeNotifyEnabled && fullReached && !settings.lastFullChargeReached) {
            if (now - settings.lastFullChargeNotifyAt >= cooldownMs) {
                val event = BatteryRuleEngine.buildEvent(BatteryLevelState.FULL_CHARGE, snapshot, settings)
                dispatcher.send(event, settings)
                repository.updateNotifyState(BatteryLevelState.FULL_CHARGE, now)
            }
        }

        // 7. 更新本次读取的电源连接状态与充满电达标状态，为下次比较做准备
        repository.updateBatteryRuntimeState(
            plugged = snapshot.plugged,
            fullChargeReached = fullReached
        )

        // 8. 结合电量和阈值，评测出最新的电量区间状态（NORMAL, LOW, CRITICAL, DANGER, RECOVERED）
        val newState = BatteryRuleEngine.evaluate(snapshot, settings)
        
        // 9. 处理「电量恢复」通知：如果新状态评估为 RECOVERED
        if (newState == BatteryLevelState.RECOVERED) {
            val wasLow = settings.lastState == BatteryLevelState.LOW ||
                settings.lastState == BatteryLevelState.CRITICAL ||
                settings.lastState == BatteryLevelState.DANGER

            // 如果此前处于低电量阶段（LOW, CRITICAL, DANGER），且满足冷却规则，则触发电量恢复通知
            if (wasLow && BatteryRuleEngine.shouldNotify(newState, settings, now)) {
                val event = BatteryRuleEngine.buildEvent(newState, snapshot, settings)
                dispatcher.send(event, settings)
                repository.updateNotifyState(newState, now)
            }
            // 归一化重置 lastState 为 NORMAL
            repository.updateLastState(BatteryLevelState.NORMAL)
            return true
        }

        // 10. 处理「低电量告警」通知
        if (BatteryRuleEngine.shouldNotify(newState, settings, now)) {
            val event = BatteryRuleEngine.buildEvent(newState, snapshot, settings)
            dispatcher.send(event, settings)
            repository.updateNotifyState(newState, now)
        } else {
            // 如果不满足通知条件（如处于冷却期或状态未变化），仅保存更新 lastState
            repository.updateLastState(newState)
        }

        return true
    }
}
