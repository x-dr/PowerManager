package cn.tryxd.powermanager.model

/**
 * 应用配置信息类
 */
data class AppSettings(
    val monitorEnabled: Boolean = true, // 是否启用电量监控
    val persistentNotificationEnabled: Boolean = false, // 是否启用常驻（前台服务）通知
    val chargingNotifyEnabled: Boolean = true, // 是否启用充电通知
    val fullChargeNotifyEnabled: Boolean = true, // 是否启用充满电通知
    val fullChargeThreshold: Int = 100, // 充满电阈值（默认为 100%）
    val deviceName: String = "Android Device", // 设备名称（用于推送通知中区分设备）
    val lowThreshold: Int = 20, // 低电量阈值
    val criticalThreshold: Int = 10, // 严重低电量阈值
    val dangerThreshold: Int = 5, // 危险低电量阈值
    val recoverThreshold: Int = 30, // 电量恢复阈值（用于从低电量状态恢复）
    val cooldownMinutes: Int = 60, // 通知冷却时间（分钟，防止频繁推送）
    val localNotifyEnabled: Boolean = true, // 是否启用本地系统通知
    val barkEnabled: Boolean = false, // 是否启用 Bark 推送
    val barkServer: String = "https://api.day.app", // Bark 服务器地址
    val barkKey: String = "", // Bark 推送密钥
    val telegramEnabled: Boolean = false, // 是否启用 Telegram Bot 推送
    val telegramBotToken: String = "", // Telegram Bot Token
    val telegramChatId: String = "", // Telegram Chat ID
    val webhookEnabled: Boolean = false, // 是否启用自定义 Webhook 推送
    val webhookUrl: String = "", // Webhook URL
    val lastState: BatteryLevelState = BatteryLevelState.NORMAL, // 上一次电量状态
    val lastCheckAt: Long = 0L, // 上次检查时间戳
    val lastNotifyAt: Long = 0L, // 上次通知时间戳
    val lastLowNotifyAt: Long = 0L, // 上次低电量通知时间戳
    val lastCriticalNotifyAt: Long = 0L, // 上次严重低电量通知时间戳
    val lastDangerNotifyAt: Long = 0L, // 上次危险低电量通知时间戳
    val lastRecoverNotifyAt: Long = 0L, // 上次电量恢复通知时间戳
    val lastChargingNotifyAt: Long = 0L, // 上次充电通知时间戳
    val lastFullChargeNotifyAt: Long = 0L, // 上次充满电通知时间戳
    val lastPlugged: Boolean = false, // 上次是否插入电源
    val lastFullChargeReached: Boolean = false // 上次是否达到充满电状态
)

/**
 * 电池状态快照，用于记录当前电池的各项参数
 */
data class BatterySnapshot(
    val percent: Int, // 电池百分比 (0-100)
    val charging: Boolean, // 是否正在充电
    val plugged: Boolean, // 是否连接电源
    val temperatureCelsius: Float?, // 电池温度 (摄氏度)
    val voltageMv: Int?, // 电池电压 (毫伏)
    val timestamp: Long = System.currentTimeMillis() // 记录时间戳
)

/**
 * 推送通知事件封装类
 */
data class NotifyEvent(
    val state: BatteryLevelState, // 当前电池状态分类
    val title: String, // 通知标题
    val body: String // 通知内容
)

/**
 * 电池电量/状态的枚举类型
 */
enum class BatteryLevelState {
    NORMAL,      // 正常状态
    LOW,         // 低电量
    CRITICAL,    // 严重低电量
    DANGER,      // 危险低电量（极低电量）
    RECOVERED,   // 电量恢复（已充电至恢复阈值以上）
    CHARGING,    // 正在充电
    FULL_CHARGE  // 充满电
}
