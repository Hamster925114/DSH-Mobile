# 发布流程（M4）

## 1. 版本号

- `android/app/build.gradle.kts`：`versionCode` + `versionName`
- `packages/bridge/package.json` / `packages/client-ui/package.json`
- `CHANGELOG.md`

当前：`0.2.0` / `versionCode 2`。

## 2. 生成签名密钥（只做一次，离线保管）

```powershell
keytool -genkeypair -v `
  -keystore D:\secure\dsh-mobile-release.jks `
  -alias dsh-mobile `
  -keyalg RSA -keysize 4096 -validity 10000
```

不要提交 keystore、密码或 `local.properties`。仓库 `.gitignore` 已忽略 `*.jks` / `*.keystore`。

## 3. 本地构建签名 release

```powershell
$env:JAVA_HOME='D:\apps\jdk-17\jdk-17.0.20.1+1'
$env:DSH_MOBILE_KEYSTORE='D:\secure\dsh-mobile-release.jks'
$env:DSH_MOBILE_KEYSTORE_PASSWORD='...'
$env:DSH_MOBILE_KEY_ALIAS='dsh-mobile'
$env:DSH_MOBILE_KEY_PASSWORD='...'

cd D:\project\DSH-Mobile\android
.\gradlew.bat :app:assembleRelease
```

输出：`android/app/build/outputs/apk/release/app-release.apk`。

未设置 `DSH_MOBILE_KEYSTORE` 时，Gradle 仍会产出 `app-release-unsigned.apk`，供 CI 做编译验证，但不能安装发布。

## 4. 校验

```powershell
& "$env:JAVA_HOME\bin\apksigner.bat" verify --print-certs `
  android\app\build\outputs\apk\release\app-release.apk
```

同时检查：

- `versionName` / `versionCode` 正确
- 安装到干净设备后能配对
- 设备管理/审计页可打开
- 文件预览下载、blob 导出可落盘
- 通知深链、亮暗主题、文件/终端面板正常

## 5. CI

`.github/workflows/ci.yml`：

- Bridge：Node 22 + pnpm 9，`pnpm test`（27 个测试）
- Android：JDK 17，debug 构建、JVM 测试、`lintDebug`、unsigned release
- 手动触发 `workflow_dispatch` 时，若配置以下 secrets，可构建签名 release：
  - `DSH_MOBILE_KEYSTORE_BASE64`
  - `DSH_MOBILE_KEYSTORE_PASSWORD`
  - `DSH_MOBILE_KEY_ALIAS`
  - `DSH_MOBILE_KEY_PASSWORD`

生成 base64：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('D:\secure\dsh-mobile-release.jks'))
```

## 6. 发布检查清单

- [ ] 版本号/CHANGELOG 更新
- [ ] Bridge 27 个测试通过
- [ ] Android JVM 测试 + lint + release 构建通过
- [ ] 签名 APK 在干净设备安装并完成一次配对
- [ ] 设备令牌撤销后下一次请求 401
- [ ] 审计日志记录 pair/login/revoke
- [ ] 文件预览下载和 blob 导出成功
- [ ] 隐私说明与已知限制随包/下载页发布
- [ ] 备份签名密钥，记录证书指纹

## 7. 当前未做

- R8/资源压缩（避免 Compose/ZXing 反射问题，后续单独验证）
- 正式下载站/应用商店上架（当前提供 docs/download.md 与构建产物）
- 崩溃上报（当前无第三方 SDK）
- 真机 instrumented smoke 已通过；CI 只编译 `assembleDebugAndroidTest`（无模拟器）
