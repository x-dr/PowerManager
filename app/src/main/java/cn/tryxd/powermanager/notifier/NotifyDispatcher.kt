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

class NotifyDispatcher(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun send(event: NotifyEvent, settings: AppSettings): List<String> {
        val results = mutableListOf<String>()
        if (settings.localNotifyEnabled) {
            runCatching { sendLocal(event) }
                .onSuccess { results += "local: ok" }
                .onFailure { results += "local: ${it.message ?: "failed"}" }
        }
        if (settings.barkEnabled && settings.barkKey.isNotBlank()) {
            runCatching { sendBark(event, settings) }
                .onSuccess { results += "bark: ok" }
                .onFailure { results += "bark: ${it.message ?: "failed"}" }
        }
        if (settings.telegramEnabled && settings.telegramBotToken.isNotBlank() && settings.telegramChatId.isNotBlank()) {
            runCatching { sendTelegram(event, settings) }
                .onSuccess { results += "telegram: ok" }
                .onFailure { results += "telegram: ${it.message ?: "failed"}" }
        }
        if (settings.webhookEnabled && settings.webhookUrl.isNotBlank()) {
            runCatching { sendWebhook(event, settings) }
                .onSuccess { results += "webhook: ok" }
                .onFailure { results += "webhook: ${it.message ?: "failed"}" }
        }
        return results
    }

    private fun sendLocal(event: NotifyEvent) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "battery_alerts"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Battery Alerts", NotificationManager.IMPORTANCE_HIGH)
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }

        val notification = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(event.title)
            .setContentText(event.body.lines().firstOrNull().orEmpty())
            .setStyle(android.app.Notification.BigTextStyle().bigText(event.body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(event.state.ordinal + 2000, notification)
    }

    private suspend fun sendBark(event: NotifyEvent, settings: AppSettings) = withContext(Dispatchers.IO) {
        val server = settings.barkServer.trim().trimEnd('/').ifBlank { "https://api.day.app" }
        val url = "$server/${Uri.encode(settings.barkKey)}/${Uri.encode(event.title)}/${Uri.encode(event.body)}"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
        }
    }

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
