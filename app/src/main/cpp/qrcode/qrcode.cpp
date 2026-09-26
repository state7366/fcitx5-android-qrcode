/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

/*
 * QR Code Scanner input method engine (QRSCAN).
 *
 * This engine is intentionally a stub: it only registers a "qrcode" entry in
 * fcitx's input method list so the user can switch to it with fcitx's own
 * input-method-switch UI. The actual camera panel lives in the Android layer
 * (org.fcitx.fcitx5.android.input.qrscan.QrScanWindow), which listens for
 * FcitxEvent.IMChangeEvent with uniqueName == "qrcode".
 *
 * All QRSCAN code is self-contained in this directory to keep future upstream
 * merges trivial: the only touch point outside is one add_subdirectory() line
 * in the parent CMakeLists.txt (marked with QRSCAN).
 */

#include <fcitx/addonfactory.h>
#include <fcitx/inputmethodengine.h>

namespace fcitx {

class QrCodeEngine : public InputMethodEngineV2 {
public:
    std::vector<InputMethodEntry> listInputMethods() override {
        std::vector<InputMethodEntry> result;
        // InputMethodEntry is move-only and setLabel/setIcon return lvalue refs,
        // hence the explicit std::move (same pattern as fcitx's keyboard engine).
        result.push_back(
            std::move(InputMethodEntry("qrcode", "QR Code Scanner", "zh_CN", "qrcode")
                          .setLabel("QR")
                          .setIcon("fcitx-qrcode")));
        return result;
    }

    void keyEvent(const InputMethodEntry &, KeyEvent &keyEvent) override {
        // The QR panel covers the whole keyboard area while active; swallow
        // every key so nothing leaks into the editor during scanning.
        keyEvent.filterAndAccept();
    }
};

class QrCodeEngineFactory : public AddonFactory {
public:
    AddonInstance *create(AddonManager *) override { return new QrCodeEngine; }
};

} // namespace fcitx

FCITX_ADDON_FACTORY(fcitx::QrCodeEngineFactory)
