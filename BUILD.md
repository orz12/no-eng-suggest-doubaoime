# 构建与安装说明

本文说明 Debug 与带签名 Release 的可复现构建、校验和安装流程。

## 环境要求

| 项 | 本次成功时使用的版本 / 路径 | 说明 |
|----|------------------------------|------|
| JDK | 17 或 21 | AGP 最低要求 JDK 17；需设置 `JAVA_HOME` |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk` | 需有 `platforms;android-36`、platform-tools 与 build-tools |
| Gradle Wrapper | 8.11.1（仓库自带 `gradlew.bat`） | 勿强依赖本机全局 Gradle |
| AGP | 8.9.1（见 `gradle/libs.versions.toml`） | Android API 36 的官方最低支持版本 |
| libxposed API | `io.github.libxposed:api:102.0.0` | `compileOnly`，运行时由 LSPosed 提供 |
| libxposed Service | `io.github.libxposed:service:101.0.0` | 模块应用检测 LSPosed 服务与作用域 |
| adb | SDK `platform-tools` | 手机需已授权调试 |

## 一次性准备

### 1. `local.properties`（勿提交）

仓库已 `.gitignore` 忽略该文件。在项目根目录创建：

```properties
sdk.dir=C:/Users/你的用户名/AppData/Local/Android/Sdk
```

Windows 下路径用正斜杠。也可用环境变量 `ANDROID_HOME` / `ANDROID_SDK_ROOT`，但 AGP 仍推荐本文件。

PowerShell 一键生成示例：

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk" -replace '\\','/'
"sdk.dir=$sdk" | Set-Content -Path .\local.properties -Encoding ASCII
```

### 2. 确认设备在线

```powershell
adb devices
```

应看到 `device` 状态（无线调试亦可）。

## Debug 构建

在 `doubaoime-lsposed` 根目录执行：

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"

.\scripts\build-and-install.ps1 -BuildOnly
```

成功标志：输出 `BUILD SUCCESSFUL`，产物：

```text
app\build\outputs\apk\debug\app-debug.apk
```

Debug 构建并安装：

```powershell
.\scripts\build-and-install.ps1
```

## Release 签名配置

Release 不使用源码中的固定密码，也不会自动创建签名。将模板复制为本地配置：

```powershell
Copy-Item .\keystore.properties.example .\keystore.properties
```

编辑 `keystore.properties`：

```properties
storeFile=C:/Users/你的用户名/keys/release.keystore
storePassword=你的密钥库密码
keyAlias=你的密钥别名
keyPassword=你的私钥密码
```

`keystore.properties` 已加入 `.gitignore`，密钥文件也不应放进仓库。
`storeFile` 可使用绝对路径或相对于项目根目录的路径。

CI 或临时构建也可不创建文件，改用四个环境变量：

```powershell
$env:DOUBAO_SIGNING_STORE_FILE = "C:\keys\release.keystore"
$env:DOUBAO_SIGNING_STORE_PASSWORD = "store password"
$env:DOUBAO_SIGNING_KEY_ALIAS = "release"
$env:DOUBAO_SIGNING_KEY_PASSWORD = "key password"
```

四项必须同时提供；配置不完整时 Gradle 会直接报错。

## Release 构建、校验与安装

推荐使用统一脚本：

```powershell
.\scripts\build-and-install.ps1 -BuildType Release
```

仅生成并校验签名 APK：

```powershell
.\scripts\build-and-install.ps1 -BuildType Release -BuildOnly
```

脚本执行 `assembleRelease`，随后通过最新可用 Build Tools 的 `apksigner`
验证证书，再执行 `adb install -r`。产物路径：

```text
app\build\outputs\apk\release\app-release.apk
```

如果连接了多台设备，必须指定序列号：

```powershell
.\scripts\build-and-install.ps1 -BuildType Release -DeviceSerial 设备序列号
```

签名证书必须与手机上已安装的同包名应用一致，否则 Android 会返回
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`。脚本不会自动卸载旧应用，避免丢失设置。

## 手工安装与版本验证

```powershell
adb install -r .\app\build\outputs\apk\release\app-release.apk
```

验证已安装版本：

```powershell
adb shell dumpsys package com.doubao.ime.noensuggest | findstr versionName
```

## 安装后启用（LSPosed）

1. 打开 LSPosed 管理器，启用模块「豆包输入法英文直输」
2. 作用域应用推荐列表，勾选 `com.bytedance.android.doubaoime`
3. 强制停止豆包输入法，或重启系统
4. 需要诊断时在模块应用“日志”页开启日志，再过滤 `DoubaoNoEnSuggest`

## 校验 APK 内是否带上现代模块元数据

