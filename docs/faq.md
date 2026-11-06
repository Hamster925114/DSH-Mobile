# FAQ / 常见问题

## 配对

**配对页 404 / 只有电脑能打开？**
配对页只允许本机 loopback 或本机 LAN 源地址访问。用电脑打开 `http://127.0.0.1:53985/mobile/pair`，不要用手机或公网域名打开。

**配对码过期？**
默认 120 秒，一次性。刷新桌面配对页会生成新码；已用过的码不能重放。

**手机显示 401「DSH Mobile 已锁定」？**
M3 设备门禁已启用，手机没有设备令牌。安装 0.2.0+ APK，重新扫一次配对码。旧版 Cookie 无法自动迁移。

## 连接

**`127.0.0.1 拒绝连接` / `ERR_CONNECTION_REFUSED`？**
DSH 端口变了或 Serve 指错端口：

```powershell
tailscale serve status
tailscale serve --bg http://127.0.0.1:53986
```

**DSH 重启后手机打不开？**
确认 DSH 仍固定 `127.0.0.1:53985`，bridge 设备代理监听 `53986`。`/mobile/hello` 应返回 200，未配对访问 `/` 应返回 401。

## 页面/插件

**Failed to load plugins？**
确认设备代理已更新到 0.2.0；该问题由代理重编码 `plugins/??...` 查询导致，已在 0.2.0 修复。强制停止 App 重开。

**文件/终端点了没反应？**
0.2.0 通过 `sidebarRight.openTab()` 打开面板，并修复了折叠右栏隐藏全屏面板的问题。若仍无反应，确认 `@dsh-mobile/client-ui` 已安装并重载页面。

**抽屉只有图标 / 点图标就收起？**
0.2.0 会在打开抽屉时展开 DSH 完整侧栏，显示文字；点图标不再自动关闭，右缘 `×` 或遮罩关闭。

## 文件

**找不到下载选项？**
文件面板打开一个文件后，预览头部右上角有「下载」。DSH 导出的 PDF/会话包是 `blob:`，由分块桥保存到公共 `Download/`。

**上传失败？**
文件选择后先复制到 App `shared/` 再交给 WebView；若仍失败，检查 DSH 连接是否反复断开。

## 通知

**收不到通知？**
Android 13+ 需要通知权限；设置里开启“后台事件通知”。App 被强停/系统回收后没有 FCM 推送，这是产品限制。

**点通知没进对应会话？**
DSH 页面冷启动可能 20–30 秒，App 最多等 60 秒；超时会提示手动打开。

## 设备管理/审计

**怎么撤销丢失的手机？**
电脑打开 `http://127.0.0.1:53985/mobile/devices/ui`，点“撤销”。撤销后该设备下一次 HTTP/WebSocket 请求返回 401。

**审计日志在哪里？**
App：设置 → 设备管理 → 审计日志；桌面：设备管理页下方；文件：`$DSH_HOME/mobile/audit.jsonl`。

**为什么设备列表里有旧记录？**
撤销记录会保留用于审计；当前设备仍可用，除非显示“已撤销”。

## 构建

**Gradle 报 JDK 版本错误？**
用 JDK 17（`JAVA_HOME` 指向 Temurin/Android Studio JDK 17）。

**release APK 是 unsigned？**
未设置 `DSH_MOBILE_KEYSTORE` 等环境变量时只做编译验证。签名步骤见 [release.md](release.md)。
