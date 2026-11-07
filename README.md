# DSH Mobile

Android 远程控制端：在手机上操作桌面版 DeepSeek Harness（DSH）的会话、工作区、模型、插件、文件预览、终端和审批。

当前版本：**0.2.0（M4 发布准备）**。M1/M2/M3 功能已完成；M4 已补 CI、release 构建、隐私说明和发布文档。

- 仓库：<https://github.com/Hamster925114/DSH-Mobile>
- 下载：<https://github.com/Hamster925114/DSH-Mobile/releases>
- **详细使用说明：[docs/usage.md](docs/usage.md)**

## 方案

- Android 原生外壳（Kotlin + Compose）+ WebView 加载 DSH SPA。
- 桌面端安装两个插件：
  - `@dsh-mobile/bridge`：配对、设备令牌、设备代理、设备管理、审计。
  - `@dsh-mobile/client-ui`：移动皮肤、抽屉、文件/终端面板、深链桥。
- 远程入口：Tailscale Serve → bridge 设备代理 `53986` → DSH `53985`。
- 通知：只做前台服务（FGS），不做杀进程推送/FCM。
- 文件：只读 + Agent 修改；支持选择/拍照上传、预览下载、blob 导出保存。

## 已实现

- 配对：扫码/手动码、Cookie 持久化、连接诊断、Tailscale 重连。
- 移动 UI：底部导航、抽屉、全屏文件/终端面板、终端按键栏、横屏。
- 通知：审批/错误/完成通知，点通知直达会话。
- 文件：多选上传、拍照、剪贴板图片、下载完成通知、文件预览下载。
- 设备安全：独立设备令牌、Android Keystore、`53986` 代理门禁、即时撤销。
- 审计：配对、登录/续期、撤销、重命名、拒绝访问；App/桌面可查看。
- 发布基础：`0.2.0`、release 签名配置、GitHub Actions CI、隐私说明。

## 快速开始

1. 桌面安装 bridge 和 client-ui，按 [docs/usage.md](docs/usage.md) / [docs/lan-setup.md](docs/lan-setup.md) 配置 Tailscale。
2. 固定 DSH 端口 `127.0.0.1:53985`，设备代理 `53986`：

```powershell
tailscale serve --bg http://127.0.0.1:53986
```

3. 电脑打开 `http://127.0.0.1:53985/mobile/pair`，用 App 扫码。
4. 构建/安装：

```powershell
$env:JAVA_HOME='D:\apps\jdk-17\jdk-17.0.20.1+1'
cd android
.\gradlew.bat :app:assembleDebug
```

## 构建与测试

```powershell
# Android：JVM 测试 + debug
cd android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug

# 发布前：lint + unsigned release
.\gradlew.bat :app:lintDebug :app:assembleRelease

# bridge：27 个 Node 测试
cd ..\packages\bridge
corepack pnpm test
```

签名 release 见 [docs/release.md](docs/release.md)。

## 文档

- [PLAN.md](PLAN.md)：完整里程碑与验收
- [docs/usage.md](docs/usage.md)：完整安装/配对/日常使用说明
- [android/README.md](android/README.md)：配对、构建、排错
- [docs/device-tokens.md](docs/device-tokens.md)：设备令牌/门禁/审计
- [docs/download.md](docs/download.md)：安装包/校验/首次使用
- [docs/release.md](docs/release.md)：签名、CI、发布清单
- [docs/privacy.md](docs/privacy.md)：隐私说明
- [docs/faq.md](docs/faq.md)：常见问题
- [CHANGELOG.md](CHANGELOG.md)：版本变更

## 已知限制

- 设备门禁只保护 Tailscale `53986` 入口；局域网直连 `53985` 不经过门禁。
- 前台服务受 Android 后台限制；强停/系统回收后无推送。
- release 暂未启用 R8 压缩。
- 平板适配、语音输入按产品决定不做。
- 当前无崩溃上报/统计 SDK。
