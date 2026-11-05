# DSH Mobile — Android 客户端（M1）

Kotlin + Jetpack Compose 外壳，WebView 加载桌面 DSH 的 Web GUI，原生负责配对、Cookie、连接状态和前台事件通知。

- 包名：`com.dshmobile.app`
- 应用名：`DSH Mobile`
- minSdk：26（Android 8.0）
- targetSdk / compileSdk：35
- AGP：8.7.3，Gradle：8.11.1，Kotlin：2.1.0
- 不使用 Google Play Services / Firebase

---

## 1. 在 Android Studio 中打开

本仓库需要 **JDK 17 或 21**。不要用 Android Studio 自带的 JDK 25：Gradle 8.11.1 的 Kotlin DSL 不识别 Java 25，会报 `IllegalArgumentException: 25.0.4`。

1. Android Studio（Ladybug 或更新）→ **Open**。
2. 选择 `D:\project\DSH-Mobile\android`（不是仓库根目录）。
3. **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** 选择 17 或 21；没有就点 **Download JDK** 下载 Temurin 17。
4. 等待 Gradle Sync。首次会下载 Gradle 8.11.1 和依赖。
5. SDK Manager 确认已安装 **Android SDK Platform 35**、**Build-Tools 35.x**。
6. 连接手机（Android 8.0+，开启 USB 调试）或建一个 API 35 模拟器。
7. Run `app`。

命令行（可选，需本机已有 JDK 17 + SDK 35）：

```powershell
cd D:\project\DSH-Mobile\android
$env:JAVA_HOME = 'D:\apps\jdk-17\jdk-17.0.20.1+1'
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

APK 输出：`app\build\outputs\apk\debug\app-debug.apk`。

> 国内网络下 `services.gradle.org` / `github.com` 经常超时。`settings.gradle.kts` 已把阿里云 Maven 镜像放在最前面；Gradle 发行版可用腾讯镜像 `https://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip`。Android Studio Sync 报 `Could not get resource ... gradle-8.11.1-src.zip` 只是源码包下载失败，不影响命令行构建。

---

## 2. 配对流程

### 2.1 桌面端准备

1. 安装 bridge（见 [docs/m0-setup.md](../docs/m0-setup.md)）。
2. 按 [docs/lan-setup.md](../docs/lan-setup.md) 配置三路地址：
   - 局域网：`http://<电脑局域网 IP>:53985`（DSH `webserver.host` 必须绑定该 LAN IP，不能写 `0.0.0.0`）
   - Tailscale：`https://<machine>.<tailnet>.ts.net`
   - frp + VPS：`https://dsh.example.com`
3. 把三个地址写进 bridge 的 `endpoints`，并把域名加入 `connection.trustedHosts`。
4. 完整重启 DSH。
5. 在电脑浏览器打开 `http://<bind-ip>:53985/mobile/pair`，页面会为每种连接显示二维码和同一个 16 位配对码。

### 2.2 固定端口

DSH 重启后默认可能换端口，Tailscale Serve 仍指向旧端口时，手机会显示 `127.0.0.1 拒绝连接`。

在桌面 profile 的 `cordis.patch.yml` 顶部固定：

```yaml
- id: webserver
  config:
    host: 127.0.0.1
    port: 53985
```

**M3 起 Serve 指向设备代理，而不是 DSH 本身**。DSH 仍监听 `53985`，bridge 在同机 `53986` 上做设备令牌门禁：

```powershell
tailscale serve --bg http://127.0.0.1:53986
```

验证：

```powershell
tailscale serve status
curl.exe -I https://<machine>.<tailnet>.ts.net/mobile/hello
```

`/mobile/hello` 允许未配对访问并应返回 `200`；`/` 未配对时应返回 `401`，说明设备门禁已生效。

> 如果页面顶部出现黄色警告，说明当前没有手机可达的地址：DSH 绑定的是回环地址。先改 `webserver.host` 或配置 `endpoints`，再重启 DSH。

