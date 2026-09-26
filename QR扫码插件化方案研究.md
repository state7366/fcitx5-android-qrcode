# QR 扫码输入法化（fcitx engine / 插件模式）方案研究

日期：2026-09-26 ｜ 前置：`qrscan-continuous-v1`（持续扫描版，tag 已存档）

## 极限形态考证：原版主程序零改动 + qrcode 全在插件 APK —— 不可行

对原版主程序的全部插件扩展点逐一实证（2026-09-26）：

| 原版扩展点 | 实证位置 | 能提供什么 |
|---|---|---|
| 数据合并 | `DataManager.sync()` L181-232 | 静态 conf/词典铺进 fcitx 数据目录 |
| native 引擎 dlopen | `Fcitx.kt:428-457` → `FCITX_ADDON_DIRS` → `androidaddonloader.cpp` | 注册 fcitx addon/InputMethodEngine |
| AIDL 插件服务 | `lib/common/.../IFcitxRemoteService.aidl` | **仅 9 方法**：getVersionName/getPid/getLoadedPlugins/restartFcitx/剪贴板转换器×2/reloadPinyinDict/reloadQuickPhrase |
| plugin-base | `lib/plugin-base/`（全部源码） | 仅 AboutActivity（插件关于页） |

对照扫码的四个必要能力：

| 能力 | 零改动可行性 | 依据 |
|---|---|---|
| ① qrcode 进输入法列表 | ✅ | C++ engine + conf（方案 A） |
| ② 感知"切到了 qrcode" | ❌ **死结** | `IMChangeEvent` 只在主程序进程 eventFlow 内流转；AIDL 无输入法状态 API；插件进程无任何感知通道 |
| ③ 相机扫码 UI | ❌ **死结** | 键盘面板是主程序私有 InputWindow 体系；native 层不持 Android Context（`native-lib.cpp` 实证，androidnotification 亦走 Kotlin 代理转发——addon 永不直接触碰 Android UI 是该项目设计哲学）；hidden API（ActivityThread.currentApplication）在 targetSdk 36 被 greylist 封锁 |
| ④ 提交文本到编辑器 | ⚠️ 依赖②③ | engine 可 `ic->commitString()`，但扫码结果在插件进程，AIDL 无 commitText 方法，无合法回传通道 |

理论 hack 链（不推荐）：engine activate → JNI 反射 hidden API 拿 Application → startActivity 弹插件全屏扫码页 → 动态广播回传 → commitString。风险：hidden API 封锁、全屏 Activity 触发 IME 隐藏重蹈状态坑、双进程生命周期脆弱、ColorOS 杀后台。

**结论：UI 扩展点与事件通道的双重缺失是硬性约束，「原版主程序 + 纯插件实现扫码」不成立。**
若追求"主程序最少改动"，可为上游设计通用桥（plugin.xml 声明 `<hasInputPanel>`、IMChangeEvent 命中时 startService 通知插件、AIDL 增加 commitText），约 20 行主程序改动 + 插件化全部其余代码——这是可向上游提 PR 的方向；对当前 fork 则直接选方案 B。

---

## 结论

**可行。** fcitx5-android 的插件/引擎机制完全支持把 "QR 扫码"注册为 fcitx 输入法（uniqueName=`qrcode`），
用户通过键盘上的**输入法切换图标**（或长按弹出的输入法选择列表）切入/切出 QR 模式，
切换事件经 `FcitxEvent.IMChangeEvent` 驱动 UI 层显示/隐藏 `QrScanWindow`。

**关键架构约束：相机 UI 必须内置主程序。** fcitx5-android 插件机制只提供
「数据文件 + native 引擎 + 可选 Messenger IPC」三类扩展点，**没有 UI 层扩展点**——
`QrScanWindow`（CameraX 预览、扫描框、权限申请）必须编译进主程序 APK。
因此插件 APK 能承载的只是一个 ~80 行的空壳 engine，99% 的功能代码仍在主程序。

## fcitx5-android 插件机制（代码实证）

