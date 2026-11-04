# 局域网 / Tailscale / frp + VPS 三路连接

DSH Mobile 可以同时保存三种连接方式，并在 App 的「连接管理」里一键切换：

| 方式 | 典型地址 | 适用场景 | 特点 |
| --- | --- | --- | --- |
| 局域网 | `http://192.168.1.10:53985` | 手机和电脑在同一 Wi-Fi | 延迟最低；只在家里/办公室可用 |
| Tailscale | `https://<machine>.<tailnet>.ts.net` | 在外面用手机控制家里电脑 | 推荐；自动加密，不需要公网 IP |
| frp + VPS | `https://dsh.example.com` | Tailscale 不可用时的备用 | 依赖 VPS；需要自己维护 TLS 和 frp |

三种方式使用**不同的 Host**，所以浏览器 Cookie 也分开。每种连接第一次使用时都要配对一次；之后切换只换地址，不需要重新配对。

## 1. 局域网：先让 DSH 绑定 LAN IP

DSH 的 `dsh-host-webserver` **明确拒绝 `0.0.0.0`**，理由是避免把远程代码执行暴露到所有网卡。必须绑定一个具体的局域网 IP。

先查电脑的 LAN IPv4：

```powershell
Get-NetIPAddress -AddressFamily IPv4 |
  Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
  Select-Object InterfaceAlias, IPAddress, PrefixLength
```

假设是 `192.168.1.10`，在
`C:\Users\<you>\.dsh\profiles\desktop\cordis.patch.yml`
追加/合并这些覆盖项：

```yaml
- id: webserver
  config:
    host: '192.168.1.10'
    port: 53985

- id: web-runtime
  config:
    publicUrl: 'http://192.168.1.10:53985'

- id: connection
  config:
    trustedHosts:
      - '192.168.1.10:53985'
      - '<machine>.<tailnet>.ts.net'
      - 'dsh.example.com'
```

注意：

- 改成 LAN IP 后，`http://127.0.0.1:53985` 不再监听；桌面浏览器也要改用 `http://192.168.1.10:53985`。
- 建议在路由器里给电脑做 DHCP 固定租约，否则 IP 变了要改配置。
- `trustedHosts` 只写 `host` 或 `host:port`，不要带 `http://`。
- 只允许私有网段访问 53985，不要把端口映射到公网。

Windows 防火墙示例（管理员 PowerShell，按实际网段修改）：

```powershell
New-NetFirewallRule `
  -DisplayName "DSH Mobile LAN" `
  -Direction Inbound `
  -Protocol TCP `
  -LocalPort 53985 `
  -RemoteAddress 192.168.1.0/24 `
  -Action Allow
```

重启 DSH 后，在电脑上打开：

```text
http://192.168.1.10:53985/mobile/pair
```

配对页只允许本机访问：它接受回环请求，或“源地址 = 目标地址”的本机 LAN 请求；手机直接访问这个页面会得到 404。这是故意的，避免把一次性登录码暴露给整个局域网。

## 2. Tailscale

电脑和手机都安装 Tailscale，并登录同一个 tailnet。假设电脑的 tailnet 域名是
`machine.tailnet.ts.net`。

如果 DSH 绑定的是 LAN IP（推荐，这样局域网和 Tailscale 可以同时用）：

```powershell
tailscale serve --bg http://192.168.1.10:53985
```

如果 DSH 绑定的是 `127.0.0.1`：

```powershell
tailscale serve --bg http://127.0.0.1:53985
```

Tailscale 会给出 HTTPS 地址，例如：

```text
https://machine.tailnet.ts.net
```

把这个域名加入 `connection.trustedHosts`，并把 bridge 的 `endpoints` 配上：

```yaml
- insert:
    - id: dsh-mobile-bridge
      name: '@dsh-mobile/bridge'
      config:
        publicUrl: ''
        endpoints:
          - id: lan
            label: 局域网
            url: 'http://192.168.1.10:53985'
            kind: lan
          - id: tailscale
            label: Tailscale
            url: 'https://machine.tailnet.ts.net'
            kind: tailscale
          - id: frp
            label: frp + VPS
            url: 'https://dsh.example.com'
            kind: frp
        pairingTtlSeconds: 120
```

`endpoints` 是给配对页/二维码用的**允许列表**。App 在配对时会把这个 base 发给 bridge；bridge 只接受列表里的地址，避免被用来给任意 Host 签发登录 URL。

## 3. frp + VPS

frp 只负责把 VPS 的流量转发到家里的 DSH；HTTPS 最好由 VPS 上的 Nginx/Caddy 终止。

`frpc.ini` 示例：

```ini
[common]
server_addr = vps.example.com
server_port = 7000
token = <strong-token>

[dsh-mobile]
type = tcp
local_ip = 192.168.1.10
local_port = 53985
remote_port = 7001
```

VPS 上 Nginx 反向代理到 `127.0.0.1:7001`，并配置证书：

```nginx
server {
    listen 443 ssl http2;
    server_name dsh.example.com;

    ssl_certificate     /etc/letsencrypt/live/dsh.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/dsh.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:7001;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
    }
}
```

把 `dsh.example.com` 加入 `connection.trustedHosts` 和 bridge 的 `endpoints`。

安全要求：

- VPS 只开放 443；frp 控制端口 7000 用强 token，并限制来源 IP。
- 不要把 53985 直接映射到公网。
- 配对页通过 frp 会被 bridge 拒绝（Host 不是 bind 地址），这是有意的；请在局域网内打开配对页扫码，或手动输入配对码。
- frp 是备用通道；优先 Tailscale。

## 4. App 端：保存三套连接并切换

1. 打开 DSH Mobile →「连接管理」。
2. 默认已有「局域网」「Tailscale」「frp + VPS」三个槽位；点「编辑」填地址。
3. 点某个连接的「使用」；如果该 Host 还没有 Cookie，会跳到配对页并预填地址。
4. 在电脑上打开配对页，扫描对应连接的二维码；配对成功后该连接变为「当前连接」。
5. 对另外两种连接重复配对。
6. 之后在「连接管理」点「使用」即可切换；WebView 会加载新地址，事件前台服务也会切到新地址。

「测试」按钮只检查 `/mobile/hello` 和 `session/list`，不会切换当前连接。

## 5. 常见问题

**手机打开是 403 / Host not allowed**

把手机实际使用的 Host（含端口，如果非默认端口）加入 `connection.trustedHosts`，然后重启 DSH。Tailscale 用 tailnet 域名，frp 用 HTTPS 域名。

**手机打开是连接超时**

- 确认 DSH 绑定的是 LAN IP，而不是 `127.0.0.1`。
- 确认手机和电脑在同一网段，防火墙放行 53985。
- Tailscale 下用 `tailscale status` 确认两端在线；frp 下确认 frpc 和 Nginx 都在运行。

**配对页 404**

配对页只允许本机访问。请用电脑上的 `http://<bind-ip>:53985/mobile/pair`，不要用手机或公网域名打开。如果 DSH 绑定 `127.0.0.1`，用 `http://127.0.0.1:53985/mobile/pair`。

**切换连接后要求重新配对**

不同 Host 的 Cookie 不共享，这是浏览器的同源策略。每种连接配对一次即可；如果 Cookie 过期（默认 30 天），重新配对。

**Tailscale 下 WebSocket 失败**

确认 `tailscale serve` 仍在运行，且 `trustedHosts` 包含 tailnet 域名；检查 Android 前台服务的通知权限。
