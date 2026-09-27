# QRSCAN 注入点清单（上游合并对照表）

所有 QR 扫码相关改动均以 `QRSCAN` 标记（Kotlin/C++ 用 `// QRSCAN-BEGIN ... // QRSCAN-END`
或行尾 `# QRSCAN`，XML 用 `<!-- QRSCAN: ... -->`）。合并上游更新时：
`git grep -n "QRSCAN"` 即可列出全部注入点；冲突解决时保留 QRSCAN 块即可。

## 一、新增文件（与上游零冲突，直接保留）

| 文件 | 作用 |
|---|---|
| `app/src/main/cpp/qrcode/qrcode.cpp` | qrcode stub engine（注册 fcitx 输入法条目，吞噬按键） |
| `app/src/main/cpp/qrcode/CMakeLists.txt` | engine 构建（MODULE libqrcode.so）+ 两个 conf 的 `install(... COMPONENT config)` 规则（照 androidfrontend 约定，由 installProjectConfig 装进 assets） |
| `app/src/main/cpp/qrcode/qrcode-addon.conf` | fcitx addon 描述（安装为 assets `usr/share/fcitx5/addon/qrcode.conf`；**勿直接提交 assets/usr 下的文件，该目录被 gitignore**） |
| `app/src/main/cpp/qrcode/qrcode-inputmethod.conf` | fcitx 输入法描述（安装为 assets `usr/share/fcitx5/inputmethod/qrcode.conf`） |
| `app/src/main/java/org/fcitx/fcitx5/android/input/qrscan/QrScanWindow.kt` | 相机扫码面板（CameraX+ZXing，持续扫描） |
| `app/src/main/java/org/fcitx/fcitx5/android/input/qrscan/QrScanPermissionActivity.kt` | CAMERA 权限申请透明 Activity |
| `app/src/main/res/drawable/ic_qr_scan.xml` | 工具栏 QR 图标 |
| `tools/ecm/` | ECM (extra-cmake-modules) 内置副本，Windows 构建免外部依赖（BSD-3，源自 KDE） |
| `tools/gettext/` | msgfmt/msgmerge Python shim + 启动器（msgfmt.py 纯标准库；msgfmt.cmd 用 PATH 上的 python/py） |

## 二、上游文件修改（QRSCAN 标记，合并时注意）

| 文件 | 注入内容 |
|---|---|
| `app/src/main/cpp/CMakeLists.txt` | 一行 `add_subdirectory(qrcode) # QRSCAN` |
| `app/src/main/java/.../core/SubtypeManager.kt` | 常量 `QRCODE_SUBTYPE = "qrcode"` |
| `app/src/main/java/.../input/FcitxInputMethodService.kt` | ① IMChangeEvent 分支头部 QRSCAN 块（进/出 QR 面板，单一事实来源）② `pendingQrScan`/`lastRealImBeforeQrScan` 字段 ③ onCurrentInputMethodSubtypeChanged 头部 qrcode→activateIme 块 ④ onFinishInputView 的 QR 清理块 ⑤ import KeyboardWindow/QrScanWindow |
| `app/src/main/java/.../input/InputView.kt` | startInput 的 QRSCAN 块（查 fcitx 当前 IM==qrcode 则显示面板；覆盖 service 重启丢事件场景）；import SubtypeManager/QrScanWindow |
| `app/src/main/java/.../input/wm/InputWindowManager.kt` | 只读属性 `val current: InputWindow?` |
| `app/src/main/java/.../input/bar/KawaiiBarComponent.kt` | qrScanButton onClick → activateIme("qrcode")；import SubtypeManager |
| `app/src/main/java/.../input/bar/ui/idle/ButtonsBarUi.kt` | qrScanButton 定义 |
| `app/src/main/AndroidManifest.xml` | CAMERA 权限/feature + QrScanPermissionActivity 声明 |
| `app/src/main/res/values/strings.xml` | qr_scan 系列字符串 |
| `app/src/main/res/values/themes.xml` | QrScanPermissionActivity 透明主题 |
| `app/src/main/res/xml/input_method.xml` | **无静态 qrcode subtype**（注释说明；engine 模式下由 SubtypeManager.syncWith 动态创建） |
| `app/build.gradle.kts` + `gradle/libs.versions.toml` | CameraX 1.4.2 + ZXing 3.5.4 依赖；fork applicationId（`org.fcitx.fcitx5.android.qrscan`）；cmake targets 增加 `"qrcode"` |
| `build-logic/convention/src/main/kotlin/NativeBaseConventionPlugin.kt` | QRSCAN-BEGIN/END 块：向所有 native 模块的 cmake arguments 注入 `-DECM_DIR`/`-DGETTEXT_MSGFMT_EXECUTABLE`/`-DGETTEXT_MSGMERGE_EXECUTABLE`（env var > gradle 属性 > 默认路径，复用 `Utils.kt` 的 `ep()`；Windows 构建可移植性，详见 BUILDING.md） |