### 2.3 手机端

1. 打开 DSH Mobile。
2. 在“连接方式”里选局域网 / Tailscale / frp + VPS；也可以点“连接管理”先编辑地址。
3. 点“扫码”，扫桌面配对页对应连接的二维码；二维码会带连接类型，自动选中局域网 / Tailscale / frp 槽位。也可以手动填地址 + 配对码。
4. 点“连接”。

App 会：

```text
POST {base}/mobile/pair          {code, base, deviceName, platform}
  -> {url, deviceId, deviceToken}
POST/GET {base}/mobile/pair      // 通过设备代理；配对路径允许未配对访问
GET  {url}                       // 303 + Set-Cookie
  -> 写入 Android CookieManager + Keystore 中的 deviceToken
GET  {base}/mobile/hello         // 健康检查
POST {base}/api/session/list     // {"args":{"_request":{}}}
  -> 读取会话数量
```

Cookie 同时供 WebView 和 OkHttp 使用；设备令牌额外保存在 Android Keystore，用于 Cookie 过期后的静默续期和撤销校验。成功后 base URL 保存到对应的连接槽位，下次启动直接进主页。

### 2.4 地址格式

- 可以省略 `http://` / `https://`：局域网和自定义连接默认补 `http://`，Tailscale 和 frp 默认补 `https://`；带 scheme 时以输入为准。
- 可以带路径前缀（反代把 DSH 挂在子路径时），例如 `https://example.com/dsh`。
- 不能带 query / fragment。
- 配对码一次性，默认 120 秒；过期后桌面页刷新会生成新码。

### 2.5 离开电脑后如何连接

**不需要每次扫码。** 扫码只用于首次建立 Cookie；之后打开 App 会直接读取已保存的连接和 Cookie。

- Cookie 默认 30 天，签名密钥在桌面 `$DSH_HOME/.credentials.yaml`，DSH 重启后通常仍然有效。
- App 被关闭、手机重启、Tailscale 重新连接，都**不需要重新扫码**。
- 如果 App 启动时 Tailscale 还没连上，会显示「连接暂时失败」；先连 Tailscale，再点 **「重试已保存连接（无需扫码）」**。
- 只有以下情况需要重新配对：Cookie 过期且设备令牌也过期/被撤销、App 数据被清除/卸载、DSH 凭据重置、连接域名变化。
- 确实需要重新配对又不在电脑旁时：用 Tailscale SSH / 远程桌面打开桌面配对页，读取 16 位配对码，在 App 里**手动输入地址 + 配对码**；不一定要扫码。

> M3 起每个设备有独立令牌（Android Keystore 加密保存）。Cookie 过期时 App 会用 `/mobile/token/exchange` 自动续期，不需要重新扫码；设备被撤销后下一次请求立即返回 401。

### 2.6 设备管理与撤销

- 详细协议见 [docs/device-tokens.md](../docs/device-tokens.md)。
- App：设置 → **设备管理（查看 / 撤销设备）**。
- 桌面：设备列表保存在 `$DSH_HOME/mobile/devices.json`，只写令牌 SHA-256，不保存明文。
- 撤销一台设备后，它下一次 HTTP 请求或 WebSocket 连接会被 `53986` 门禁拒绝；当前设备撤销后会清空 Cookie 和 Keystore 令牌并回到配对页。
- 设备管理接口要求桌面 loopback 或有效 DSH owner Cookie；单独持有 device token 不能撤销其他设备。

> 首次从旧版本升级到 M3：Serve 切到 `53986` 后，旧 Cookie 没有设备令牌，手机会显示 401/要求重新配对；这是预期的迁移步骤。重新扫一次码即可。

---

## 3. 已实现（M1）

