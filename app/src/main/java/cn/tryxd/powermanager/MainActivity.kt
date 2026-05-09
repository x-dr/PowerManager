package cn.tryxd.powermanager

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import cn.tryxd.powermanager.data.SettingsRepository
import cn.tryxd.powermanager.model.BatteryLevelState
import cn.tryxd.powermanager.monitor.BatteryReader
import cn.tryxd.powermanager.notifier.NotifyDispatcher
import cn.tryxd.powermanager.rule.BatteryRuleEngine
import cn.tryxd.powermanager.worker.BatteryCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: SettingsRepository
    private lateinit var statusView: TextView

    private lateinit var monitorEnabled: CheckBox
    private lateinit var localNotifyEnabled: CheckBox
    private lateinit var barkEnabled: CheckBox
    private lateinit var telegramEnabled: CheckBox
    private lateinit var webhookEnabled: CheckBox

    private lateinit var deviceName: EditText
    private lateinit var lowThreshold: EditText
    private lateinit var criticalThreshold: EditText
    private lateinit var dangerThreshold: EditText
    private lateinit var recoverThreshold: EditText
    private lateinit var cooldownMinutes: EditText
    private lateinit var barkServer: EditText
    private lateinit var barkKey: EditText
    private lateinit var telegramBotToken: EditText
    private lateinit var telegramChatId: EditText
    private lateinit var webhookUrl: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SettingsRepository(applicationContext)
        requestNotificationPermission()
        setContentView(buildContentView())
        loadSettings()
        refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun buildContentView(): View {
        val scrollView = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
        }
        scrollView.addView(root)

        root.addView(title("PowerManager"))
        statusView = TextView(this).apply {
            textSize = 15f
            setPadding(0, 12, 0, 20)
        }
        root.addView(statusView)

        monitorEnabled = checkBox("启用后台监控")
        localNotifyEnabled = checkBox("启用本机通知")
        root.addView(monitorEnabled)
        root.addView(localNotifyEnabled)

        deviceName = editText("设备名称", InputType.TYPE_CLASS_TEXT)
        lowThreshold = editText("低电量阈值，例如 20", InputType.TYPE_CLASS_NUMBER)
        criticalThreshold = editText("严重电量阈值，例如 10", InputType.TYPE_CLASS_NUMBER)
        dangerThreshold = editText("危险电量阈值，例如 5", InputType.TYPE_CLASS_NUMBER)
        recoverThreshold = editText("恢复阈值，例如 30", InputType.TYPE_CLASS_NUMBER)
        cooldownMinutes = editText("通知冷却分钟，例如 60", InputType.TYPE_CLASS_NUMBER)

        addSection(root, "监控设置")
        listOf(deviceName, lowThreshold, criticalThreshold, dangerThreshold, recoverThreshold, cooldownMinutes).forEach {
            root.addView(it)
        }

        addSection(root, "Bark")
        barkEnabled = checkBox("启用 Bark")
        barkServer = editText("Bark 服务地址，默认 https://api.day.app", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        barkKey = editText("Bark Key", InputType.TYPE_CLASS_TEXT)
        root.addView(barkEnabled)
        root.addView(barkServer)
        root.addView(barkKey)

        addSection(root, "Telegram Bot")
        telegramEnabled = checkBox("启用 Telegram")
        telegramBotToken = editText("Bot Token", InputType.TYPE_CLASS_TEXT)
        telegramChatId = editText("Chat ID", InputType.TYPE_CLASS_TEXT)
        root.addView(telegramEnabled)
        root.addView(telegramBotToken)
        root.addView(telegramChatId)

        addSection(root, "Webhook")
        webhookEnabled = checkBox("启用 Webhook")
        webhookUrl = editText("Webhook URL", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        root.addView(webhookEnabled)
        root.addView(webhookUrl)

        val saveButton = button("保存设置并启动监控") {
            saveSettings(startWorker = true)
        }
        val checkButton = button("立即检测一次") {
            BatteryCheckWorker.checkNow(applicationContext)
            toast("已提交立即检测任务")
            refreshStatus()
        }
        val testButton = button("发送测试通知") {
            saveSettings(startWorker = false) { sendTestNotification() }
        }
        val stopButton = button("停止后台监控") {
            BatteryCheckWorker.stop(applicationContext)
            toast("已停止后台监控")
        }
        val refreshButton = button("刷新状态") {
            refreshStatus()
        }

        listOf(saveButton, checkButton, testButton, stopButton, refreshButton).forEach {
            root.addView(it)
        }
        root.addView(note())
        return scrollView
    }

    private fun loadSettings() {
        scope.launch {
            val settings = repository.getSettings()
            monitorEnabled.isChecked = settings.monitorEnabled
            localNotifyEnabled.isChecked = settings.localNotifyEnabled
            barkEnabled.isChecked = settings.barkEnabled
            telegramEnabled.isChecked = settings.telegramEnabled
            webhookEnabled.isChecked = settings.webhookEnabled

            deviceName.setText(settings.deviceName)
            lowThreshold.setText(settings.lowThreshold.toString())
            criticalThreshold.setText(settings.criticalThreshold.toString())
            dangerThreshold.setText(settings.dangerThreshold.toString())
            recoverThreshold.setText(settings.recoverThreshold.toString())
            cooldownMinutes.setText(settings.cooldownMinutes.toString())
            barkServer.setText(settings.barkServer)
            barkKey.setText(settings.barkKey)
            telegramBotToken.setText(settings.telegramBotToken)
            telegramChatId.setText(settings.telegramChatId)
            webhookUrl.setText(settings.webhookUrl)
        }
    }

    private fun saveSettings(startWorker: Boolean, afterSaved: (() -> Unit)? = null) {
        scope.launch {
            val old = repository.getSettings()
            val settings = old.copy(
                monitorEnabled = monitorEnabled.isChecked,
                localNotifyEnabled = localNotifyEnabled.isChecked,
                deviceName = deviceName.text.toString().trim().ifBlank { "Android Device" },
                lowThreshold = lowThreshold.intValue(20).coerceIn(1, 100),
                criticalThreshold = criticalThreshold.intValue(10).coerceIn(1, 100),
                dangerThreshold = dangerThreshold.intValue(5).coerceIn(1, 100),
                recoverThreshold = recoverThreshold.intValue(30).coerceIn(1, 100),
                cooldownMinutes = cooldownMinutes.intValue(60).coerceAtLeast(1),
                barkEnabled = barkEnabled.isChecked,
                barkServer = barkServer.text.toString().trim().ifBlank { "https://api.day.app" },
                barkKey = barkKey.text.toString().trim(),
                telegramEnabled = telegramEnabled.isChecked,
                telegramBotToken = telegramBotToken.text.toString().trim(),
                telegramChatId = telegramChatId.text.toString().trim(),
                webhookEnabled = webhookEnabled.isChecked,
                webhookUrl = webhookUrl.text.toString().trim()
            )
            repository.saveSettings(settings)
            if (startWorker && settings.monitorEnabled) {
                BatteryCheckWorker.start(applicationContext)
                BatteryCheckWorker.checkNow(applicationContext)
            }
            toast("设置已保存")
            refreshStatus()
            afterSaved?.invoke()
        }
    }

    private fun refreshStatus() {
        scope.launch {
            val settings = repository.getSettings()
            val snapshot = BatteryReader.read(applicationContext)
            val batteryText = if (snapshot == null) {
                "电量：读取失败"
            } else {
                val charging = if (snapshot.charging) "充电中" else "未充电"
                "电量：${snapshot.percent}% / $charging"
            }
            statusView.text = buildString {
                appendLine(batteryText)
                appendLine("监控：${if (settings.monitorEnabled) "已启用" else "未启用"}")
                appendLine("上次检查：${formatTime(settings.lastCheckAt)}")
                appendLine("上次通知：${formatTime(settings.lastNotifyAt)}")
                appendLine("上次状态：${settings.lastState}")
            }
        }
    }

    private fun sendTestNotification() {
        scope.launch {
            val settings = repository.getSettings()
            val snapshot = BatteryReader.read(applicationContext)
                ?: return@launch toast("电量读取失败，无法测试")
            val event = BatteryRuleEngine.buildEvent(BatteryLevelState.LOW, snapshot, settings)
            val results = withContext(Dispatchers.IO) {
                NotifyDispatcher(applicationContext).send(event, settings)
            }
            toast(results.joinToString("\n").ifBlank { "没有启用任何通知渠道" })
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 24f
        setPadding(0, 0, 0, 8)
    }

    private fun addSection(root: LinearLayout, text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 18f
            setPadding(0, 28, 0, 8)
        })
    }

    private fun checkBox(text: String): CheckBox = CheckBox(this).apply {
        this.text = text
        textSize = 16f
    }

    private fun editText(hint: String, inputTypeValue: Int): EditText = EditText(this).apply {
        this.hint = hint
        inputType = inputTypeValue
        setSingleLine(true)
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun note(): TextView = TextView(this).apply {
        text = "提示：WorkManager 最短周期为 15 分钟。国产 ROM 建议手动允许自启动、后台运行、忽略电池优化。"
        textSize = 13f
        setPadding(0, 24, 0, 0)
    }

    private fun EditText.intValue(defaultValue: Int): Int {
        return text.toString().trim().toIntOrNull() ?: defaultValue
    }

    private fun formatTime(value: Long): String {
        if (value <= 0L) return "从未"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
