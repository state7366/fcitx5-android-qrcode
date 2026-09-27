/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * OCRSCAN: which OCR backend is active and how it is parameterized.
 *
 * Serialized as JSON so it can be exported / imported as a single file, which is
 * how users move their API credentials between devices.
 */
@Serializable
data class OcrConfig(
    val engineId: String = TesseractProvider.ID,
    val params: Map<String, String> = emptyMap()
) {
    fun get(key: String, default: String = ""): String = params[key]?.takeIf { it.isNotEmpty() } ?: default

    companion object {
        val Default = OcrConfig()
    }
}

/** Descriptor of one configurable field shown in the OCR settings screen. */
data class OcrFieldSpec(
    val key: String,
    val label: String,
    val hint: String = "",
    /** Rendered as a password field and masked when exported. */
    val secret: Boolean = false,
    val defaultValue: String = ""
)

/** Static description of an OCR backend, used to render its settings form. */
data class OcrEngineSpec(
    val id: String,
    val displayName: String,
    /** One-liner shown under the name in the picker. */
    val description: String,
    /** true = runs on device, no image ever leaves the phone. */
    val local: Boolean,
    val fields: List<OcrFieldSpec> = emptyList()
)

/**
 * OCRSCAN: persisted OCR settings (`ocr-config.json` in app-private storage).
 */
object OcrConfigStore {

    private const val FILE_NAME = "ocr-config.json"

    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun load(context: Context): OcrConfig = runCatching {
        val f = file(context)
        if (!f.exists()) return OcrConfig.Default
        json.decodeFromString<OcrConfig>(f.readText())
    }.onFailure {
        Timber.e(it, "OcrConfigStore: failed to load config, using defaults")
    }.getOrDefault(OcrConfig.Default)

    fun save(context: Context, config: OcrConfig) {
        file(context).writeText(json.encodeToString(config))
    }

    /** Serialize for export (e.g. to a document picked by the user). */
    fun serialize(config: OcrConfig): String = json.encodeToString(config)

    /** Parse an imported file; returns null when the content is not a valid config. */
    fun parse(text: String): OcrConfig? = runCatching {
        json.decodeFromString<OcrConfig>(text)
    }.onFailure {
        Timber.e(it, "OcrConfigStore: failed to parse imported config")
    }.getOrNull()
}
