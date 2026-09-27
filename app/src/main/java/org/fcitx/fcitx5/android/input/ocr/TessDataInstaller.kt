/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * OCRSCAN: locates the Tesseract traineddata directory.
 *
 * Models are no longer bundled in the APK (the user asked that all models be
 * external). They live under `/sdcard/Android/data/<pkg>/files/ocr-models/tessdata`
 * and must be downloaded or pushed manually (see [OcrModelStore]). Tesseract's
 * `init(datapath, languages)` expects `datapath` to contain a directory literally
 * named `tessdata`, so we return the *parent* of that folder as `datapath`.
 *
 * A legacy fallback to `assets/tessdata` is kept for local developer builds that
 * still vendor the files, but fresh installs get them from [OcrModelStore].
 *
 * @return the directory to pass as `datapath` (parent of `tessdata`), or null
 * when no traineddata is found
 */
object TessDataInstaller {

    private const val ASSET_DIR = "tessdata"
    private const val SUFFIX = ".traineddata"

    fun install(context: Context): File? {
        // 1) External model pack (preferred): ocr-models/tessdata/<*.traineddata>
        val extDir = OcrModelStore.dirFor(context, ASSET_DIR)
        if (extDir.exists()) {
            val names = extDir.list { _, n -> n.endsWith(SUFFIX) }
            if (!names.isNullOrEmpty() && names.all { File(extDir, it).length() > 0L }) {
                Timber.d("TessDataInstaller: using external tessdata at ${extDir.absolutePath}")
                return extDir.parentFile
            }
        }
        // 2) Legacy assets fallback (developer builds that still bundle them)
        val names = context.assets.list(ASSET_DIR)
        if (!names.isNullOrEmpty()) {
            val base = File(context.filesDir, "tesseract")
            val dir = File(base, ASSET_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                Timber.e("TessDataInstaller: cannot create ${dir.absolutePath}")
                return null
            }
            var installed = 0
            names.filter { it.endsWith(SUFFIX) }.forEach { name ->
                val out = File(dir, name)
                if (out.exists() && out.length() > 0L) {
                    installed++
                    return@forEach
                }
                runCatching {
                    context.assets.open("$ASSET_DIR/$name").use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    installed++
                    Timber.d("TessDataInstaller: installed $name (${out.length()} bytes)")
                }.onFailure {
                    Timber.e(it, "TessDataInstaller: failed to extract $name")
                    out.delete()
                }
            }
            if (installed > 0) return base
        }
        Timber.e("TessDataInstaller: no tessdata found (external or assets)")
        return null
    }
}
