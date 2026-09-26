# fcitx5-android 二维码扫描插件 · 可行性分析

> 目标：研究能否给 fcitx5-android（Android 输入法）开发一个"二维码扫描插件"——
> 切换到特定输入法后自动调起摄像头扫描二维码，把解码结果当作键盘输入提交到当前文本框。
> 结论先行：**技术上可行**，但有 3 个必须正视的现实约束（权限/隐私、IME 窗口相机实测、分发渠道）。

---

## 1. 结论速览

| 维度 | 判断 |
|------|------|
| 架构契合度 | ✅ 极高。项目已有 `InputWindow` 面板机制，天然适合塞一个"扫码面板" |
| 相机预览能否进 IME 窗口 | ⚠️ 理论可行，需真机实测（部分国产 ROM 可能限制 IME 窗口相机） |
| 运行时权限（CAMERA） | ⚠️ IME 是 Service，需拉 Activity 申请，项目已有现成范式可复用 |
| 扫码 → 提交文本 | ✅ 标准。`InputConnection.commitText()` 即可 |
| 隐私/商店审核 | ⚠️ 高风险。Google Play 大概率拒审；F-Droid/GitHub 自编译无碍 |
| 可行性总评 | **可行**，建议做成"应用内 InputWindow 功能 + 专用输入法子类型"，先出最小原型验证相机预览 |

---

## 2. 项目架构契合度（为什么能做）

通读 `app/src/main/java/org/fcitx/fcitx5/android/input/` 后确认：

- **主服务 `FcitxInputMethodService`**（`LifecycleInputMethodService` 子类）
  - `onCreateInputView()` 返回 null，随后 `replaceInputViews()` 自行 `setInputView(InputView)`；`onConfigureWindow()` 把窗口设为 `MATCH_PARENT × MATCH_PARENT`。
  - 即 **IME 窗口的 UI 完全自定义、可全屏**，相机预览视图能直接塞进去。
  - 文本提交入口就是 `commitText(text)` → `currentInputConnection.commitText(...)`（第 423 行）。扫码结果提交到输入框是标准操作，无需 hack。
- **`InputView`** 用传统 Android View（`splitties.views.dsl`，非 Compose），核心是一个 **`InputWindowManager`**，管理 `KeyboardWindow` / `symbolPicker` / `emojiPicker` / `emoticonPicker` 等面板，靠 `attachWindow()` 切换。
- **`InputWindow` 基类契约**（`input/wm/InputWindow.kt`）正好对应相机生命周期：
  - `onCreateView(): View` → 创建相机预览 + 取景框叠加层
  - `onAttached()` → 打开相机、启动解码
  - `onDetached()` → 释放相机
- **"切换输入法"在 fcitx5-android 里的实现**：对应 `InputMethodSubtype`（Pinyin/Rime…），有 `IMChangeEvent` / `SwitchInputMethodEvent` 钩子。新增一个"QR 扫码"子类型即可在用户选中时自动调起扫码面板。

> 一句话：加一个 `QrScanWindow : SimpleInputWindow` 是成本最低的扩展点，不用碰 fcitx5 核心（native 层）。

---

## 3. 必须正视的约束（风险点）

### 3.1 权限与隐私（最大现实障碍）
- 需要 `android.permission.CAMERA`（危险权限）。IME 是后台 Service，**无法直接弹系统权限框**，必须拉起一个 Activity 去 `requestPermissions`。
  - 项目已有范式：`MainActivity.kt` 用 `requestPermissions(POST_NOTIFICATIONS)`，`registerForActivityResult` 也普遍使用 → 可复用，建议新建一个**透明 `QrScanPermissionActivity`** 专门申请 CAMERA 并返回结果。
- 第三方 IME 本身就能看到用户所有击键；再叠加"切到某输入法就自动开摄像头" = **极高隐私敏感度**。必须在设置里给显式开关 + 首次授权明示，且默认不自动开。
- **分发渠道影响实现选型**：fcitx5-android 主分发在 **F-Droid + GitHub Releases**，很多用户设备**无 Google 移动服务（GMS）**。这直接决定扫码库选型（见 §4）。

### 3.2 IME 窗口内相机预览（需真机实测）
- IME 窗口类型为 `TYPE_INPUT_METHOD`，体系允许承载任意 View；相机预览（CameraX `PreviewView` / Camera2 `TextureView`）应能渲染。社区有 IME 内嵌相机的先例。
- **但**：MIUI / 鸿蒙 / 部分国产定制 ROM 可能对 IME 窗口的相机或悬浮行为做限制，**必须在真机验证**，不能只靠模拟器。
- IME 窗口通常为半屏高度，取景框与对焦需适配"半屏扫码"布局（扫码面板替换键盘区域即可）。

### 3.3 生命周期与资源占用
- 在 `onAttached()` 开相机、`onDetached()` 释放；还要监听 `FcitxInputMethodService.onWindowShown()/onWindowHidden()`，确保 **IME 收起时释放相机**，否则会长期占用摄像头导致其他 App 无法开相机。
- 横竖屏、分屏、多窗口都要处理相机释放/重建。

