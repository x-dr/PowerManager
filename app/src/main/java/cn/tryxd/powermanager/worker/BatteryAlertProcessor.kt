package cn.tryxd.powermanager.worker

import android.content.Context
import cn.tryxd.powermanager.data.SettingsRepository
import cn.tryxd.powermanager.model.BatteryLevelState
import cn.tryxd.powermanager.monitor.BatteryReader
import cn.tryxd.powermanager.notifier.NotifyDispatcher
import cn.tryxd.powermanager.rule.BatteryRuleEngine

object BatteryAlertProcessor {
    suspend fun process(context: Context, markChecked: Boolean = true): Boolean {
        val appContext = context.applicationContext
        val repository = SettingsRepository(appContext)
        val settings = repository.getSettings()
        if (markChecked) repository.markChecked()

        if (!settings.monitorEnabled) return true

        val snapshot = BatteryReader.read(appContext) ?: return false
        val dispatcher = NotifyDispatcher(appContext)
        val now = System.currentTimeMillis()
        val cooldownMs = settings.cooldownMinutes.coerceAtLeast(1) * 60_000L

        val fullThreshold = settings.fullChargeThreshold.coerceIn(50, 100)
        val fullReached = snapshot.plugged && snapshot.percent >= fullThreshold

        if (settings.chargingNotifyEnabled && snapshot.plugged && !settings.lastPlugged) {
            if (now - settings.lastChargingNotifyAt >= cooldownMs) {
                val event = BatteryRuleEngine.buildEvent(BatteryLevelState.CHARGING, snapshot, settings)
                dispatcher.send(event, settings)
                repository.updateNotifyState(BatteryLevelState.CHARGING, now)
            }
        }

        if (settings.fullChargeNotifyEnabled && fullReached && !settings.lastFullChargeReached) {
            if (now - settings.lastFullChargeNotifyAt >= cooldownMs) {
                val event = BatteryRuleEngine.buildEvent(BatteryLevelState.FULL_CHARGE, snapshot, settings)
                dispatcher.send(event, settings)
                repository.updateNotifyState(BatteryLevelState.FULL_CHARGE, now)
            }
        }

        repository.updateBatteryRuntimeState(
            plugged = snapshot.plugged,
            fullChargeReached = fullReached
        )

        val newState = BatteryRuleEngine.evaluate(snapshot, settings)
        if (newState == BatteryLevelState.RECOVERED) {
            val wasLow = settings.lastState == BatteryLevelState.LOW ||
                settings.lastState == BatteryLevelState.CRITICAL ||
                settings.lastState == BatteryLevelState.DANGER

            if (wasLow && BatteryRuleEngine.shouldNotify(newState, settings, now)) {
                val event = BatteryRuleEngine.buildEvent(newState, snapshot, settings)
                dispatcher.send(event, settings)
                repository.updateNotifyState(newState, now)
            }
            repository.updateLastState(BatteryLevelState.NORMAL)
            return true
        }

        if (BatteryRuleEngine.shouldNotify(newState, settings, now)) {
            val event = BatteryRuleEngine.buildEvent(newState, snapshot, settings)
            dispatcher.send(event, settings)
            repository.updateNotifyState(newState, now)
        } else {
            repository.updateLastState(newState)
        }

        return true
    }
}
