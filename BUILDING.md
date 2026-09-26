# 编译指南（Windows + Android Studio）

本 fork = fcitx5-android 上游 + QR 扫码输入法（engine 模式）。全部定制改动以
`QRSCAN` 标记，注入点清单见 `QRSCAN_注入点清单.md`。

## 一、环境依赖（一次性）

| 依赖 | 版本/位置 | 说明 |
|---|---|---|
| Android Studio | **2026.1.4.8（quail4 patch1）**，最低要能跑 AGP **9.4.1**；旧版 AS 报 `Latest supported version is AGP 9.2.0` 即为版本过低，需升级 AS 而非降级 AGP | 打开项目根目录即可 |
| JDK | 25（vfox 或 Android Studio 内嵌 JBR 17+ 均可，Gradle JVM 在 Settings → Build Tools → Gradle 里选） | 本机用 vfox 的 JDK 25 |
| Android SDK | `C:\Users\<你>\AppData\Local\Android\Sdk` | 首次启动 Setup Wizard 自动下载 |
| NDK | **28.0.13004108**（严格匹配） | SDK Manager → SDK Tools → NDK (Side by side) 勾选 Show Package Details 选此版本 |
| CMake | **3.31.6** | 同上，SDK Tools → CMake |
| SDK Platforms / Build-Tools | android-36 / 36.1.0 | 同上 |
| ECM (Extra CMake Modules) | 解压到任意目录，设置**环境变量** `ECM_DIR=<目录>/share/ECM/cmake` | KDE 官方 zip：<https://github.com/KDE/extra-cmake-modules> 下载源码解压即可（纯 cmake 脚本无需编译）；本机放在 `C:\Users\pony\.workbuddy\ecm` |
| gettext shim（Windows 必需） | `msgfmt.cmd`/`msgmerge.cmd` + `msgfmt.py` | 见下节 |

### Windows gettext shim（关键）

fcitx5 的 CMake 需要 GNU gettext 的 `msgfmt`/`msgmerge`，Windows 没有官方易用版本，
本项目用 Python shim 代替（已在仓库外，需要在新机器重建一次）：

1. 准备 `C:\Users\<你>\.workbuddy\gettext\msgfmt.py`（Python 3.10+ 可用，
   实现 .po→.mo 编译、`--desktop` 模板复制；源码可向原机器索取或按此规格重写：
   解析 .po 的 msgid/msgstr/msgctxt，写 GNU .mo（无 hash 表），支持
   `--no-hash --endianness` 与 `--desktop -d 模板 -o 输出`）。
2. 同目录放 `msgfmt.cmd` / `msgmerge.cmd`：
   ```bat
   @echo off
   python "%~dp0msgfmt.py" %*
   ```
3. 若 shim 放在**其他路径**，构建前设置环境变量（二选一，无需改代码）：
   ```
   set GETTEXT_MSGFMT_EXECUTABLE=D:\tools\gettext\msgfmt.cmd
   set GETTEXT_MSGMERGE_EXECUTABLE=D:\tools\gettext\msgmerge.cmd
   ```
   不设置则回落到默认路径 `C:/Users/pony/.workbuddy/gettext/`。

## 二、获取代码

```bash
# 方式 A：直接拷贝整个 fcitx5-android-research 目录（含 .git 与 lib/* 子模块内容）
# 方式 B：重新克隆
git clone --depth 1 <你的远端或原机路径> fcitx5-android-research
cd fcitx5-android-research
git submodule update --init --depth 1   # lib/* 子模块（fcitx5、libime 等，kenlm 为嵌套子模块）
```

## 三、Android Studio 编译步骤

1. **File → Open**，选择项目根目录 `fcitx5-android-research`（含 settings.gradle.kts）。
2. 首次同步会提示安装缺失的 SDK 组件，按提示装齐 NDK 28.0.13004108 / CMake 3.31.6 / android-36。
3. **设置环境变量**（重要，Android Studio 从启动它的 shell 继承环境）：
   - `ECM_DIR=C:/Users/<你>/.workbuddy/ecm/share/ECM/cmake`
   - （可选）`GETTEXT_MSGFMT_EXECUTABLE` / `GETTEXT_MSGMERGE_EXECUTABLE`
   - Windows：系统属性 → 环境变量 → 用户变量，设置后**重启 Android Studio**。
4. **Build → Make Project**（或 ▶ 运行 app）。首次全量 native 编译约 20~40 分钟。
5. 产物：
   - Debug：`app/build/outputs/apk/debug/*-arm64-v8a-debug.apk`（自动 debug 签名）
   - Release：`./gradlew :app:assembleRelease -PsignKeyFile=<keystore> -PsignKeyPwd=<密码> -PsignKeyAlias=<别名>`
     （签名配置读取项目属性/环境变量 SIGN_KEY_FILE/SIGN_KEY_PWD/SIGN_KEY_ALIAS）

