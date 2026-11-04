# DSH Mobile 协议笔记

基于本机 DSH `0.2.1-rc.3` 的实测与包内实现整理。仅记录移动端会用到的部分。

---

## 1. 认证

### 1.1 启动 token

DSH 每次进程启动生成随机 launch token。带 token 的 URL：

```text
https://<host>/?token=<launch-token>
```

`GET` 该 URL：

- 校验 token；
- `303` 重定向到 `./`；
- `Set-Cookie` 写入浏览器 Cookie。

Cookie 形式：

```text
dsh-auth-<base64url(sha256(authority))>=v1.<payload>.<sig>;
Max-Age=2592000; Path=/; HttpOnly; SameSite=Strict
```

- 默认 30 天；签名密钥持久化在 `$DSH_HOME/.credentials.yaml` 的 `client-connection/browser-session`，桌面重启后仍有效。
- `Secure` 只在 DSH 自己的 listener 是 HTTPS 时加；TLS 在反代终止时没有 `Secure`，但仍可用。
- `authority` 来自请求的 Host；反代必须保证 token 请求和后续 API 请求的 Host 一致（改写或保留都行，但要一致）。

### 1.2 API Cookie（另一种方式）

```text
GET /connect?token=<launch-token>&interactions=answer|none
```

返回 API Cookie（version 2），Peer 与 WebSocket 绑定。移动端**不使用**这种方式，因为它的生命周期与进程/Peer 绑定；用浏览器 Cookie 更稳。

### 1.3 401 与重新配对

未认证请求返回 `401`：

```text
dsh web authentication required; reopen the URL printed by dsh web.
```

处理：清除旧 Cookie → 重新走 `/mobile/pair` → 换新 tokenized URL → 重新取 Cookie。

---

## 2. RPC

### 2.1 请求

```http
POST /api/<endpoint>
Content-Type: application/json

{
  "type": "client-request",
  "rpcId": "<uuid>",
  "method": "<endpoint>",
  "payload": { "args": { "_request": { } } }
}
```

> `payload` 必须**恰好**有一个 `args` 字段，且 `args` 是普通对象；直接写 `{}` 会得到 `gateway/internal: Remote payload must contain exactly one plain-object args field`。
>
> `args` 里是**具名参数**。很多 controller 方法只有一个 `_request` 参数，例如 `session/list` 的 `args` 是 `{"_request":{"cursor":"..."}}`；空请求也要写 `{"_request":{}}`，否则会得到 `gateway/arguments-invalid: missing "_request"`。
>
> 每个 endpoint 的准确参数形状见对应包的 `lib/typert.host.js` 里的 `parameters` 描述（例如 `dsh-api-session-controller`），或直接复用该包生成的 Remote client 类型。

### 2.2 响应

成功：

```json
{
  "type": "server-response",
  "rpcId": "<uuid>",
  "result": { "ok": true, "value": {} }
}
```

失败：

```json
{
  "type": "server-response",
  "rpcId": "<uuid>",
  "result": {
    "ok": false,
    "error": { "code": "...", "message": "...", "details": {} }
  }
}
```

`rpcId` 必须原样回传；并发请求靠它匹配。

### 2.3 常用 endpoint

| 用途 | endpoint |
|---|---|
| 会话列表 | `session/list` |
| 新建/分支/重命名/归档 | `session/create`、`session/fork`、`session/rename`、`session/archive` |
| 历史分页 | `session/page` |
| 订阅/流 | `session/follow` |
| 发送/取消/排队 | `session/prompt`、`session/cancel`、`session/updateQueue` |
| 模型目录/选择 | `session/modelCatalog`、`session/selectModel`、`model/selection` |
| 工作区 | `workspace/list`、`workspace/create`、`workspace/rename`、`workspace/reorder` |
| 文件（只读） | `workspaceFiles/list`、`read`、`readBytes`、`stat`、`changes` |
| 终端 | `terminal/*` |
| 设置/凭据 | `settings/*`、`credentials/*` |
| 插件 | `pluginManager/*`、`pluginMarket/*`、`pluginInventory/list` |
| 审批 | `approval/request`（事件） |
| 任务/定时/目标 | `job/*`、`schedule/*`、`goals/*` |

> `workspaceFiles` 没有写操作；手机端不做直接编辑。

---

## 3. 流式通道

```text
WSS /api/remote.mux
```

### 3.1 客户端帧

```json
{"type":"open","streamId":"<uuid>","endpoint":"$events","payload":{}}
{"type":"item","streamId":"<uuid>","item":{}}
{"type":"end","streamId":"<uuid>"}
{"type":"cancel","streamId":"<uuid>"}
```

### 3.2 服务端帧

```json
{"type":"item","streamId":"<uuid>","item":{}}
{"type":"end","streamId":"<uuid>"}
{"type":"error","streamId":"<uuid>","error":{"code":"...","message":"..."}}
```

`streamId` 由客户端生成，用来复用/取消。

---

## 4. `$events`

打开：

```json
{"type":"open","streamId":"<uuid>","endpoint":"$events","payload":{}}
```

第一条 `item`：

```json
{"type":"ready","clientId":"...","host":{"home":"C:\\\\Users\\\\..."}}
```

后续：

```json
{"type":"emit","event":"api-session/status","args":[...]}
{"type":"waterfall","event":"approval/request","eventId":"...","agentId":"...","request":{}}
{"type":"cancel","eventId":"..."}
```

### 4.1 审批回传

```http
POST /api/$events/result
Content-Type: application/json

{
  "type": "client-request",
  "rpcId": "<uuid>",
  "method": "$events/result",
  "payload": {
    "clientId": "<ready.clientId>",
    "eventId": "<waterfall.eventId>",
    "outcome": "allow" | "deny"
  }
}
```

M0–M2 不做原生审批；点通知打开 SPA，由 SPA 回答。

---

## 5. 关键事件

| 事件 | 用途 |
|---|---|
| `api-session/status` | running/idle，用于“任务完成” |
| `api-session/error` | 任务出错 |
| `api-session/activity` | 活动状态 |
| `api-session/added` / `removed` | 会话增删 |
| `approval/request` | 审批瀑布 |
| `job/*` | 后台任务 |
| `schedule/*` | 定时任务 |

---

## 6. 移动端实现要点

1. **Cookie 存储**：用 OkHttp `CookieJar` 或 WebView `CookieManager`；两者要同步，避免“HTTP 能调、WebView 没登录”。
2. **Cookie 名称**：不要硬编码；从 `Set-Cookie` 读取 `name=value`。
3. **Host 一致性**：token 请求、API 请求、WebSocket 必须走同一个 Host；反代不要在不同 location 改写不同 Host。
4. **WebSocket**：OkHttp 支持；注意 `Authorization` 不用，靠 Cookie；反代要升级连接。
5. **重连**：指数退避 + 网络变化；重连后重新 `open $events`。
6. **401**：清除 Cookie，提示重新配对；不要无限重试。
7. **审批**：`waterfall` 的 `eventId` 只对当前 `clientId` 有效；重连后旧 eventId 可能失效。
8. **不要**把 launch token 写进日志/崩溃报告。

---

## 7. 参考

- `@deepseek-ai/dsh-client-connection`：Cookie、`authenticatedUrl`、`/connect`。
- `@deepseek-ai/dsh-host-webserver`：路由、`getOrigin()`、监听生命周期。
- `@deepseek-ai/dsh-api-gateway`：`/api` 分发、错误映射。
- `@deepseek-ai/dsh-web-app`：SPA、`/api/remote.mux`。
