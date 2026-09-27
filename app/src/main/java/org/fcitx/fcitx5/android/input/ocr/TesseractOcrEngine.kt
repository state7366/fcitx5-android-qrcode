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
 * OCRSCAN: Tesseract-based [OcrEngine] (vendored `tesseract4android` AAR, which
 * ships Tesseract 5 + Leptonica + libjpeg/libpng as self-contained native libs).
 *
 * Language data is NOT bundled: it lives in the external model pack directory
 * (`files/ocr-models/tessdata`, see [OcrModelStore]) and must be downloaded or
 * pushed manually. A legacy `assets/tessdata` fallback remains for developer
 * builds that still vendor the files.
 */
class TesseractOcrEngine(
    private val languages: List<String> = DEFAULT_LANGUAGES
) : OcrEngine {

    @Volatile
    private var api: TessBaseAPI? = null

    @Volatile
    private var prepareErr: String? = null

    override val prepareError: String? get() = prepareErr

    override val id: String get() = TesseractProvider.ID

    override val displayName: String get() = "Tesseract"

    override fun prepare(context: Context): Boolean {
        if (api != null) return true
        prepareErr = null
        val dataPath = TessDataInstaller.install(context)
        if (dataPath == null) {
            prepareErr = "语言数据未下载：tessdata（OCR 设置 → 模型）"
            Timber.e("TesseractOcrEngine: no traineddata found")
            return false
        }
        val langs = languages.joinToString("+")
        val engine = TessBaseAPI()
        return try {
            if (!engine.init(dataPath.absolutePath, langs)) {
                prepareErr = "Tesseract 初始化失败（languages=$langs）"
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

    const val KEY_LANGUAGES = "languages"

    override val id: String get() = ID

    override val displayName: String get() = "Tesseract（本地）"

    override val spec: OcrEngineSpec = OcrEngineSpec(
        id = ID,
        displayName = "Tesseract（本地）",
        description = "完全离线，图片不出手机；中文印刷体效果尚可。语言包已改为外置，需在「模型」中下载",
        local = true,
        fields = listOf(
            OcrFieldSpec(
                KEY_LANGUAGES, "语言",
                defaultValue = TesseractOcrEngine.DEFAULT_LANGUAGES.joinToString("+"),
                hint = "需与已下载的语言包对应，如 chi_sim+eng"
            )
        )
    )

    override fun create(context: Context, config: OcrConfig): OcrEngine {
        val langs = config.get(KEY_LANGUAGES, TesseractOcrEngine.DEFAULT_LANGUAGES.joinToString("+"))
            .split('+').map { it.trim() }.filter { it.isNotEmpty() }
        return TesseractOcrEngine(langs.ifEmpty { TesseractOcrEngine.DEFAULT_LANGUAGES })
    }
}
