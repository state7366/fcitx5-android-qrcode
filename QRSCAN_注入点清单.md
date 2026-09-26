# QRSCAN 注入点清单（上游合并对照表）

所有 QR 扫码相关改动均以 `QRSCAN` 标记（Kotlin/C++ 用 `// QRSCAN-BEGIN ... // QRSCAN-END`
或行尾 `# QRSCAN`，XML 用 `<!-- QRSCAN: ... -->`）。合并上游更新时：
`git grep -n "QRSCAN"` 即可列出全部注入点；冲突解决时保留 QRSCAN 块即可。

## 一、新增文件（与上游零冲突，直接保留）

| 文件 | 作用 |
|---|---|
| `app/src/main/cpp/qrcode/qrcode.cpp` | qrcode stub engine（注册 fcitx 输入法条目，吞噬按键） |
| `app/src/main/cpp/qrcode/CMakeLists.txt` | engine 构建（MODULE libqrcode.so） |
| `app/src/main/assets/usr/share/fcitx5/addon/qrcode.conf` | fcitx addon 描述 |
| `app/src/main/assets/usr/share/fcitx5/inputmethod/qrcode.conf` | fcitx 输入法描述 |
| `app/src/main/java/org/fcitx/fcitx5/android/input/qrscan/QrScanWindow.kt` | 相机扫码面板（CameraX+ZXing，持续扫描） |
| `app/src/main/java/org/fcitx/fcitx5/android/input/qrscan/QrScanPermissionActivity.kt` | CAMERA 权限申请透明 Activity |
| `app/src/main/res/drawable/ic_qr_scan.xml` | 工具栏 QR 图标 |

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