## 三、架构不变式（修改 QR 逻辑时必须维持）

1. **单一事实来源 = `FcitxEvent.IMChangeEvent`**：进入 QR 面板只在该事件
   uniqueName=="qrcode" 的分支；离开只在「非 qrcode 且当前窗口是 QrScanWindow」分支。
   系统子类型选择器、工具栏按钮、Cancel、IME 隐藏恢复，全部汇聚到这里。
2. **系统 IME 子类型永不等于 qrcode**：IMChangeEvent 的 qrcode 分支直接 return，
   不执行 `switchInputMethod` 同步。Android 侧 IME 状态机永远只看到真实文本输入法。
3. **engine 是纯 stub**：`cpp/qrcode/qrcode.cpp` 只做注册与按键吞噬；
   所有 UI/相机/提交逻辑在 Android 层 `input/qrscan/`。
4. **离开 QR 的回切目标 = `lastRealImBeforeQrScan`**（fallback "keyboard-us"），
   经 `postFcitxJob { activateIme(...) }` 触发，由 IMChangeEvent 闭环。
5. **使用前提**：用户需在 fcitx 设置→输入法列表启用 "QR Code Scanner"。

## 四、合并上游的操作流程

1. `git fetch upstream && git merge upstream/main`（或 rebase）。
2. 冲突文件对照本表第二节逐一解决：保留 QRSCAN 块，接受上游其余改动。
3. 若上游改动了 IMChangeEvent 分支结构，把 QRSCAN 块重新移植到「分支最前面」。
4. `git grep -n QRSCAN` 复查注入点数量与本表一致。
5. 构建 + 真机回归：切换图标切入/切出、持续扫描、Cancel、隐藏后恢复。

---

# 五、OCRSCAN（第二个扫描面板，二维码之外新增）

标记关键字 `OCRSCAN`，与 QRSCAN 平行、互不干扰。

