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

本仓库**不要求**命令行安装 JDK/Android SDK；用 Android Studio 自带的 JDK 17 和 SDK Manager 即可。

1. Android Studio（Ladybug 或更新）→ **Open**。
2. 选择 `D:\project\DSH-Mobile\android`（不是仓库根目录）。
3. 等待 Gradle Sync。首次会下载 Gradle 8.11.1 和依赖。
4. SDK Manager 确认已安装 **Android SDK Platform 35**、**Build-Tools 35.x**。
5. 连接手机（Android 8.0+，开启 USB 调试）或建一个 API 35 模拟器。
6. Run `app`。

命令行（可选，需本机已有 JDK 17 + SDK 35）：

```powershell
cd D:\project\DSH-Mobile\android
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

APK 输出：`app\build\outputs\apk\debug\app-debug.apk`。

> 本仓库的 `gradle-wrapper.jar` 来自 Gradle v8.11.1，已校验 zip 完整；若公司网络拦截 `services.gradle.org`，在 Android Studio 的 Gradle 设置里改用本地 Gradle 8.11.1。

---

## 2. 配对流程

### 2.1 桌面端准备

1. 安装 bridge（见 [docs/m0-setup.md](../docs/m0-setup.md)）。
2. 在 desktop profile 的 `cordis.patch.yml` 中把 `publicUrl` 设为手机能访问的地址：
   - Tailscale：`https://<machine>.<tailnet>.ts.net`
   - frp：`https://dsh.example.com`
   - 局域网调试：`http://<电脑局域网 IP>:53985`
3. 完整重启 DSH。
4. 在电脑浏览器打开 `http://127.0.0.1:53985/mobile/pair`，会显示二维码和 16 位配对码。

> 如果页面顶部出现黄色警告，说明 `publicUrl` 还是空的，二维码里是 `127.0.0.1`，手机连不上。

### 2.2 手机端

1. 打开 DSH Mobile。
2. 点“扫码”，扫桌面配对页的二维码；或手动填地址 + 配对码。
3. 点“连接”。

App 会：

```text
POST {base}/mobile/pair          {code}
  -> {url}                       // 带 launch token 的 URL
GET  {url}                       // 303 + Set-Cookie
  -> 写入 Android CookieManager
GET  {base}/mobile/hello         // 健康检查
POST {base}/api/session/list     // {"args":{"_request":{}}}
  -> 读取会话数量
```

Cookie 同时供 WebView 和 OkHttp 使用；成功后会保存 base URL，下次启动直接进主页。

### 2.3 地址格式

- 必须带 `http://` 或 `https://`。
- 可以带路径前缀（反代把 DSH 挂在子路径时），例如 `https://example.com/dsh`。
- 不能带 query / fragment。
- 配对码一次性，默认 120 秒；过期后桌面页刷新会生成新码。

---

## 3. 已实现（M1）

| 模块 | 内容 |
|---|---|
| 配对 | 手动输入、ZXing 扫码、`dshmobile://pair?...` / JSON 解析、地址归一化 |
| 认证 | 一次性码换 token URL、抓 `Set-Cookie`、CookieManager 持久化、`/mobile/hello` 校验 |
| 主页 | WebView 加载 `{base}/`，JS/DOM storage、Cookie、文件选择、下载、外链跳浏览器、返回键 |
| 移动适配 | 注入基础 CSS（隐藏滚动条、输入框 16px 防 iOS 缩放、safe-area） |
| 设置 | 连接信息、刷新会话数、重新加载、清除配对 |
| 前台服务 | `DshEventService` 连 `wss://.../api/remote.mux`，订阅 `$events`，审批/错误本地通知 |
| 通知 | Android 13+ 运行时权限；Android 14 `dataSync` 前台服务类型 |
| 测试 | `DshUrl`、`PairingPayload` 单元测试 |

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
    PairingPayload.kt          # 二维码/深链 JSON 解析
    SettingsRepository.kt      # DataStore：baseUrl、通知开关
    DshApi.kt                  # 配对、Cookie、hello、session/list RPC
  service/
    DshEventService.kt         # 前台 $events WebSocket + 通知
  ui/
    DshMobileApp.kt            # Loading / Pairing / Home 状态机
    MainViewModel.kt           # 配对、恢复、退出、会话数
    PairingScreen.kt
    HomeScreen.kt              # WebView + 文件选择/下载/设置浮层
    SettingsSheet.kt
    theme/Theme.kt
