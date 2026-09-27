/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.qrscan.QrScanPermissionActivity
import org.fcitx.fcitx5.android.input.qrscan.QrScanWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import timber.log.Timber
import java.util.concurrent.Executors
import android.content.BroadcastReceiver

/**
 * OCRSCAN: camera panel for the "ocr" input method.
 *
 * Mirrors [QrScanWindow], but instead of decoding a barcode it runs a still frame
 * through an [OcrEngine] obtained from [OcrEngineRegistry]. Unlike QR scanning,
 * recognition is single-shot (tap to capture) because OCR is far too slow to run
 * on every frame.
 */
class OcrScanWindow : InputWindow.ExtendedInputWindow<OcrScanWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()

    private val lifecycleOwner = object : androidx.lifecycle.LifecycleOwner {
        private val registry = androidx.lifecycle.LifecycleRegistry(this)
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        fun create() { registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
        fun resume() { registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        fun destroy() { registry.currentState = androidx.lifecycle.Lifecycle.State.DESTROYED }
    }

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var captureButton: TextView
    private lateinit var commitButton: TextView
    private lateinit var copyButton: TextView

    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzer: CaptureAnalyzer? = null

    private val worker = Executors.newSingleThreadExecutor()
    private val mainExecutor by lazy { ContextCompat.getMainExecutor(context) }

    @Volatile
    private var engine: OcrEngine? = null

    @Volatile
    private var busy = false

    /** Recognized text waiting to be committed / copied. */
    private var pendingText: String = ""

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != QrScanWindow.ACTION_QR_PERMISSION_RESULT) return
            if (intent.getBooleanExtra(QrScanWindow.EXTRA_GRANTED, false)) {
                startCamera()
            } else {
                statusText.setText(R.string.ocr_permission_required)
                finishOcrScan()
            }
        }
    }

    override val title: String by lazy { context.getString(R.string.ocr_scan) }

    override fun onCreateView(): View {
        previewView = PreviewView(context).apply {
            // Same rationale as QrScanWindow: TextureView stays inside the IME
            // window, a SurfaceView would escape it.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        statusText = context.textView {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            text = context.getString(R.string.ocr_scan_hint)
        }
        resultText = context.textView {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            text = ""
        }
        val resultScroll = ScrollView(context).apply {
            backgroundColor = Color.argb(160, 0, 0, 0)
            addView(resultText)
        }
        captureButton = actionButton(context.getString(R.string.ocr_capture)) { requestCapture() }
        commitButton = actionButton(context.getString(R.string.ocr_commit)) { commitPending() }
        copyButton = actionButton(context.getString(R.string.ocr_copy)) { copyPending() }
        commitButton.isEnabled = false
        copyButton.isEnabled = false
        val buttonBar = context.horizontalLayout {
            add(captureButton, lParams(wrapContent, wrapContent))
            add(commitButton, lParams(wrapContent, wrapContent))
            add(copyButton, lParams(wrapContent, wrapContent))
        }
        val cancelButton = actionButton(context.getString(android.R.string.cancel)) { finishOcrScan() }
        return context.frameLayout {
            backgroundColor = Color.BLACK
            add(previewView, lParams(matchParent, matchParent))
            add(resultScroll, lParams(matchParent, dp(RESULT_PANEL_DP)) {
                gravity = Gravity.BOTTOM
                bottomMargin = dp(56)
            })
            add(buttonBar, lParams(wrapContent, wrapContent) {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(8)
            })
            add(statusText, lParams(wrapContent, wrapContent) {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
            })
            add(cancelButton, lParams(wrapContent, wrapContent) {
                gravity = Gravity.TOP or Gravity.START
                topMargin = dp(8)
                marginStart = dp(8)
            })
        }
    }

    private fun actionButton(label: String, onClick: () -> Unit) = context.textView {
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        text = label
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { onClick() }
    }

    override fun onAttached() {
        ContextCompat.registerReceiver(
            context, permissionReceiver,
            IntentFilter(QrScanWindow.ACTION_QR_PERMISSION_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        lifecycleOwner.create()
        if (hasCameraPermission()) {
            startCamera()
        } else {
            statusText.setText(R.string.ocr_permission_required)
            context.startActivity(Intent(context, QrScanPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        prepareEngine()
    }

    override fun onDetached() {
        runCatching { context.unregisterReceiver(permissionReceiver) }
        lifecycleOwner.destroy()
        cameraProvider?.unbindAll()
        cameraProvider = null
        analyzer = null
        busy = false
        pendingText = ""
        service.pendingScanPanel = null
        val e = engine
        engine = null
        worker.execute { runCatching { e?.close() } }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun prepareEngine() {
        worker.execute {
            val created = OcrEngineRegistry.create(context)
            val ok = created?.let { runCatching { it.prepare(context) }.getOrElse { false } } ?: false
            mainExecutor.execute {
                if (!windowManager.isAttached(this)) {
                    worker.execute { runCatching { created?.close() } }
                    return@execute
                }
                if (ok) {
                    engine = created
                    Timber.d("OcrScan: engine ready (${created?.id})")
                } else {
                    runCatching { created?.close() }
                    statusText.setText(R.string.ocr_engine_unavailable)
                    captureButton.isEnabled = false
                }
            }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                if (lifecycleOwner.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.DESTROYED) return@addListener
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analyzer = CaptureAnalyzer { onFrameCaptured(it) }.also {
                    analysis.setAnalyzer(worker, it)
                }
                lifecycleOwner.resume()
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, cameraSelector(), preview, analysis)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start OCR camera")
                statusText.setText(R.string.ocr_camera_error)
            }
        }, mainExecutor)
    }

    private fun cameraSelector(): CameraSelector = try {
        CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()
    } catch (_: Exception) {
        CameraSelector.DEFAULT_BACK_CAMERA
    }

    private fun requestCapture() {
        if (busy) return
        busy = true
        pendingText = ""
        commitButton.isEnabled = false
        copyButton.isEnabled = false
        statusText.setText(R.string.ocr_recognizing)
        resultText.text = ""
        analyzer?.requested = true
    }

    private fun onFrameCaptured(bitmap: Bitmap) {
        val e = engine
        if (e == null) {
            onRecognized(OcrResult(""))
            return
        }
        val result = runCatching { e.recognize(bitmap) }.getOrElse {
            Timber.e(it, "OcrScan: recognition failed")
            OcrResult("")
        }
        onRecognized(result)
    }

    private fun onRecognized(result: OcrResult) {
        mainExecutor.execute {
            if (!windowManager.isAttached(this)) return@execute
            busy = false
            if (result.isEmpty) {
                statusText.setText(R.string.ocr_result_empty)
                resultText.text = ""
                commitButton.isEnabled = false
                copyButton.isEnabled = false
                return@execute
            }
            pendingText = result.text
            resultText.text = result.text
            commitButton.isEnabled = true
            copyButton.isEnabled = true
            statusText.setText(R.string.ocr_recognized)
        }
    }

    private fun commitPending() {
        val text = pendingText
        if (text.isEmpty()) return
        service.commitText(text)
        statusText.setText(R.string.ocr_committed)
    }

    private fun copyPending() {
        val text = pendingText
        if (text.isEmpty()) return
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ocr", text))
            statusText.setText(R.string.ocr_copied)
        }
    }

    /**
     * Leave the OCR panel and return to the normal keyboard. Switches fcitx back to
     * the last real input method; the resulting IMChangeEvent keeps service-side
     * state consistent (single source of truth).
     */
    private fun finishOcrScan() {
        if (windowManager.isAttached(this)) {
            windowManager.attachWindow(KeyboardWindow)
            service.postFcitxJob {
                activateIme(service.lastRealImBeforeScan ?: "keyboard-us")
            }
        }
    }

    /** Grabs one frame when [requested] is set; drops everything else. */
    private class CaptureAnalyzer(private val onFrame: (Bitmap) -> Unit) : ImageAnalysis.Analyzer {

        @Volatile
        var requested = false

        override fun analyze(image: ImageProxy) {
            if (!requested) {
                image.close()
                return
            }
            requested = false
            val rotation = image.imageInfo.rotationDegrees
            val raw = image.toBitmap()
            image.close()
            val bitmap = if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            } else raw
            onFrame(bitmap)
        }
    }

    companion object {
        private const val RESULT_PANEL_DP = 96
    }
}
