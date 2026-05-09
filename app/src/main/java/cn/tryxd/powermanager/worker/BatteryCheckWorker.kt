package cn.tryxd.powermanager.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.tryxd.powermanager.data.SettingsRepository
import cn.tryxd.powermanager.model.BatteryLevelState
import cn.tryxd.powermanager.monitor.BatteryReader
import cn.tryxd.powermanager.notifier.NotifyDispatcher
import cn.tryxd.powermanager.rule.BatteryRuleEngine
import java.util.concurrent.TimeUnit

class BatteryCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = SettingsRepository(applicationContext)
        val settings = repository.getSettings()
        repository.markChecked()

        if (!settings.monitorEnabled) return Result.success()

        val snapshot = BatteryReader.read(applicationContext) ?: return Result.retry()
        val newState = BatteryRuleEngine.evaluate(snapshot, settings)
        val now = System.currentTimeMillis()

        if (newState == BatteryLevelState.RECOVERED) {
            val wasLow = settings.lastState == BatteryLevelState.LOW ||
                settings.lastState == BatteryLevelState.CRITICAL ||
                settings.lastState == BatteryLevelState.DANGER

            if (wasLow && BatteryRuleEngine.shouldNotify(newState, settings, now)) {
                val event = BatteryRuleEngine.buildEvent(newState, snapshot, settings)
                NotifyDispatcher(applicationContext).send(event, settings)
                repository.updateNotifyState(newState, now)
            }
            repository.updateLastState(BatteryLevelState.NORMAL)
            return Result.success()
        }

        if (BatteryRuleEngine.shouldNotify(newState, settings, now)) {
            val event = BatteryRuleEngine.buildEvent(newState, snapshot, settings)
            NotifyDispatcher(applicationContext).send(event, settings)
            repository.updateNotifyState(newState, now)
        } else {
            repository.updateLastState(newState)
        }

        return Result.success()
    }

    companion object {
        private const val UNIQUE_PERIODIC_WORK = "battery_check_periodic"

        fun start(context: Context) {
            val request = PeriodicWorkRequestBuilder<BatteryCheckWorker>(15, TimeUnit.MINUTES)
                .addTag(UNIQUE_PERIODIC_WORK)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun checkNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<BatteryCheckWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC_WORK)
        }
    }
}
