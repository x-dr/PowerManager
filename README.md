# PowerManager

PowerManager 是一个使用 Kotlin 编写的 Android 电量监控 App。

## 功能

- 不需要 root
- 使用 WorkManager 每 15 分钟后台检查一次电量
- 支持开机后自动恢复监控
- 支持本机通知、Bark、Telegram Bot、自定义 Webhook
- 支持低电量、严重低电量、危险电量、恢复电量四种提醒
- 支持通知冷却，避免重复轰炸
- 支持立即检测和测试通知

## 通知策略

默认阈值：

- 低电量：20%
- 严重低电量：10%
- 危险电量：5%
- 恢复电量：30%
- 冷却时间：60 分钟

## 编译

仓库已内置 GitHub Actions：

```bash
gradle assembleDebug --no-daemon
```

构建完成后，debug APK 会上传到 Actions artifact：`PowerManager-debug-apk`。

本地编译需要：

- JDK 17
- Android SDK
- Gradle 8.10.2 或兼容版本

## 使用建议

国产 ROM 为了更稳定，建议手动允许：

- 自启动
- 后台运行
- 忽略电池优化
- 通知权限

## Webhook 请求格式

```json
{
  "event": "low",
  "title": "设备电量低",
  "body": "设备：Android Device\n当前电量：18%",
  "device": "Android Device",
  "timestamp": 1710000000000
}
```
