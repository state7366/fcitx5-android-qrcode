/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.util.Size
import android.view.Gravity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.fcitx.fcitx5.android.BuildConfig
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
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent
import timber.log.Timber
import java.util.concurrent.Executors

/**
 * OCRSCAN: camera panel for the "ocr" input method.
 *
 * Mirrors [QrScanWindow], but instead of decoding a barcode it runs a still frame
 * through an [OcrEngine] obtained from [OcrEngineRegistry] (the active backend is
 * chosen in the OCR settings screen). Recognition is single-shot -- OCR is far
 * too slow to run on every frame.
 */
class OcrScanWindow : InputWindow.ExtendedInputWindow<OcrScanWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()

    private val lifecycleOwner = object : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun create() { registry.currentState = Lifecycle.State.CREATED }
        fun resume() { registry.currentState = Lifecycle.State.RESUMED }
        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
    }

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var engineText: TextView
    private lateinit var resultText: TextView
    private lateinit var resultCard: ScrollView
    private lateinit var engineMenu: FrameLayout
    private lateinit var engineMenuList: LinearLayout
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
        engineText = context.textView {
            setTextColor(Color.argb(210, 255, 255, 255))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.argb(120, 24, 24, 24))
                cornerRadius = dp(10).toFloat()
            }
            // OCRSCAN: quick engine switch without leaving the panel
            setOnClickListener { showEngineMenu() }
        }
        resultText = context.textView {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            text = ""
        }
        resultCard = ScrollView(context).apply {
            background = roundedDrawable(Color.argb(150, 24, 24, 24), dp(10).toFloat())
            addView(resultText)
            visibility = View.GONE
        }
        captureButton = pillButton(context.getString(R.string.ocr_capture)) { requestCapture() }
        commitButton = pillButton(context.getString(R.string.ocr_commit)) { commitPending() }
        copyButton = pillButton(context.getString(R.string.ocr_copy)) { copyPending() }
        setActionsEnabled(false)
        val buttonBar = context.horizontalLayout {
            add(captureButton, lParams(wrapContent, wrapContent))
            add(commitButton, lParams(wrapContent, wrapContent) { marginStart = dp(8) })
            add(copyButton, lParams(wrapContent, wrapContent) { marginStart = dp(8) })
        }
        val cancelButton = pillButton(context.getString(android.R.string.cancel)) { finishOcrScan() }
        // OCRSCAN: the engine picker must live *inside* the IME view tree.
        // A PopupMenu / Dialog creates its own focusable Window, which steals window
        // focus from the host Activity: the editor loses focus, the input connection
        // is finished (onFinishInput) and the IME is torn down and rebuilt -- the
        // keyboard visibly disappears and comes back a moment later.
        engineMenuList = context.verticalLayout {
            background = roundedDrawable(Color.argb(235, 30, 30, 30), dp(12).toFloat())
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        // 引擎多了以后列表会顶出面板，而 IME 里不能弹 Dialog/PopupWindow（会抢焦点
        // 拆掉键盘），所以自己套一个限高的 ScrollView：超出的部分可以滚动。
        val engineMenuScroll = MaxHeightScrollView(context, context.dp(ENGINE_MENU_MAX_DP)).apply {
            isVerticalScrollBarEnabled = false
            addView(engineMenuList, FrameLayout.LayoutParams(wrapContent, wrapContent).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            })
        }
        val scrim = View(context).apply {
            setBackgroundColor(Color.argb(90, 0, 0, 0))
            setOnClickListener { hideEngineMenu() }
        }
        engineMenu = context.frameLayout {
            add(scrim, lParams(matchParent, matchParent))
            add(engineMenuScroll, lParams(wrapContent, wrapContent) {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(54)
            })
            visibility = View.GONE
        }
        return context.frameLayout {
            add(previewView, lParams(matchParent, matchParent))
            add(resultCard, lParams(matchParent, dp(RESULT_CARD_DP)) {
                gravity = Gravity.BOTTOM
                bottomMargin = dp(60)
                marginStart = dp(12)
                marginEnd = dp(12)
            })
            add(buttonBar, lParams(wrapContent, wrapContent) {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(10)
            })
            add(statusText, lParams(wrapContent, wrapContent) {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(10)
            })
            add(engineText, lParams(wrapContent, wrapContent) {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(28)
            })
            add(cancelButton, lParams(wrapContent, wrapContent) {
                gravity = Gravity.TOP or Gravity.START
                topMargin = dp(6)
                marginStart = dp(6)
            })
            add(engineMenu, lParams(matchParent, matchParent))
        }
    }

    private fun roundedDrawable(fill: Int, radius: Float) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius
    }

    private fun pillButton(label: String, onClick: () -> Unit) = context.textView {
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        text = label
        setPadding(dp(16), dp(9), dp(16), dp(9))
        background = GradientDrawable().apply {
            setColor(Color.argb(170, 32, 32, 32))
            cornerRadius = dp(18).toFloat()
        }
        setOnClickListener { onClick() }
    }

    private fun setActionsEnabled(enabled: Boolean) {
        commitButton.isEnabled = enabled
        commitButton.alpha = if (enabled) 1f else 0.45f
        copyButton.isEnabled = enabled
        copyButton.alpha = if (enabled) 1f else 0.45f
    }

    override fun onAttached() {
        ContextCompat.registerReceiver(
            context, permissionReceiver,
            IntentFilter(QrScanWindow.ACTION_QR_PERMISSION_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        lifecycleOwner.create()
        val config = OcrConfigStore.load(context)
        showEngineName(config)
        if (hasCameraPermission()) {
            startCamera()
        } else {
            statusText.setText(R.string.ocr_permission_required)
            context.startActivity(Intent(context, QrScanPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        prepareEngine(config)
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
        releaseEngine()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showEngineName(config: OcrConfig) {
        val name = OcrEngineRegistry.spec(config.engineId)?.displayName ?: config.engineId
        engineText.text = "$name ▾"
    }

    /** OCRSCAN: pick another backend on the fly (the full form lives in Settings). */
    private fun showEngineMenu() {
        if (engineMenu.visibility == View.VISIBLE) {
            hideEngineMenu()
            return
        }
        val current = OcrConfigStore.load(context).engineId
        engineMenuList.removeAllViews()
        OcrEngineRegistry.specs().forEach { spec ->
            val kind = context.getString(if (spec.local) R.string.ocr_engine_local else R.string.ocr_engine_cloud)
            engineMenuList.addView(context.textView {
                text = "${spec.displayName}  ・  $kind"
                textSize = 13f
                setTextColor(if (spec.id == current) Color.WHITE else Color.argb(190, 255, 255, 255))
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = roundedDrawable(
                    if (spec.id == current) Color.argb(140, 62, 110, 200) else Color.TRANSPARENT,
                    dp(8).toFloat()
                )
                setOnClickListener {
                    switchEngine(spec.id)
                    hideEngineMenu()
                }
            })
        }
        engineMenu.visibility = View.VISIBLE
    }

    private fun hideEngineMenu() {
        engineMenu.visibility = View.GONE
    }

    private fun switchEngine(id: String) {
        // Keep already-typed credentials so switching back and forth is lossless.
        val config = OcrConfig(engineId = id, params = OcrConfigStore.load(context).params)
        OcrConfigStore.save(context, config)
        showEngineName(config)
        statusText.setText(R.string.ocr_scan_hint)
        releaseEngine()
        prepareEngine(config)
    }

    private fun releaseEngine() {
        val e = engine
        engine = null
        worker.execute { runCatching { e?.close() } }
    }

    private fun prepareEngine(config: OcrConfig) {
        captureButton.isEnabled = true
        captureButton.alpha = 1f
        worker.execute {
            val created = OcrEngineRegistry.create(context, config)
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
                    // OCRSCAN: prefer the engine's own reason (e.g. "external model
                    // pack not downloaded") over the generic message.
                    val err = created?.prepareError
                    if (err.isNullOrBlank()) {
                        statusText.setText(R.string.ocr_engine_unavailable)
                    } else {
                        statusText.text = context.getString(R.string.ocr_failed, err)
                    }
                    captureButton.isEnabled = false
                    captureButton.alpha = 0.45f
                }
            }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) return@addListener
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
        setActionsEnabled(false)
        resultCard.visibility = View.GONE
        resultText.text = ""
        statusText.setText(R.string.ocr_recognizing)
        analyzer?.requested = true
    }

    private fun onFrameCaptured(bitmap: Bitmap) {
        val e = engine
        if (e == null) {
            onRecognized(OcrResult("", error = null))
            return
        }
        val result = runCatching { e.recognize(bitmap) }.getOrElse {
            Timber.e(it, "OcrScan: recognition failed")
            OcrResult("", error = it.message)
        }
        // OCRSCAN-DEBUG: persist the exact Bitmap fed to the engine together with
        // the recognized text so the pair can be pulled off-device for debugging
        // the reading-order / line-merge heuristics. Only in debug builds.
        saveDebugCapture(bitmap, e.id, result)
        onRecognized(result)
    }

    /**
     * OCRSCAN-DEBUG: write `<ts>.png` (the input frame) and `<ts>.txt` (engine id +
     * confidence + recognized text) into `.../files/ocr-debug/`, sharing the timestamp
     * prefix so image and text stay together. Run off the main thread (this is called
     * from the analyzer executor). Pull with e.g.
     * `adb pull /sdcard/Android/data/org.fcitx.fcitx5.android.qrscan/files/ocr-debug/`.
     */
    private fun saveDebugCapture(bitmap: Bitmap, engineId: String, result: OcrResult) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val dir = File(context.getExternalFilesDir(null), "ocr-debug").also { it.mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            val png = File(dir, "ocr_$ts.png")
            FileOutputStream(png).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val txt = File(dir, "ocr_$ts.txt")
            txt.writeText(buildString {
                appendLine("engine=$engineId")
                appendLine("confidence=${result.confidence}")
                result.error?.let { appendLine("error=$it") }
                appendLine("---- text ----")
                appendLine(result.text)
            })
            Timber.i("OcrScan: debug capture saved -> ${dir.absolutePath}/ocr_$ts.{png,txt}")
        }.onFailure { Timber.e(it, "OcrScan: failed to save debug capture") }
    }

    private fun onRecognized(result: OcrResult) {
        mainExecutor.execute {
            if (!windowManager.isAttached(this)) return@execute
            busy = false
            result.error?.let {
                statusText.text = context.getString(R.string.ocr_failed, it.take(60))
                return@execute
            }
            if (result.isEmpty) {
                statusText.setText(R.string.ocr_result_empty)
                resultCard.visibility = View.GONE
                setActionsEnabled(false)
                return@execute
            }
            pendingText = result.text
            resultText.text = result.text
            resultCard.visibility = View.VISIBLE
            setActionsEnabled(true)
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
        private const val RESULT_CARD_DP = 110
        /** 引擎浮层的最大高度，超出后可滚动（保证不会顶出 IME 面板） */
        private const val ENGINE_MENU_MAX_DP = 240
    }
}

/** ScrollView that never grows past [maxHeightPx], so long lists stay inside the panel. */
private class MaxHeightScrollView(context: Context, private val maxHeightPx: Int) :
    ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST))
    }
}
