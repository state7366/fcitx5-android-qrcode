/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * OCRSCAN: extracts the bundled traineddata models under `assets/tessdata`
 * into app-private storage.
 *
 * Tesseract's `init(datapath, languages)` expects `datapath` to contain a
 * directory literally named `tessdata`; assets inside the APK are not reachable
 * by native code, so the models must be copied out once.
 *
 * @return the directory to pass as `datapath` (parent of `tessdata`), or null
 * when no traineddata is bundled
 */
object TessDataInstaller {

    private const val ASSET_DIR = "tessdata"
    private const val SUFFIX = ".traineddata"

    fun install(context: Context): File? {
        val base = File(context.filesDir, "tesseract")
        val dir = File(base, ASSET_DIR)
        val names = context.assets.list(ASSET_DIR)
        if (names.isNullOrEmpty()) {
            Timber.e("TessDataInstaller: assets/$ASSET_DIR is empty")
            return null
        }
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
        return if (installed > 0) base else null
    }
}
