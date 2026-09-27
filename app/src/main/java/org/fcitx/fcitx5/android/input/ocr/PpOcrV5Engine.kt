/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import android.content.Context
import android.graphics.Bitmap
import timber.log.Timber

/**
 * OCRSCAN: on-device PP-OCRv5 (Baidu PaddleOCR, ncnn build) text recognizer.
 *
 * Models are NOT bundled (see [OcrModelStore]): they live under
 * `/sdcard/Android/data/<pkg>/files/ocr-models/<dirName>` and must be downloaded
 * or pushed manually before the engine can be used. `prepare()` therefore fails
 * fast with a readable [OcrResult.error] when the weights are missing, and the
 * settings screen links to the download.
 *
 * Before inference the native side applies a mild preprocessing pass (upscale +
 * CLAHE by default) configured via the [KEY_PREPROCESS] field.
 */
class PpOcrV5Engine(
    private val modelDir: String = DEFAULT_MODEL_DIR,
    private val targetSize: Int = DEFAULT_TARGET_SIZE,
    private val numThreads: Int = DEFAULT_THREADS,
    private val preprocess: String = DEFAULT_PREPROCESS,
    private val useFp16: Boolean = DEFAULT_USE_FP16
) : OcrEngine {

    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var prepareErr: String? = null

    override val prepareError: String? get() = prepareErr

    override val id: String get() = PpOcrV5Provider.ID

    override val displayName: String get() = "PP-OCRv5（本地 ncnn）"

    override fun prepare(context: Context): Boolean {
        if (handle != 0L) return true
        prepareErr = null
        if (!PpOcrV5Native.available()) {
            prepareErr = "当前 ABI 不支持 PP-OCRv5（缺少 ncnn 后端）"
            Timber.e("PpOcrV5Engine: native backend unavailable on this ABI")
            return false
        }
        val pack = OcrModelStore.CATALOG.firstOrNull { it.dirName == modelDir }
        val dir = OcrModelStore.dirFor(context, modelDir)
        if (pack != null && !OcrModelStore.isInstalled(context, pack)) {
            prepareErr = "模型未下载：$modelDir（OCR 设置 → 模型）"
            Timber.e("PpOcrV5Engine: model pack '${pack.id}' not installed at ${dir.absolutePath}")
            return false
        }
        val variant = modelDir.removePrefix("ppocrv5-").takeIf { it.isNotEmpty() } ?: "mobile"
        val detParam = java.io.File(dir, "PP_OCRv5_${variant}_det.ncnn.param")
        val detBin = java.io.File(dir, "PP_OCRv5_${variant}_det.ncnn.bin")
        val recParam = java.io.File(dir, "PP_OCRv5_${variant}_rec.ncnn.param")
        val recBin = java.io.File(dir, "PP_OCRv5_${variant}_rec.ncnn.bin")
        if (listOf(detParam, detBin, recParam, recBin).any { !it.exists() || it.length() == 0L }) {
            prepareErr = "模型文件缺失（$modelDir）"
            Timber.e("PpOcrV5Engine: missing weight file in ${dir.absolutePath}")
            return false
        }
        val h = runCatching {
            PpOcrV5Native.nativeInit(
                detParam.absolutePath, detBin.absolutePath,
                recParam.absolutePath, recBin.absolutePath,
                numThreads, useFp16, targetSize
            )
        }.getOrDefault(0L)
        return if (h != 0L) {
            handle = h
            Timber.d("PpOcrV5Engine: ready (variant=$variant, targetSize=$targetSize, threads=$numThreads)")
            true
        } else {
            prepareErr = "PP-OCRv5 初始化失败"
            Timber.e("PpOcrV5Engine: nativeInit returned 0")
            false
        }
    }

    override fun recognize(bitmap: Bitmap): OcrResult {
        if (handle == 0L) return OcrResult("", error = "PP-OCRv5 引擎未就绪")
        return runCatching {
            val text = PpOcrV5Native.nativeRecognize(handle, bitmap, preprocess).orEmpty()
            OcrResult(text.trim())
        }.getOrElse {
            Timber.e(it, "PpOcrV5Engine: recognition failed")
            OcrResult("", error = it.message)
        }
    }

    override fun close() {
        if (handle != 0L) {
            runCatching { PpOcrV5Native.nativeRelease(handle) }
            handle = 0L
        }
    }

    companion object {
        const val DEFAULT_MODEL_DIR = "ppocrv5-mobile"
        const val DEFAULT_TARGET_SIZE = 960
        const val DEFAULT_THREADS = 4
        const val DEFAULT_PREPROCESS = "upscale,clahe"
        const val DEFAULT_USE_FP16 = false
    }
}

/**
 * OCRSCAN: provider for [PpOcrV5Engine]. Registered by [OcrEngineRegistry].
 * Registered first because it is the highest-accuracy offline backend for CJK.
 */
object PpOcrV5Provider : OcrEngineProvider {

    const val ID = "ppocrv5"

    const val KEY_MODEL = "model"
    const val KEY_TARGET_SIZE = "target_size"
    const val KEY_THREADS = "threads"
    const val KEY_PREPROCESS = "preprocess"

    override val id: String get() = ID

    override val displayName: String get() = "PP-OCRv5（本地 ncnn）"

    override val spec: OcrEngineSpec = OcrEngineSpec(
        id = ID,
        displayName = displayName,
        description = "百度飞桨 PP-OCRv5 的 ncnn 本地模型，中文印刷体/OCR 效果好；需先下载模型",
        local = true,
        fields = listOf(
            OcrFieldSpec(
                KEY_MODEL, "模型目录",
                defaultValue = PpOcrV5Engine.DEFAULT_MODEL_DIR,
                hint = "ocr-models 下的子目录：ppocrv5-mobile（默认）或 ppocrv5-server"
            ),
            OcrFieldSpec(
                KEY_TARGET_SIZE, "检测缩放边长",
                defaultValue = PpOcrV5Engine.DEFAULT_TARGET_SIZE.toString(),
                hint = "640 / 960 / 1280，越大越慢但小字更准"
            ),
            OcrFieldSpec(
                KEY_THREADS, "线程数",
                defaultValue = PpOcrV5Engine.DEFAULT_THREADS.toString(),
                hint = "检测网络内部线程数"
            ),
            OcrFieldSpec(
                KEY_PREPROCESS, "本地预处理",
                defaultValue = PpOcrV5Engine.DEFAULT_PREPROCESS,
                hint = "可组合：upscale, clahe, sharpen, denoise，或 none"
            )
        )
    )

    override fun create(context: Context, config: OcrConfig): OcrEngine {
        val model = config.get(KEY_MODEL, PpOcrV5Engine.DEFAULT_MODEL_DIR)
        val targetSize = config.get(KEY_TARGET_SIZE, PpOcrV5Engine.DEFAULT_TARGET_SIZE.toString())
            .toIntOrNull() ?: PpOcrV5Engine.DEFAULT_TARGET_SIZE
        val threads = config.get(KEY_THREADS, PpOcrV5Engine.DEFAULT_THREADS.toString())
            .toIntOrNull() ?: PpOcrV5Engine.DEFAULT_THREADS
        val preprocess = config.get(KEY_PREPROCESS, PpOcrV5Engine.DEFAULT_PREPROCESS)
        return PpOcrV5Engine(
            modelDir = model.ifEmpty { PpOcrV5Engine.DEFAULT_MODEL_DIR },
            targetSize = targetSize,
            numThreads = threads,
            preprocess = preprocess.ifEmpty { PpOcrV5Engine.DEFAULT_PREPROCESS }
        )
    }
}