| 文件 | 注入点 |
|---|---|
| `app/src/main/cpp/ocr/ocr.cpp` | 新增：桩引擎，`InputMethodEntry("ocr","OCR Text Scanner","zh_CN","ocr")`，`keyEvent()` 吞键 |
| `app/src/main/cpp/ocr/ocr-addon.conf` / `ocr-inputmethod.conf` | 新增：经 `install(... COMPONENT config)` 装进 APK assets（**勿直接提交 assets/usr 下的文件，该目录被 gitignore**） |
| `app/src/main/cpp/ocr/CMakeLists.txt` | 新增：`add_library(ocr MODULE ocr.cpp)` + 两条 conf install 规则 |
| `app/src/main/cpp/CMakeLists.txt` | `add_subdirectory(ocr) # OCRSCAN` |
| `app/build.gradle.kts` | `cmake.targets` 增 `"ocr"`；`dependencies` 增 `implementation(files("libs/tesseract4android-4.8.0.aar"))` |
| `.gitignore` | `*.aar` 例外 `!app/libs/*.aar`（否则内置 AAR 不进 git，全新克隆构建失败） |
| `app/src/main/java/.../input/ocr/OcrEngine.kt` | 新增：**OCR 引擎抽象层**——`OcrEngine` 接口 + `OcrResult` + `OcrEngineProvider` |
| `.../input/ocr/OcrEngineRegistry.kt` | 新增：引擎注册表（含 `specs()` 供设置界面渲染）；换模型只需再注册一个 provider |
| `.../input/ocr/OcrConfig.kt` | 新增：`OcrConfig` / `OcrFieldSpec` / `OcrEngineSpec` / `OcrConfigStore`（`ocr-config.json`，含导入导出序列化） |
| `.../input/ocr/TesseractOcrEngine.kt` | 新增：本地离线模型（Tesseract 5 + Leptonica）+ `TesseractProvider` |
| `.../input/ocr/RemoteOcrEngines.kt` | 新增：`HttpOcrEngine` 基类 + 百度 / 腾讯云(TC3 签名) / 白描手机 WiFi / 自定义 HTTP 四个后端及其 provider |
| `.../ui/main/settings/ocr/OcrSettingsFragment.kt` | 新增：引擎选择器 + 按 `OcrEngineSpec.fields` 动态生成表单 + 配置导入导出（SAF） |
| `.../ui/main/settings/SettingsRoute.kt` | `SettingsRoute.Ocr` + `createGraph` 里 `fragment<OcrSettingsFragment, Ocr>` |
| `.../ui/main/MainFragment.kt` | Android 分类下新增「OCR 识别引擎」入口 |
| `AndroidManifest.xml` | 新增 `android.permission.INTERNET`（云端引擎需要）；`android:networkSecurityConfig="@xml/network_security_config"`（局域网明文 HTTP） |
| `res/xml/network_security_config.xml` | 新增（OCRSCAN 标记）：targetSdk 36 默认禁明文，放行局域网 OCR 服务的 `http://` |
| `.../input/ocr/TessDataInstaller.kt` | 新增：把 `assets/tessdata` 下的训练数据拷到 app 私有目录（native 读不到 APK 内 assets） |
| `.../input/ocr/OcrScanWindow.kt` | 新增：相机面板；单拍识别（拍照→识别→上屏/复制），非逐帧 |
| `app/src/main/java/.../core/SubtypeManager.kt` | `OCR_SUBTYPE = "ocr"` |
| `.../input/FcitxInputMethodService.kt` | 扫描面板状态**泛化**：`pendingQrScan: Boolean` → `pendingScanPanel: ScanPanel?`（`ScanPanel{QR,OCR}`）；`lastRealImBeforeQrScan` → `lastRealImBeforeScan` |
| `.../input/InputView.kt` | `startInput` 按当前 IM / pending 标志决定挂 QrScanWindow 还是 OcrScanWindow |
| `.../input/qrscan/QrScanWindow.kt` | 仅字段名跟随泛化（`pendingScanPanel` / `lastRealImBeforeScan`） |
| `.../input/bar/ui/idle/ButtonsBarUi.kt` + `bar/KawaiiBarComponent.kt` | 工具栏新增 OCR 按钮（`ic_ocr_scan`），点击 `activateIme(OCR_SUBTYPE)` |
| `res/values/strings.xml`、`res/values-zh-rCN/strings.xml` | `ocr_*` 文案 |
| `res/drawable/ic_ocr_scan.xml` | 新增图标 |

## 换 / 加模型的方法（抽象层用途）

1. 写 `XxxOcrEngine : OcrEngine` + `XxxProvider : OcrEngineProvider`（`spec` 里声明 `OcrEngineSpec` 与 `fields`，设置界面会自动生成表单，无需改 UI）；
2. 在 `OcrEngineRegistry.init` 里 `register(XxxProvider)`（或运行时调用 `register`）；**注册顺序就是设置界面/面板里的显示顺序**，常用的往前放；
3. `OcrConfigStore` 里存的是 `engineId + params`，用户在设置界面选中该引擎即可。OcrScanWindow / 输入法框架 / 构建接线**无需改动**。

## 已内置后端

| id | 名称 | 本地/云端 | 备注 |
|---|---|---|---|
| `tesseract` | Tesseract 5（默认） | 本地 · 隐私友好 | 内置 `chi_sim`+`eng` 训练数据，图片不出手机 |
| `baidu` | 百度智能云 OCR | 云端 | AK/SK → access_token → `accurate_basic` |
| `tencent` | 腾讯云 OCR | 云端 | TC3-HMAC-SHA256 签名，`GeneralAccurateOCR` |
| `baimiao-wifi` | 白描（手机 WiFi） | 局域网 | 白描 Android 端官方「WiFi 传输识别」：`POST /files`（multipart `fileName`+`newfile`）→ `POST /recognize/all`（`_method=recognize`）→ 轮询 `GET /files?<ts>`（`status` 2 完成 / 3 失败）→ `POST /filesResult` 取 `result`；每次唯一文件名 `前缀-时间戳.jpg`；无鉴权 |
| `custom` | 自定义 HTTP 接口 | 任意 | 自行定义 URL / headers / 请求体模板（`{base64}`）/ 结果 JSON 路径 |
