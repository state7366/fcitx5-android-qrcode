/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import android.graphics.Bitmap
import timber.log.Timber

/**
 * OCRSCAN: JNI facade for the on-device PP-OCRv5 (ncnn) recognizer.
 *
 * The native library (`libppocrv5.so`) is lazily loaded here and only when this
 * engine is actually used, so builds that never enable PP-OCRv5 do not pay for
 * the ~5 MB ncnn + opencv-mobile code in resident memory. For ABIs without
 * vendored prebuilt libs the .so is a stub whose [nativeAvailable] returns false.
 */
object PpOcrV5Native {

    init {
        runCatching { System.loadLibrary("ppocrv5") }
            .onFailure { Timber.e(it, "PpOcrV5Native: failed to load libppocrv5.so") }
    }

    private var probed = false
    private var available = false

    /** Whether the native backend is usable on this device/ABI. */
    fun available(): Boolean {
        if (!probed) {
            available = runCatching { nativeAvailable() }.getOrDefault(false)
            probed = true
        }
        return available
    }

    external fun nativeAvailable(): Boolean

    external fun nativeInit(
        detParam: String,
        detBin: String,
        recParam: String,
        recBin: String,
        numThreads: Int,
        useFp16: Boolean,
        targetSize: Int
    ): Long

    external fun nativeRelease(handle: Long)

    external fun nativeRecognize(handle: Long, bitmap: Bitmap, preprocess: String): String?
}
