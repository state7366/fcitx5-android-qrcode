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
 * To add a model later: write an [OcrEngine] + [OcrEngineProvider] pair (the
 * provider also declares an [OcrEngineSpec] describing its settings fields) and
 * register it here. Nothing else in the OCR subsystem changes -- the settings
 * screen and the scan panel are driven by [specs].
 */
object OcrEngineRegistry {

    private val providers = linkedMapOf<String, OcrEngineProvider>()

    init {
        // 常用的排前面：本地离线、以及手机端白描的局域网服务
        register(TesseractProvider)
        register(BaimiaoWifiProvider)
        // Cloud / self-hosted
        register(BaiduProvider)
        register(TencentProvider)
        register(CustomHttpProvider)
    }

    fun register(provider: OcrEngineProvider) {
        providers[provider.id] = provider
    }

    /** All registered backends, in registration order. */
    fun providers(): List<OcrEngineProvider> = providers.values.toList()

    /** Descriptors for the settings screen. */
    fun specs(): List<OcrEngineSpec> = providers.values.map { it.spec }

    fun provider(id: String): OcrEngineProvider? = providers[id]

    fun spec(id: String): OcrEngineSpec? = providers[id]?.spec

    /**
     * Create the engine described by [config]. Falls back to the local Tesseract
     * backend when the configured id is unknown (e.g. config imported from a
     * build without that backend).
     */
    fun create(context: Context, config: OcrConfig = OcrConfigStore.load(context)): OcrEngine? {
        val provider = providers[config.engineId]
            ?: providers[TesseractProvider.ID].also {
                Timber.w("OcrEngineRegistry: unknown backend '${config.engineId}', using ${TesseractProvider.ID}")
            }
        return provider?.create(context, config)
    }

    const val DEFAULT_ID: String = TesseractProvider.ID
}
