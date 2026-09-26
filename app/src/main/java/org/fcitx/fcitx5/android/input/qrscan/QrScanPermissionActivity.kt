/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.qrscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Transparent activity used solely to surface the system camera-permission dialog,
 * because an InputMethodService cannot show permission prompts directly.
 * The result is relayed back to [QrScanWindow] via a same-UID broadcast.
 */
class QrScanPermissionActivity : AppCompatActivity() {

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            sendResult(granted)
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            sendResult(true)
            finish()
            return
        }
        requestPermission.launch(Manifest.permission.CAMERA)
    }

    private fun sendResult(granted: Boolean) {
        val intent = Intent(QrScanWindow.ACTION_QR_PERMISSION_RESULT).apply {
            putExtra(QrScanWindow.EXTRA_GRANTED, granted)
        }
        sendBroadcast(intent)
    }
}
