/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import timber.log.Timber

/**
 * OCRSCAN: Tesseract-based [OcrEngine] (bundled `tesseract4android` AAR, which
 * ships Tesseract 5 + Leptonica + libjpeg/libpng as self-contained native libs).
 *
 * Language data (the traineddata models under `assets/tessdata`) is extracted to app-private
 * storage on first use, because Tesseract needs a real filesystem directory
 * named `tessdata` -- files inside the APK cannot be read by native code.
 */
class TesseractOcrEngine(
    private val languages: List<String> = DEFAULT_LANGUAGES
) : OcrEngine {

    @Volatile
    private var api: TessBaseAPI? = null

    override val id: String get() = TesseractProvider.ID

    override val displayName: String get() = "Tesseract"

    override fun prepare(context: Context): Boolean {
        if (api != null) return true
        val dataPath = TessDataInstaller.install(context)
        if (dataPath == null) {
            Timber.e("TesseractOcrEngine: no traineddata found in assets/tessdata")
            return false
        }
        val langs = languages.joinToString("+")
        val engine = TessBaseAPI()
        return try {
            if (!engine.init(dataPath.absolutePath, langs)) {
                Timber.e("TesseractOcrEngine: init failed for languages=$langs")
                engine.recycle()
                false
            } else {
                Timber.d("TesseractOcrEngine: ready, languages=$langs")
                api = engine
                true
            }
        } catch (e: Throwable) {
            Timber.e(e, "TesseractOcrEngine: init threw")
            runCatching { engine.recycle() }
            false
        }
    }

    override fun recognize(bitmap: Bitmap): OcrResult {
        val engine = api ?: return OcrResult("")
        return try {
            engine.setImage(bitmap)
            // Kotlin exposes the Java getter as a property, but the method form is
            // kept intentionally here: `getUTF8Text()` synthesizes to an awkward
            // property name and the explicit call is unambiguous.
            val text = engine.getUTF8Text().orEmpty()
            OcrResult(text.trim())
        } catch (e: Throwable) {
            Timber.e(e, "TesseractOcrEngine: recognition failed")
            OcrResult("")
        }
    }

    override fun close() {
        runCatching { api?.recycle() }
        api = null
    }

    companion object {
        /** Bundled languages; `chi_sim` + `eng` come from tesseract's `tessdata_fast`. */
        val DEFAULT_LANGUAGES: List<String> = listOf("chi_sim", "eng")
    }
}

/**
 * OCRSCAN: provider for [TesseractOcrEngine]. Registered by [OcrEngineRegistry].
 */
object TesseractProvider : OcrEngineProvider {

    const val ID = "tesseract"

    override val id: String get() = ID

    override val displayName: String get() = "Tesseract"

    override fun create(context: Context): OcrEngine = TesseractOcrEngine()
}
