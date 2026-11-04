# M0：安装 bridge 插件并验证认证链路

M0 只做一件事：证明“手机 App 能通过一次性配对码拿到 DSH 的浏览器 Cookie，并调用 `/api`”。

> 局域网 / Tailscale / frp + VPS 三路连接的完整配置与切换见 [lan-setup.md](lan-setup.md)。

不包含 Android App、移动皮肤、推送、文件写入。

---

## 1. 前置条件

- Windows 10/11，DSH 桌面端 `0.2.1-rc.3` 正在运行。
- Node.js 22+（仅用于冒烟测试）。
- 能访问 `C:\Users\<你>\.dsh\profiles\desktop`。
- 建议先退出 DSH 再改配置；至少改完后要完整重启一次。

---

## 2. 安装 bridge

### 2.1 一键脚本（推荐）

在项目根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\install-m0.ps1
```

脚本会：

1. 备份 `cordis.patch.yml` 和 `package.json` 到 `*.bak-<时间戳>`；
2. 在 desktop profile 执行 `corepack pnpm add file:D:\project\DSH-Mobile\packages\bridge`；
3. 在 `cordis.patch.yml` 末尾追加 bridge 的 `insert` 块（如果还没有）；
4. 打印下一步。

它**不会**自动重启 DSH。

如果之后要换 Tailscale/frp 地址，重新运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\install-m0.ps1 -PublicUrl "https://<machine>.<tailnet>.ts.net"
```

### 2.2 手动安装

```powershell
cd $HOME\.dsh\profiles\desktop

Copy-Item cordis.patch.yml "cordis.patch.yml.bak-$(Get-Date -Format yyyyMMddHHmmss)"
Copy-Item package.json "package.json.bak-$(Get-Date -Format yyyyMMddHHmmss)"

corepack pnpm add "file:D:\project\DSH-Mobile\packages\bridge"
```

在 `cordis.patch.yml` 末尾追加：

```yaml
- insert:
    - id: dsh-mobile-bridge
      name: '@dsh-mobile/bridge'
      config:
        # 先留空 = 使用 http://127.0.0.1:<实际端口>，只适合本机验证。
        publicUrl: ''
        # 三路连接允许列表；App 配对时只能选择这里列出的地址。
        # 先留空，按 lan-setup.md 填入实际地址后重启 DSH。
        endpoints: []
        # 示例：
        # - id: lan
        #   label: 局域网
        #   url: 'http://192.168.1.10:53985'
        #   kind: lan
        # - id: tailscale
        #   label: Tailscale
        #   url: 'https://machine.tailnet.ts.net'
        #   kind: tailscale
        # - id: frp
        #   label: frp + VPS
        #   url: 'https://dsh.example.com'
        #   kind: frp
        pairingTtlSeconds: 120
```

> `endpoints` 只影响配对页/二维码和换 token 时使用的 base；它不会改变 DSH 的监听地址。局域网直连还需要把 `webserver.host` 改成电脑的 LAN IP（见 [lan-setup.md](lan-setup.md)）。

### 2.3 重启 DSH

完全退出桌面端（托盘图标也要退出），再启动。

> 重启会结束当前正在运行的 Agent 会话；请在方便的时候做。

---

## 3. 读取配对信息

```powershell
Get-Content $HOME\.dsh\mobile\pairing.json
```

示例：

```json
{
  "v": 1,
  "base": "http://127.0.0.1:53985",
  "code": "A1B2C3D4E5F60718",
  "expiresAt": "2026-11-04T10:12:00.000Z",
  "port": 53985,
  "pid": 12345,
  "createdAt": "2026-11-04T10:10:00.000Z"
}
```

- `code` 一次性，默认 120 秒有效。
- 重启 DSH 会生成新 code。
- 桌面浏览器打开 `http://127.0.0.1:53985/mobile/pair` 也能看到 code（仅本机回环可访问）。

### 3.1 扫码配对（M1）

桌面浏览器打开：

