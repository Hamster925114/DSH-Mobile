# DSH Mobile 0.2.0 Beta

这是 **M4 基础版** 的测试发布。

## 安装

- 下载下方 `dsh-mobile-v0.2.0-debug.apk`（debug 签名，可直接安装，仅用于测试）。
- `app-release-unsigned.apk` 不能直接安装，仅用于构建验证。
- 正式签名版本需要仓库 secrets 中的生产 keystore，见 [docs/release.md](docs/release.md)。

## 功能

- 配对 / Tailscale / 重连 / 连接诊断
- 会话、工作区、模型、插件、审批
- 移动皮肤、抽屉、全屏文件/终端面板、终端按键栏
- 通知深链、亮暗主题
- 文件上传/拍照/剪贴板图片/预览下载/blob 导出
- 设备令牌、设备代理门禁、设备管理、审计日志

## 首次使用

1. 桌面按 [docs/usage.md](docs/usage.md) 安装 bridge/client-ui，配置 Tailscale Serve `53986`。
2. 电脑打开 `http://127.0.0.1:53985/mobile/pair`。
3. 手机 App 扫码/输入配对码。
4. 设置 → 设备管理可查看设备；审计日志可查配对/登录记录。

## 已知限制

- 设备门禁只保护 Tailscale `53986` 入口；局域网直连 `53985` 不经过门禁。
- 前台服务受 Android 后台限制，无 FCM 推送。
- debug APK 不是生产签名；正式分发前请构建签名 release。
- 平板适配、语音输入不做。
