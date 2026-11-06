# 下载与安装

当前版本：`0.2.0`（`versionCode 2`），包名 `com.dshmobile.app`。

## 构建产物

- Debug：`android/app/build/outputs/apk/debug/app-debug.apk`
- Release（签名后）：`android/app/build/outputs/apk/release/app-release.apk`
- 未配置签名时：`app-release-unsigned.apk`，仅供编译验证，不能安装

## 安装

```powershell
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
```

或在手机文件管理器里打开 APK 安装。Android 8.0+。

## 校验

```powershell
Get-FileHash android\app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
```

签名 release 的证书指纹：

```powershell
& "$env:JAVA_HOME\bin\apksigner.bat" verify --print-certs `
  android\app\build\outputs\apk\release\app-release.apk
```

## 首次使用

1. 桌面按 [m0-setup.md](m0-setup.md) / [lan-setup.md](lan-setup.md) 配置 bridge、Tailscale 和设备代理 `53986`。
2. 电脑打开 `http://127.0.0.1:53985/mobile/pair`，手机扫码。
3. 手机 App 进入会话；设置 → 设备管理可查看/撤销设备，审计日志可查配对/登录记录。

## 注意

- M3 起旧 Cookie 没有设备令牌，升级后需要重新配对一次。
- 设备门禁只保护 Tailscale `53986` 入口；不要同时把局域网 `53985` 暴露给不可信网络。
- 隐私说明见 [privacy.md](privacy.md)，常见问题见 [faq.md](faq.md)。
