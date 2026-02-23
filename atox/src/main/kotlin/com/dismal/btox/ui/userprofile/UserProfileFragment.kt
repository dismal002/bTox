// SPDX-FileCopyrightText: 2020-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2021-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.dismal.btox.ui.userprofile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap.CompressFormat
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputFilter
import android.view.ContextMenu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.set
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.viewModelScope
import io.nayuki.qrcodegen.QrCode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.dismal.btox.BuildConfig
import com.dismal.btox.R
import com.dismal.btox.databinding.FragmentUserProfileBinding
import com.dismal.btox.ui.BaseFragment
import com.dismal.btox.ui.Dp
import com.dismal.btox.ui.Px
import com.dismal.btox.ui.StatusDialog
import com.dismal.btox.ui.colorFromStatus
import com.dismal.btox.vmFactory
import ltd.evilcorp.core.vo.UserStatus
import ltd.evilcorp.domain.tox.MAX_AVATAR_SIZE
import ltd.evilcorp.domain.tox.ToxID

private const val TOX_MAX_NAME_LENGTH = 128
private const val TOX_MAX_STATUS_MESSAGE_LENGTH = 1007

private const val QR_CODE_TO_SCREEN_RATIO = 0.5f
private val qrCodePadding = Dp(16f)
private val qrCodeSharedImageSize = Px(1024)
private val qrCodeSharedImagePadding = Px(200)

