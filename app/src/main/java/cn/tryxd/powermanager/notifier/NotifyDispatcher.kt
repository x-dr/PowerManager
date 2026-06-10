package cn.tryxd.powermanager.notifier

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import cn.tryxd.powermanager.MainActivity
import cn.tryxd.powermanager.model.AppSettings
import cn.tryxd.powermanager.model.NotifyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 通知分发器类，用于向不同通道（本地系统通知、Bark、Telegram、Webhook）分发电池事件提醒。
 */
class NotifyDispatcher(private val context: Context) {
    // 实例化 OkHttpClient，配置连接超时和读取超时
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /**
     * 发送通知。根据配置项中启用的通道，并发/顺序调用各个通道进行通知。
     * 
     * @param event 通知事件数据
     * @param settings 当前配置信息
     * @return 返回各个通道的发送结果列表（如 "local: ok", "bark: failed" 等）
     */
    suspend fun send(event: NotifyEvent, settings: AppSettings): List<String> {
        val results = mutableListOf<String>()
        
        // 1. 本地系统通知
        if (settings.localNotifyEnabled) {
            runCatching { sendLocal(event) }
                .onSuccess { results += "local: ok" }
                .onFailure { results += "local: ${it.message ?: "failed"}" }
        }
        
        // 2. Bark 推送通知
        if (settings.barkEnabled && settings.barkKey.isNotBlank()) {
            runCatching { sendBark(event, settings) }
                .onSuccess { results += "bark: ok" }
                .onFailure { results += "bark: ${it.message ?: "failed"}" }
        }
        
        // 3. Telegram Bot 推送通知
        if (settings.telegramEnabled && settings.telegramBotToken.isNotBlank() && settings.telegramChatId.isNotBlank()) {
            runCatching { sendTelegram(event, settings) }
                .onSuccess { results += "telegram: ok" }
                .onFailure { results += "telegram: ${it.message ?: "failed"}" }
        }
        
        // 4. Webhook 自定义回调通知
        if (settings.webhookEnabled && settings.webhookUrl.isNotBlank()) {
            runCatching { sendWebhook(event, settings) }
                .onSuccess { results += "webhook: ok" }
                .onFailure { results += "webhook: ${it.message ?: "failed"}" }
        }
        return results
    }

    /**
     * 发送本地系统通知通知栏消息。
     */
    private fun sendLocal(event: NotifyEvent) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "battery_alerts"
        
        // Android 8.0 及以上系统需要注册通知渠道
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Battery Alerts", NotificationManager.IMPORTANCE_HIGH)
            manager.createNotificationChannel(channel)
        }

        // 点击通知时打开应用主界面的 PendingIntent
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 兼容处理不同 Android 版本的 Notification.Builder
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }

        // 构造通知，并支持大文本折叠显示样式
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(event.title)
            .setContentText(event.body.lines().firstOrNull().orEmpty())
            .setStyle(android.app.Notification.BigTextStyle().bigText(event.body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true) // 点击后自动清除
            .build()

        // 发送通知，使用不同的通知 ID 避免同状态消息被覆盖
        manager.notify(event.state.ordinal + 2000, notification)
    }

    /**
     * 发送 Bark 推送。
     */
    private suspend fun sendBark(event: NotifyEvent, settings: AppSettings) = withContext(Dispatchers.IO) {
        val server = settings.barkServer.trim().trimEnd('/').ifBlank { "https://api.day.app" }
        // Bark 的接口格式为: /:key/:title/:body
        val url = "$server/${Uri.encode(settings.barkKey)}/${Uri.encode(event.title)}/${Uri.encode(event.body)}"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
        }
    }

    /**
     * 发送 Telegram Bot 消息。
     */
    private suspend fun sendTelegram(event: NotifyEvent, settings: AppSettings) = withContext(Dispatchers.IO) {
        val text = "${event.title}\n\n${event.body}"
        val json = "{\"chat_id\":\"${jsonEscape(settings.telegramChatId)}\",\"text\":\"${jsonEscape(text)}\"}"
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val url = "https://api.telegram.org/bot${settings.telegramBotToken}/sendMessage"
        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
        }
    }

    /**
     * 向自定义的 Webhook 发送 POST 请求。
     */
    private suspend fun sendWebhook(event: NotifyEvent, settings: AppSettings) = withContext(Dispatchers.IO) {
        val json = """
            {
              "event": "${event.state.name.lowercase()}",
              "title": "${jsonEscape(event.title)}",
              "body": "${jsonEscape(event.body)}",
              "device": "${jsonEscape(settings.deviceName)}",
              "timestamp": ${System.currentTimeMillis()}
            }
        """.trimIndent()
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(settings.webhookUrl).post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
        }
    }

    /**
     * 简单的 JSON 字符转义工具，防止特殊字符或换行导致 JSON 格式损坏。
     */
    private fun jsonEscape(value: String): String {
        return buildString {
            value.forEach { c ->
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
        }
    }
}
