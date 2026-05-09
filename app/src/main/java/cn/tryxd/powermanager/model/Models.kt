package cn.tryxd.powermanager.model

data class AppSettings(
    val monitorEnabled: Boolean = true,
    val deviceName: String = "Android Device",
    val lowThreshold: Int = 20,
    val criticalThreshold: Int = 10,
    val dangerThreshold: Int = 5,
    val recoverThreshold: Int = 30,
    val cooldownMinutes: Int = 60,
    val localNotifyEnabled: Boolean = true,
    val barkEnabled: Boolean = false,
    val barkServer: String = "https://api.day.app",
    val barkKey: String = "",
    val telegramEnabled: Boolean = false,
    val telegramBotToken: String = "",
    val telegramChatId: String = "",
    val webhookEnabled: Boolean = false,
    val webhookUrl: String = "",
    val lastState: BatteryLevelState = BatteryLevelState.NORMAL,
    val lastCheckAt: Long = 0L,
    val lastNotifyAt: Long = 0L,
    val lastLowNotifyAt: Long = 0L,
    val lastCriticalNotifyAt: Long = 0L,
    val lastDangerNotifyAt: Long = 0L,
    val lastRecoverNotifyAt: Long = 0L
)

data class BatterySnapshot(
    val percent: Int,
    val charging: Boolean,
    val plugged: Boolean,
    val temperatureCelsius: Float?,
    val voltageMv: Int?,
    val timestamp: Long = System.currentTimeMillis()
)

data class NotifyEvent(
    val state: BatteryLevelState,
    val title: String,
    val body: String
)

enum class BatteryLevelState {
    NORMAL,
    LOW,
    CRITICAL,
    DANGER,
    RECOVERED
}
