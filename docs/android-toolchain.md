# Android 工具链（Windows）

当前开发机尚未安装 JDK / Android SDK。M1 开始前需要补齐；以下是可复现的最小安装步骤。

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
- 包名建议 `com.dshmobile.app`。

---

## 5. 本机现状

```text
java:   未安装
adb:    未安装
gradle: 未安装
```

安装完成后，在项目根目录执行：

```powershell
cd android
.\gradlew.bat assembleDebug
```

> 如果使用 Android Studio，直接打开 `android/` 目录即可；Gradle wrapper 由 Android Studio 生成/同步。
