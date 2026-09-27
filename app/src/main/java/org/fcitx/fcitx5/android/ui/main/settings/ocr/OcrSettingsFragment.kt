/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.ocr

import android.content.Context
import android.os.Bundle
import android.text.InputType
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ocr.OcrConfig
import org.fcitx.fcitx5.android.input.ocr.OcrConfigStore
import org.fcitx.fcitx5.android.input.ocr.OcrEngineRegistry
import org.fcitx.fcitx5.android.input.ocr.OcrFieldSpec
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.iso8601UTCDateTime
import org.fcitx.fcitx5.android.utils.toast

/**
 * OCRSCAN: settings for the OCR input method.
 *
 * The form is description-driven: every backend publishes an
 * [org.fcitx.fcitx5.android.input.ocr.OcrEngineSpec] with the fields it needs, so
 * adding a model later only means registering another provider in
 * [OcrEngineRegistry] -- this screen picks it up automatically.
 */
class OcrSettingsFragment : PaddingPreferenceFragment() {

    /** Working copy; committed to [OcrConfigStore] on every edit. */
    private var working: OcrConfig = OcrConfig.Default

    private lateinit var enginePref: Preference
    private lateinit var paramsCategory: PreferenceCategory

    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private lateinit var importLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                lifecycleScope.launch {
                    runCatching {
                        val payload = OcrConfigStore.serialize(working)
                        withContext(Dispatchers.IO) {
                            ctx.contentResolver.openOutputStream(uri)!!.use {
                                it.write(payload.toByteArray())
                            }
                        }
                    }.onSuccess {
                        ctx.toast(R.string.ocr_config_exported)
                    }.onFailure {
                        ctx.toast(it)
                    }
                }
            }
        importLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                lifecycleScope.launch {
                    val parsed = runCatching {
                        val text = withContext(Dispatchers.IO) {
                            ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                        }
                        OcrConfigStore.parse(text)
                    }.onFailure {
                        ctx.toast(it)
                    }.getOrNull()
                    if (parsed == null) {
                        ctx.toast(R.string.ocr_config_invalid)
                        return@launch
                    }
                    // An unknown backend (config from a build that had it) falls back to
                    // Tesseract inside the registry; keep the raw id so the user notices.
                    working = parsed
                    OcrConfigStore.save(ctx, working)
                    refreshEngineSummary()
                    rebuildParams()
                    ctx.toast(R.string.ocr_config_imported)
                }
            }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        working = OcrConfigStore.load(ctx)
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            enginePref = Preference(ctx).apply {
                isSingleLineTitle = false
                isIconSpaceReserved = false
                title = ctx.getString(R.string.ocr_engine)
                setOnPreferenceClickListener {
                    showEnginePicker()
                    true
                }
            }
            addPreference(enginePref)
            paramsCategory = PreferenceCategory(ctx).apply {
                setTitle(R.string.ocr_engine_params)
            }
            addPreference(paramsCategory)
            addPreference(R.string.ocr_export_config) {
                exportLauncher.launch("fcitx5-ocr-config_${iso8601UTCDateTime()}.json")
            }
            addPreference(R.string.ocr_import_config) {
                importLauncher.launch("application/json")
            }
        }
        refreshEngineSummary()
        rebuildParams()
    }

    private fun showEnginePicker() {
        val ctx = requireContext()
        val specs = OcrEngineRegistry.specs()
        val current = specs.indexOfFirst { it.id == working.engineId }
        val items = specs.map {
            "${it.displayName}  ・  ${ctx.getString(kindLabel(it.local))}"
        }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.ocr_select_engine)
            .setSingleChoiceItems(items, current) { dialog, which ->
                val spec = specs[which]
                // Values are carried over as-is, so switching back and forth does not
                // wipe credentials that were already typed in.
                working = working.copy(engineId = spec.id)
                OcrConfigStore.save(ctx, working)
                refreshEngineSummary()
                rebuildParams()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun kindLabel(local: Boolean) =
        if (local) R.string.ocr_engine_local else R.string.ocr_engine_cloud

    private fun refreshEngineSummary() {
        val spec = OcrEngineRegistry.spec(working.engineId)
        enginePref.summary = spec?.let {
            "${it.displayName}  ・  ${getString(kindLabel(it.local))}\n${it.description}"
        } ?: working.engineId
    }

    private fun rebuildParams() {
        val ctx = requireContext()
        val spec = OcrEngineRegistry.spec(working.engineId)
        paramsCategory.removeAll()
        if (spec == null || spec.fields.isEmpty()) {
            paramsCategory.addPreference(R.string.ocr_no_params)
            return
        }
        spec.fields.forEach { field ->
            paramsCategory.addPreference(OcrParamPreference(ctx).apply {
                key = "ocr_${spec.id}_${field.key}"
                title = field.label
                dialogTitle = field.label
                // "Restore default" in the dialog reads this value.
                setDefaultValue(field.defaultValue)
                text = working.get(field.key, field.defaultValue)
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    summarize(field, pref.text)
                }
                setOnBindEditTextListener { editText ->
                    editText.hint = field.hint
                    editText.inputType = if (field.secret) {
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    } else {
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    }
                    editText.setSingleLine(field.secret)
                    editText.setSelection(editText.text?.length ?: 0)
                }
                setOnPreferenceChangeListener { _, newValue ->
                    working = working.copy(params = working.params + (field.key to newValue.toString()))
                    OcrConfigStore.save(ctx, working)
                    true
                }
            })
        }
    }

    private fun summarize(field: OcrFieldSpec, value: String?): String {
        if (value.isNullOrEmpty()) return getString(R.string.ocr_not_set)
        return if (field.secret) getString(R.string.ocr_secret_filled) else value
    }

    /**
     * EditTextPreference whose value lives in [OcrConfigStore] (a JSON file) instead of
     * SharedPreferences, so it must not initialize itself from the default value.
     */
    private class OcrParamPreference(context: Context) : EditTextPreference(context) {
        init {
            isPersistent = false
            isSingleLineTitle = false
            isIconSpaceReserved = false
        }

        override fun onSetInitialValue(defaultValue: Any?) = Unit
    }
}
