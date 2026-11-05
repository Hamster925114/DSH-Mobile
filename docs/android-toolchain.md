# Android 工具链（Windows）

当前开发机已安装 Android Studio、Android SDK 35，并在 `D:\apps\jdk-17\jdk-17.0.20.1+1` 放了 JDK 17（Android Studio 自带的 JDK 25 与 Gradle 8.11.1 不兼容）。以下是可复现的最小安装步骤。

---

## 1. JDK 17

```powershell
winget install --id EclipseAdoptium.Temurin.17.JDK -e
```

验证：

```powershell
java -version
# openjdk version "17.x.x" ...
```

如果 `winget` 不可用，从 <https://adoptium.net/> 下载 `.msi` 安装，勾选 “Set JAVA_HOME”。

---

## 2. Android SDK

### 方案 A：Android Studio（推荐）

```powershell
winget install --id Google.AndroidStudio -e
```

首次启动按向导安装：

- Android SDK Platform 35
- Android SDK Build-Tools 35.0.0
- Android SDK Platform-Tools
- Android Emulator（可选）

### 方案 B：Command-line Tools

1. 下载 <https://developer.android.com/studio#command-tools> 的 Windows zip；
2. 解压到 `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest`；
3. 设置环境变量：

```powershell
[Environment]::SetEnvironmentVariable('ANDROID_HOME', "$env:LOCALAPPDATA\Android\Sdk", 'User')
[Environment]::SetEnvironmentVariable('ANDROID_SDK_ROOT', "$env:LOCALAPPDATA\Android\Sdk", 'User')
$env:Path += ";$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin"
```

4. 安装包：

```powershell
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

---

## 3. 验证

```powershell
java -version
adb version
sdkmanager --list | Select-String "platforms;android-35"
```

`adb devices` 能列出真机或模拟器即可。

---

## 4. 工程约定

- Kotlin 2.x + Jetpack Compose（Material 3）。
- `minSdk 26`（Android 8.0），`targetSdk 35`。
- 网络：OkHttp 4.x + kotlinx.serialization；WebSocket 用 OkHttp。
- 二维码：ZXing core（不依赖 GMS）。
- 安全：Android Keystore 存 Cookie/证书；`EncryptedSharedPreferences` 或 DataStore + Keystore。
- 不使用 Firebase / GMS；通知用前台服务。
- 包名已确定：`com.dshmobile.app`；应用名 `DSH Mobile`。

---

## 5. 本机现状

```text
java:   未安装（Android Studio 自带 JBR 17 即可）
adb:    未安装（随 SDK Platform-Tools）
gradle: 未安装（仓库已包含 Gradle 8.11.1 wrapper）
```

仓库已包含 `android/gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar`。安装 Android Studio + SDK 35 后，用 Android Studio 打开 `android/` 目录，或：

```powershell
cd android
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

> 必须在 Android Studio 的 **Gradle JDK** 里选 JDK 17/21，不能选 bundled JDK 25：Gradle 8.11.1 的 Kotlin DSL 会报 `IllegalArgumentException: 25.0.4`。首次 Sync 会下载 Gradle 8.11.1 与依赖；`settings.gradle.kts` 已优先使用阿里云 Maven 镜像，GitHub 源码包超时通常不影响命令行构建。
