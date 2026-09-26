# fcitx5-android-qrcode

[fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 的 Fork，在其基础上新增 **二维码扫描输入法（QR Code Scanner）**：在输入法面板内直接调起相机扫码，识别结果上屏到当前输入框，无需切换应用。

> 本仓库的一切功能均建立在上游项目 [fcitx5-android/fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 之上，衷心感谢上游作者与贡献者的杰出工作。除下文列出的修改外，全部代码、设计与构建体系均来自上游仓库。

## 本仓库相对上游的主要修改

### 1. 二维码扫描输入引擎（核心新增）

- **`app/src/main/cpp/qrcode/`**：新增 qrcode C++ 引擎（Fcitx addon，`libqrcode.so`），以及 `qrcode-addon.conf` / `qrcode-inputmethod.conf` 两个描述文件（通过 CMake `install(... COMPONENT config)` 安装进 APK assets，保证全新克隆构建即可用）。
- **`QrScanWindow`（Kotlin 输入窗口）**：扫码面板 UI，以 `IMChangeEvent` 作为输入法切换的唯一事实来源，保证状态一致性。
- 引擎注册为普通输入法：**安装后需在 设置 → 输入法 → 添加 中手动启用 "QR Code Scanner"**，然后像切换拼音/五笔一样切换过去即可扫码。
- 所有对上游代码的侵入性修改均以 `QRSCAN-BEGIN` / `QRSCAN-END` 注释标记，便于日后合并上游更新；完整清单见 [QRSCAN_注入点清单.md](QRSCAN_注入点清单.md)。
- applicationId 改为 `org.fcitx.fcitx5.android.qrscan`，可与官方版共存安装。

### 2. Windows 构建体系加固（对上游构建脚本的改进）

上游构建在 Windows 上需要手工安装 MSYS2/ECM/gettext 并配置环境变量，本仓库将这些依赖内化，**全新克隆后仅需 Python 3.10+ 在 PATH 中**即可构建：

- **`tools/ecm/`**：内置 KDE extra-cmake-modules（BSD-3 许可），不再依赖系统安装。
- **`tools/gettext/`**：内置纯 Python 标准库实现的 `msgfmt` 替代（.po → .mo 编译 + `--desktop` 模板复制）与 `msgmerge` 占位脚本，不再需要 GNU Gettext。
- **`build-logic` 集中注入**：在 `NativeBaseConventionPlugin` 统一为所有 native 模块注入 `ECM_DIR` / `GETTEXT_MSGFMT_EXECUTABLE` 等参数，解析优先级为 环境变量 > Gradle 属性 > 内置默认值。
- **`tools/cmake/qrscan-windows-env.cmake`**：通过 `-DCMAKE_PROJECT_INCLUDE` 在 cmake 进程内设置 `ENV{ECM_DIR}`，规避 lib/fcitx5 子模块 `FindECM.cmake` 在 Windows 上只认环境变量且硬编码 msys64 路径的问题（无需给子模块打补丁）。
- **`BuildMetadataPlugin`**：修复 Gradle 9 下 `redirectIdeApkOutputs<Variant>` 与 build-metadata 输出目录的隐式依赖校验错误（Android Studio 内点 Run 构建时触发）。

详细的 Windows 构建指南与踩坑记录见 [BUILDING.md](BUILDING.md)。

## 构建

见 [BUILDING.md](BUILDING.md)。要点：

- Android Studio 2026.1.4.8+（AGP 9.4.1 要求），Android SDK Platform & Build-Tools 35，NDK 25 + CMake 3.22.1。
- Windows 下无需再安装 MSYS2 / ECM / gettext（已内置于 `tools/`），只需 Python 3.10+。
- Release 签名通过 `-PsignKeyFile/-PsignKeyPwd/-PsignKeyAlias`（或 `SIGN_KEY_*` 环境变量）传入。

## 上游项目简介（保留自原版 README）

[Fcitx5](https://github.com/fcitx/fcitx5) input method framework and engines ported to Android.

### Supported Languages

- English (with spell check)
- Chinese
  - Pinyin, Shuangpin, Wubi, Cangjie and custom tables (built-in, powered by [fcitx5-chinese-addons](https://github.com/fcitx/fcitx5-chinese-addons))
  - Zhuyin/Bopomofo (via [Chewing Plugin](./plugin/chewing))
  - Jyutping (via [Jyutping Plugin](./plugin/jyutping/), powered by [libime-jyutping](https://github.com/fcitx/libime-jyutping))
- Vietnamese (via [UniKey Plugin](./plugin/unikey), supports Telex, VNI and VIQR)
- Japanese (via [Anthy Plugin](./plugin/anthy))
- Korean (via [Hangul Plugin](./plugin/hangul))
- Sinhala (via [Sayura Plugin](./plugin/sayura))
- Thai (via [Thai Plugin](./plugin/thai))
- Generic (via [RIME Plugin](./plugin/rime), supports importing custom schemas)

### Implemented Features

- Virtual Keyboard (layout not customizable yet)
- Expandable candidate view
- Clipboard management (plain text only)
- Theming (custom color scheme, background image and dynamic color aka monet color after Android 12)
- Popup preview on key press
- Long press popup keyboard for convenient symbol input
- Symbol and Emoji picker
- Plugin System for loading addons from other installed apk
- Floating candidates panel when using physical keyboard

In case you want Fcitx5 on other platforms: [macOS](https://github.com/fcitx-contrib/fcitx5-macos), [iOS](https://github.com/fcitx-contrib/fcitx5-ios), [HarmonyOS](https://github.com/fcitx-contrib/fcitx5-harmony), [ChromeOS](https://github.com/fcitx-contrib/fcitx5-chrome), [Windows](https://github.com/fcitx-contrib/fcitx5-windows); or [try Fcitx5 in the browser](https://fcitx-contrib.github.io/online/index.html)

## 致谢

- **[fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)**：本仓库的上游与基石，感谢其作者与所有贡献者。
- [Fcitx5](https://github.com/fcitx/fcitx5)、[fcitx5-chinese-addons](https://github.com/fcitx/fcitx5-chinese-addons) 及插件生态中的所有引擎项目。
- [KDE extra-cmake-modules](https://github.com/KDE/extra-cmake-modules)（BSD-3，内置于 `tools/ecm/`）。

## License

与上游一致，详见 [LICENSE](LICENSE)。
