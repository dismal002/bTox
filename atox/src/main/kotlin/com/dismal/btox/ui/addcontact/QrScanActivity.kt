// SPDX-FileCopyrightText: 2026 bTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.dismal.btox.ui.addcontact

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Camera
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dismal.btox.R
import com.dismal.btox.databinding.ActivityQrScanBinding
import com.dismal.btox.settings.Settings
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

class QrScanActivity : AppCompatActivity(), SurfaceHolder.Callback, Camera.PreviewCallback {
    private lateinit var binding: ActivityQrScanBinding

    private var camera: Camera? = null
    private var surfaceReady = false
    private val decoding = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var useManualAutoFocus = false
    private var processedFrames = 0

    private val autoFocusRunnable = object : Runnable {
        override fun run() {
            val activeCamera = camera ?: return
            if (!useManualAutoFocus) return
            try {
                activeCamera.autoFocus { _, _ ->
                    mainHandler.postDelayed(this, AUTO_FOCUS_INTERVAL_MS)
                }
            } catch (_: Exception) {
                mainHandler.postDelayed(this, AUTO_FOCUS_INTERVAL_MS)
            }
        }
    }

    private val reader = MultiFormatReader().apply {
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
            put(DecodeHintType.POSSIBLE_FORMATS, listOf(com.google.zxing.BarcodeFormat.QR_CODE))
            put(DecodeHintType.TRY_HARDER, true)
        }
        setHints(hints)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.setNavigationOnClickListener { finishWithCancel() }
        binding.surface.holder.addCallback(this)
    }

    override fun onResume() {
        super.onResume()
        ensureCameraPermissionAndStart()
    }

    override fun onPause() {
        closeCamera()
        super.onPause()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        openCameraIfPossible(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        closeCamera()
    }

    override fun onPreviewFrame(data: ByteArray?, camera: Camera?) {
        val activeCamera = camera ?: return
        val frame = data ?: return
        processedFrames = (processedFrames + 1) % FRAME_SKIP_DIVISOR
        if (processedFrames != 0) return
        if (!decoding.compareAndSet(false, true)) return

        val parameters = activeCamera.parameters ?: run {
            decoding.set(false)
            return
        }
        val size = parameters.previewSize ?: run {
            decoding.set(false)
            return
        }

        try {
            val result = decodeFrame(frame, size.width, size.height)
            playScanSuccessToneIfEnabled()
            val intent = Intent().putExtra("SCAN_RESULT", result.text)
            setResult(Activity.RESULT_OK, intent)
            finish()
        } catch (_: Exception) {
            // Keep scanning.
        } finally {
            reader.reset()
            decoding.set(false)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_PERMISSION_REQUEST) return

        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCameraIfPossible(binding.surface.holder)
        } else {
            Toast.makeText(this, R.string.qr_camera_permission_required, Toast.LENGTH_LONG).show()
            finishWithCancel()
        }
    }

    private fun ensureCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            openCameraIfPossible(binding.surface.holder)
            return
        }

        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST,
        )
    }

    private fun openCameraIfPossible(holder: SurfaceHolder) {
        if (!surfaceReady || camera != null) return

        try {
            val opened = Camera.open() ?: run {
                Toast.makeText(this, R.string.qr_camera_open_failed, Toast.LENGTH_LONG).show()
                finishWithCancel()
                return
            }
            camera = opened
            opened.setPreviewDisplay(holder)
            configureCameraParameters(opened)
            opened.setDisplayOrientation(90)
            opened.setPreviewCallback(this)
            opened.startPreview()
            startAutoFocusIfNeeded()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.qr_camera_open_failed, Toast.LENGTH_LONG).show()
            finishWithCancel()
        }
    }

    private fun closeCamera() {
        stopAutoFocus()
        camera?.apply {
            setPreviewCallback(null)
            stopPreview()
            release()
        }
        camera = null
        decoding.set(false)
    }

    private fun buildCenterCropSource(data: ByteArray, width: Int, height: Int): PlanarYUVLuminanceSource? {
        // Camera preview is landscape; rotate so decoding works in portrait.
        val rotated = ByteArray(data.size)
        var i = 0
        for (x in 0 until width) {
            for (y in height - 1 downTo 0) {
                rotated[i++] = data[y * width + x]
            }
        }
        val rotatedWidth = height
        val rotatedHeight = width
        val cropSize = (min(rotatedWidth, rotatedHeight) * 0.82f).toInt()
        val left = (rotatedWidth - cropSize) / 2
        val top = (rotatedHeight - cropSize) / 2

        return try {
            PlanarYUVLuminanceSource(
                rotated,
                rotatedWidth,
                rotatedHeight,
                left,
                top,
                cropSize,
                cropSize,
                false,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun buildFullFrameSource(data: ByteArray, width: Int, height: Int): PlanarYUVLuminanceSource? {
        val rotated = ByteArray(data.size)
        var i = 0
        for (x in 0 until width) {
            for (y in height - 1 downTo 0) {
                rotated[i++] = data[y * width + x]
            }
        }
        val rotatedWidth = height
        val rotatedHeight = width
        return try {
            PlanarYUVLuminanceSource(
                rotated,
                rotatedWidth,
                rotatedHeight,
                0,
                0,
                rotatedWidth,
                rotatedHeight,
                false,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeFrame(data: ByteArray, width: Int, height: Int): com.google.zxing.Result {
        val center = buildCenterCropSource(data, width, height)
        if (center != null) {
            runCatching { return reader.decodeWithState(BinaryBitmap(HybridBinarizer(center))) }
        }

        val full = buildFullFrameSource(data, width, height)
            ?: throw IllegalStateException("No frame source available")
        return reader.decodeWithState(BinaryBitmap(HybridBinarizer(full)))
    }

    private fun configureCameraParameters(activeCamera: Camera) {
        val params = activeCamera.parameters ?: return

        val size = selectPreviewSize(params.supportedPreviewSizes)
        if (size != null) {
            params.setPreviewSize(size.width, size.height)
        }

        val focusMode = selectFocusMode(params.supportedFocusModes.orEmpty())
        if (focusMode != null) {
            params.focusMode = focusMode
        }
        useManualAutoFocus = focusMode == Camera.Parameters.FOCUS_MODE_AUTO ||
            focusMode == Camera.Parameters.FOCUS_MODE_MACRO

        if (params.supportedSceneModes?.contains(Camera.Parameters.SCENE_MODE_BARCODE) == true) {
            params.sceneMode = Camera.Parameters.SCENE_MODE_BARCODE
        }
        if (params.isVideoStabilizationSupported) {
            params.videoStabilization = true
        }

        runCatching { activeCamera.parameters = params }
    }

    private fun selectFocusMode(supportedModes: List<String>): String? {
        return when {
            supportedModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) ->
                Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            supportedModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) ->
                Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            supportedModes.contains(Camera.Parameters.FOCUS_MODE_AUTO) ->
                Camera.Parameters.FOCUS_MODE_AUTO
            supportedModes.contains(Camera.Parameters.FOCUS_MODE_MACRO) ->
                Camera.Parameters.FOCUS_MODE_MACRO
            else -> null
        }
    }

    private fun selectPreviewSize(sizes: List<Camera.Size>?): Camera.Size? {
        val supported = sizes ?: return null
        if (supported.isEmpty()) return null

        val targetRatio = 4f / 3f
        return supported
            .filter { it.width >= 960 && it.height >= 720 }
            .ifEmpty { supported }
            .sortedWith(
                compareBy<Camera.Size> {
                    abs((it.width.toFloat() / it.height) - targetRatio)
                }.thenByDescending { it.width * it.height },
            )
            .firstOrNull()
    }

    private fun startAutoFocusIfNeeded() {
        stopAutoFocus()
        if (useManualAutoFocus) {
            mainHandler.post(autoFocusRunnable)
        }
    }

    private fun stopAutoFocus() {
        mainHandler.removeCallbacks(autoFocusRunnable)
        runCatching { camera?.cancelAutoFocus() }
    }

    private fun finishWithCancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun playScanSuccessToneIfEnabled() {
        if (!Settings(this).outgoingMessageSoundsEnabled) return
        val player = MediaPlayer.create(this, R.raw.beep) ?: return
        player.setOnCompletionListener { it.release() }
        player.setOnErrorListener { mp, _, _ ->
            mp.release()
            true
        }
        player.start()
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 9001
        private const val AUTO_FOCUS_INTERVAL_MS = 1400L
        private const val FRAME_SKIP_DIVISOR = 2
    }
}
