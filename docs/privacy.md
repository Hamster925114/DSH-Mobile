# 隐私说明（DSH Mobile）

DSH Mobile 是一个本地优先的远程控制外壳，不包含广告、统计 SDK 或第三方崩溃上报。

## App 保存的数据

| 数据 | 位置 | 用途 |
|---|---|---|
| 连接地址、连接名称 | Android DataStore | 记住局域网/Tailscale/frp 入口 |
| DSH 登录 Cookie | Android `CookieManager` | 让 WebView 与 OkHttp 共用登录态 |
| 设备令牌 | Android Keystore 加密后存 SharedPreferences | Cookie 过期后续期、设备撤销校验 |
| 通知开关、终端按键状态 | Android DataStore/内存 | 本地设置 |
| 下载文件 | 公共 `Download/` | 用户主动下载的文件 |

## 桌面端保存的数据

| 数据 | 位置 | 用途 |
|---|---|---|
| 配对码 | `$DSH_HOME/mobile/pairing.json` | 一次性配对，过期/使用后轮换 |
| 设备注册表 | `$DSH_HOME/mobile/devices.json` | 设备名称、令牌 SHA-256、创建/最近使用/撤销时间 |
| 审计日志 | `$DSH_HOME/mobile/audit.jsonl` | 配对、登录/续期、撤销、重命名、拒绝访问 |

审计日志包含设备名、设备 ID、来源 IP、事件时间和详情；**不包含**设备令牌明文、DSH Cookie 或 launch token。

## 网络

- App 只连接用户配置的 DSH 地址（默认经 Tailscale Serve）。
- 设备代理只监听回环 `127.0.0.1:53986`，由 Tailscale Serve 暴露给 tailnet。
- 没有自建云端、没有遥测上报、没有 FCM。

## 删除数据

- App：设置 → 清除配对，会清除 Cookie 和设备令牌。
- 桌面：删除 `$DSH_HOME/mobile/devices.json` 可清空设备；删除 `audit.jsonl` 可清空审计。
- 卸载 App 会删除 App 私有数据；公共 `Download/` 中已下载文件需手动删除。

## 安全边界

- 设备令牌只能访问设备门禁；设备管理接口还要求桌面 loopback 或有效 DSH owner Cookie。
- 设备门禁目前只覆盖 Tailscale `53986` 入口；局域网直连 `53985` 是另一条路径，部署时应只保留受保护入口。
- release 构建当前未启用 R8；正式发布前应完成签名密钥离线保管和安装包校验。
