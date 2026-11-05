# 设备令牌与设备门禁（M3）

## 目标

每台手机有独立的设备身份和令牌；丢失手机时，在另一台已配对设备或桌面撤销它，**下一次请求立即失效**，不需要重启 DSH、也不需要让所有设备重新配对。

## 架构

```text
手机 WebView / OkHttp
        │  Cookie: dsh_mobile_device=<deviceToken>
        │  Cookie: dsh-auth-...=<DSH browser cookie>
        ▼
Tailscale Serve :443
        │
        ▼
127.0.0.1:53986   @dsh-mobile/bridge device proxy
        │  校验 deviceToken 的 SHA-256、撤销/过期状态
        ▼
127.0.0.1:53985   DSH Web GUI
```

- `53986` 只绑定回环地址，由 Tailscale Serve 暴露给手机。
- `/mobile/pair`、`/mobile/hello` 不要求设备 Cookie，保证首次配对和健康检查可用。
- 其余 HTTP 请求与 WebSocket upgrade 都要求有效 `dsh_mobile_device`。
- 设备管理接口额外要求桌面 loopback 或有效 DSH owner Cookie；单独持有 device token 不能撤销其他设备。

## 配对

```http
POST /mobile/pair
{"code":"...","base":"https://...","deviceName":"Pixel 9","platform":"android"}
```

响应新增：

```json
{
  "url": "https://.../?token=...",
  "base": "https://...",
  "deviceId": "a1b2c3d4e5f6",
  "deviceToken": "dshm_a1b2c3d4e5f6_<secret>",
  "deviceExpiresAt": "..."
}
```

`deviceToken` 只在配对响应中出现一次。桌面 `devices.json` 只保存：

```json
{
  "v": 1,
  "devices": [
    {
      "id": "a1b2c3d4e5f6",
      "name": "Pixel 9",
      "platform": "android",
      "tokenHash": "<sha256>",
      "createdAt": "...",
      "lastSeenAt": "...",
      "expiresAt": "...",
      "revokedAt": null
    }
  ]
}
```

Android 端用 Android Keystore 的 AES/GCM 密钥加密令牌，密文放 SharedPreferences；卸载/清除数据会丢令牌，需要重新配对。

## 续期与撤销

- Cookie 过期：`POST /mobile/token/exchange`，`Authorization: Bearer <deviceToken>`，返回新的 launch URL；App 用它换新 Cookie。
- 撤销：`POST /mobile/devices/revoke` `{id}`。代理下一次请求返回 401；当前设备会清空 Cookie 和 Keystore 令牌。
- 管理列表：`GET /mobile/devices`；重命名：`POST /mobile/devices/rename`。
- 设备令牌默认 30 天；`deviceTtlDays` 可调。`/mobile/token/exchange` 会滑动续期。

## 迁移

旧版本只有 30 天 DSH Cookie，没有设备令牌。切换到 `53986` 后旧手机会被 401 拒绝，这是预期的：在电脑打开 `http://127.0.0.1:53985/mobile/pair`，用新 APK 重新配对一次。

## 测试

```powershell
cd packages/bridge
node --test test/*.test.js
```

覆盖配对、令牌续期、撤销、代理 HTTP 门禁、代理 WebSocket 门禁、公共配对路径。
