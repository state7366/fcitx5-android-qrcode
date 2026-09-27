/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import android.content.Context
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * OCRSCAN: model storage that lives OUTSIDE the APK.
 *
 * The user asked that OCR models (PP-OCRv5 ncnn weights, Tesseract traineddata)
 * not be bundled into the install -- models can be large (PP-OCRv5 server is
 * ~170 MB) and most users only need one variant. So every model is an "external
 * pack" under:
 *
 *     /sdcard/Android/data/<pkg>/files/ocr-models/<dirName>/
 *
 * which the user can either download from inside the app (see [OcrSettingsFragment])
 * or push manually with `adb push` / a file manager. The path is intentionally
 * on external storage so it survives reinstalls and is easy to reach from a PC.
 */
object OcrModelStore {

    const val MODELS_DIR_NAME = "ocr-models"

    /**
     * Root directory for all model packs. Prefers app-specific external storage
     * (visible at the documented `/sdcard/Android/data/<pkg>/files/ocr-models`),
     * falls back to internal files dir when external storage is unavailable.
     */
    fun rootDir(context: Context): File {
        val ext = runCatching { context.getExternalFilesDir(null) }.getOrNull()
        val base = ext ?: context.filesDir
        return File(base, MODELS_DIR_NAME)
    }

    /** Directory for a single pack (override [subDir] for custom placement). */
    fun dirFor(context: Context, subDir: String): File = File(rootDir(context), subDir)

    /** Human-readable absolute root path to show in the UI / docs. */
    fun rootPath(context: Context): String = rootDir(context).absolutePath

    /**
     * One downloadable set of model files. [dirName] is the sub-folder under
     * [MODELS_DIR_NAME]; engines should resolve their model paths against
     * [dirFor].
     */
    data class OcrModelPack(
        val id: String,
        val displayName: String,
        val description: String,
        val dirName: String,
        /** Which engine consumes this pack (for UI grouping). */
        val engineId: String,
        val files: List<ModelFile>,
        /** Whether the engine cannot start without this pack. */
        val required: Boolean = true
    )

    data class ModelFile(val name: String, val url: String, val sizeBytes: Long)

    /**
     * Built-in catalog. URLs point at the upstream sources so no private hosting
     * is needed; mirrors can be added later by appending to [OcrModelPack.files].
     *
     *  - PP-OCRv5: nihui/ncnn-android-ppocrv5 `app/src/main/assets` (BSD-3, the
     *    same tree the native code is ported from).
     *  - Tesseract: official tessdata_fast (chi_sim + eng).
     */
    val CATALOG: List<OcrModelPack> = listOf(
        OcrModelPack(
            id = "ppocrv5-mobile",
            displayName = "PP-OCRv5 mobile（ncnn，约 10.6 MB）",
            description = "飞桨 PP-OCRv5 移动端模型，速度与精度均衡，默认推荐",
            dirName = "ppocrv5-mobile",
            engineId = PpOcrV5Provider.ID,
            required = true,
            files = listOf(
                ModelFile("PP_OCRv5_mobile_det.ncnn.param",
                    "https://raw.githubusercontent.com/nihui/ncnn-android-ppocrv5/master/app/src/main/assets/PP_OCRv5_mobile_det.ncnn.param",
                    24821),
                ModelFile("PP_OCRv5_mobile_det.ncnn.bin",
                    "https://raw.githubusercontent.com/nihui/ncnn-android-ppocrv5/master/app/src/main/assets/PP_OCRv5_mobile_det.ncnn.bin",
                    2357216),
                ModelFile("PP_OCRv5_mobile_rec.ncnn.param",
                    "https://raw.githubusercontent.com/nihui/ncnn-android-ppocrv5/master/app/src/main/assets/PP_OCRv5_mobile_rec.ncnn.param",
                    20031),
                ModelFile("PP_OCRv5_mobile_rec.ncnn.bin",
                    "https://raw.githubusercontent.com/nihui/ncnn-android-ppocrv5/master/app/src/main/assets/PP_OCRv5_mobile_rec.ncnn.bin",
                    8242276)
            )
        ),
        OcrModelPack(
            id = "ppocrv5-server",
            displayName = "PP-OCRv5 server（ncnn，约 171 MB）",
            description = "飞桨 PP-OCRv5 服务端大模型，精度更高但很慢、很占内存",
            dirName = "ppocrv5-server",
            engineId = PpOcrV5Provider.ID,
            required = false,
            files = listOf(
                ModelFile("PP_OCRv5_server_det.ncnn.param",
                    "https://raw.githubusercontent.com/nihui/ncnn-android-ppocrv5/master/app/src/main/assets/PP_OCRv5_server_det.ncnn.param",
                    25845),
                ModelFile("PP_OCRv5_server_det.ncnn.bin",
                    "https://raw.githubusercontent.com/nihui/ncnn-android-ppocrv5/master/app/src/main/assets/PP_OCRv5_server_det.ncnn.bin",
                    87638464),
                ModelFile("PP_OCRv5_server_rec.ncnn.param",
                    "https://raw.githubusercontent.com/nihui/ncnn-android-ppocrv5/master/app/src/main/assets/PP_OCRv5_server_rec.ncnn.param",
                    16433),
                ModelFile("PP_OCRv5_server_rec.ncnn.bin",
                    "https://raw.githubusercontent.com/nihui/ncnn-android-ppocrv5/master/app/src/main/assets/PP_OCRv5_server_rec.ncnn.bin",
                    84070276)
            )
        ),
        OcrModelPack(
            id = "tesseract-fast",
            displayName = "Tesseract 语言包（chi_sim + eng，约 6.5 MB）",
            description = "tesseract-ocr tessdata_fast；目录名必须为 tessdata",
            dirName = "tessdata",
            engineId = TesseractProvider.ID,
            required = true,
            files = listOf(
                ModelFile("chi_sim.traineddata",
                    "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/chi_sim.traineddata",
                    2469156),
                ModelFile("eng.traineddata",
                    "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/eng.traineddata",
                    4113088)
            )
        )
    )

