# DSH Mobile 详细使用说明

本文按“桌面准备 → 手机配对 → 日常使用 → 发布/排错”说明。更完整的规划见 [PLAN.md](../PLAN.md)。

## 1. 它是什么

DSH Mobile 是一个 Android 远程控制端，用手机操作桌面版 DeepSeek Harness（DSH）。它不重写 DSH，而是用 WebView 加载同一套 DSH Web GUI，再加一层移动皮肤和原生能力：

```text
Android App (Kotlin/Compose)
  ├─ 配对 / Cookie / 设备令牌
  ├─ WebView + @dsh-mobile/client-ui 移动皮肤
  ├─ 文件选择、拍照、下载、剪贴板
  └─ 前台服务：审批/完成通知 + 深链
            │
            ▼
Tailscale Serve :443
            │
            ▼
@dsh-mobile/bridge 设备代理 127.0.0.1:53986
            │  校验 dsh_mobile_device
            ▼
DSH Web GUI 127.0.0.1:53985
```

## 2. 前置条件

### 电脑

- Windows 10/11（当前主要在 Windows 验证）
- DSH `0.2.1-rc.3` 或兼容版本
- Node.js 22+、Corepack/pnpm
- Tailscale（推荐）；手机与电脑登录同一 tailnet
- JDK 17 + Android SDK 35（只有自己构建 APK 时需要）

### 手机

- Android 8.0+（minSdk 26）
- 安装 Tailscale 并登录同一账号
- 安装 DSH Mobile APK

## 3. 桌面一次性配置

### 3.1 固定 DSH 端口

编辑 `$HOME/.dsh/profiles/desktop/cordis.patch.yml`，确认顶部有：

```yaml
- id: webserver
  config:
    host: 127.0.0.1
    port: 53985
```

DSH 仍只监听回环；手机通过 Tailscale 进来。

### 3.2 安装 bridge 和 client-ui

在本仓库根目录：

```powershell
cd $HOME\.dsh\profiles\desktop

corepack pnpm add file:D:\project\DSH-Mobile\packages\bridge
corepack pnpm add file:D:\project\DSH-Mobile\packages\client-ui
```

在 `cordis.patch.yml` 中加入（或合并已有的 bridge 配置）：

```yaml
- id: connection
  name: '@deepseek-ai/dsh-client-connection'
  config:
    trustedHosts:
      - 'hamster.tail52bb47.ts.net'

- insert:
    - id: dsh-mobile-client-ui
      name: '@dsh-mobile/client-ui'

- insert:
    - id: dsh-mobile-bridge
      name: '@dsh-mobile/bridge'
      config:
        endpoints:
          - id: tailscale
            label: Tailscale
            url: 'https://hamster.tail52bb47.ts.net'
            kind: tailscale
        pairingTtlSeconds: 120
        deviceProxyPort: 53986
        deviceTtlDays: 30
```

把域名替换成你的 tailnet 域名。配置里 `deviceProxyPort` 是设备令牌门禁端口。

### 3.3 重启 DSH

重启桌面 DSH，让 bridge 和设备代理加载。验证：

```powershell
Get-NetTCPConnection -LocalPort 53985,53986 -State Listen
```

应看到两个端口都在监听。

### 3.4 Tailscale Serve 指向设备代理

```powershell
tailscale serve --bg http://127.0.0.1:53986
tailscale serve status
```

正确状态应显示 `/ → http://127.0.0.1:53986`。

验证：

```powershell
curl.exe -I https://hamster.tail52bb47.ts.net/mobile/hello
curl.exe -I https://hamster.tail52bb47.ts.net/
```

- `/mobile/hello` 应返回 `200`（未配对也可访问）
- `/` 未配对应返回 `401`（设备门禁生效）

### 3.5 打开配对页

电脑浏览器打开：

```text
http://127.0.0.1:53985/mobile/pair
```

页面会显示二维码和 16 位配对码。**不要用手机直接打开这个页面**，它只允许桌面本机访问。

## 4. 手机安装与配对

### 4.1 安装 APK

从 GitHub Releases 下载 APK，或本地构建：

```powershell
$env:JAVA_HOME='D:\apps\jdk-17\jdk-17.0.20.1+1'
cd D:\project\DSH-Mobile\android
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### 4.2 配对

1. 手机打开 DSH Mobile。
2. 选择 **Tailscale**。
3. 点“扫码”扫桌面配对页二维码；或手动填：
   - 地址：`https://hamster.tail52bb47.ts.net`
   - 配对码：桌面页面上的 16 位码
4. 点“连接”。

