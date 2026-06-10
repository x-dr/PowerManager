package cn.tryxd.powermanager.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 电池状态检查后台任务 Worker。
 * 继承自 [CoroutineWorker]，借助 Android WorkManager 框架实现系统级的后台周期性检查，具有自动唤醒与省电优化支持。
 */
class BatteryCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    /**
     * 实际后台任务执行的方法。
     */
    override suspend fun doWork(): Result {
        // 调用处理器执行电量检测及告警
        val ok = BatteryAlertProcessor.process(applicationContext, markChecked = true)
        // 执行成功返回 Success，失败则根据策略自动重试
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE_PERIODIC_WORK = "battery_check_periodic"

        /**
         * 启动周期性电量检查（每 15 分钟运行一次，这是 WorkManager 的最小周期限制）。
         */
        fun start(context: Context) {
            val request = PeriodicWorkRequestBuilder<BatteryCheckWorker>(15, TimeUnit.MINUTES)
                .addTag(UNIQUE_PERIODIC_WORK)
                .build()
            // 使用 enqueueUniquePeriodicWork 保证不会重复入队，并使用 UPDATE 策略更新当前正在运行的任务
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /**
         * 立即执行一次一次性的电量检查任务。
         */
        fun checkNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<BatteryCheckWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }

        /**
         * 停止并取消周期性的电量检查后台任务。
         */
        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC_WORK)
        }
    }
}
