package cn.tryxd.powermanager.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class BatteryCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ok = BatteryAlertProcessor.process(applicationContext, markChecked = true)
        return if (ok) Result.success() else Result.retry()
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