成功后 App 会保存 Cookie 和设备令牌，并进入会话列表。配对码一次性，默认 120 秒。

> M3 起旧 Cookie 没有设备令牌；升级后第一次使用需要重新配对一次。

## 5. 日常使用

### 会话

- 底部“会话”返回聊天。
- 左侧 ≡ 打开抽屉：完整侧栏、会话列表、工作区、搜索。
- 点通知（审批/完成/错误）会直达对应会话。

### 文件

- 底部“文件”打开文件面板。
- 点文件进入预览；预览头部有 **“下载”** 按钮，保存到手机 `Download/`。
- DSH 导出的 PDF/会话包是 `blob:`，会自动分块保存。
- 上传：聊天输入框左侧 **＋ → File**；支持多选和拍照。
- 文件编辑不在手机上直接做，交给 Agent。

### 终端

- 底部“终端”打开全屏终端。
- 原生按键栏：Esc、Tab、Enter、Ctrl、Alt、Ctrl+C、方向键。
- 横屏时按键栏更紧凑。

### 粘贴图片

- 底部“粘贴”读取系统剪贴板图片，转成 JPEG 后填入 DSH 输入框。
- 只支持图片，文本请用系统输入法粘贴。

### 设置

- 连接信息、刷新会话数、重新加载。
- 连接管理/诊断。
- 后台事件通知开关（Android 13+ 需要通知权限）。
- 设备管理：查看/撤销设备、审计日志。
- 隐私说明、清除配对。

## 6. 设备管理、撤销与审计

### App

设置 → 设备管理：

- 查看本机和其他设备
- 撤销其他设备
- 撤销本机（会清除 Cookie + 设备令牌并回到配对页）
- 审计日志：配对、登录/续期、撤销、重命名、拒绝访问

### 桌面（手机丢失时）

```text
http://127.0.0.1:53985/mobile/devices/ui
```

可撤销丢失设备。撤销后该设备下一次 HTTP/WebSocket 请求立即 401。

### 审计文件

```text
$DSH_HOME/mobile/audit.jsonl
```

不包含 deviceToken、Cookie 或 launch token。超过 2 MB 轮转。

## 7. 下载与导出

- 普通 HTTP 下载：`DownloadManager`，带 Cookie，保存到公共 `Download/`。
- DSH `blob:` 下载：JS 拦截 → 256 KB 分块 → 公共 `Download/`。
- 文件预览：右上角“下载”读取当前文件并保存。
- 下载完成通知：普通下载由 `DownloadCompleteReceiver` 提供“打开/分享”。

## 8. 构建、测试、发布

```powershell
# Android JVM 测试 + debug
cd android
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug

# lint + unsigned release
.\gradlew.bat :app:lintDebug :app:assembleRelease

# bridge 27 个 Node 测试
cd ..\packages\bridge
corepack pnpm test
```

签名 release：

```powershell
$env:DSH_MOBILE_KEYSTORE='D:\secure\dsh-mobile-release.jks'
$env:DSH_MOBILE_KEYSTORE_PASSWORD='...'
$env:DSH_MOBILE_KEY_ALIAS='dsh-mobile'
$env:DSH_MOBILE_KEY_PASSWORD='...'
.\gradlew.bat :app:assembleRelease
```

详见 [release.md](release.md)。GitHub Actions 见 `.github/workflows/ci.yml`。

## 9. 排错

| 现象 | 处理 |
|---|---|
| 配对页 404 | 用电脑 loopback 打开；不要用手机/公网 |
| 手机 401 已锁定 | 设备没有令牌；安装 0.2.0+ 重新扫码 |
| 页面 `Failed to load plugins` | 确认代理是 0.2.0；强制停止 App 重开 |
| 文件/终端打不开 | 确认 client-ui 已安装；重载页面 |
| 找不到下载 | 打开文件预览，右上角“下载” |
| 下载失败 | 确认存储空间；blob 会保存到 `Download/` |
| 通知不出现 | Android 13+ 通知权限；设置开关 |
| 收不到通知 | 前台服务被系统回收后无推送；重新打开 App |
| Tailscale 连不上 | `tailscale serve status`；确认 Serve 指 53986 |

更多见 [faq.md](faq.md)。

## 10. 安全边界

- 设备令牌只用于门禁；设备管理还需要 DSH owner Cookie。
- 设备门禁目前只保护 Tailscale `53986`；局域网直连 `53985` 不经过门禁。
- 没有广告/统计/第三方崩溃 SDK。
- 生产签名密钥必须离线保管，不能提交仓库。
- 不要把 53985/53986 直接暴露到公网；使用 Tailscale 或带认证的 TLS 反代。
