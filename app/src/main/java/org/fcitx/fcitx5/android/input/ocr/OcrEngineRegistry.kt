/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import android.content.Context
import timber.log.Timber

/**
 * OCRSCAN: registry of available OCR backends.
 *
 * To add a model later: write an [OcrEngine] + [OcrEngineProvider] pair, register
 * the provider here (or call [register] from app startup), and optionally expose
 * the new [id] in preferences. Nothing else in the OCR subsystem changes.
 */
object OcrEngineRegistry {

    private val providers = linkedMapOf<String, OcrEngineProvider>()

    init {
        // Tesseract is the only bundled backend for now.
        register(TesseractProvider)
    }

    fun register(provider: OcrEngineProvider) {
        providers[provider.id] = provider
    }

    /** All registered backends, in registration order. */
    fun providers(): List<OcrEngineProvider> = providers.values.toList()

    fun provider(id: String): OcrEngineProvider? = providers[id]

    /**
     * Create an engine instance.
     * @param id backend id; when null or unknown, the first registered backend is used
     * @return null when nothing is registered
     */
    fun create(context: Context, id: String? = null): OcrEngine? {
        val provider = providers[id] ?: providers.values.firstOrNull()
        if (provider == null) {
            Timber.w("OcrEngineRegistry: no OCR backend registered")
            return null
        }
        if (id != null && providers[id] == null) {
            Timber.w("OcrEngineRegistry: unknown backend '$id', falling back to '${provider.id}'")
        }
        return provider.create(context)
    }

    const val DEFAULT_ID: String = TesseractProvider.ID
}
