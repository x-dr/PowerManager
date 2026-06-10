package cn.tryxd.powermanager.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cn.tryxd.powermanager.model.AppSettings
import cn.tryxd.powermanager.model.BatteryLevelState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// 使用 DataStore 扩展属性定义设置数据存储实例，文件名为 power_manager_settings
private val Context.powerSettingsDataStore by preferencesDataStore(name = "power_manager_settings")

/**
 * 设置仓库类，负责使用 Jetpack DataStore Preferences 持久化和读取配置信息。
 */
class SettingsRepository(private val context: Context) {

    /**
     * 电量配置流，当 DataStore 中的数据改变时，会发射新的 [AppSettings] 对象。
     */
    val settingsFlow: Flow<AppSettings> = context.powerSettingsDataStore.data.map { p ->
        AppSettings(
            monitorEnabled = p[Keys.MONITOR_ENABLED] ?: true,
            persistentNotificationEnabled = p[Keys.PERSISTENT_NOTIFICATION_ENABLED] ?: false,
            chargingNotifyEnabled = p[Keys.CHARGING_NOTIFY_ENABLED] ?: true,
            fullChargeNotifyEnabled = p[Keys.FULL_CHARGE_NOTIFY_ENABLED] ?: true,
            fullChargeThreshold = p[Keys.FULL_CHARGE_THRESHOLD] ?: 100,
            deviceName = p[Keys.DEVICE_NAME] ?: "Android Device",
            lowThreshold = p[Keys.LOW_THRESHOLD] ?: 20,
            criticalThreshold = p[Keys.CRITICAL_THRESHOLD] ?: 10,
            dangerThreshold = p[Keys.DANGER_THRESHOLD] ?: 5,
            recoverThreshold = p[Keys.RECOVER_THRESHOLD] ?: 30,
            cooldownMinutes = p[Keys.COOLDOWN_MINUTES] ?: 60,
            localNotifyEnabled = p[Keys.LOCAL_NOTIFY_ENABLED] ?: true,
            barkEnabled = p[Keys.BARK_ENABLED] ?: false,
            barkServer = p[Keys.BARK_SERVER] ?: "https://api.day.app",
            barkKey = p[Keys.BARK_KEY] ?: "",
            telegramEnabled = p[Keys.TELEGRAM_ENABLED] ?: false,
            telegramBotToken = p[Keys.TELEGRAM_BOT_TOKEN] ?: "",
            telegramChatId = p[Keys.TELEGRAM_CHAT_ID] ?: "",
            webhookEnabled = p[Keys.WEBHOOK_ENABLED] ?: false,
            webhookUrl = p[Keys.WEBHOOK_URL] ?: "",
            lastState = parseState(p[Keys.LAST_STATE] ?: BatteryLevelState.NORMAL.name),
            lastCheckAt = p[Keys.LAST_CHECK_AT] ?: 0L,
            lastNotifyAt = p[Keys.LAST_NOTIFY_AT] ?: 0L,
            lastLowNotifyAt = p[Keys.LAST_LOW_NOTIFY_AT] ?: 0L,
            lastCriticalNotifyAt = p[Keys.LAST_CRITICAL_NOTIFY_AT] ?: 0L,
            lastDangerNotifyAt = p[Keys.LAST_DANGER_NOTIFY_AT] ?: 0L,
            lastRecoverNotifyAt = p[Keys.LAST_RECOVER_NOTIFY_AT] ?: 0L,
            lastChargingNotifyAt = p[Keys.LAST_CHARGING_NOTIFY_AT] ?: 0L,
            lastFullChargeNotifyAt = p[Keys.LAST_FULL_CHARGE_NOTIFY_AT] ?: 0L,
            lastPlugged = p[Keys.LAST_PLUGGED] ?: false,
            lastFullChargeReached = p[Keys.LAST_FULL_CHARGE_REACHED] ?: false
        )
    }

    /**
     * 挂起函数，单次获取当前最新的配置快照。
     */
    suspend fun getSettings(): AppSettings = settingsFlow.first()

