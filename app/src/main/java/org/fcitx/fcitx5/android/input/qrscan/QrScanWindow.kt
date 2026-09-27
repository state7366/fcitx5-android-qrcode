/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.qrscan

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.mechdancer.dependency.manager.must
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import timber.log.Timber
import java.util.concurrent.Executors

class QrScanWindow : InputWindow.ExtendedInputWindow<QrScanWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()

    // Lifecycle owner tied to the window's attach/detach so CameraX can release the camera
    // automatically when the QR panel is closed.
    private val lifecycleOwner = object : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun create() { registry.currentState = Lifecycle.State.CREATED }
        fun resume() { registry.currentState = Lifecycle.State.RESUMED }
        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
    }

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var scanFrame: View
    private lateinit var rootView: View
    private var cameraProvider: ProcessCameraProvider? = null
    private var scanned = false
    private var lastCommitted: String? = null
    private var lastCommittedAt: Long = 0L

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_QR_PERMISSION_RESULT) return
            val granted = intent.getBooleanExtra(EXTRA_GRANTED, false)
            if (granted) {
                startCamera()
            } else {
                statusText.setText(R.string.qr_scan_permission_required)
                finishQrScan()
            }
        }
    }

    override val title: String by lazy { context.getString(R.string.qr_scan) }

    override fun onCreateView(): View {
        // Use COMPATIBLE (TextureView) instead of PERFORMANCE (SurfaceView):
        // a SurfaceView renders on a separate surface that is NOT clipped by its parent and is
        // known to be unstable inside an InputMethodService window (it can draw over the app and
        // crash on teardown). TextureView respects the view hierarchy, keeping the preview inside
        // the keyboard area.
        previewView = PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        statusText = context.textView {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            text = context.getString(R.string.qr_scan_hint)
        }
        scanFrame = context.view(::View) {
            background = frameDrawable(Color.WHITE)
        }
        val cancelButton = context.textView {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            text = context.getString(android.R.string.cancel)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { finishQrScan() }
        }
        return context.frameLayout {
            backgroundColor = Color.BLACK
            add(previewView, lParams(matchParent, matchParent))
            add(scanFrame, lParams(dp(SCAN_FRAME_MAX_DP), dp(SCAN_FRAME_MAX_DP)) {
                gravity = Gravity.CENTER
            })
            add(statusText, lParams(wrapContent, wrapContent) {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            })
            add(cancelButton, lParams(wrapContent, wrapContent) {
                gravity = Gravity.TOP or Gravity.START
                topMargin = dp(8)
                marginStart = dp(8)
            })
        }.also { root ->
            rootView = root
            root.doOnLayout {
                // The IME window is fullscreen but the QR panel only occupies the keyboard area
                // (its height is the user-configurable keyboard height, e.g. ~30% of the screen).
                // Clamp the viewfinder square so it always fits inside that area.
                val margin = context.dp(SCAN_FRAME_MARGIN_DP)
                val minSide = context.dp(SCAN_FRAME_MIN_DP)
                val maxFrame = context.dp(SCAN_FRAME_MAX_DP)
                val side = (minOf(root.height, root.width) - margin).coerceIn(minSide, maxFrame)
                scanFrame.updateLayoutParams<FrameLayout.LayoutParams> {
                    width = side
                    height = side
                }
            }
        }
    }

    override fun onAttached() {
        ContextCompat.registerReceiver(
            context, permissionReceiver,
            IntentFilter(ACTION_QR_PERMISSION_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        lifecycleOwner.create()
        if (hasCameraPermission()) {
            startCamera()
        } else {
            statusText.setText(R.string.qr_scan_permission_required)
            context.startActivity(Intent(context, QrScanPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    override fun onDetached() {
        runCatching { context.unregisterReceiver(permissionReceiver) }
        lifecycleOwner.destroy()
        cameraProvider?.unbindAll()
        cameraProvider = null
        scanned = false
        lastCommitted = null
        service.pendingScanPanel = null
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        if (scanned) return
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                // The window may have been detached while the camera provider was being fetched
                // asynchronously (e.g. the user closed the panel right away). Binding to a
                // destroyed lifecycle throws, so bail out instead.
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
                analysis.setAnalyzer(Executors.newSingleThreadExecutor(), QrAnalyzer { onQrDetected(it) })
                lifecycleOwner.resume()
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, cameraSelector(), preview, analysis)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start QR camera")
                statusText.setText(R.string.qr_scan_camera_error)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun cameraSelector(): CameraSelector = try {
        CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()
    } catch (_: Exception) {
        CameraSelector.DEFAULT_BACK_CAMERA
    }

    private fun onQrDetected(result: String) {
        if (scanned) return
        val now = SystemClock.uptimeMillis()
        // The camera keeps pointing at the same code after a commit; without this dedup the
        // same text would be committed on every frame once scanning resumes.
        if (result == lastCommitted && now - lastCommittedAt < RESCAN_COOLDOWN_MS) return
        scanned = true
        lastCommitted = result
        lastCommittedAt = now
        Timber.d("QrScan: detected result=${result.take(40)}")
        ContextCompat.getMainExecutor(service).execute {
            // The panel may have been closed (back/return button) before this ran; don't commit
            // text in that case.
            if (!windowManager.isAttached(this)) {
                Timber.d("QrScan: window detached before callback, dropping result")
                scanned = false
                return@execute
            }
            Timber.d("QrScan: currentInputConnection=${service.currentInputConnection != null}")
            service.commitText(result)
            showCommittedFeedback(result)
            // Continuous scanning: stay in the QR panel and resume decoding after a short
            // pause, instead of switching back to the keyboard after every code.
            rootView.postDelayed({
                scanned = false
                if (windowManager.isAttached(this)) {
                    statusText.setText(R.string.qr_scan_hint)
                    scanFrame.background = frameDrawable(Color.WHITE)
                }
            }, RESUME_SCAN_DELAY_MS)
        }
    }

    private fun showCommittedFeedback(result: String) {
        val preview = if (result.length > 24) result.take(24) + "…" else result
        statusText.text = context.getString(R.string.qr_scan_committed, preview)
        scanFrame.background = frameDrawable(Color.GREEN)
    }

    private fun frameDrawable(color: Int) = GradientDrawable().apply {
        setStroke(context.dp(2), color)
        cornerRadius = context.dp(8).toFloat()
    }

    /**
     * Leave the QR panel and return to the normal keyboard. Only invoked when the user taps
     * Cancel (or camera permission is denied); scanning success keeps the panel open for
     * continuous scanning. Switches fcitx back to the last real input method; the resulting
     * IMChangeEvent keeps the service-side state consistent (single source of truth).
     */
    private fun finishQrScan() {
        if (windowManager.isAttached(this)) {
            // attach keyboard first for instant visual feedback; also releases the camera
            // via this window's onDetached
            returnToKeyboard()
            service.postFcitxJob {
                activateIme(service.lastRealImBeforeScan ?: "keyboard-us")
            }
        }
    }

    private fun returnToKeyboard() {
        if (windowManager.isAttached(this)) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    private class QrAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {
        private val reader = MultiFormatReader()

        override fun analyze(image: ImageProxy) {
            val rotation = image.imageInfo.rotationDegrees
            val raw = image.toBitmap()
            image.close()
            val bitmap = if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            } else raw
            try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val binary = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))
                val text = reader.decode(binary).text
                if (!text.isNullOrEmpty()) onResult(text)
            } catch (_: Exception) {
                // no QR code in this frame; keep scanning
            }
        }
    }

    companion object {
        const val ACTION_QR_PERMISSION_RESULT = "org.fcitx.fcitx5.android.QR_PERMISSION_RESULT"
        const val EXTRA_GRANTED = "granted"

        // viewfinder square size limits (dp). The keyboard area height is a user-configurable
        // percentage of the screen (often ~30%), so a fixed large frame would overflow it.
        private const val SCAN_FRAME_MAX_DP = 240
        private const val SCAN_FRAME_MIN_DP = 120
        private const val SCAN_FRAME_MARGIN_DP = 32

        // pause before decoding resumes after a successful commit (gives the user time to
        // move the camera to the next code)
        private const val RESUME_SCAN_DELAY_MS = 1200L

        // minimum interval before the SAME code content may be committed again
        private const val RESCAN_COOLDOWN_MS = 3000L
    }
}
