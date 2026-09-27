/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

/*
 * OCR (text recognition) input method engine (OCRSCAN).
 *
 * Mirrors the QRSCAN qrcode engine: this is intentionally a stub that only
 * registers an "ocr" entry in fcitx's input method list, so the user can
 * switch to it with fcitx's own input-method-switch UI. The actual camera
 * panel lives in the Android layer
 * (org.fcitx.fcitx5.android.input.ocr.OcrScanWindow), which is opened when
 * FcitxEvent.IMChangeEvent reports uniqueName == "ocr".
 *
 * All OCRSCAN code is self-contained in this directory to keep future upstream
 * merges trivial: the only touch point outside is one add_subdirectory() line
 * in the parent CMakeLists.txt (marked with OCRSCAN).
 */

#include <fcitx/addonfactory.h>
#include <fcitx/inputmethodengine.h>

namespace fcitx {

class OcrEngine : public InputMethodEngineV2 {
public:
    std::vector<InputMethodEntry> listInputMethods() override {
        std::vector<InputMethodEntry> result;
        // InputMethodEntry is move-only and setLabel/setIcon return lvalue refs,
        // hence the explicit std::move (same pattern as fcitx's keyboard engine).
        result.push_back(
            std::move(InputMethodEntry("ocr", "OCR Text Scanner", "zh_CN", "ocr")
                          .setLabel("OCR")
                          .setIcon("fcitx-ocr")));
        return result;
    }

    void keyEvent(const InputMethodEntry &, KeyEvent &keyEvent) override {
        // The OCR panel covers the whole keyboard area while active; swallow
        // every key so nothing leaks into the editor during recognition.
        keyEvent.filterAndAccept();
    }
};

class OcrEngineFactory : public AddonFactory {
public:
    AddonInstance *create(AddonManager *) override { return new OcrEngine; }
};

} // namespace fcitx

FCITX_ADDON_FACTORY(fcitx::OcrEngineFactory)