class UserProfileFragment : BaseFragment<FragmentUserProfileBinding>(FragmentUserProfileBinding::inflate) {
    private val vm: UserProfileViewModel by viewModels { vmFactory }
    private lateinit var currentStatus: UserStatus
    private var currentAvatarUri = ""
    private val avatarPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            startAvatarCrop(uri)
        }
    private var pendingCropOutputUri: Uri? = null
    private val avatarCropper =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val output = pendingCropOutputUri
            pendingCropOutputUri = null

            if (output == null) return@registerForActivityResult
            if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
            saveAvatar(output)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, compat ->
            val bars = compat.getInsets(WindowInsetsCompat.Type.systemBars())
            val gestures = compat.getInsets(WindowInsetsCompat.Type.systemGestures())
            val ime = compat.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = max(bars.bottom, max(gestures.bottom, ime.bottom))
            appBar.updatePadding(left = bars.left, right = bars.right)
            mainSection.updatePadding(left = bars.left, right = bars.right, bottom = bottomInset)
            compat
        }

        profileToolbar.apply {
            inflateMenu(R.menu.user_profile_detail_menu)
            for (i in 0 until menu.size()) {
                menu.getItem(i).icon?.mutate()?.setTint(Color.WHITE)
            }
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.action_edit_profile -> {
                        editNameRow.performClick()
                        true
                    }
                    R.id.action_user_more -> {
                        Toast.makeText(requireContext(), R.string.action_not_supported, Toast.LENGTH_SHORT).show()
                        true
                    }
                    else -> false
                }
            }
            setNavigationOnClickListener {
                activity?.onBackPressed()
            }
        }

        vm.user.observe(viewLifecycleOwner) { user ->
            currentStatus = user.status
            currentAvatarUri = user.avatarUri
            val displayName = user.name.ifBlank { getString(R.string.contact_default_name) }

            userName.text = displayName
            headerNameContainer.visibility = View.VISIBLE
            headerNameContainer.bringToFront()
            userName.visibility = View.VISIBLE
            userName.bringToFront()
            userStatusMessage.text = user.statusMessage.ifEmpty { getString(R.string.status_note_fallback) }
            aboutHeader.text = getString(R.string.about_contact, displayName)
            userStatus.setColorFilter(colorFromStatus(requireContext(), user.status))
            headerAvatar.setFrom(user)
        }

        userToxId.text = vm.toxId.string()

        // TODO(robinlinden): This should open a nice dialog where you show the QR and have both share and copy buttons.
        profileShareId.setOnClickListener {
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, vm.toxId.string())
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.tox_id_share)))
        }
        registerForContextMenu(profileShareId)

        showQr.setOnClickListener {
            createQrCodeDialog().show()
        }

        editNameRow.setOnClickListener {
            val nameEdit = EditText(requireContext()).apply {
                text.append(binding.userName.text)
                filters = arrayOf(InputFilter.LengthFilter(TOX_MAX_NAME_LENGTH))
                setSingleLine()
            }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.name)
                .setView(nameEdit)
                .setPositiveButton(R.string.update) { _, _ ->
                    vm.setName(nameEdit.text.toString())
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        editStatusMessageRow.setOnClickListener {
            val statusMessageEdit =
                EditText(requireContext()).apply {
                    text.append(binding.userStatusMessage.text)
                    filters = arrayOf(InputFilter.LengthFilter(TOX_MAX_STATUS_MESSAGE_LENGTH))
                }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.status_message)
                .setView(statusMessageEdit)
                .setPositiveButton(R.string.update) { _, _ ->
                    vm.setStatusMessage(statusMessageEdit.text.toString())
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        editStatusRow.setOnClickListener {
            StatusDialog(requireContext(), currentStatus) { status -> vm.setStatus(status) }.show()
        }

        editAvatarRow.setOnClickListener {
            avatarPicker.launch(arrayOf("image/*"))
        }

        headerAvatar.setOnClickListener {
            avatarPicker.launch(arrayOf("image/*"))
        }

        headerAvatar.setOnLongClickListener {
            if (currentAvatarUri.isBlank()) {
                return@setOnLongClickListener false
            }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.profile_photo_description)
                .setMessage(R.string.remove_profile_photo_message)
                .setPositiveButton(R.string.delete) { _, _ ->
                    releasePersistedReadPermission(currentAvatarUri)
                    vm.clearAvatar()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) = binding.run {
        super.onCreateContextMenu(menu, v, menuInfo)
        when (v.id) {
            R.id.profile_share_id -> requireActivity().menuInflater.inflate(
                R.menu.user_profile_share_id_context_menu,
                menu,
            )
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean = binding.run {
        return when (item.itemId) {
            R.id.copy -> {
                val clipboard = requireActivity().getSystemService<ClipboardManager>()!!
                clipboard.setPrimaryClip(ClipData.newPlainText(getText(R.string.tox_id), vm.toxId.string()))
                Toast.makeText(requireContext(), getText(R.string.copied), Toast.LENGTH_SHORT).show()
                true
            }
            R.id.qr -> {
                createQrCodeDialog().show()
                true
            }
            else -> super.onContextItemSelected(item)
        }
    }

    private fun createQrCodeDialog(): AlertDialog {
        val qrSize =
            min(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * QR_CODE_TO_SCREEN_RATIO
        val bmp = asQr(vm.toxId, Px(qrSize.toInt()), qrCodePadding.asPx(resources))
        val qrCode = ImageView(requireContext()).apply {
            setPadding(qrCodePadding.asPx(resources).px)
            setImageBitmap(bmp)
        }

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.tox_id)
            .setView(qrCode)
            .setPositiveButton(getString(R.string.share)) { _, _ ->
                vm.viewModelScope.launch {
                    val qrImageUri = getQrForSharing()
                    val shareIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        clipData = ClipData.newRawUri(null, qrImageUri)
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, qrImageUri)
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.tox_id_share)))
                }
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .create()
    }

    private fun saveQrForSharing(qrBmp: Bitmap): Uri {
        val imagesFolder = File(requireContext().cacheDir, "shared_images").apply { mkdirs() }
        val file = File(imagesFolder, "tox_id_qr_code.png")
        FileOutputStream(file).use { stream ->
            qrBmp.compress(Bitmap.CompressFormat.PNG, 90, stream)
        }

        return FileProvider.getUriForFile(requireContext(), "${BuildConfig.APPLICATION_ID}.fileprovider", file)
    }

    private fun asQr(id: ToxID, qrSize: Px, padding: Px): Bitmap {
        val qrData = QrCode.encodeText("tox:%s".format(id.string()), QrCode.Ecc.LOW)
        var bmpQr: Bitmap = createBitmap(qrData.size, qrData.size, Bitmap.Config.RGB_565)
        for (x in 0 until qrData.size) {
            for (y in 0 until qrData.size) {
                bmpQr[x, y] = if (qrData.getModule(x, y)) Color.BLACK else Color.WHITE
            }
        }

        bmpQr = bmpQr.scale(qrSize.px, qrSize.px, false)

        val bmpQrWithPadding =
            createBitmap(bmpQr.width + 2 * padding.px, bmpQr.height + 2 * padding.px, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmpQrWithPadding)
        canvas.drawPaint(
            Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            },
        )
        canvas.drawBitmap(bmpQr, padding.px.toFloat(), padding.px.toFloat(), null)

        return bmpQrWithPadding
    }

    private suspend fun getQrForSharing(): Uri = withContext(Dispatchers.IO) {
        val bmp = asQr(vm.toxId, qrCodeSharedImageSize, qrCodeSharedImagePadding)
        saveQrForSharing(bmp)
    }

    private fun saveAvatar(uri: Uri) {
        if (!validateAvatarSize(uri)) {
            return
        }

        val resolver = requireContext().contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching {
            resolver.takePersistableUriPermission(uri, flags)
        }

        if (currentAvatarUri.isNotBlank() && currentAvatarUri != uri.toString()) {
            releasePersistedReadPermission(currentAvatarUri)
        }

        vm.setAvatar(uri)
    }

    private fun startAvatarCrop(sourceUri: Uri) {
        val cropOutput = File(requireContext().cacheDir, "shared_images/avatar_cropped.jpg").apply {
            parentFile?.mkdirs()
        }
        val outputUri = FileProvider.getUriForFile(
            requireContext(),
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            cropOutput,
        )
        pendingCropOutputUri = outputUri

        val cropIntent = Intent("com.android.camera.action.CROP").apply {
            setDataAndType(sourceUri, "image/*")
            putExtra("crop", "true")
            putExtra("scale", true)
            putExtra("return-data", false)
            putExtra("output", outputUri)
            putExtra("outputFormat", CompressFormat.JPEG.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newRawUri(null, sourceUri)
        }

        val handlers = requireContext().packageManager.queryIntentActivities(cropIntent, 0)
        if (handlers.isEmpty()) {
            pendingCropOutputUri = null
            Toast.makeText(requireContext(), R.string.avatar_crop_not_supported, Toast.LENGTH_SHORT).show()
            saveAvatar(sourceUri)
            return
        }

        handlers.forEach { handler ->
            requireContext().grantUriPermission(
                handler.activityInfo.packageName,
                sourceUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            requireContext().grantUriPermission(
                handler.activityInfo.packageName,
                outputUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }

        avatarCropper.launch(cropIntent)
    }

    private fun validateAvatarSize(uri: Uri): Boolean {
        val sizeBytes = avatarSize(uri)
        if (sizeBytes in 1..MAX_AVATAR_SIZE) return true
        if (sizeBytes <= 0L) return true

        val sizeKb = (sizeBytes + 1023L) / 1024L
        val maxKb = MAX_AVATAR_SIZE / 1024L
        Toast.makeText(
            requireContext(),
            getString(R.string.avatar_too_large, sizeKb, maxKb),
            Toast.LENGTH_LONG,
        ).show()
        return false
    }

    private fun avatarSize(uri: Uri): Long {
        val resolver = requireContext().contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0) {
                    return cursor.getLong(idx)
                }
            }
        }
        return runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length
            } ?: -1L
        }.getOrDefault(-1L)
    }

    private fun releasePersistedReadPermission(uri: String) {
        if (uri.isBlank() || !uri.startsWith("content://")) return
        runCatching {
            requireContext().contentResolver.releasePersistableUriPermission(
                uri.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }
}