| 环节 | 实现 | 位置 |
|---|---|---|
| 插件声明 | 独立 APK，包名前缀 `org.fcitx.fcitx5.android.plugin.*`，依赖 `:lib:plugin-base`，manifest 挂 `plugin.MANIFEST` intent-filter + `res/xml/plugin.xml`（apiVersion=0.1） | `lib/plugin-base/src/main/AndroidManifest.xml:17-31` |
| 插件发现 | `queryIntentActivities(PLUGIN_INTENT)` → 解析 plugin.xml → 记录 `nativeLibraryDir` | `core/data/DataManager.kt:93-168` |
| 数据合并 | 插件 assets 的 fcitx 配置（addon conf、词典、inputmethod conf）铺进 `dataDir/usr/share` | `DataManager.sync()` L181-232 |
| 引擎加载 | 主程序+插件 nativeLibraryDir 拼成 `FCITX_ADDON_DIRS` 传 JNI；native 侧 `AndroidSharedLibraryLoader` 按 addon conf 的 `Library=libxxx` dlopen 插件 .so | `core/Fcitx.kt:428-457`、`cpp/native-lib.cpp:568`、`cpp/androidaddonloader/androidaddonloader.cpp:30-100` |
| 输入法切换事件 | native `androidfrontend` watchEvent(InputContextInputMethodActivated) → JNI handleFcitxEvent(6) → `FcitxEvent.IMChangeEvent(InputMethodEntry)` → `eventFlow` + `inputMethodEntryCached` | `cpp/androidfrontend/androidfrontend.cpp:322-340`、`core/Fcitx.kt:512-525` |
| 系统子类型同步 | IMChangeEvent 时 `SubtypeManager.subtypeOf(im)` → `switchInputMethod()`（Android 14+） | `FcitxInputMethodService.kt:315-326` |
| 官方示例 | `plugin/`：anthy、chewing、hangul、jyutping、rime、sayura、thai、unikey（native 引擎）；clipboard-filter（纯 Kotlin + IPC service） | `plugin/*/build.gradle.kts`、`plugin/anthy/src/main/res/xml/plugin.xml` |

F-Droid 对应产物：Fcitx5 (Jyutping/RIME/Anthy/Chewing/Hangul/Sayura/Thai/Unikey/Clipboard Filter Plugin)。

## 已排除的路线

- **keyboard engine 纯 conf 注册**：`KeyboardEngine::listInputMethods()` 只枚举 xkb rules
  layout（`lib/fcitx5/.../im/keyboard/keyboard.cpp:251-348`），无 conf 扩展口。
- **fcitx5-lua 注册输入法**：lua imeapi 只能在既有引擎上挂扩展，不能创建 InputMethodEngine。

## 方案对比

| 维度 | A. 插件 APK + C++ engine（fdroid 模式） | B. 主程序内置 C++ engine（推荐） | C. 维持现状（静态系统子类型） |
|---|---|---|---|
| 切换入口 | 输入法切换图标 + 选择列表 + 工具栏按钮 | 同左 | 系统子类型选择器 + 工具栏按钮 |
| engine 载体 | 独立插件 APK（plugin-base + cmake） | `app/src/main/cpp/qrcode/` | 无（伪子类型） |
| 相机 UI | 内置主程序（强制） | 内置主程序 | 内置主程序 |
| 分发 | 主程序 + 插件两个 APK | 单 APK | 单 APK |
| 构建复杂度 | 高（新 gradle 模块、plugin.xml、双 APK 联调） | 中（1 个 cpp 目录 + 2 个 conf + Kotlin 桥接） | 已完成 |
| 上游同步友好度 | 主程序可保持接近上游 | 改动集中在自身 repo | 改动已在自身 repo |
| 切换稳定性 | fcitx 原生切换链 | fcitx 原生切换链 | 系统子类型 hack（已踩坑：隐藏残留/状态污染） |

**推荐 B**：获得与 A 完全相同的 fcitx 原生切换体验和稳定性，避免双 APK 分发与插件联调成本；
若未来确有"按需分发"诉求，B 的 engine 代码可平移到 `plugin/qrcode` 模块即变成 A。

## 方案 B 详细设计

### 1. addon 描述 `app/src/main/assets/usr/share/fcitx5/addon/qrcode.conf`
```ini
[Addon]
Name=QR Code Scanner
Type=SharedLibrary
Library=libqrcode
Category=InputMethod
Version=0.1.3
```

### 2. 输入法描述 `app/src/main/assets/usr/share/fcitx5/inputmethod/qrcode.conf`
```ini
[InputMethod]
Name=QR Code Scanner
Icon=fcitx-qrcode
Label=QR
LangCode=zh_CN
Addon=qrcode
```