```

关键约定：

- **Cookie 单一来源**：`android.webkit.CookieManager`。OkHttp 通过自定义 `CookieJar` 读写同一个存储，避免“API 能调、WebView 没登录”。
- **不硬编码 Cookie 名**：从 `Set-Cookie` 原样写入。
- **不跟随重定向**：token URL 的 `303` 必须手动处理，否则可能丢掉 `Set-Cookie` 或跳到错误地址。
- **RPC 参数**：`payload` 恰好一个 `args`；`session/list` 必须 `{"args":{"_request":{}}}`。

---

## 5. M1 验收清单

- [ ] Android Studio 能 Sync，`assembleDebug` 成功。
- [ ] 手机与电脑同一 Tailscale tailnet（或 frp 可用）。
- [ ] 桌面 `/mobile/pair` 显示二维码；App 扫码成功。
- [ ] 连接后 WebView 显示 DSH 会话列表，不需要在手机浏览器再登录。
- [ ] 打开一个会话，能发消息、看到流式输出。
- [ ] 切换模型、工作区、插件页面可打开。
- [ ] 文件选择（附件）能选到本地文件。
- [ ] 下载文件能进系统下载目录。
- [ ] 杀 App 重开，仍保持登录（30 天 Cookie）。
- [ ] 设置里“清除配对”后回到配对页，旧 Cookie 不再可用。
- [ ] 开启“后台事件通知”后，前台服务通知常驻；审批/错误能收到本地通知。
- [ ] 连续完成 10 个真实任务，不需要碰电脑。

---

## 6. 已知限制

- **DSH SPA 是桌面布局**：M1 只注入少量 CSS，复杂页面（终端、diff、插件市场）在手机上可能拥挤；M2 的 `client-ui` 移动皮肤解决。
- **明文 HTTP**：`usesCleartextTraffic="true"` 仅为局域网调试；Tailscale/frp 应使用 HTTPS，正式版应移除。
- **前台服务**：Android 15 对 `dataSync` 有 6 小时/天的限制；App 被强停或系统回收后不会收到事件。这是产品决策，不是 bug。
- **WebSocket 401**：当前实现会提示并停止重连；重新配对后再开启通知。
- **无 GMS**：不依赖 FCM，因此没有真正的离线推送。

---

## 7. 排错

| 现象 | 检查 |
|---|---|
| Sync 失败：SDK 35 缺失 | SDK Manager 安装 Platform 35 / Build-Tools 35 |
| `Unsupported class file major version` | Android Studio 的 Gradle JDK 设为 17 或 21 |
| 配对 410 | 码过期/已用过；刷新桌面 `/mobile/pair` 生成新码 |
| 配对 403 | 码错误；注意大小写（App 会自动转大写） |
| 配对 429 | 失败次数过多；等 1 分钟 |
| 连接超时 | `publicUrl` 是否是手机可达地址；Tailscale 是否在线；防火墙 |
| 403 / Host 不匹配 | DSH `connection.trustedHosts` 加域名，或反代统一 Host |
| WebView 白屏 | 设置里“重新加载”；看 logcat 的 `chromium` / `DshApi` |
| 通知不出现 | Android 13+ 通知权限；设置里开关；`dsh_events` 渠道 |
| 扫码没反应 | 相机权限；模拟器无相机时用手动输入 |
