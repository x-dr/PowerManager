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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用主 Activity 界面类。
 * 负责渲染配置表单及状态面板，管理用户输入的各项监控参数及通知设置。
 */
class MainActivity : Activity() {
    // 定义作用于 UI 的协程作用域（绑定主线程调度器 Dispatchers.Main）
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var uiRefreshJob: Job? = null
    private lateinit var repository: SettingsRepository

    // 状态卡片组件定义
    private lateinit var batteryPercentView: TextView
    private lateinit var batterySubView: TextView
    private lateinit var statusView: TextView

    // 各项配置的复选框 CheckBox
    private lateinit var monitorEnabled: CheckBox
    private lateinit var persistentNotificationEnabled: CheckBox
    private lateinit var chargingNotifyEnabled: CheckBox
    private lateinit var fullChargeNotifyEnabled: CheckBox
    private lateinit var localNotifyEnabled: CheckBox
    private lateinit var barkEnabled: CheckBox
    private lateinit var telegramEnabled: CheckBox
    private lateinit var webhookEnabled: CheckBox

    // 各项配置的输入框 EditText
    private lateinit var deviceName: EditText
    private lateinit var lowThreshold: EditText
    private lateinit var criticalThreshold: EditText
    private lateinit var dangerThreshold: EditText
    private lateinit var recoverThreshold: EditText
    private lateinit var fullChargeThreshold: EditText
    private lateinit var cooldownMinutes: EditText
    private lateinit var barkServer: EditText
    private lateinit var barkKey: EditText
    private lateinit var telegramBotToken: EditText
    private lateinit var telegramChatId: EditText
    private lateinit var webhookUrl: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SettingsRepository(applicationContext)
        // 动态申请 Android 13+ 的通知权限
        requestNotificationPermission()
        // 编程式构建并填充主页面布局
        setContentView(buildContentView())
        // 异步加载已持久化的配置
        loadSettings()
        // 刷新当前的电池及监控服务状态
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        // 页面重新可见时启动 30 秒间隔的 UI 自动刷新机制
        startUiAutoRefresh()
    }

    override fun onPause() {
        // 页面不可见时停止自动刷新，避免不必要的耗电
        stopUiAutoRefresh()
        super.onPause()
    }

    override fun onDestroy() {
        stopUiAutoRefresh()
        super.onDestroy()
        // 销毁 Activity 时取消协程作用域下的所有子任务，防止协程内存泄漏
        scope.cancel()
    }

    /**
     * 启动定时刷新回路，每 30 秒执行一次 [refreshStatus]。
     */
    private fun startUiAutoRefresh() {
        if (uiRefreshJob?.isActive == true) return
        uiRefreshJob = scope.launch {
            while (isActive) {
                refreshStatus()
                delay(30_000L)
            }
        }
    }

    /**
     * 停止定时刷新回路。
     */
    private fun stopUiAutoRefresh() {
        uiRefreshJob?.cancel()
        uiRefreshJob = null
    }

    /**
     * 编程式创建主视图容器和表单元素。
     */
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

        // 添加头部标题和状态卡片
        root.addView(headerView())
        root.addView(statusCard())

        // 构造主要的 CheckBox 复选框
        monitorEnabled = checkBox("启用后台监控", "低频后台检查，默认 15 分钟一次")
        persistentNotificationEnabled = checkBox("启用常驻通知栏", "通知栏实时显示电量，可降低系统清理概率")
        chargingNotifyEnabled = checkBox("启用开始充电提醒", "插上电源后立即发送提醒")
        fullChargeNotifyEnabled = checkBox("启用充满电提醒", "达到满电阈值后发送提醒")
        localNotifyEnabled = checkBox("启用本机通知", "在手机本机弹出低电量、充电和满电提醒")

        // 构造主要的 EditText 输入框
        deviceName = editText("Android Device", InputType.TYPE_CLASS_TEXT)
        lowThreshold = editText("20", InputType.TYPE_CLASS_NUMBER)
        criticalThreshold = editText("10", InputType.TYPE_CLASS_NUMBER)
        dangerThreshold = editText("5", InputType.TYPE_CLASS_NUMBER)
        recoverThreshold = editText("30", InputType.TYPE_CLASS_NUMBER)
        fullChargeThreshold = editText("100", InputType.TYPE_CLASS_NUMBER)
        cooldownMinutes = editText("60", InputType.TYPE_CLASS_NUMBER)

        // 添加“监控设置”卡片组
        root.addView(card("监控设置", "配置电量阈值、充电提醒、常驻通知和设备名称") {
            addView(monitorEnabled)
            addView(persistentNotificationEnabled)
            addView(chargingNotifyEnabled)
            addView(fullChargeNotifyEnabled)
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
            addField(this, "满电阈值 / %", "达到此电量并且仍接入电源时发送满电提醒，范围 50–100", fullChargeThreshold)
            addField(this, "冷却时间 / 分钟", "同一种通知在冷却时间内不会重复发送", cooldownMinutes)
        })

        // 构造 Bark、Telegram、Webhook 等第三方通知渠道组件
        barkEnabled = checkBox("启用 Bark", "适合推送到 iPhone / iPad")
        barkServer = editText("https://api.day.app", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        barkKey = editText("Bark Key", InputType.TYPE_CLASS_TEXT)

        telegramEnabled = checkBox("启用 Telegram Bot", "通过机器人发送到指定 Chat ID")
        telegramBotToken = editText("Bot Token", InputType.TYPE_CLASS_TEXT)
        telegramChatId = editText("Chat ID", InputType.TYPE_CLASS_TEXT)

        webhookEnabled = checkBox("启用 Webhook", "向自定义 HTTP/HTTPS 接口 POST JSON")
        webhookUrl = editText("https://example.com/notify", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)

        // 添加“通知渠道”卡片组
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

        // 添加“操作”卡片组，提供保存、单次测试、刷新和注销等入口
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

    /**
     * 从仓库异步读取配置项，并回显至各复选框及文本输入框中。
     */
    private fun loadSettings() {
        scope.launch {
            val settings = repository.getSettings()
            monitorEnabled.isChecked = settings.monitorEnabled
            persistentNotificationEnabled.isChecked = settings.persistentNotificationEnabled
            chargingNotifyEnabled.isChecked = settings.chargingNotifyEnabled
            fullChargeNotifyEnabled.isChecked = settings.fullChargeNotifyEnabled
            localNotifyEnabled.isChecked = settings.localNotifyEnabled
            barkEnabled.isChecked = settings.barkEnabled
            telegramEnabled.isChecked = settings.telegramEnabled
            webhookEnabled.isChecked = settings.webhookEnabled

            deviceName.setText(settings.deviceName)
            lowThreshold.setText(settings.lowThreshold.toString())
            criticalThreshold.setText(settings.criticalThreshold.toString())
            dangerThreshold.setText(settings.dangerThreshold.toString())
            recoverThreshold.setText(settings.recoverThreshold.toString())
            fullChargeThreshold.setText(settings.fullChargeThreshold.toString())
            cooldownMinutes.setText(settings.cooldownMinutes.toString())
            barkServer.setText(settings.barkServer)
            barkKey.setText(settings.barkKey)
            telegramBotToken.setText(settings.telegramBotToken)
            telegramChatId.setText(settings.telegramChatId)
            webhookUrl.setText(settings.webhookUrl)
        }
    }

    /**
     * 将输入框/复选框的最新配置收集，并通过仓库更新持久化，同时调度/停用后台 Worker 以及常驻前台服务。
     * 
     * @param startWorker 是否要在保存后立即启动电量轮询 WorkManager 任务
     * @param afterSaved 保存完成后的可选回调函数
     */
    private fun saveSettings(startWorker: Boolean, afterSaved: (() -> Unit)? = null) {
        scope.launch {
            val old = repository.getSettings()
            val settings = old.copy(
                monitorEnabled = monitorEnabled.isChecked,
                persistentNotificationEnabled = persistentNotificationEnabled.isChecked,
                chargingNotifyEnabled = chargingNotifyEnabled.isChecked,
                fullChargeNotifyEnabled = fullChargeNotifyEnabled.isChecked,
                fullChargeThreshold = fullChargeThreshold.intValue(100).coerceIn(50, 100),
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
            
            // 如果启用了后台监控且要求启动监控，则注册 WorkManager
            if (startWorker && settings.monitorEnabled) {
                BatteryCheckWorker.start(applicationContext)
                BatteryCheckWorker.checkNow(applicationContext)
            }
            
            // 根据配置项决定启动还是停止常驻前台监控服务
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

    /**
     * 停止前台常驻服务，并重置持久化和 UI 上的 persistentNotificationEnabled 项。
     */
    private fun stopPersistentNotification() {
        scope.launch {
            repository.setPersistentNotificationEnabled(false)
            persistentNotificationEnabled.isChecked = false
            BatteryForegroundService.stop(applicationContext)
            toast("已停止常驻通知栏")
            refreshStatus()
        }
    }

    /**
     * 刷新当前的电池电量、工作状态，以及后台各周期任务的历史运行信息。
     */
    private fun refreshStatus() {
        scope.launch {
            val settings = repository.getSettings()
            val snapshot = BatteryReader.read(applicationContext)
            val uiRefreshAt = System.currentTimeMillis()
            
            // 刷新电池百分比和大面板状态
            if (snapshot == null) {
                batteryPercentView.text = "--%"
                batterySubView.text = "电量读取失败 · ${formatTime(uiRefreshAt)}"
                batteryPercentView.setTextColor(color("#64748B"))
            } else {
                val charging = when {
                    snapshot.charging -> "充电中"
                    snapshot.plugged -> "已接入电源"
                    else -> "未充电"
                }
                batteryPercentView.text = "${snapshot.percent}%"
                batterySubView.text = "$charging · ${settings.deviceName} · ${formatTime(uiRefreshAt)}"
                batteryPercentView.setTextColor(batteryColor(snapshot.percent))
            }
            
            // 刷新辅助检查元信息明细列表
            statusView.text = buildString {
                appendLine("界面刷新：${formatTime(uiRefreshAt)}")
                appendLine("后台监控：${if (settings.monitorEnabled) "已启用" else "未启用"}")
                appendLine("常驻通知：${if (settings.persistentNotificationEnabled) "已启用" else "未启用"}")
                appendLine("充电提醒：${if (settings.chargingNotifyEnabled) "已启用" else "未启用"}")
                appendLine("满电提醒：${if (settings.fullChargeNotifyEnabled) "已启用" else "未启用"} / ${settings.fullChargeThreshold}%")
                appendLine("后台检查：${formatTime(settings.lastCheckAt)}")
                appendLine("上次通知：${formatTime(settings.lastNotifyAt)}")
                appendLine("上次状态：${settings.lastState}")
            }
        }
    }

    /**
     * 保存完设置后，以“低电量 LOW”状态为模拟对象，向当前启用的通知渠道分发一条测试消息。
     */
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

    /**
     * 针对 Android 13 (Tiramisu, API 33) 及以上系统动态申请 POST_NOTIFICATIONS 通知权限。
     */
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
            text = "提示：当前电量显示会在页面可见时每 30 秒自动刷新；后台检查由系统调度，WorkManager 的 15 分钟周期不是严格定时，可能被系统省电策略延后。"
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
