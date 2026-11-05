# @dsh-mobile/client-ui

DSH Web 客户端的**手机移动皮肤**，由 DSH Mobile 的 WebView 加载。

第一版只做 CSS 布局层，不修改 DSH 业务逻辑：

- 手机宽度下让会话内容使用完整宽度
- 收紧 header / tabs / composer 留白
- 隐藏桌面 turn rail
- 加大菜单行和触控目标
- 限制菜单最大宽度
- 标记 `html[data-dsh-mobile-ui="1"]`，方便后续加抽屉 / Sheet

## 安装

```powershell
cd $HOME\.dsh\profiles\desktop
pnpm add file:D:\project\DSH-Mobile\packages\client-ui
```

然后在桌面 profile 的 `cordis.patch.yml` 里加入：

```yaml
- insert:
    - id: dsh-mobile-client-ui
      name: '@dsh-mobile/client-ui'
```

重启 DSH。

## 回滚

删除 `cordis.patch.yml` 里的 `dsh-mobile-client-ui` 条目，或从 profile 移除包，然后重启 DSH。

## 后续

- 移动抽屉（替代 56px 左侧 rail）
- 全屏 Sheet（文件 / 终端）
- 通知深链
- 横屏 / 平板断点