    /**
     * 保存除运行时状态外的用户可编辑配置项。
     */
    suspend fun saveSettings(settings: AppSettings) {
        context.powerSettingsDataStore.edit { p ->
            p[Keys.MONITOR_ENABLED] = settings.monitorEnabled
            p[Keys.PERSISTENT_NOTIFICATION_ENABLED] = settings.persistentNotificationEnabled
            p[Keys.CHARGING_NOTIFY_ENABLED] = settings.chargingNotifyEnabled
            p[Keys.FULL_CHARGE_NOTIFY_ENABLED] = settings.fullChargeNotifyEnabled
            p[Keys.FULL_CHARGE_THRESHOLD] = settings.fullChargeThreshold
            p[Keys.DEVICE_NAME] = settings.deviceName
            p[Keys.LOW_THRESHOLD] = settings.lowThreshold
            p[Keys.CRITICAL_THRESHOLD] = settings.criticalThreshold
            p[Keys.DANGER_THRESHOLD] = settings.dangerThreshold
            p[Keys.RECOVER_THRESHOLD] = settings.recoverThreshold
            p[Keys.COOLDOWN_MINUTES] = settings.cooldownMinutes
            p[Keys.LOCAL_NOTIFY_ENABLED] = settings.localNotifyEnabled
            p[Keys.BARK_ENABLED] = settings.barkEnabled
            p[Keys.BARK_SERVER] = settings.barkServer
            p[Keys.BARK_KEY] = settings.barkKey
            p[Keys.TELEGRAM_ENABLED] = settings.telegramEnabled
            p[Keys.TELEGRAM_BOT_TOKEN] = settings.telegramBotToken
            p[Keys.TELEGRAM_CHAT_ID] = settings.telegramChatId
            p[Keys.WEBHOOK_ENABLED] = settings.webhookEnabled
            p[Keys.WEBHOOK_URL] = settings.webhookUrl
        }
    }

    /**
     * 单独更新是否开启前台服务（常驻通知）的设置。
     */
    suspend fun setPersistentNotificationEnabled(enabled: Boolean) {
        context.powerSettingsDataStore.edit { p ->
            p[Keys.PERSISTENT_NOTIFICATION_ENABLED] = enabled
        }
    }

    /**
     * 标记当前已完成一次电池检查，更新检查时间戳。
     */
    suspend fun markChecked(now: Long = System.currentTimeMillis()) {
        context.powerSettingsDataStore.edit { p ->
            p[Keys.LAST_CHECK_AT] = now
        }
    }

    /**
     * 更新上一次的电量状态（如果状态是充电中或已充满，则将其归一化为 NORMAL，避免逻辑混淆）。
     */
    suspend fun updateLastState(state: BatteryLevelState) {
        val normalizedState = when (state) {
            BatteryLevelState.CHARGING,
            BatteryLevelState.FULL_CHARGE -> BatteryLevelState.NORMAL
            else -> state
        }
        context.powerSettingsDataStore.edit { p ->
            p[Keys.LAST_STATE] = normalizedState.name
        }
    }

    /**
     * 更新电池运行时状态：是否已接通电源、是否已达到充满电状态。
     */
    suspend fun updateBatteryRuntimeState(plugged: Boolean, fullChargeReached: Boolean) {
        context.powerSettingsDataStore.edit { p ->
            p[Keys.LAST_PLUGGED] = plugged
            p[Keys.LAST_FULL_CHARGE_REACHED] = fullChargeReached
        }
    }