| 模块 | 内容 |
|---|---|
| 配对 | 手动输入、ZXing 扫码、`dshmobile://pair?...` / JSON 解析、地址归一化 |
| 认证 | 一次性码换 token URL、抓 `Set-Cookie`、CookieManager 持久化、`/mobile/hello` 校验 |
| 连接管理 | 局域网 / Tailscale / frp + VPS 三个槽位，添加/编辑/删除/测试/诊断/切换，DataStore 持久化 |
| 连接诊断 | DNS → TCP → TLS → HTTP(bridge) → Cookie → API → WebSocket 全链路；失败项给出 Tailscale/frp/Host/Cookie 修复建议 |
| 主页 | WebView 加载 `{base}/`，JS/DOM storage、Cookie、文件选择、拍照、下载、外链跳浏览器、返回键 |
| 移动适配 | 注入基础 CSS（隐藏滚动条、输入框 16px 防 iOS 缩放、safe-area） |
| 设置 | 当前连接、刷新会话数、重新加载、连接管理（含诊断入口）、清除配对 |
| M2 底部导航 | 会话 / 文件 / 终端 / 设置，调用 WebView 现有入口 |
| M2 终端按键栏 | Esc / Tab / Enter / Ctrl / Alt / Ctrl+C / 方向键，点击后发送到 xterm |
| M2 离线提示 | 系统网络 + DSH `/mobile/hello` 健康探测；断线顶部横幅，恢复后自动重载 |
| M2 客户端皮肤 | `@dsh-mobile/client-ui`：全宽会话列、中栏固定到内容轨道（修复 0px 命中区）、折叠右栏隐藏、全屏 Sheet、移动留白、触控尺寸；抽屉展开 DSH 完整侧栏（图标带文字），右缘 `×` 关闭 |
| M2 横屏 | 紧凑图标底栏、矮终端按键栏、旋转不重建 WebView |
| M2 深链 | 通知携带 `session_id` / `dshmobile://session/<id>`；`singleTop` + `onNewIntent`；`client-ui` 暴露 `uiWorkspace.openSession` 桥 |
| M2 文件/终端 | 底栏通过 `@dsh-mobile/client-ui` 的 `sidebarRight.openTab()` 桥直接打开文件/终端面板；全屏面板即使 `data-rightbar-collapsed` 也保持显示（用 `data-sidebar-right-open` 区分关闭的图标栏）；文件选择支持多选 + 拍照，选中文件先缓存到 `shared/` 再由 `FileProvider` 提供稳定 URI；下载完成通知含“打开/分享” |
| M2 剪贴板 | 底栏“粘贴”把剪贴板图片转 JPEG/Base64，合成 `ClipboardEvent` 填入 DSH 输入框 |
| M2 主题 | 系统亮/暗同步状态栏/导航栏图标、WebView 背景色；DSH 主题跟随系统 |
| M3 设备令牌 | 配对签发独立 deviceToken（设备端 Android Keystore 加密）；`/mobile/token/exchange` 静默续期；`53986` 设备代理对 HTTP/WebSocket 做门禁 |
| M3 设备管理 | App 设置 → 设备管理：列表/撤销；桌面 `devices.json` 只存 SHA-256；撤销后下一次请求立即 401 |
| 前台服务 | `DshEventService` 连 `wss://.../api/remote.mux`，订阅 `$events`，审批/错误/完成本地通知 |
| 通知 | Android 13+ 运行时权限；Android 14 `dataSync` 前台服务类型；下载完成独立渠道 |
| 测试 | `DshUrl`、`PairingPayload`、`ConnectionProfile`、`ConnectionDiagnostics`、`SessionLink` 共 27 个 JVM 单元测试；bridge 22 个 Node 测试（含设备令牌与代理门禁） |

### 3.1 明确不做（M1）

- 不直接编辑文件（`workspaceFiles` 只有读接口；让 Agent 改）。
- 不做杀进程后的推送（只有前台服务）。
- 不做原生会话/工作区/模型 UI（先用 WebView 里的 DSH SPA；M2 再做移动皮肤和原生入口）。
- 不做审批的原生回传（点通知回到 SPA 审批）。