## 四、命令行构建（等价）

```bash
set ECM_DIR=C:/Users/<你>/.workbuddy/ecm/share/ECM/cmake
gradlew :app:assembleDebug        # 或 :app:assembleRelease（需签名参数）
```

## 四·五、Run/Debug Configuration 配置

本项目是输入法（IME），"构建"不依赖 Run Configuration，但日常开发建议配 3 个：

1. **app（自动生成）**：Gradle 同步成功后自动出现在工具栏下拉框。选设备 → ▶ 即可
   编译 debug APK 并安装（IME 装完需到系统设置启用）。Build Variant 在
   **Build → Select Build Variant** 切换；切到 release 但未给签名参数时只能产未签名 APK。
2. **Gradle 任务配置（手动建，推荐）**：**Run → Edit Configurations → + → Gradle**，
   `Run` 栏填任务与参数，`Gradle project` 选根工程。建议建两条：
   - `assembleDebug`：`:app:assembleDebug -PbuildABI=arm64-v8a`
   - `assembleRelease`：`:app:assembleRelease -PbuildABI=arm64-v8a -PsignKeyFile=D:\tools\keystore\<你的>.jks -PsignKeyPwd=<密码> -PsignKeyAlias=<别名>`
   - `-PbuildABI=arm64-v8a` 只编 arm64（对应 build-logic 的 `buildAbiOverride`），全 ABI 会慢数倍。
   - 签名三参数也支持环境变量形式 `SIGN_KEY_FILE/SIGN_KEY_PWD/SIGN_KEY_ALIAS`。
3. **快捷方式**：右侧 Gradle 工具窗口 → `app → Tasks → build → assembleDebug` 双击即跑。

注意：Gradle 配置**没有环境变量设置框**，`ECM_DIR`/`GETTEXT_*` 必须在启动
Android Studio 之前设为系统/用户环境变量，改完要**完全退出并重启 AS**。

## 五、常见坑（均已在原机踩过）

| 症状 | 解法 |
|---|---|
| `find_package(ECM) 找不到` | 环境变量 `ECM_DIR` 未设置或 Android Studio 未重启；Debug 曾经成功不代表 Release(RelWithDebInfo) 不需要——两者是独立 CMake 配置目录 |
| `find_package(Gettext) 找不到 msgfmt` | gettext shim 未就位或路径不对（见第一节） |
| 资源合并/打包阶段"拒绝访问"（zip-cache、timing.txt 等） | Windows 文件锁：删除对应 `app/build/intermediates/incremental/<失败任务名>` 目录重试 |
| `kenlm` 子模块缺失 | `git submodule update --init --depth 1`（libime 的嵌套子模块） |
| libqrcode.so 不在 APK 里 | 确认 `app/build.gradle.kts` 的 `cmake.targets` 含 `"qrcode"`（AGP 只构建列表内 target） |
| `Starting Daemon > Downloading toolchain from api.foojay.io` 卡死 | Gradle daemon JVM toolchain 自动供给：本机没有满足条件的 JVM 时去 foojay 下 JDK（直连极慢）。解法：① AS 的 **Settings → Build Tools → Gradle → Gradle JVM** 显式选本地 JDK（如 AS 自带 `C:\Program Files\Android\Android Studio\jbr`）；② 用户级 `~/.gradle/gradle.properties` 加 `org.gradle.java.installations.paths=<本地JDK路径>` + `org.gradle.java.installations.auto-download=false`；③ 挂代理让它下完：`set JAVA_TOOL_OPTIONS=-Dhttps.proxyHost=192.168.2.2 -Dhttps.proxyPort=1081` 后重启 AS。foojay ID `39701d92e1756bb2f141eb67cd4c660e` 的真身 = **Temurin JDK 21.0.7+6 win-x64 zip**，可从 GitHub Releases 或清华 TUNA `mirrors.tuna.tsinghua.edu.cn/Adoptium/` 手动下载后按解法②登记 |
| 每次 sync 都下载 gradle-9.6.1-bin.zip | wrapper 机制按 `gradle-wrapper.properties` 的 distributionUrl 下载，与"本地已装 Gradle"无关。预置缓存：`~/.gradle/wrapper/dists/gradle-9.6.1-bin/<hash>/`（含 `.ok` 标记）拷到新机器同路径即可跳过下载 |

## 六、本 fork 与上游的差异

- applicationId = `org.fcitx.fcitx5.android.qrscan`（与官方共存；官方插件 APK 不可被检测）
- QR 扫码输入法：`app/src/main/cpp/qrcode/`（stub engine）+ `app/src/main/java/.../input/qrscan/`（相机面板）
- 全部注入点：`git grep -n QRSCAN`；合并上游流程见 `QRSCAN_注入点清单.md`