    /**
     * 当触发相应状态的通知时，更新总体的上次通知时间戳以及该特定状态的上次通知时间戳。
     * 此外，根据不同状态更新 lastState，以实现状态机流转。
     */
    suspend fun updateNotifyState(state: BatteryLevelState, now: Long = System.currentTimeMillis()) {
        context.powerSettingsDataStore.edit { p ->
            p[Keys.LAST_NOTIFY_AT] = now
            when (state) {
                BatteryLevelState.LOW -> {
                    p[Keys.LAST_STATE] = state.name
                    p[Keys.LAST_LOW_NOTIFY_AT] = now
                }
                BatteryLevelState.CRITICAL -> {
                    p[Keys.LAST_STATE] = state.name
                    p[Keys.LAST_CRITICAL_NOTIFY_AT] = now
                }
                BatteryLevelState.DANGER -> {
                    p[Keys.LAST_STATE] = state.name
                    p[Keys.LAST_DANGER_NOTIFY_AT] = now
                }
                BatteryLevelState.RECOVERED -> {
                    p[Keys.LAST_STATE] = BatteryLevelState.NORMAL.name
                    p[Keys.LAST_RECOVER_NOTIFY_AT] = now
                }
                BatteryLevelState.CHARGING -> p[Keys.LAST_CHARGING_NOTIFY_AT] = now
                BatteryLevelState.FULL_CHARGE -> p[Keys.LAST_FULL_CHARGE_NOTIFY_AT] = now
                BatteryLevelState.NORMAL -> p[Keys.LAST_STATE] = BatteryLevelState.NORMAL.name
            }
        }
    }

    /**
     * 解析字符串形式的电池状态为枚举值，解析失败时返回默认的 NORMAL。
     */
    private fun parseState(value: String): BatteryLevelState {
        return runCatching { BatteryLevelState.valueOf(value) }.getOrDefault(BatteryLevelState.NORMAL)
    }

    /**
     * DataStore 键定义对象，映射到对应的配置数据类型。
     */
    private object Keys {
        val MONITOR_ENABLED = booleanPreferencesKey("monitor_enabled")
        val PERSISTENT_NOTIFICATION_ENABLED = booleanPreferencesKey("persistent_notification_enabled")
        val CHARGING_NOTIFY_ENABLED = booleanPreferencesKey("charging_notify_enabled")
        val FULL_CHARGE_NOTIFY_ENABLED = booleanPreferencesKey("full_charge_notify_enabled")
        val FULL_CHARGE_THRESHOLD = intPreferencesKey("full_charge_threshold")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val LOW_THRESHOLD = intPreferencesKey("low_threshold")
        val CRITICAL_THRESHOLD = intPreferencesKey("critical_threshold")
        val DANGER_THRESHOLD = intPreferencesKey("danger_threshold")
        val RECOVER_THRESHOLD = intPreferencesKey("recover_threshold")
        val COOLDOWN_MINUTES = intPreferencesKey("cooldown_minutes")
        val LOCAL_NOTIFY_ENABLED = booleanPreferencesKey("local_notify_enabled")
        val BARK_ENABLED = booleanPreferencesKey("bark_enabled")
        val BARK_SERVER = stringPreferencesKey("bark_server")
        val BARK_KEY = stringPreferencesKey("bark_key")
        val TELEGRAM_ENABLED = booleanPreferencesKey("telegram_enabled")
        val TELEGRAM_BOT_TOKEN = stringPreferencesKey("telegram_bot_token")
        val TELEGRAM_CHAT_ID = stringPreferencesKey("telegram_chat_id")
        val WEBHOOK_ENABLED = booleanPreferencesKey("webhook_enabled")
        val WEBHOOK_URL = stringPreferencesKey("webhook_url")
        val LAST_STATE = stringPreferencesKey("last_state")
        val LAST_CHECK_AT = longPreferencesKey("last_check_at")
        val LAST_NOTIFY_AT = longPreferencesKey("last_notify_at")
        val LAST_LOW_NOTIFY_AT = longPreferencesKey("last_low_notify_at")
        val LAST_CRITICAL_NOTIFY_AT = longPreferencesKey("last_critical_notify_at")
        val LAST_DANGER_NOTIFY_AT = longPreferencesKey("last_danger_notify_at")
        val LAST_RECOVER_NOTIFY_AT = longPreferencesKey("last_recover_notify_at")
        val LAST_CHARGING_NOTIFY_AT = longPreferencesKey("last_charging_notify_at")
        val LAST_FULL_CHARGE_NOTIFY_AT = longPreferencesKey("last_full_charge_notify_at")
        val LAST_PLUGGED = booleanPreferencesKey("last_plugged")
        val LAST_FULL_CHARGE_REACHED = booleanPreferencesKey("last_full_charge_reached")
    }
}
