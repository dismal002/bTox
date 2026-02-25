package com.dismal.btox.ui.chat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Camera
import android.net.Uri
import android.os.Bundle
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.dismal.btox.R
import com.dismal.btox.databinding.ActivityChatCameraCaptureBinding
import java.io.File
import java.io.FileOutputStream

class ChatCameraCaptureActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private lateinit var binding: ActivityChatCameraCaptureBinding
    private var camera: Camera? = null
    private var surfaceReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatCameraCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.setNavigationOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        binding.surface.holder.addCallback(this)
        binding.captureButton.setOnClickListener { capture() }
    }

    override fun onResume() {
        super.onResume()
        ensurePermissionAndStart()
    }

    override fun onPause() {
        closeCamera()
        super.onPause()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        openCameraIfReady(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        closeCamera()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_PERMISSION_REQUEST) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCameraIfReady(binding.surface.holder)
        } else {
            Toast.makeText(this, R.string.media_picker_camera_permission_required, Toast.LENGTH_LONG).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    private fun ensurePermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCameraIfReady(binding.surface.holder)
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
    }

    private fun openCameraIfReady(holder: SurfaceHolder) {
        if (!surfaceReady || camera != null) return
        try {
            val opened = Camera.open() ?: throw IllegalStateException("Unable to open camera")
            camera = opened
            opened.setPreviewDisplay(holder)
            opened.setDisplayOrientation(90)
            opened.startPreview()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.media_picker_camera_open_failed, Toast.LENGTH_LONG).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    private fun closeCamera() {
        camera?.apply {
            stopPreview()
            release()
        }
        camera = null
    }

    private fun capture() {
        val active = camera ?: return
        active.takePicture(null, null) { data, cam ->
            runCatching {
                val uri = saveJpeg(data)
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(EXTRA_CAPTURED_URI, uri.toString()),
                )
                finish()
            }.onFailure {
                Toast.makeText(this, R.string.media_picker_camera_open_failed, Toast.LENGTH_LONG).show()
                cam.startPreview()
            }
        }
    }

    private fun saveJpeg(data: ByteArray): Uri {
        val file = File(cacheDir.resolve("shared_images"), "camera_${System.currentTimeMillis()}.jpg")
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(data) }
        return FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 1003
        const val EXTRA_CAPTURED_URI = "captured_uri"
    }
}
