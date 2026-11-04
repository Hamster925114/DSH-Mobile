# DSH Mobile

Android 远程控制端，用来在手机上操作桌面版 DeepSeek Harness（DSH）：会话、工作区、模型、插件、文件预览、终端、审批。

**当前状态：M1 脚手架（Android 工程 + 配对/WebView/前台事件服务），等待 Android Studio 首次构建与真机验收。**

## 已定方案

- Android 原生外壳（Kotlin + Compose）+ WebView 加载 DSH SPA。
- 桌面端安装两个小插件：
  - `@dsh-mobile/bridge`：配对码、二维码、公网地址、健康检查。
  - `@dsh-mobile/client-ui`：移动皮肤（M2 开始）。
- 接入：Tailscale 主用，frp + VPS 备用。
- 通知：只做前台服务，不做杀进程推送。
- 文件：只读 + 让 Agent 改，不做手机端直接编辑。

完整计划见 [PLAN.md](PLAN.md)。

## 目录

```text
android/                 # M1：Gradle / Compose 工程（用 Android Studio 打开）
packages/
  bridge/                # M0：@dsh-mobile/bridge（Host 插件，含二维码）
  client-ui/             # M2：@dsh-mobile/client-ui（移动皮肤）
docs/
  m0-setup.md            # 安装 bridge、验证认证链路、Tailscale/frp
  protocol-notes.md      # RPC / 流式 / 事件协议
  android-toolchain.md   # JDK / Android SDK 安装
tools/
  smoke-auth.mjs         # 配对 -> Cookie -> session/list 冒烟测试
  install-m0.ps1         # 一键安装 bridge（可选）
```

## M0 快速开始

```powershell
# 1. 安装 bridge 到 desktop profile（不会自动重启 DSH）
powershell -ExecutionPolicy Bypass -File .\tools\install-m0.ps1

# 2. 重启 DSH 桌面端

# 3. 读取配对信息并跑冒烟测试
Get-Content $HOME\.dsh\mobile\pairing.json
node .\tools\smoke-auth.mjs
```

桌面浏览器打开 `http://127.0.0.1:53985/mobile/pair` 可以看到配对二维码。详细步骤、Tailscale/frp 配置和排错见 [docs/m0-setup.md](docs/m0-setup.md)。

## Android（M1）

用 Android Studio 打开 [android/](android/) 目录，Sync 后 Run。需要：

- Android Studio Ladybug 或更新（自带 JDK 17）；
- Android SDK Platform 35、Build-Tools 35.x；
- 手机 Android 8.0+。

构建、配对流程、M1 验收清单和已知限制见 [android/README.md](android/README.md)。

## 开发

```powershell
cd packages\bridge
corepack pnpm test
```

要求 Node.js 22+。Android 构建需要 JDK 17+ 和 Android SDK；当前机器没有安装，使用 Android Studio 的 bundled JDK/SDK。