```text
http://127.0.0.1:53985/mobile/pair
```

页面会为 `endpoints` 里的每种连接各显示一个二维码，内容是：

```text
dshmobile://pair?base=<URL 编码后的 base>&code=<配对码>
```

手机 DSH Mobile 的“扫码”会解析这个深链。也可以手动输入页面上的 16 位配对码。

> 如果页面顶部出现黄色警告，说明当前没有手机可达的地址：DSH 绑定的是回环地址。先把 `webserver.host` 改成电脑 LAN IP，或配置 Tailscale/frp 的 `endpoints`，再重启 DSH。

二维码由 bridge 的 `qrcode` 依赖在本地生成，不经过第三方服务。安装脚本会一并安装该依赖；如果是手动安装，进入 `packages/bridge` 执行一次 `corepack pnpm install`。

---

## 4. 冒烟测试

```powershell
node D:\project\DSH-Mobile\tools\smoke-auth.mjs
```

它做四步：

1. `GET /mobile/hello`；
2. `POST /mobile/pair`，用 code 换 tokenized URL；
3. `GET <tokenized URL>`，拿到浏览器 Cookie；
4. `POST /api/session/list`，带 Cookie 调 RPC。

成功输出类似：

```text
[ok] pairing file: C:\Users\hamst\.dsh\mobile\pairing.json
     base=http://127.0.0.1:53985 port=53985 expires=...
[ok] bridge 0.0.1 (dsh=0.2.1-rc.3)
[ok] pairing code exchanged for a tokenized URL
[ok] browser cookie captured (dsh-auth-...)
[ok] session/list: 5 session(s)
     first: session-...
[done] M0 auth path works. The Android app can now reuse this flow.
```

> `session/list` 的 RPC 参数是 `{"args":{"_request":{}}}`，不是 `{}`。DSH 的 `payload` 必须恰好有一个 `args` 字段，且 `args` 里是具名参数；详见 [protocol-notes.md](protocol-notes.md)。

如果 `session/list` 返回 401/403，见 §8。

### 4.1 已实测（2026-11-04）

- 把 bridge 写入 `cordis.patch.yml` 后，DSH 热重载了插件，`GET /mobile/hello` 返回 `200`。
- `POST /mobile/pair` 换到 tokenized URL，`GET` 后拿到 `dsh-auth-...` Cookie。
- `POST /api/session/list` 返回 5 个会话。
- 结论：配对/认证/RPC 链路可用；Android 端可以按同一流程实现。

---

## 5. Tailscale 配置（主用）

电脑和手机登录同一个 tailnet。

```powershell
tailscale up
tailscale serve --bg --https=443 http://127.0.0.1:53985
```

得到地址：

```text
https://<machine>.<tailnet>.ts.net
```

把 `publicUrl` 改成这个地址，重启 DSH：

```yaml
- insert:
    - id: dsh-mobile-bridge
      name: '@dsh-mobile/bridge'
      config:
        publicUrl: 'https://<machine>.<tailnet>.ts.net'
        pairingTtlSeconds: 120
```

如果 Tailscale Serve 保留了原始 Host，DSH 可能返回 403。需要在 `cordis.patch.yml` 里信任该域名：

```yaml
- id: connection
  config:
    trustedHosts: ['<machine>.<tailnet>.ts.net']
```

先用手机浏览器打开 `https://<machine>.<tailnet>.ts.net/`：

- 能打开登录页/需要 token：网络和 TLS 通了；
- 403：Host/Origin 未信任；
- 超时：Tailscale 未连接或 Serve 未启动。

> Tailscale 的 HTTPS 证书需要在 admin console 启用；首次 `tailscale serve` 可能会提示。

---

## 6. frp + VPS 配置（备用）

### 6.1 VPS：frps

`frps.toml`：

```toml
bindPort = 7000
auth.method = "token"
auth.token = "<随机长 token>"
```

### 6.2 电脑：frpc

