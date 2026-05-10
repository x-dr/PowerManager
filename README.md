# PowerManager

PowerManager 是一个使用 Kotlin 编写的 Android 电量监控 App。

## 功能

- 不需要 root
- 使用 WorkManager 每 15 分钟后台检查一次电量
- 支持开机后自动恢复监控
- 支持常驻通知栏，通知栏显示当前电量和充电状态
- 常驻通知支持一键停止
- 支持插上电源后发送开始充电提醒
- 支持达到满电阈值后发送充满电提醒
- 满电阈值可配置，范围 50% 到 100%，默认 100%
- 支持本机通知、Bark、Telegram Bot、自定义 Webhook
- 支持低电量、严重低电量、危险电量、恢复电量、开始充电、充满电提醒
- 支持通知冷却，避免重复轰炸
- 支持立即检测和测试通知

## 通知策略

默认阈值：

- 低电量：20%
- 严重低电量：10%
- 危险电量：5%
- 恢复电量：30%
- 满电阈值：100%
- 冷却时间：60 分钟

## 充电提醒说明

- 开始充电提醒：通过系统电源广播触发，插上电源后会尽快检测并发送提醒。
- 充满电提醒：达到满电阈值并且仍接入电源时发送提醒。
- 如果启用常驻通知栏，常驻服务会每分钟刷新一次电量，也会顺便检查满电提醒。
- 如果没有启用常驻通知栏，满电提醒主要依赖 WorkManager 周期检测，可能最多延迟约 15 分钟。

## 下载 APK

GitHub Actions 构建成功后会自动上传 APK 到 Releases：

- Release tag：`debug-latest`
- Release name：`PowerManager Debug Latest`
- APK：`PowerManager-debug-latest.apk`

同时也会保留 Actions artifact：`PowerManager-debug-apk`，方便排查构建问题。

## 编译

仓库已内置 GitHub Actions：

```bash
gradle assembleDebug --no-daemon --stacktrace --info
```

本地编译需要：

- JDK 17
- Android SDK
- Gradle 8.9 或兼容版本

## 免卸载升级的签名配置（推荐）

为避免“签名不一致导致只能卸载重装”，请固定 release keystore，不要在不同机器上重新生成。

1. 复制配置模板：

```bash
cp keystore/release.properties.example keystore/release.properties
```

2. 填入同一套 release 签名信息（或改用环境变量/`~/.gradle/gradle.properties`）：

- `PM_RELEASE_STORE_FILE`
- `PM_RELEASE_STORE_PASSWORD`
- `PM_RELEASE_KEY_ALIAS`
- `PM_RELEASE_KEY_PASSWORD`

3. 发布前执行校验：

```bash
./gradlew verifyReleaseSigning
```

4. 仅在校验通过后构建发布包：

```bash
./gradlew assembleRelease
```

## 使用建议

国产 ROM 为了更稳定，建议手动允许：

- 自启动
- 后台运行
- 忽略电池优化
- 通知权限

如果启用常驻通知栏，需要保持通知权限允许，否则通知栏可能不显示。

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