现代 API **不再**依赖 `assets/xposed_init` 与 Manifest 里的 `xposedmodule` metadata，而是靠 APK 内：

- `META-INF/xposed/module.prop`
- `META-INF/xposed/java_init.list`
- `META-INF/xposed/scope.list`

PowerShell 抽查：

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apk = ".\app\build\outputs\apk\release\app-release.apk"
$zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $apk))
$zip.Entries |
  Where-Object { $_.FullName -like "META-INF/xposed*" } |
  ForEach-Object { $_.FullName }
$zip.Dispose()
```

应能看到上述三个文件。

## 踩坑与经验（务必保留）

### 1. Maven Central 直接访问可能 403

本机首次构建时，访问 `https://repo.maven.apache.org/...` 返回 **403 Forbidden**，导致 AGP / Kotlin / ASM 等依赖全部解析失败。

**处理：** 已在 `settings.gradle.kts` 的 `pluginManagement` 与 `dependencyResolutionManagement` 中优先加入阿里云镜像：

- `https://maven.aliyun.com/repository/google`
- `https://maven.aliyun.com/repository/public`
- `https://maven.aliyun.com/repository/gradle-plugin`（仅插件侧）

后面再回退到 `google()` / `mavenCentral()`。若镜像异常，可临时 `--refresh-dependencies` 重试：

```powershell
.\scripts\build-and-install.ps1 -BuildOnly -RefreshDependencies
```

### 2. 不要在模块代码里硬依赖 `androidx.annotation`（除非显式加依赖）

入口类若 `import androidx.annotation.NonNull` / `RequiresApi`，在仅 `compileOnly(libxposed-api)`、未声明 annotation 依赖时会编译失败。

**处理：** 壳代码去掉这些注解即可；或自行增加：

```kotlin
implementation("androidx.annotation:annotation:1.9.1")
```

### 3. API 选型固定为 102

- 依赖：`compileOnly("io.github.libxposed:api:102.0.0")`
- `module.prop`：`minApiVersion=102`、`targetApiVersion=102`
- 入口继承：`io.github.libxposed.api.XposedModule`
- 官方参考：[Develop Xposed Modules Using Modern Xposed API](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)、[libxposed/example](https://github.com/libxposed/example)

旧式 `assets/xposed_init` + `de.robv.android.xposed:api:82` **不是**本项目路径。

### 4. `staticScope=true` 的含义

`module.prop` 中 `staticScope=true` 表示作用域以模块声明为准，用户不宜随便扩到推荐列表之外。当前 `scope.list` 仅一行：

```text
com.bytedance.android.doubaoime
```

### 5. 网络与首次 Wrapper

首次执行会下载 Gradle 8.11.1 发行包；若 `services.gradle.org` 较慢，需等待或配置本地已有的 Gradle 发行缓存（`%USERPROFILE%\.gradle\wrapper\dists`）。

### 6. 清理重建

```powershell
.\gradlew.bat clean assembleDebug --no-daemon
```

### 7. `CXX5304` SDK XML 或平台目录警告

如果 CMake 报告只支持 SDK XML version 3、实际遇到 version 4，说明 Android SDK
Command-line Tools、CMake 与 Android Studio 的安装年代不一致；请通过 SDK Manager
更新 Command-line Tools 和 CMake。若同时提示某个平台目录名称与 package id 不一致，
应在 SDK Manager 中卸载并重新安装该平台。此类本机 SDK 警告不由项目脚本生成，
但建议清理后再建立正式发布环境。

## 目录速查

```text
doubaoime-lsposed/
  README.md                 # 概览 + 快速命令
  BUILD.md                  # 本文：完整命令与经验
  scripts/build-and-install.ps1
  keystore.properties.example
  settings.gradle.kts       # 含阿里云镜像
  gradle/libs.versions.toml # AGP / libxposed 版本
  app/src/main/java/.../ModuleMain.java
  app/src/main/resources/META-INF/xposed/
```

## 当前成功基线

- 模块版本名：`0.9.16-password-dedup`（`versionCode=55`）
- `compileSdk=36`，`targetSdk=35`，仅构建 `arm64-v8a`
- Debug 与带外部签名的 Release 均由同一脚本支持
- Release 安装前执行 `apksigner verify --verbose --print-certs`
- 包名：`com.doubao.ime.noensuggest`

Native 输入链、ShadowHook 模式约束和英文隐藏预编辑问题的完整排查记录见
[REVERSE_ENGINEERING.md](./REVERSE_ENGINEERING.md)。该文档中的回归约束属于发布基线，
修改 Hook 模式或代理调用方式时必须同步检查。
