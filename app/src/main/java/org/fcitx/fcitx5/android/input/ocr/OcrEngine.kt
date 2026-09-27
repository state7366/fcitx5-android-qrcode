/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import android.content.Context
import android.graphics.Bitmap

/**
 * Result of one recognition pass.
 *
 * @param text recognized text; empty when nothing was found
 * @param confidence mean confidence in `[0, 1]`, or -1 when the engine cannot report it
 */
data class OcrResult(val text: String, val confidence: Float = -1f) {
    val isEmpty: Boolean get() = text.isBlank()
}

/**
 * OCRSCAN: abstraction over the text-recognition backend.
 *
 * The OCR panel depends only on this interface, so a different model (Tesseract,
 * PaddleOCR / ONNX Runtime, ML Kit, a remote service, ...) can be dropped in by
 * registering another [OcrEngineProvider] in [OcrEngineRegistry] -- no changes
 * to the window, the input-method plumbing or the build wiring are needed.
 *
 * Implementations are expected to be used from a single background thread; all
 * methods may block.
 */
interface OcrEngine {

    /** Stable id, e.g. `"tesseract"`. */
    val id: String

    /** Human readable name, shown in UI / logs. */
    val displayName: String

    /**
     * Load model files and native resources. Called off the main thread.
     * @return false when the engine cannot run (missing data, init failure, ...)
     */
    fun prepare(context: Context): Boolean

    /**
     * Recognize text from a still image. Called off the main thread, after a
     * successful [prepare].
     */
    fun recognize(bitmap: Bitmap): OcrResult

    /** Release native resources. The instance must not be used afterwards. */
    fun close()
}

/**
 * Factory for one [OcrEngine] flavour. Register instances in [OcrEngineRegistry].
 */
interface OcrEngineProvider {
    val id: String
    val displayName: String
    fun create(context: Context): OcrEngine
}