    fun pack(id: String): OcrModelPack? = CATALOG.firstOrNull { it.id == id }

    /** True when every expected file exists and is non-empty. */
    fun isInstalled(context: Context, pack: OcrModelPack): Boolean {
        val dir = dirFor(context, pack.dirName)
        return pack.files.all { f ->
            val file = File(dir, f.name)
            file.exists() && file.length() > 0L
        }
    }

    fun installedSize(context: Context, pack: OcrModelPack): Long {
        val dir = dirFor(context, pack.dirName)
        return pack.files.sumOf { f -> runCatching { File(dir, f.name).length() }.getOrDefault(0L) }
    }

    /**
     * Total catalog size in bytes (for the "未安装时点击下载" summary).
     */
    fun totalBytes(pack: OcrModelPack): Long = pack.files.sumOf { it.sizeBytes }

    /**
     * Download a pack to [dirFor]. [onProgress] is called with (bytesDownloaded,
     * packTotalBytes); runs on the calling thread (call from a coroutine).
     * Throws on HTTP/IO failure so the caller can surface the message.
     */
    fun download(context: Context, pack: OcrModelPack, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val dir = dirFor(context, pack.dirName)
        dir.mkdirs()
        val total = totalBytes(pack).coerceAtLeast(1L)
        var done: Long = 0L
        for (file in pack.files) {
            val dest = File(dir, file.name)
            downloadFile(file.url, dest)
            done += file.sizeBytes
            onProgress(done, total)
        }
        Timber.d("OcrModelStore: downloaded pack '${pack.id}' into ${dir.absolutePath}")
    }

    private fun downloadFile(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "fcitx5-android-ocr-model-fetcher")
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw RuntimeException("下载失败 HTTP $code: $url")
            }
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (dest.length() == 0L) {
                dest.delete()
                throw RuntimeException("下载内容为空: $url")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Remove a pack's files (best-effort). */
    fun delete(context: Context, pack: OcrModelPack): Boolean {
        val dir = dirFor(context, pack.dirName)
        var ok = true
        pack.files.forEach { f ->
            val file = File(dir, f.name)
            if (file.exists() && !file.delete()) ok = false
        }
        runCatching { dir.delete() }
        return ok
    }
}