### 3.4 "自动调起"的触发语义
- 最贴合用户原话的做法：新增专用子类型"QR 扫码"，在 `IMChangeEvent` 处特判 → `attachWindow(QrScanWindow)` 并自动开相机。
- 更稳妥的做法（推荐组合）：专用子类型 + 设置里"自动调起"开关默认关，避免意外开摄像头。

---

## 4. 扫码方案选型（横向对比后给结论）

| 方案 | 优点 | 缺点 | 是否适合本项目 |
|------|------|------|----------------|
| **ZXing（zxing-android-embedded）** | 纯 Java、完全离线、**无 GMS 依赖**；可嵌入任意 View 自行取帧解码 | 解码速度/畸变鲁棒性弱于 ML Kit；需自己接相机取帧 | ✅ **推荐**（契合 F-Droid 无 GMS 场景） |
| **ML Kit Barcode Scanning** | 准、快、畸变/模糊鲁棒 | 依赖 `play-services-mlkit`，**无 GMS 设备失效**；商店隐私审查更严 | ⚠️ 仅当你确定目标设备都有 GMS 时考虑 |
| **Google Code Scanner** | 现成扫码 UI | 自带 Activity、不可嵌入 IME 窗口 | ❌ 不适合（无法内嵌） |

**结论**：默认用 **ZXing + Camera2/CameraX 自取帧** 做内嵌解码，保证离线 + F-Droid 可用。若日后确认只面向 GMS 设备，可加 ML Kit 作为增强后端（策略模式切换）。

---

## 5. 推荐实现路径

1. **新增输入法子类型**：在 `res/xml/` 的 subtype 定义里加一条"QR 扫码"，不绑定真实 fcitx5 IM。
2. **新增 `QrScanWindow : SimpleInputWindow<QrScanWindow>()`**：
   - `onCreateView()`：返回 `FrameLayout`，内含相机预览 `TextureView`/`PreviewView` + 取景框 + 取消按钮。
   - `onAttached()`：检查 CAMERA 权限 → 无则启动 `QrScanPermissionActivity` 申请；有权限则开相机、循环取帧送 ZXing 解码。
   - 解码成功：`service.commitText(result, 1)`（经 scope 拿到 `FcitxInputMethodService` 实例），并提交后自动切回普通键盘。
   - `onDetached()`：停止取帧、释放相机。
3. **在 `InputView.init` 注册该窗口**；在 `IMChangeEvent` 特判"QR 扫码"子类型 → `attachWindow(QrScanWindow)`。
4. **KawaiiBar 加一个扫码入口按钮**（作为不依赖子类型的备选开关）。
5. **设置项**：`advanced` 偏好里加"扫码自动调起""连续扫码"等开关。

> 关于"插件"二字的澄清：fcitx5-android 现有的 `plugin/` 体系是给 **fcitx5 native addon（.so 输入法引擎）** 用的，不是 UI 面板插件。二维码扫描是 UI 功能，**最自然的形态是 `app` 模块内的 `InputWindow` 功能**，而非独立插件 APK。若要物理隔离，可把它拆成独立 Gradle module，但仍是 app 依赖，不走 native 插件加载机制。

---

## 6. 最小验证原型（MVP）

目标：用最少代码验证"IME 里能不能出相机预览 + 解码后能否进输入框"。

- 在 `app` 模块加 `QrScanWindow`（Camera2 `TextureView` 预览 + ZXing 解一帧）。
- KawaiiBar 加按钮切到该窗口。
- 验证清单：
  1. IME 弹出后能看到相机实时预览（真机，含一台国产 ROM）；
  2. 扫一个 URL/文本码 → 解码字符串 `commitText` 进当前 EditText；
  3. 切走/收起 IME 后相机被释放（`Camera2.cameraDevice.close()` 生效）；
  4. 无 CAMERA 权限时弹权限申请且不过崩。

---

## 7. 构建前置条件（与你刚装好的 Android Studio 呼应）

- 本项目**不是纯 Gradle Java 工程**：native 核心 fcitx5 / libime 是 git 子模块，需 `git submodule update --init` + **NDK + CMake**，且官方推荐用 nix 或预编译工具链。
- 我们刚装好的 `C:\Program Files\Android\Android Studio` 可用于导入与构建，但还需：
  - Android SDK（首次启动 Setup Wizard 下载，默认 `C:\Users\pony\AppData\Local\Android\Sdk`）；
  - NDK（SDK Manager 里装，或按官方 `shell.nix`/`flake.nix` 用 nix 构建）；
  - 拉取子模块（约数百 MB native 代码）。
- 仅做"MVP 验证相机预览"时，可先在 `app` 模块加代码、用 CI/官方预编译 native 产物，避免全量重编 native。

---

## 8. 未决/待你拍板项

1. **触发方式**：专用子类型"自动调起"（最贴合原话） vs KawaiiBar 按钮（更简单）？建议两者都做、默认按钮。
2. **扫码后行为**：纯填入文本 / 识别 URL 时询问"打开还是填入" / WIFI 码自动连（需额外权限，建议仅填入）？
3. **目标设备是否都有 GMS**：决定 ZXing（默认）还是可上 ML Kit。
4. **是否要物理隔离成独立 module**：默认进 `app` 即可。
