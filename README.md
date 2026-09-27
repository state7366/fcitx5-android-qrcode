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

### 2. OCR 文字识别输入法（`OCRSCAN`）

- 与二维码平行的第二个相机扫描面板：对准文字 → 点「拍照识别」 → 结果可**上屏**或**复制**。全屏预览 + 半透明结果卡片，不再有取景框遮挡。
- **OCR 引擎抽象层**：`input/ocr/OcrEngine.kt` 定义 `OcrEngine` 接口，`OcrEngineRegistry` 负责注册与创建，引擎用 `OcrEngineSpec` + `OcrFieldSpec` 自描述配置项，因此新增一个模型**只需注册一个 provider**，面板、设置界面与输入法框架零改动。
- **内置 5 种后端，可在 设置 → OCR 识别引擎 中手动切换**（列表顺序即常用程度，本地与局域网的排前面）：

  | 引擎 | 类型 | 说明 |
  |---|---|---|
  | Tesseract 5（默认） | 本地 · 隐私友好 | `tesseract4android` AAR 内置于 `app/libs/`，离线可用、图片不出手机；`assets/tessdata/` 下的 `chi_sim`+`eng`（tessdata_fast）首次使用时释放到 App 私有目录 |
  | 白描（手机 WiFi） | 局域网 | 白描 Android 端官方「WiFi 传输识别」（首页右上角 WIFI 按钮开启）。复刻官方 Web UI 的端点：`POST /files`（multipart `fileName` + `newfile`）→ `POST /recognize/all`（`_method=recognize`）→ 轮询 `GET /files?<ts>` → `POST /filesResult` 取 `result`，**无鉴权**。见下方「白描手机 WiFi 传输」小节 |
  | 百度智能云 OCR | 云端 · 功能强 | AK/SK 换取 access_token，默认 `accurate_basic` 高精度版 |
  | 腾讯云 OCR | 云端 · 功能强 | TC3-HMAC-SHA256 签名，默认 `GeneralAccurateOCR` |
  | 自定义 HTTP 接口 | 任意 | 自己填 URL / 请求头 / 请求体模板（`{base64}` 占位）/ 结果 JSON 路径，可对接任何服务商 |

#### 白描手机 WiFi 传输（实测端点）

在白描 App 首页右上角点 WIFI 按钮开启后，App 内会起一个局域网 HTTP 服务（例如 `http://192.168.3.5:51314`），浏览器打开该地址即为官方的「WiFi 传输识别」页面。本引擎完全复用该页面（`transfer.js` / `result.js`）自己调用的端点，未使用任何私有或未公开协议：

| 方法 | 路径 | 参数 | 返回 |
|---|---|---|---|
| POST | `/files` | multipart：`fileName=<文件名>`、`newfile=<图片>`（jpg/jpeg/png） | 空，200 表示接收 |
| GET | `/files?<时间戳>` | — | `{recognizeStatus, files:[{path,name,width,height,status,size}]}` |
| POST | `/recognize/all` | `_method=recognize` | 异步启动批量识别 |
| POST | `/filesResult` | — | `{recognizeResult, files:[{... ,result}]}` |
| POST | `/fileAllDelete` | `_method=delete` | 清空列表 |

- 文件 `status`：`0` 未识别 / `1` 识别中 / `2` 已识别 / `3` 识别失败；全局 `recognizeStatus` 同理。
- **同名上传会覆盖但不会重置识别状态**，所以引擎每次都用唯一名 `fcitx5-ocr-<时间戳>.jpg`；自己堆积到 12 张时才整表清理（官方 UI 上限 50 张）。
- 实测限制：`fileDelete`（单张删除）在现行版本是空操作，所以可选的「识别后清空列表」走的是 `fileAllDelete`。
- 实测限制：官方说明要求白描保持前台；后台时服务可能被回收，此时会报连接失败/超时。
- 实测限制：扩展名必须与图片内容一致（PNG 内容命名成 `.jpg` 会一直停在 `status=0`）；引擎固定上传 JPEG + `.jpg`。
- 实测限制：**同名覆盖不会重置 `status`**，所以每次都用唯一文件名（前缀+时间戳），否则第二次会读到上一张的旧结果。
- 服务端会把一张图里的多个文本块直接拼接，块之间不保证有换行。
- 因为是明文 `http://`，`res/xml/network_security_config.xml`（OCRSCAN 标记）整体放行了明文流量，否则 targetSdk 36 下局域网请求会被系统拒绝。

- **配置导入导出**：设置页可把当前引擎与凭据导出为 `ocr-config.json`，或导入之前导出的文件（密钥字段在界面上掩码显示）。
- 同样需在 fcitx **设置 → 输入法 → 添加** 中启用 "OCR Text Scanner" 才能使用。
- 完整注入点清单与「换模型的方法」见 [QRSCAN_注入点清单.md](QRSCAN_注入点清单.md) 第五节。

### 3. Windows 构建体系加固（对上游构建脚本的改进）

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