`frpc.toml`：

```toml
serverAddr = "<VPS IP>"
serverPort = 7000
auth.method = "token"
auth.token = "<同一个 token>"

[[proxies]]
name = "dsh"
type = "tcp"
localIP = "127.0.0.1"
localPort = 53985
remotePort = 15385
```

### 6.3 VPS：nginx TLS 反代

```nginx
server {
    listen 443 ssl;
    server_name dsh.example.com;

    ssl_certificate     /etc/letsencrypt/live/dsh.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/dsh.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:15385;
        proxy_http_version 1.1;

        # 让 DSH 的 Host/Origin 校验通过
        proxy_set_header Host 127.0.0.1:53985;

        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
        proxy_buffering off;
    }
}
```

然后把 `publicUrl` 设为 `https://dsh.example.com`，重启 DSH。

安全要求：

- frps 的 7000 端口只对电脑 IP 开放（云安全组/防火墙）。
- 公网只开 443；15385 不要暴露。
- 国内 VPS 用 80/443 域名通常需要备案；香港/海外 VPS 可避开但延迟更高。

---

## 7. 端口固定（建议）

桌面端端口可能随启动变化。固定它：

```yaml
- id: webserver
  config:
    host: 127.0.0.1
    port: 53985
    compression: gzip
    compressionLevel: 1
    compressionThresholdBytes: 1024
```

> patch 会整体替换该插件的 `config`，不要漏字段。改完重启。
>
> 需要手机在同一 Wi-Fi 直连时，把 `host` 改成电脑的具体 LAN IP（不能写 `0.0.0.0`），并同步修改 `web-runtime.publicUrl`；详见 [lan-setup.md](lan-setup.md)。

---

## 8. 常见错误

| 现象 | 原因 | 处理 |
|---|---|---|
| `/mobile/hello` 404 | 插件没加载 | 检查 patch 行、`corepack pnpm list @dsh-mobile/bridge`、DSH 日志 |
| `pairing.json` 不存在 | 插件启动失败 / `DSH_HOME` 不对 | 看日志；确认 `DSH_HOME`；确认目录可写 |
| 403 | Host/Origin 未信任 | 配 `trustedHosts`，或反代 `proxy_set_header Host 127.0.0.1:53985` |
| 401 | Cookie 没拿到/过期/跨 Host | 确认 tokenized URL 与 API 同 Host；重新配对 |
| 410 | code 过期或已使用 | 重启 DSH 生成新 code |
| 429 | 配对尝试过多 | 等 1 分钟；检查是否脚本循环 |
| WebSocket 失败 | 反代没升级连接 | 加 `Upgrade`/`Connection`，`proxy_read_timeout` |
| TLS 证书错误 | 自签/链不完整 | 用受信任证书；不要在生产关校验 |
| 端口变了 | 未固定 | 见 §7；bridge 的 `hello` 会报告实际端口 |

---

## 9. 回滚

```powershell
cd $HOME\.dsh\profiles\desktop

# 1) 删除 cordis.patch.yml 里 dsh-mobile-bridge 的 insert 块
# 2) 移除依赖
corepack pnpm remove @dsh-mobile/bridge

# 3) 重启 DSH
```

或者恢复安装脚本生成的 `cordis.patch.yml.bak-*` 和 `package.json.bak-*`，再 `corepack pnpm install`。

---

## 10. 安全说明

- `/mobile/pair` 是未认证路由，但 code 是 64 位随机、一次性、默认 120 秒、按 IP 限速；仍然必须走 TLS。
- `pairing.json` 含 code，不要提交到 git、不要发群里。
- 不要把 `53985` 直接暴露到公网；用 Tailscale 或 frp + TLS。
- Cookie 默认 30 天；设备丢失时删除 `$DSH_HOME/.credentials.yaml` 里的 `client-connection/browser-session` 记录并重启，可全局撤销。
- M0 的 bridge 还没有设备列表/撤销 UI；这些在 M3 做。
