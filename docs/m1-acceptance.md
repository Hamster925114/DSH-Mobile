# M1 真机验收记录

- 日期：2026-11-05
- 设备：vivo V2553A（`10CG7X08AS000HJ`），Android 15
- 桌面：DSH `0.2.1-rc.3`，`https://hamster.tail52bb47.ts.net`（Tailscale serve → `127.0.0.1:53985`）
- APK：`android/app/build/outputs/apk/debug/app-debug.apk`
- 测试：`22` 个 JVM 单元测试通过；bridge `14` 个测试通过

## 已通过

| 项目 | 结果 |
|---|---|
| 构建 / 安装 / 启动 | 通过 |
| Tailscale 配对、Cookie 持久化 | 通过（App 重启后仍保持登录） |
| WebView 加载 DSH SPA | 通过 |
| 连接诊断 | 8/8 通过：地址、DNS、TCP、TLS、HTTP bridge、Cookie、API、WebSocket |
| 新建会话 | 通过 |
| 发送消息 / 流式回复 | 通过（“只回复 OK”收到 `OK`，1 秒完成） |
| 切换模型 | 通过（`DeepSeek-V4.1-Flash` → `DeepSeek-V4-Pro` → 切回） |
| 文件预览 | 通过（Workspace files → `PLAN.md`） |
| 终端 | 通过（`cmd.exe` 打开，执行 `echo DSH_MOBILE_TERMINAL_OK` 返回 `DSH_MOBILE_TERMINAL_OK`） |
| 插件页面 | 通过（Installed / Plugin Market 可打开） |
| 插件启停 | 通过（`dshmarket` 开关切到启用再恢复原状态） |
| DSH 设置 | 通过（头像 → Settings，移动端上下布局） |
| 添加工作区 | 通过（目录列表、Cancel/Open 正常） |
| 原生设置 | 通过（连接、刷新、重载、通知开关、清除配对） |
| 前台服务 `$events` | 通过（`events socket open` → `events ready`） |
| 审批通知 | 通过（Read Only 会话写入触发 `waterfall approval/request`；通知栏收到「需要审批」/「DSH 正在等待你的审批」） |
| 文件选择 / 上传 | 用户真机确认通过 |
| 文件下载 | 用户真机确认通过（新版本保存到系统 Downloads） |

## 待验收

| 项目 | 说明 |
|---|---|
| 10 个完整真实任务 | 核心任务已逐项通过，仍需一次不间断的完整流程 |
| LAN / frp 切换 | 用户已决定舍弃第二条链路，不再作为 M1 阻塞项 |

## 已知限制

- 仅前台服务通知；App 被杀/强停后不保证收到。
- Android WebView 的 `100vh = 0`，App 已为菜单和 `[role="dialog"]` 注入高度修复；若 DSH 升级后类名变化，需要复查。
- DSH 移动布局仍是桌面 SPA 的窄屏折叠，M2 才做 `@dsh-mobile/client-ui` 皮肤。