### 3. engine `app/src/main/cpp/qrcode/`（模板参照 `cpp/androidkeyboard/`）
```cpp
// qrcode.cpp 骨架（约 80 行）
class QrCodeEngine : public fcitx::InputMethodEngineV2 {
public:
    std::vector<fcitx::InputMethodEntry> listInputMethods() override {
        return { fcitx::InputMethodEntry("qrcode", _("QR Code Scanner"), "zh_CN", "qrcode")
                     .setLabel("QR").setIcon("fcitx-qrcode") };
    }
    void activate(const fcitx::InputMethodEntry &, fcitx::InputContextEvent &) override {}
    void deactivate(const fcitx::InputMethodEntry &, fcitx::InputContextEvent &) override {}
    void keyEvent(const fcitx::InputMethodEntry &, fcitx::KeyEvent &keyEvent) override {
        keyEvent.filterAndAccept();   // 吞掉所有按键：QR 面板显示期间不产生任何文本输入
    }
};
FCITX_ADDON_FACTORY(QrCodeEngineFactory)
```
CMake：`add_library(qrcode MODULE qrcode.cpp)` + `target_link_libraries(qrcode Fcitx5::Core)`；
`app/src/main/cpp/CMakeLists.txt` 加 `add_subdirectory(qrcode)`；`native-lib.cpp` 的
target_link_libraries 无需改（addon 经 FCITX_ADDON_DIRS/dlopen 加载）。

### 4. Kotlin 桥接（复用现有 QR 基建）
`FcitxInputMethodService.handleFcitxEvent` 的 `IMChangeEvent` 分支（L315 附近）：
```kotlin
is FcitxEvent.IMChangeEvent -> {
    if (event.data.uniqueName == SubtypeManager.QRCODE_SUBTYPE) {   // "qrcode"，常量恰好复用
        inputView?.windowManager?.attachWindow(QrScanWindow())
        return  // 不激活、不同步子类型（或保留同步让 SubtypeManager 建动态子类型）
    }
    if (inputView?.windowManager?.current is QrScanWindow) {
        inputView?.windowManager?.attachWindow(KeyboardWindow)      // 切回真实输入法 → 回键盘
    }
    ...原有逻辑...
}
```
配套调整：
- `input_method.xml` 移除静态 qrcode subtype（改由 `SubtypeManager.syncWith()` 动态管理）；
- `QrScanWindow.finishQrScan()`：Cancel 退出从 `switchToCurrentInputMethodSubtype()` 改为
  `postFcitxJob { activateIme(上一个真实输入法) }`（fcitx API，切换链更干净）；
- `onFinishInputView` 的 QR 清理保留（逻辑改为 activateIme 回切），继续防止隐藏残留；
- 用户在 fcitx 设置 → 输入法列表里启用"QR Code Scanner"后，切换图标/选择列表即可到达。

### 5. 迁移收益（相对现状 C）
- 切入/切出全部走 fcitx 原生 InputMethodActivated 事件链，**不再依赖系统子类型拦截 hack**；
- 退出 QR = 切回任意输入法，与用户肌肉记忆一致；
- `IMChangeEvent` 单一事实来源，`pendingQrScan` 的挂起/消费时序问题不复存在；
- 物理键盘按键被 engine 吞掉，不会向编辑器漏字符。

## 工作量评估（方案 B）

| 项 | 预估 |
|---|---|
| C++ engine + 2 个 conf + CMake 接线 | ~0.5 天（含本机 cmake/ECM 已就绪的构建验证） |
| Kotlin 桥接与现有 QR 逻辑迁移 | ~0.5 天 |
| 真机回归（切入/切出/隐藏/持续扫描/Cancel） | ~0.5 天 |
| （可选）平移为插件 APK（方案 A） | 追加 ~1 天 |

## 参考
- 插件发现/加载：`core/data/DataManager.kt`、`core/Fcitx.kt:428-457`、`cpp/androidaddonloader/`
- engine 模板：`app/src/main/cpp/androidkeyboard/`（CMake + conf.in.in + FCITX_ADDON_FACTORY）
- 官方插件：`plugin/anthy/`（native）、`plugin/clipboard-filter/`（IPC）
- 切换事件链：`cpp/androidfrontend/androidfrontend.cpp:322-340` → `core/Fcitx.kt:512-525` → `FcitxInputMethodService.kt:315`