---

## 4. 代码结构

```text
app/src/main/java/com/dshmobile/app/
  MainActivity.kt              # edge-to-edge、通知权限、Compose 入口
  DshApplication.kt
  data/
    DshUrl.kt                  # base URL 归一化/校验
    SessionLink.kt             # 通知/深链会话 ID 解析与归一化
    PairingPayload.kt          # 二维码/深链 JSON 解析
    ConnectionProfile.kt       # 连接槽位（LAN/Tailscale/frp/CUSTOM）
    SettingsRepository.kt      # DataStore：连接列表、当前连接、通知开关
    DeviceTokenStore.kt        # Android Keystore AES/GCM 加密设备令牌
    DshApi.kt                  # 配对、Cookie、设备令牌、设备管理、RPC
    ConnectionDiagnostics.kt   # DNS/TCP/TLS/HTTP/Cookie/API/WebSocket 诊断
  service/
    DshEventService.kt         # 前台 $events WebSocket + 审批/错误/完成通知
    DownloadCompleteReceiver.kt # 下载完成通知（打开/分享）
  ui/
    DshMobileApp.kt            # Loading / Pairing / Home 状态机
    MainViewModel.kt           # 配对、连接切换、恢复、退出、会话数
    PairingScreen.kt           # 配对 + 连接方式选择
    ConnectionsScreen.kt       # 连接管理（添加/编辑/测试/诊断/删除/切换）
    ConnectionDiagnosticsDialog.kt # 诊断结果弹层
    HomeScreen.kt              # WebView + 文件选择/下载/设置浮层 + M2 底部导航（会话/文件/终端/设置）
    SettingsSheet.kt           # 原生设置 Dialog
    DevicesDialog.kt           # M3 设备列表/撤销
    theme/Theme.kt
```

关键约定：

- **Cookie 单一来源**：`android.webkit.CookieManager`。OkHttp 通过自定义 `CookieJar` 读写同一个存储，避免“API 能调、WebView 没登录”。
- **不硬编码 Cookie 名**：从 `Set-Cookie` 原样写入。
- **不跟随重定向**：token URL 的 `303` 必须手动处理，否则可能丢掉 `Set-Cookie` 或跳到错误地址。
- **RPC 参数**：`payload` 恰好一个 `args`；`session/list` 必须 `{"args":{"_request":{}}}`。

---

## 5. M1 验收清单

真机结果见 [docs/m1-acceptance.md](../docs/m1-acceptance.md)。

- [x] Android Studio 能 Sync，`assembleDebug` 成功。
- [ ] 局域网 / Tailscale / frp + VPS 至少两条链路可用（Tailscale 已通）。
- [x] 桌面 `/mobile/pair` 为每种连接显示二维码；App 扫码成功（Tailscale）。
- [ ] 三种连接分别配对一次后，在「连接管理」里切换不需要重新配对。
- [x] 连接后 WebView 显示 DSH 会话列表，不需要在手机浏览器再登录。
- [x] 打开一个会话，能发消息、看到流式输出。
- [x] 切换模型、工作区、插件页面可打开。
- [x] 文件选择（附件）能选到本地文件。
- [x] 下载文件能进系统下载目录。
- [x] 杀 App 重开，仍保持登录（30 天 Cookie）。
- [ ] 设置里“清除配对”后回到配对页，旧 Cookie 不再可用。
- [x] 开启“后台事件通知”后，前台服务通知常驻；审批/错误/完成能收到本地通知。
- [x] 点审批/完成通知能直达对应会话（`singleTop` + `openSession` 桥）。
- [x] 文件选择支持多选和拍照；下载完成通知可打开/分享。
- [x] 剪贴板图片可粘贴进 DSH 输入框。
- [x] 系统亮/暗切换时状态栏、导航栏、WebView 背景和 DSH 主题同步。
- [ ] 连续完成 10 个真实任务，不需要碰电脑。

