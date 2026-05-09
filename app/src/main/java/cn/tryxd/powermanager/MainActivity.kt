package cn.tryxd.powermanager

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import cn.tryxd.powermanager.service.BatteryForegroundService
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

    private lateinit var batteryPercentView: TextView
    private lateinit var batterySubView: TextView
    private lateinit var statusView: TextView

    private lateinit var monitorEnabled: CheckBox
    private lateinit var persistentNotificationEnabled: CheckBox
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
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(color("#F5F7FB"))
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(24))
        }
        scrollView.addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(headerView())
        root.addView(statusCard())

        monitorEnabled = checkBox("启用后台监控", "低频后台检查，默认 15 分钟一次")
        persistentNotificationEnabled = checkBox("启用常驻通知栏", "通知栏实时显示电量，可降低系统清理概率")
        localNotifyEnabled = checkBox("启用本机通知", "在手机本机弹出低电量提醒")

        deviceName = editText("Android Device", InputType.TYPE_CLASS_TEXT)
        lowThreshold = editText("20", InputType.TYPE_CLASS_NUMBER)
        criticalThreshold = editText("10", InputType.TYPE_CLASS_NUMBER)
        dangerThreshold = editText("5", InputType.TYPE_CLASS_NUMBER)
        recoverThreshold = editText("30", InputType.TYPE_CLASS_NUMBER)
        cooldownMinutes = editText("60", InputType.TYPE_CLASS_NUMBER)

        root.addView(card("监控设置", "配置电量阈值、常驻通知和设备名称") {
            addView(monitorEnabled)
            addView(persistentNotificationEnabled)
            addView(localNotifyEnabled)
            addDivider(this)
            addField(this, "设备名称", "推送和常驻通知里显示的设备名", deviceName)
            addTwoColumns(
                leftLabel = "低电量",
                leftDesc = "触发普通提醒",
                leftField = lowThreshold,
                rightLabel = "严重电量",
                rightDesc = "触发严重提醒",
                rightField = criticalThreshold
            )
            addTwoColumns(
                leftLabel = "危险电量",
                leftDesc = "即将关机提醒",
                leftField = dangerThreshold,
                rightLabel = "恢复阈值",
                rightDesc = "高于此值解除低电量",
                rightField = recoverThreshold
            )
            addField(this, "冷却时间 / 分钟", "同一种通知在冷却时间内不会重复发送", cooldownMinutes)
        })

        barkEnabled = checkBox("启用 Bark", "适合推送到 iPhone / iPad")
        barkServer = editText("https://api.day.app", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        barkKey = editText("Bark Key", InputType.TYPE_CLASS_TEXT)

        telegramEnabled = checkBox("启用 Telegram Bot", "通过机器人发送到指定 Chat ID")
        telegramBotToken = editText("Bot Token", InputType.TYPE_CLASS_TEXT)
        telegramChatId = editText("Chat ID", InputType.TYPE_CLASS_TEXT)

        webhookEnabled = checkBox("启用 Webhook", "向自定义 HTTP/HTTPS 接口 POST JSON")
        webhookUrl = editText("https://example.com/notify", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)

        root.addView(card("通知渠道", "可以同时开启多个渠道，测试通知会逐个发送") {
            addView(barkEnabled)
            addField(this, "Bark 服务地址", null, barkServer)
            addField(this, "Bark Key", null, barkKey)
            addDivider(this)
            addView(telegramEnabled)
            addField(this, "Telegram Bot Token", null, telegramBotToken)
            addField(this, "Telegram Chat ID", null, telegramChatId)
            addDivider(this)
            addView(webhookEnabled)
            addField(this, "Webhook URL", null, webhookUrl)
        })

        root.addView(card("操作", "保存配置后会立即检查一次，并注册周期任务") {
            addView(actionButton("保存设置并启动监控", "#2563EB", "#FFFFFF") {
                saveSettings(startWorker = true)
            })
            addView(actionButton("立即检测一次", "#EEF2FF", "#3730A3") {
                BatteryCheckWorker.checkNow(applicationContext)
                toast("已提交立即检测任务")
                refreshStatus()
            })
            addView(actionButton("发送测试通知", "#ECFDF5", "#047857") {
                saveSettings(startWorker = false) { sendTestNotification() }
            })
            addView(actionButton("刷新状态", "#F1F5F9", "#334155") {
                refreshStatus()
            })
            addView(actionButton("停止常驻通知栏", "#FFF7ED", "#C2410C") {
                stopPersistentNotification()
            })
            addView(actionButton("停止后台监控", "#FEF2F2", "#B91C1C") {
                BatteryCheckWorker.stop(applicationContext)
                toast("已停止后台监控")
            })
        })

        root.addView(note())
        return scrollView
    }

    private fun loadSettings() {
        scope.launch {
            val settings = repository.getSettings()
            monitorEnabled.isChecked = settings.monitorEnabled
            persistentNotificationEnabled.isChecked = settings.persistentNotificationEnabled
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
                persistentNotificationEnabled = persistentNotificationEnabled.isChecked,
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
            if (settings.persistentNotificationEnabled) {
                BatteryForegroundService.start(applicationContext)
            } else {
                BatteryForegroundService.stop(applicationContext)
            }
            toast("设置已保存")
            refreshStatus()
            afterSaved?.invoke()
        }
    }

    private fun stopPersistentNotification() {
        scope.launch {
            repository.setPersistentNotificationEnabled(false)
            persistentNotificationEnabled.isChecked = false
            BatteryForegroundService.stop(applicationContext)
            toast("已停止常驻通知栏")
            refreshStatus()
        }
    }

    private fun refreshStatus() {
        scope.launch {
            val settings = repository.getSettings()
            val snapshot = BatteryReader.read(applicationContext)
            if (snapshot == null) {
                batteryPercentView.text = "--%"
                batterySubView.text = "电量读取失败"
                batteryPercentView.setTextColor(color("#64748B"))
            } else {
                val charging = if (snapshot.charging) "充电中" else "未充电"
                batteryPercentView.text = "${snapshot.percent}%"
                batterySubView.text = "$charging · ${settings.deviceName}"
                batteryPercentView.setTextColor(batteryColor(snapshot.percent))
            }
            statusView.text = buildString {
                appendLine("后台监控：${if (settings.monitorEnabled) "已启用" else "未启用"}")
                appendLine("常驻通知：${if (settings.persistentNotificationEnabled) "已启用" else "未启用"}")
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

    private fun headerView(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(12))
            addView(TextView(context).apply {
                text = "PowerManager"
                textSize = 30f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(color("#0F172A"))
            })
            addView(TextView(context).apply {
                text = "安卓电量监控与多通道推送"
                textSize = 14f
                setTextColor(color("#64748B"))
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun statusCard(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded("#FFFFFF", 22, "#E5EAF1")
            elevation = dp(2).toFloat()
            setPadding(dp(18), dp(18), dp(18), dp(18))
            layoutParams = cardParams()

            addView(TextView(context).apply {
                text = "当前设备状态"
                textSize = 14f
                setTextColor(color("#64748B"))
            })
            batteryPercentView = TextView(context).apply {
                text = "--%"
                textSize = 48f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(color("#2563EB"))
                setPadding(0, dp(4), 0, 0)
            }
            addView(batteryPercentView)
            batterySubView = TextView(context).apply {
                text = "正在读取电量..."
                textSize = 15f
                setTextColor(color("#334155"))
                setPadding(0, 0, 0, dp(10))
            }
            addView(batterySubView)
            statusView = TextView(context).apply {
                textSize = 13f
                setTextColor(color("#64748B"))
                setLineSpacing(dp(2).toFloat(), 1.0f)
                background = rounded("#F8FAFC", 14, "#E5EAF1")
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            addView(statusView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun card(title: String, subtitle: String, block: LinearLayout.() -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded("#FFFFFF", 22, "#E5EAF1")
            elevation = dp(1).toFloat()
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = cardParams()

            addView(TextView(context).apply {
                text = title
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(color("#0F172A"))
            })
            addView(TextView(context).apply {
                text = subtitle
                textSize = 13f
                setTextColor(color("#64748B"))
                setPadding(0, dp(3), 0, dp(12))
            })
            block()
        }
    }

    private fun checkBox(text: String, description: String): CheckBox {
        return CheckBox(this).apply {
            this.text = "$text\n$description"
            textSize = 15f
            setTextColor(color("#1E293B"))
            setPadding(0, dp(6), 0, dp(6))
        }
    }

    private fun editText(hint: String, inputTypeValue: Int): EditText {
        return EditText(this).apply {
            this.hint = hint
            inputType = inputTypeValue
            textSize = 15f
            setSingleLine(true)
            setTextColor(color("#0F172A"))
            setHintTextColor(color("#94A3B8"))
            background = rounded("#F8FAFC", 14, "#CBD5E1")
            setPadding(dp(12), 0, dp(12), 0)
            minHeight = dp(48)
        }
    }

    private fun addField(root: LinearLayout, label: String, desc: String?, field: EditText) {
        root.addView(TextView(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color("#334155"))
            setPadding(0, dp(8), 0, dp(2))
        })
        if (!desc.isNullOrBlank()) {
            root.addView(TextView(this).apply {
                text = desc
                textSize = 12f
                setTextColor(color("#94A3B8"))
                setPadding(0, 0, 0, dp(6))
            })
        }
        root.addView(field, fieldParams())
    }

    private fun LinearLayout.addTwoColumns(
        leftLabel: String,
        leftDesc: String,
        leftField: EditText,
        rightLabel: String,
        rightDesc: String,
        rightField: EditText
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        row.addView(column(leftLabel, leftDesc, leftField), columnParams(isLeft = true))
        row.addView(column(rightLabel, rightDesc, rightField), columnParams(isLeft = false))
        addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun column(label: String, desc: String, field: EditText): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = label
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(color("#334155"))
                setPadding(0, dp(8), 0, dp(2))
            })
            addView(TextView(context).apply {
                text = desc
                textSize = 12f
                setTextColor(color("#94A3B8"))
                setPadding(0, 0, 0, dp(6))
            })
            addView(field, fieldParams())
        }
    }

    private fun addDivider(root: LinearLayout) {
        root.addView(View(this).apply {
            setBackgroundColor(color("#E5EAF1"))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            setMargins(0, dp(12), 0, dp(12))
        })
    }

    private fun actionButton(text: String, bgColor: String, textColor: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 15f
            isAllCaps = false
            gravity = Gravity.CENTER
            setTextColor(color(textColor))
            background = rounded(bgColor, 16, bgColor)
            setPadding(dp(8), 0, dp(8), 0)
            minHeight = dp(48)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
                setMargins(0, dp(6), 0, dp(6))
            }
        }
    }

    private fun note(): TextView {
        return TextView(this).apply {
            text = "提示：常驻通知栏需要通知权限；WorkManager 最短周期为 15 分钟。国产 ROM 建议手动允许自启动、后台运行、忽略电池优化和通知权限。"
            textSize = 13f
            setTextColor(color("#64748B"))
            background = rounded("#EEF2FF", 18, "#C7D2FE")
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(4), 0, 0)
            }
        }
    }

    private fun cardParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(8), 0, dp(12))
        }
    }

    private fun fieldParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            setMargins(0, 0, 0, dp(8))
        }
    }

    private fun columnParams(isLeft: Boolean): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (isLeft) setMargins(0, 0, dp(6), 0) else setMargins(dp(6), 0, 0, 0)
        }
    }

    private fun rounded(fill: String, radiusDp: Int, stroke: String): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color(fill))
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), color(stroke))
        }
    }

    private fun batteryColor(percent: Int): Int {
        return when {
            percent <= 5 -> color("#DC2626")
            percent <= 10 -> color("#EA580C")
            percent <= 20 -> color("#D97706")
            else -> color("#2563EB")
        }
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

    private fun color(hex: String): Int = Color.parseColor(hex)

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}
