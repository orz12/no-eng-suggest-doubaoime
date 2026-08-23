# 豆包输入法英文直输（LSPosed）

基于 [libxposed API 102](https://github.com/libxposed/api) 和 ShadowHook 的
LSPosed 模块。

- 包名：`com.doubao.ime.noensuggest`
- 推荐作用域：`com.bytedance.android.doubaoime`
- 目标：英文输入下去除候选与联想（完整「单字母直上屏」）
- 当前：`0.9.16-password-dedup`（`versionCode=55`）
- 文件日志：`/sdcard/Download/DoubaoNoEnSuggest.log`
- 日志默认关闭，可在模块应用的“日志”页启用

更完整的编译命令与踩坑记录见 [BUILD.md](./BUILD.md)。英文隐藏预编辑的逆向链路、
日志证据、已排除假设和回归约束见 [REVERSE_ENGINEERING.md](./REVERSE_ENGINEERING.md)。

## 快速构建与安装

在项目根目录 `doubaoime-lsposed` 下（PowerShell）：

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"   # 按本机 JDK 路径调整
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"

# 首次需 local.properties（见 BUILD.md）
.\scripts\build-and-install.ps1
```

仅构建 Debug：

```powershell
.\scripts\build-and-install.ps1 -BuildOnly
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## Release 构建与安装

Release 必须由用户自己的密钥签名。复制
`keystore.properties.example` 为 `keystore.properties`，填写本机密钥信息后执行：

```powershell
.\scripts\build-and-install.ps1 -BuildType Release
```

脚本会依次构建、使用 `apksigner` 校验签名并通过 adb 覆盖安装。Release 产物：
`app/build/outputs/apk/release/app-release.apk`。

不要提交 `keystore.properties` 或密钥文件。环境变量配置、证书兼容性和手工命令见
[BUILD.md](./BUILD.md)。

## 启用

1. LSPosed 管理器 → 启用「豆包输入法英文直输」
2. 作用域勾选推荐项 `com.bytedance.android.doubaoime`
3. 强停豆包输入法或重启
4. 日志过滤标签：`DoubaoNoEnSuggest`

## 模块元数据

| 文件 | 作用 |
|------|------|
| `app/src/main/resources/META-INF/xposed/module.prop` | API 102、staticScope、hot reload |
| `app/src/main/resources/META-INF/xposed/java_init.list` | 入口类 `ModuleMain` |
| `app/src/main/resources/META-INF/xposed/scope.list` | 推荐作用域 |

入口类：`com.doubao.ime.noensuggest.ModuleMain`