---

## 6. 已知限制

- **DSH SPA 是桌面布局**：M1 只注入少量 CSS，复杂页面（终端、diff、插件市场）在手机上可能拥挤；M2 的 `client-ui` 移动皮肤解决。
- **明文 HTTP**：`usesCleartextTraffic="true"` 仅为局域网调试；Tailscale/frp 应使用 HTTPS，正式版应移除。
- **前台服务**：Android 15 对 `dataSync` 有 6 小时/天的限制；App 被强停或系统回收后不会收到事件。这是产品决策，不是 bug。
- **WebSocket 401**：当前实现会提示并停止重连；重新配对后再开启通知。
- **深链冷启动慢**：Tailscale 首次加载 DSH 插件包可能 20–30 秒；App 会最多等 60 秒，超时提示手动打开。
- **剪贴板只支持图片**：文本仍用系统粘贴；图片走合成 `ClipboardEvent`，若 DSH 前端改版可能需要更新选择器。
- **无 GMS**：不依赖 FCM，因此没有真正的离线推送。
- **设备门禁只保护代理入口**：当前 `53986` 代理绑定回环，只保护 Tailscale 入口；直连局域网 `53985` 的路径不经过设备令牌校验。
- **旧设备迁移**：M3 前配对的设备没有 deviceToken；切到 `53986` 后会被 401 拒绝，需要重新扫一次码。

---

## 7. 排错

| 现象 | 检查 |
|---|---|
| Sync 失败：SDK 35 缺失 | SDK Manager 安装 Platform 35 / Build-Tools 35 |
| `Unsupported class file major version` | Android Studio 的 Gradle JDK 设为 17 或 21 |
| 配对 410 | 码过期/已用过；刷新桌面 `/mobile/pair` 生成新码 |
| 配对 403 | 码错误；注意大小写（App 会自动转大写） |
| 配对 429 | 失败次数过多；等 1 分钟 |
| 连接超时 | 当前连接地址是否手机可达；局域网是否同网段/防火墙；Tailscale/frp 是否在线 |
| 403 / Host 不匹配 | DSH `connection.trustedHosts` 加域名，或反代统一 Host |
| 配对 400 `unknown-base` | 把该地址加入 bridge 的 `endpoints` 允许列表，重启 DSH |
| 配对页 404 | 配对页只允许本机访问；用电脑打开 `http://<bind-ip>:53985/mobile/pair` |
| `127.0.0.1 拒绝连接` / `ERR_CONNECTION_REFUSED` | DSH 重启后端口变了，但 Tailscale Serve 仍指旧端口；`tailscale serve status` 确认，再执行 `tailscale serve --bg http://127.0.0.1:53986`（M3 设备代理） |
| 手机显示 401「DSH Mobile 已锁定」 | Serve 已切到 53986，但该手机还没有设备令牌；在桌面配对页重新扫码配对一次 |
| WebView 白屏 | 设置里“重新加载”；看 logcat 的 `chromium` / `DshApi` |
| 文件显示“上传失败” | 重新选择文件；当前版本会先缓存到 App 的 `shared/` 再上传。若仍失败，看 logcat 的 `DshEventService` / DSH 连接是否反复 `connection lost` |
| 点通知没进对应会话 | 确认 DSH 页面已启动且 `@dsh-mobile/client-ui` 已加载；深链最多等 60 秒，超时会提示手动打开 |
| 亮/暗切换后颜色不更新 | 系统是否切换了深色模式；DSH 主题设为“跟随系统”；必要时重新加载 WebView |
| 通知不出现 | Android 13+ 通知权限；设置里开关；`dsh_events` 渠道 |
| 扫码没反应 | 相机权限；模拟器无相机时用手动输入 |
