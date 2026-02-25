// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2021-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.dismal.btox.ui.chat

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Camera
import android.graphics.drawable.ClipDrawable
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.ContextMenu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.math.MathUtils.lerp
import com.squareup.picasso.Picasso
import com.squareup.picasso.Callback
import java.io.File
import java.io.FileOutputStream
import java.net.URLConnection
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import com.dismal.btox.R
import com.dismal.btox.databinding.FragmentChatBinding
import com.dismal.btox.requireStringArg
import com.dismal.btox.settings.AppColorResolver
import com.dismal.btox.truncated
import com.dismal.btox.ui.BaseFragment
import com.dismal.btox.vmFactory
import ltd.evilcorp.core.vo.ConnectionStatus
import ltd.evilcorp.core.vo.FileTransfer
import ltd.evilcorp.core.vo.Message
import ltd.evilcorp.core.vo.MessageType
import ltd.evilcorp.core.vo.PublicKey
import ltd.evilcorp.core.vo.isComplete
import ltd.evilcorp.domain.feature.CallState
import com.dismal.btox.ui.contactlist.ARG_SHARE

private const val TAG = "ChatFragment"
const val CONTACT_PUBLIC_KEY = "publicKey"
const val FOCUS_ON_MESSAGE_BOX = "focusOnMessageBox"
private const val MAX_CONFIRM_DELETE_STRING_LENGTH = 20
private const val VOICE_MEMO_CACHE_SUBDIR = "shared_images"
private const val VOICE_MEMO_MIN_DURATION_MS = 300L

class OpenMultiplePersistableDocuments : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent = super.createIntent(context, input)
        .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
}

class ChatFragment : BaseFragment<FragmentChatBinding>(FragmentChatBinding::inflate) {
    private val viewModel: ChatViewModel by viewModels { vmFactory }

    private lateinit var contactPubKey: String
    private var contactName = ""
    private var selectedFt: Int = Int.MIN_VALUE
    private var fts: List<FileTransfer> = listOf()
    private var messageActionMode: ActionMode? = null
    private var lastAppliedBackgroundUri = ""  // Track the last background we applied locally
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var mediaPhotoGridAdapter: MediaPhotoGridAdapter
    private var mediaCamera: Camera? = null
    private var mediaCameraSurfaceReady = false
    private var voiceMemoRecorder: MediaRecorder? = null
    private var voiceMemoFile: File? = null
    private var voiceMemoRecording = false
    private var voiceMemoStartedAt = 0L
    private var voiceMemoPlayer: MediaPlayer? = null
    private var voiceMemoPreviewDurationMs = 0
    private var voiceMemoPreviewSeeking = false
    private val voicePreviewUiHandler = Handler(Looper.getMainLooper())
    private val updateVoicePreviewProgress = object : Runnable {
        override fun run() {
            val player = voiceMemoPlayer ?: return
            if (!binding.voicePreviewLayout.isVisible) return
            if (!voiceMemoPreviewSeeking) {
                updateVoicePreviewUi(player.currentPosition, voiceMemoPreviewDurationMs)
            }
            if (player.isPlaying) {
                voicePreviewUiHandler.postDelayed(this, 250L)
            }
        }
    }

    private val exportBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { dest ->
            if (dest == null) return@registerForActivityResult
            viewModel.backupHistory(contactPubKey, dest)
        }

    private val exportFtLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { dest ->
        if (dest == null) return@registerForActivityResult
        viewModel.exportFt(selectedFt, dest)
    }

    private val attachFilesLauncher =
        registerForActivityResult(OpenMultiplePersistableDocuments()) { files ->
            viewModel.setActiveChat(PublicKey(contactPubKey))
            for (file in files) {
                activity?.contentResolver?.takePersistableUriPermission(file, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                viewModel.createFt(file)
            }
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                showCameraPanel()
            } else {
                Toast.makeText(requireContext(), R.string.media_picker_camera_permission_required, Toast.LENGTH_LONG).show()
            }
        }

    private val recordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                showVoiceRecorderPanel()
            } else {
                Toast.makeText(requireContext(), R.string.media_picker_mic_permission_required, Toast.LENGTH_LONG).show()
            }
        }

    private val photosPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                showPhotosPanel()
            } else {
                Toast.makeText(requireContext(), R.string.media_picker_photos_permission_required, Toast.LENGTH_LONG).show()
            }
        }

    private val chatBackgroundPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                Log.d(TAG, "Chat background picker cancelled")
                return@registerForActivityResult
            }
            Log.d(TAG, "Chat background selected: $uri, contactPubKey='$contactPubKey'")
            val copiedUri = copyChatBackgroundToPrivateStorage(uri) ?: return@registerForActivityResult
            Log.d(TAG, "Chat background copied to: $copiedUri")
            viewModel.contact.value?.chatBackgroundUri?.let { deleteLocalChatBackgroundIfOwned(it) }
            // Apply the background immediately to the UI
            applyChatBackground(copiedUri)
            Log.d(TAG, "Background applied to UI, now saving to database with contactPubKey='$contactPubKey'")
            // Save to database - this may trigger the observer, but the UI is already updated
            viewModel.setChatBackgroundUri(PublicKey(contactPubKey), copiedUri)
            Log.d(TAG, "Chat background URI saved to database")
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?): Unit = binding.run {
        contactPubKey = requireStringArg(CONTACT_PUBLIC_KEY)
        Log.d(TAG, "onViewCreated with contactPubKey='$contactPubKey'")
        lastAppliedBackgroundUri = ""  // Reset when entering a new/same chat
        viewModel.setActiveChat(PublicKey(contactPubKey))

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (mediaPickerPanel.isVisible) {
                        if (voiceMemoRecording) {
                            stopVoiceMemoRecording(keepForPreview = false)
                        }
                        clearVoiceMemoPreview(deleteFile = true)
                        hideCameraPanel()
                        hideVoiceRecorderPanel()
                        hidePhotosPanel()
                        mediaPickerPanel.isVisible = false
                        return
                    }
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            },
        )

        ViewCompat.setOnApplyWindowInsetsListener(view) { _, compat ->
            val bars = compat.getInsets(WindowInsetsCompat.Type.systemBars())
            val gestures = compat.getInsets(WindowInsetsCompat.Type.systemGestures())
            val ime = compat.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = max(bars.bottom, max(gestures.bottom, ime.bottom))
            appBarLayout.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            composerContainer.updatePadding(left = bars.left, right = bars.right, bottom = bottomInset)
            messages.updatePadding(left = bars.left, right = bars.right)
            compat
        }

        ViewCompat.setWindowInsetsAnimationCallback(
            view,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                var startBottom = 0
                var endBottom = 0

                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    val pos = IntArray(2)
                    outgoingMessage.getLocationInWindow(pos)
                    startBottom = pos[1]
                }

                override fun onStart(
                    animation: WindowInsetsAnimationCompat,
                    bounds: WindowInsetsAnimationCompat.BoundsCompat,
                ): WindowInsetsAnimationCompat.BoundsCompat {
                    val pos = IntArray(2)
                    outgoingMessage.getLocationInWindow(pos)
                    endBottom = pos[1]
                    val offset = (startBottom - endBottom).toFloat()
                    messages.translationY = offset
                    bottomBar.translationY = offset

                    return bounds
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    val animation = runningAnimations[0]
                    val offset = lerp((startBottom - endBottom).toFloat(), 0f, animation.interpolatedFraction)
                    messages.translationY = offset
                    bottomBar.translationY = offset
                    return insets
                }
            },
        )

        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener {
            WindowInsetsControllerCompat(requireActivity().window, view).hide(WindowInsetsCompat.Type.ime())
            findNavController().navigateUp()
        }

        applyComposerUiStyle()

        toolbar.inflateMenu(R.menu.chat_options_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.backup_history -> {
                    exportBackupLauncher.launch(
                        "backup-atox-messages_${contactPubKey}_${
                            SimpleDateFormat(
                                """yyyy-MM-dd'T'HH-mm-ss""",
                                Locale.getDefault(),
                            ).format(Date())
                        }.json",
                    )
                    true
                }
                R.id.clear_history -> {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.clear_history)
                        .setMessage(getString(R.string.clear_history_confirm, contactName))
                        .setPositiveButton(R.string.clear_history) { _, _ ->
                            Toast.makeText(requireContext(), R.string.clear_history_cleared, Toast.LENGTH_LONG).show()
                            viewModel.clearHistory()
                        }
                        .setNegativeButton(android.R.string.cancel, null).show()
                    true
                }
                R.id.action_add_contact -> {
                    findNavController().navigate(R.id.addContactFragment)
                    true
                }
                R.id.action_people_and_options -> {
                    findNavController().navigate(
                        R.id.action_chatFragment_to_contactProfileFragment,
                        bundleOf(CONTACT_PUBLIC_KEY to contactPubKey),
                    )
                    true
                }
                R.id.action_archive -> {
                    val archived = viewModel.contact.value?.archived == true
                    viewModel.setArchived(!archived)
                    Toast.makeText(
                        requireContext(),
                        getString(if (archived) R.string.conversation_unarchived else R.string.conversation_archived),
                        Toast.LENGTH_SHORT,
                    ).show()
                    findNavController().popBackStack()
                    true
                }
                R.id.action_delete -> {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.delete_contact)
                        .setMessage(getString(R.string.contact_list_delete_contact_confirm, contactName))
                        .setPositiveButton(R.string.delete) { _, _ ->
                            viewModel.clearHistory()
                            findNavController().popBackStack()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                }
                R.id.action_chat_background -> {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.action_chat_background)
                        .setItems(
                            arrayOf(
                                getString(R.string.chat_background_set),
                                getString(R.string.chat_background_clear),
                            ),
                        ) { _, which ->
                            when (which) {
                                0 -> chatBackgroundPickerLauncher.launch(arrayOf("image/*"))
                                1 -> {
                                    viewModel.contact.value?.chatBackgroundUri?.let { deleteLocalChatBackgroundIfOwned(it) }
                                    applyChatBackground("")
                                    viewModel.setChatBackgroundUri(PublicKey(contactPubKey), "")
                                }
                            }
                        }
                        .show()
                    true
                }
                R.id.call -> {
                    if (!viewModel.callingNeedsConfirmation()) {
                        navigateToCallScreen()
                        return@setOnMenuItemClickListener true
                    }
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.call_confirm)
                        .setPositiveButton(R.string.call) { _, _ ->
                            navigateToCallScreen()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                }
                else -> super.onOptionsItemSelected(item)
            }
        }

        contactHeader.setOnClickListener {
            WindowInsetsControllerCompat(requireActivity().window, view).hide(WindowInsetsCompat.Type.ime())
            findNavController().navigate(
                R.id.action_chatFragment_to_contactProfileFragment,
                bundleOf(CONTACT_PUBLIC_KEY to contactPubKey),
            )
        }

        chatAdapter = ChatAdapter(layoutInflater, resources)
        chatAdapter.material3StyleEnabled = viewModel.useMaterial3Ui()
        messages.adapter = chatAdapter
        mediaPhotoGridAdapter = MediaPhotoGridAdapter { uri ->
            viewModel.setActiveChat(PublicKey(contactPubKey))
            viewModel.createFt(uri)
            mediaPickerPanel.isVisible = false
            hideVoiceRecorderPanel()
            hidePhotosPanel()
        }
        mediaPickerPhotosGrid.layoutManager = GridLayoutManager(requireContext(), 3)
        mediaPickerPhotosGrid.adapter = mediaPhotoGridAdapter
        mediaCameraSurface.holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    mediaCameraSurfaceReady = true
                    openEmbeddedCameraIfReady(holder)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    mediaCameraSurfaceReady = false
                    closeEmbeddedCamera()
                }
            },
        )
        val emptyMessagesView = noMessagesView
        emptyMessagesView.setImageHint(R.drawable.ic_oobe_freq_list)
        emptyMessagesView.setTextHint(R.string.chat_empty_text)
        emptyMessagesView.setIsImageVisible(true)
        emptyMessagesView.setIsVerticallyCentered(true)

        viewModel.contact.observe(viewLifecycleOwner) {
            if (it == null) {
                Log.e(TAG, "Contact $contactPubKey does not exist, leaving chat")
                findNavController().popBackStack()
                return@observe
            }
            it.name = it.name.ifEmpty { getString(R.string.contact_default_name) }

            contactName = it.name
            ongoingCall.info.text = getString(R.string.in_call_with, contactName)
            viewModel.contactOnline = it.connectionStatus != ConnectionStatus.None

            title.text = contactName
            // TODO(robinlinden): Replace last message with last seen.
            subtitle.text = when {
                it.typing -> getString(R.string.contact_typing)
                it.lastMessage == 0L -> getString(R.string.never)
                else -> DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(it.lastMessage)
            }.lowercase(Locale.getDefault())

            avatarImageView.setFrom(it)
            chatAdapter.activeContact = it
            chatAdapter.notifyDataSetChanged()
            
            Log.d(TAG, "Observer update - Database background URI: '${it.chatBackgroundUri}', Last applied: '$lastAppliedBackgroundUri'")
            
            // Prevent race condition: database updates are async, so the observer may fire with
            // stale data. If the database value is empty but we just applied a non-empty background,
            // skip updating to avoid showing the cleared state before the database write completes.
            // In all other cases, apply what the database has (could be a matching update, a different
            // background if user changed devices, or an empty value if clearing was successful).
            val skipUpdate = it.chatBackgroundUri.isBlank() && lastAppliedBackgroundUri.isNotBlank()
            if (skipUpdate) {
                Log.d(TAG, "Skipping stale observer update (database empty, locally applied: '$lastAppliedBackgroundUri')")
            } else {
                Log.d(TAG, "Applying background from database: '${it.chatBackgroundUri}'")
                applyChatBackground(it.chatBackgroundUri)
            }
            
            toolbar.menu.findItem(R.id.action_archive)?.title = getString(
                if (it.archived) R.string.action_unarchive else R.string.action_archive,
            )

            if (it.draftMessage.isNotEmpty() && outgoingMessage.text.isEmpty()) {
                outgoingMessage.setText(it.draftMessage)
                viewModel.clearDraft()
            }

            updateActions()
        }

        viewModel.callState.observe(viewLifecycleOwner) { state ->
            when (state) {
                CallAvailability.Unavailable -> {
                    toolbar.menu.findItem(R.id.call).title = getString(R.string.call)
                    toolbar.menu.findItem(R.id.call).isEnabled = false
                }
                CallAvailability.Available -> {
                    toolbar.menu.findItem(R.id.call).title = getString(R.string.call)
                    toolbar.menu.findItem(R.id.call).isEnabled = true
                }
                CallAvailability.Active -> {
                    toolbar.menu.findItem(R.id.call).title = getString(R.string.ongoing_call)
                    toolbar.menu.findItem(R.id.call).isEnabled = true
                }
                null -> {}
            }
        }

        viewModel.ongoingCall.observe(viewLifecycleOwner) {
            if (it is CallState.InCall && it.publicKey.string() == contactPubKey) {
                ongoingCall.container.visibility = View.VISIBLE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    ongoingCall.duration.visibility = View.VISIBLE
                    ongoingCall.duration.base = it.startTime
                    ongoingCall.duration.isCountDown = false
                    ongoingCall.duration.start()
                } else {
                    ongoingCall.duration.visibility = View.GONE
                }
            } else {
                ongoingCall.container.visibility = View.GONE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    ongoingCall.duration.stop()
                }
            }
        }

        ongoingCall.endCall.setOnClickListener { viewModel.onEndCall() }
        ongoingCall.info.setOnClickListener { navigateToCallScreen() }

        viewModel.messages.observe(viewLifecycleOwner) {
            chatAdapter.messages = it
            chatAdapter.notifyDataSetChanged()
            emptyMessagesView.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.fileTransfers.observe(viewLifecycleOwner) {
            fts = it
            chatAdapter.fileTransfers = it
            chatAdapter.notifyDataSetChanged()
        }

        messages.setOnItemClickListener { _, view, position, _ ->
            when (view.id) {
                R.id.accept -> viewModel.acceptFt(chatAdapter.messages[position].correlationId)
                R.id.reject, R.id.cancel -> viewModel.rejectFt(chatAdapter.messages[position].correlationId)
                R.id.fileTransfer -> {
                    val id = chatAdapter.messages[position].correlationId
                    val ft = chatAdapter.fileTransfers.find { it.id == id } ?: return@setOnItemClickListener
                    if (chatAdapter.onFileTransferClicked(ft)) return@setOnItemClickListener
                    if (ft.outgoing) return@setOnItemClickListener
                    if (!ft.isComplete()) return@setOnItemClickListener
                    if (!ft.destination.startsWith("file://")) return@setOnItemClickListener
                    val contentType = URLConnection.guessContentTypeFromName(ft.fileName)
                    val filePath = ft.destination.toUri().path ?: return@setOnItemClickListener
                    val uri = FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.fileprovider",
                        File(filePath),
                    )
                    val shareIntent = Intent(Intent.ACTION_VIEW).apply {
                        putExtra(Intent.EXTRA_TITLE, ft.fileName)
                        setDataAndType(uri, contentType)
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    try {
                        WindowInsetsControllerCompat(requireActivity().window, view).hide(WindowInsetsCompat.Type.ime())
                        startActivity(Intent.createChooser(shareIntent, null))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.mimetype_handler_not_found, contentType),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }

        messages.setOnItemLongClickListener { _, _, position, _ ->
            val message = chatAdapter.messages.getOrNull(position) ?: return@setOnItemLongClickListener false
            startMessageActionMode(message)
            true
        }

        registerForContextMenu(send)
        send.setOnClickListener { send(MessageType.Normal) }

        attach.setOnClickListener {
            WindowInsetsControllerCompat(requireActivity().window, view).hide(WindowInsetsCompat.Type.ime())
            mediaPickerPanel.isVisible = !mediaPickerPanel.isVisible
            if (mediaPickerPanel.isVisible) {
                hideCameraPanel()
                hideVoiceRecorderPanel()
                hidePhotosPanel()
            }
        }

        mediaPickerCamera.setOnClickListener {
            hideVoiceRecorderPanel()
            hidePhotosPanel()
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    android.Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                showCameraPanel()
            } else {
                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            }
        }

        mediaPickerPhotos.setOnClickListener {
            hideVoiceRecorderPanel()
            hideCameraPanel()
            if (canReadPhotos()) {
                showPhotosPanel()
            } else {
                photosPermissionLauncher.launch(photoPermission())
            }
        }

        mediaPickerVoice.setOnClickListener {
            hideCameraPanel()
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    android.Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                showVoiceRecorderPanel()
            } else {
                recordAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        }

        mediaPickerFile.setOnClickListener {
            hideVoiceRecorderPanel()
            hidePhotosPanel()
            hideCameraPanel()
            attachFilesLauncher.launch(arrayOf("*/*"))
            mediaPickerPanel.isVisible = false
        }

        mediaCameraCapture.setOnClickListener { captureEmbeddedPhoto() }

        voiceRecordButton.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!voiceMemoRecording) {
                        clearVoiceMemoPreview(deleteFile = true)
                        startVoiceMemoRecording()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (voiceMemoRecording) {
                        val heldFor = SystemClock.elapsedRealtime() - voiceMemoStartedAt
                        stopVoiceMemoRecording(keepForPreview = heldFor >= VOICE_MEMO_MIN_DURATION_MS)
                        if (heldFor < VOICE_MEMO_MIN_DURATION_MS) {
                            voiceHintText.setTypeface(null, Typeface.BOLD)
                        }
                    }
                    true
                }
                else -> true
            }
        }

        voicePreviewPlayPauseButton.setOnClickListener {
            toggleVoiceMemoPreviewPlayback()
        }
        voicePreviewSeekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        updateVoicePreviewUi(progress, voiceMemoPreviewDurationMs)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    voiceMemoPreviewSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val player = voiceMemoPlayer ?: run {
                        voiceMemoPreviewSeeking = false
                        return
                    }
                    val progress = seekBar?.progress ?: 0
                    player.seekTo(progress)
                    voiceMemoPreviewSeeking = false
                    updateVoicePreviewUi(progress, voiceMemoPreviewDurationMs)
                }
            },
        )
        voicePreviewSendInline.setOnClickListener {
            sendVoiceMemoPreview()
        }
        voicePreviewDiscardInline.setOnClickListener {
            clearVoiceMemoPreview(deleteFile = true)
            updateVoiceRecorderIdleUi()
        }

        outgoingMessage.doAfterTextChanged {
            viewModel.setTyping(outgoingMessage.text.isNotEmpty())
            updateActions()
        }
        outgoingMessage.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                collapseMediaPickerForTyping()
            }
        }
        outgoingMessage.setOnClickListener {
            collapseMediaPickerForTyping()
        }

        updateActions()

        if (arguments?.getBoolean(FOCUS_ON_MESSAGE_BOX) == true) {
            outgoingMessage.requestFocus()
        }
    }

    override fun onPause() {
        messageActionMode?.finish()
        if (voiceMemoRecording) {
            stopVoiceMemoRecording(keepForPreview = false)
        }
        clearVoiceMemoPreview(deleteFile = false)
        closeEmbeddedCamera()
        if (::chatAdapter.isInitialized) {
            chatAdapter.releaseAudio()
        }
        viewModel.setDraft(binding.outgoingMessage.text.toString())
        viewModel.setActiveChat(PublicKey(""))
        lastAppliedBackgroundUri = ""  // Reset tracking when leaving this chat
        super.onPause()
    }

    override fun onResume() = binding.run {
        viewModel.setActiveChat(PublicKey(contactPubKey))
        viewModel.setTyping(outgoingMessage.text.isNotEmpty())
        super.onResume()
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) = binding.run {
        super.onCreateContextMenu(menu, v, menuInfo)
        v.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0))
        when (v.id) {
            R.id.send -> requireActivity().menuInflater.inflate(R.menu.chat_send_long_press_menu, menu)
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean = binding.run {
        return when (item.itemId) {
            R.id.send_action -> {
                send(MessageType.Action)
                true
            }
            else -> super.onContextItemSelected(item)
        }
    }

    private fun startMessageActionMode(message: Message) {
        messageActionMode?.finish()
        val host = requireActivity() as? AppCompatActivity ?: return
        messageActionMode = host.startSupportActionMode(
            object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: android.view.Menu): Boolean {
                    mode.title = null
                    mode.subtitle = null
                    mode.menuInflater.inflate(R.menu.chat_message_action_mode_menu, menu)
                    binding.appBarLayout.visibility = View.GONE
                    // Some devices/themes ignore actionModeBackground; force Messaging-style CAB colors.
                    (host.findViewById<View?>(androidx.appcompat.R.id.action_mode_bar)
                        ?: host.findViewById(androidx.appcompat.R.id.action_bar))
                        ?.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.ui_white))
                    val dark = ContextCompat.getColor(requireContext(), R.color.ui_icon_dark)
                    host.findViewById<ImageView?>(androidx.appcompat.R.id.action_mode_close_button)
                        ?.setColorFilter(dark)
                    for (i in 0 until menu.size()) {
                        menu.getItem(i).icon?.mutate()?.setTint(dark)
                    }
                    return true
                }

                override fun onPrepareActionMode(mode: ActionMode, menu: android.view.Menu): Boolean = false

                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                    when (item.itemId) {
                        R.id.action_share_message -> {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, message.message)
                            }
                            startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
                        }
                        R.id.action_forward_message -> {
                            mode.finish()
                            findNavController().navigate(
                                R.id.contactListFragment,
                                bundleOf(ARG_SHARE to message.message),
                            )
                            return true
                        }
                        R.id.action_copy_message -> {
                            val clipboard = requireActivity().getSystemService<ClipboardManager>()!!
                            clipboard.setPrimaryClip(ClipData.newPlainText(getText(R.string.message), message.message))
                            Toast.makeText(requireContext(), getText(R.string.copied), Toast.LENGTH_SHORT).show()
                        }
                        R.id.action_view_message_info -> {
                            showMessageInfo(message)
                        }
                        R.id.action_delete_message -> {
                            AlertDialog.Builder(requireContext())
                                .setTitle(R.string.delete_message)
                                .setMessage(
                                    getString(
                                        R.string.delete_message_confirm,
                                        message.message.truncated(MAX_CONFIRM_DELETE_STRING_LENGTH),
                                    ),
                                )
                                .setPositiveButton(R.string.delete) { _, _ ->
                                    viewModel.delete(message)
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                        else -> return false
                    }
                    mode.finish()
                    return true
                }

                override fun onDestroyActionMode(mode: ActionMode) {
                    binding.appBarLayout.visibility = View.VISIBLE
                    if (messageActionMode === mode) {
                        messageActionMode = null
                    }
                }
            },
        )
    }

    private fun showMessageInfo(message: Message) {
        val senderLabel = when (message.sender) {
            ltd.evilcorp.core.vo.Sender.Sent -> "Sent"
            ltd.evilcorp.core.vo.Sender.Received -> "Received"
        }
        val typeLabel = when (message.type) {
            MessageType.Normal -> "Text"
            MessageType.Action -> "Action"
            MessageType.FileTransfer -> "File transfer"
        }
        val timeLabel = if (message.timestamp > 0L) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(message.timestamp)
        } else {
            getString(R.string.unknown_time)
        }
        val text = listOf(
            getString(R.string.message_info_sender, senderLabel),
            getString(R.string.message_info_type, typeLabel),
            getString(R.string.message_info_time, timeLabel),
            getString(R.string.message_info_chars, message.message.length),
        ).joinToString(separator = "\n")
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.message_info_title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun send(type: MessageType) = binding.run {
        viewModel.clearDraft()
        viewModel.send(outgoingMessage.text.toString(), type)
        outgoingMessage.text.clear()
    }

    private fun updateActions() = binding.run {
        send.visibility = if (outgoingMessage.text.isEmpty()) View.GONE else View.VISIBLE
        attach.visibility = if (send.isVisible) View.GONE else View.VISIBLE
        attach.isEnabled = viewModel.contactOnline
        attach.clearColorFilter()
        attach.alpha = if (attach.isEnabled) 1.0f else 0.45f
        if (!attach.isEnabled || send.isVisible) {
            if (voiceMemoRecording) {
                stopVoiceMemoRecording(keepForPreview = false)
            } else {
                voiceMemoFile?.delete()
                voiceMemoFile = null
            }
            clearVoiceMemoPreview(deleteFile = true)
            hideCameraPanel()
            mediaPickerPanel.isVisible = false
            hideVoiceRecorderPanel()
        }
    }

    private fun canReadPhotos(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), photoPermission()) == PackageManager.PERMISSION_GRANTED

    private fun photoPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun showPhotosPanel() {
        binding.mediaPickerPanel.isVisible = true
        binding.mediaPickerContent.isVisible = true
        binding.mediaPickerCameraChooser.isVisible = false
        closeEmbeddedCamera()
        binding.mediaPickerPhotosGrid.isVisible = true
        binding.mediaPickerPhotos.isActivated = true
        binding.mediaPickerCamera.isActivated = false
        binding.mediaPickerVoice.isActivated = false
        binding.mediaPickerFile.isActivated = false
        loadPhotosIntoPanel()
    }

    private fun hidePhotosPanel() {
        binding.mediaPickerPhotosGrid.isVisible = false
        binding.mediaPickerPhotosEmpty.isVisible = false
        binding.mediaPickerPhotos.isActivated = false
    }

    private fun loadPhotosIntoPanel() {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val items = mutableListOf<Uri>()
        requireContext().contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext() && items.size < 200) {
                val id = cursor.getLong(idIdx)
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                items += uri
            }
        }
        mediaPhotoGridAdapter.submitItems(items)
        binding.mediaPickerPhotosEmpty.isVisible = items.isEmpty()
    }

    private fun showCameraPanel() {
        binding.mediaPickerPanel.isVisible = true
        binding.mediaPickerContent.isVisible = true
        binding.mediaPickerCameraChooser.isVisible = true
        binding.mediaPickerPhotosGrid.isVisible = false
        binding.mediaPickerPhotosEmpty.isVisible = false
        binding.mediaPickerVoiceChooser.isVisible = false
        binding.mediaPickerCamera.isActivated = true
        binding.mediaPickerPhotos.isActivated = false
        binding.mediaPickerVoice.isActivated = false
        binding.mediaPickerFile.isActivated = false
        openEmbeddedCameraIfReady(binding.mediaCameraSurface.holder)
    }

    private fun hideCameraPanel() {
        binding.mediaPickerCameraChooser.isVisible = false
        binding.mediaPickerCamera.isActivated = false
        closeEmbeddedCamera()
    }

    private fun openEmbeddedCameraIfReady(holder: SurfaceHolder) {
        if (!mediaCameraSurfaceReady || !binding.mediaPickerCameraChooser.isVisible || mediaCamera != null) return
        try {
            val opened = Camera.open() ?: return
            mediaCamera = opened
            opened.setPreviewDisplay(holder)
            opened.setDisplayOrientation(90)
            opened.startPreview()
        } catch (_: Exception) {
            closeEmbeddedCamera()
            Toast.makeText(requireContext(), R.string.media_picker_camera_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun closeEmbeddedCamera() {
        mediaCamera?.apply {
            stopPreview()
            release()
        }
        mediaCamera = null
    }

    private fun captureEmbeddedPhoto() {
        val cam = mediaCamera ?: return
        cam.takePicture(null, null) { data, camera ->
            runCatching {
                val file = File(requireContext().cacheDir.resolve("shared_images"), "camera_${System.currentTimeMillis()}.jpg")
                file.parentFile?.mkdirs()
                file.outputStream().use { it.write(data) }
                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    file,
                )
                viewModel.setActiveChat(PublicKey(contactPubKey))
                viewModel.createFt(uri)
                binding.mediaPickerPanel.isVisible = false
                hideCameraPanel()
            }.onFailure {
                Toast.makeText(requireContext(), R.string.media_picker_camera_open_failed, Toast.LENGTH_LONG).show()
                runCatching { camera.startPreview() }
            }
        }
    }

    private fun startVoiceMemoRecording() {
        clearVoiceMemoPreview(deleteFile = true)
        val cacheDir = File(requireContext().cacheDir, VOICE_MEMO_CACHE_SUBDIR)
        cacheDir.mkdirs()
        val outputFile = File(cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(requireContext())
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(128_000)
            recorder.setAudioSamplingRate(44_100)
            recorder.setOutputFile(outputFile.absolutePath)
            recorder.prepare()
            recorder.start()
            voiceMemoRecorder = recorder
            voiceMemoFile = outputFile
            voiceMemoRecording = true
            voiceMemoStartedAt = SystemClock.elapsedRealtime()
            binding.voicePreviewLayout.visibility = View.GONE
            binding.voiceHintText.visibility = View.GONE
            binding.voiceTimerText.visibility = View.VISIBLE
            binding.voiceTimerText.base = SystemClock.elapsedRealtime()
            binding.voiceTimerText.start()
            updateVoiceButtonAppearance(isRecording = true)
        } catch (_: Throwable) {
            recorder.release()
            voiceMemoRecorder = null
            voiceMemoFile = null
            voiceMemoRecording = false
            updateVoiceButtonAppearance(isRecording = false)
            Toast.makeText(requireContext(), R.string.media_picker_voice_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun stopVoiceMemoRecording(keepForPreview: Boolean) {
        val recorder = voiceMemoRecorder ?: return
        val file = voiceMemoFile
        try {
            recorder.stop()
        } catch (_: Throwable) {
            // Can throw when recording is too short.
        } finally {
            recorder.release()
            voiceMemoRecorder = null
            voiceMemoRecording = false
            binding.voiceTimerText.stop()
            binding.voiceTimerText.visibility = View.GONE
            updateVoiceButtonAppearance(isRecording = false)
        }

        if (keepForPreview && file != null && file.exists() && file.length() > 0L) {
            showVoiceMemoPreview(file)
        } else {
            file?.delete()
            voiceMemoFile = null
            updateVoiceRecorderIdleUi()
        }

        voiceMemoStartedAt = 0L
    }

    private fun showVoiceRecorderPanel() {
        binding.mediaPickerPanel.isVisible = true
        binding.mediaPickerContent.isVisible = true
        binding.mediaPickerVoiceChooser.isVisible = true
        binding.mediaPickerCameraChooser.isVisible = false
        closeEmbeddedCamera()
        binding.mediaPickerPhotosGrid.isVisible = false
        binding.mediaPickerPhotosEmpty.isVisible = false
        binding.mediaPickerVoice.isActivated = true
        binding.mediaPickerCamera.isActivated = false
        binding.mediaPickerPhotos.isActivated = false
        binding.mediaPickerFile.isActivated = false
        updateVoiceRecorderIdleUi()
        updateVoiceButtonAppearance(isRecording = voiceMemoRecording)
    }

    private fun hideVoiceRecorderPanel() {
        if (voiceMemoRecording) {
            stopVoiceMemoRecording(keepForPreview = false)
        }
        clearVoiceMemoPreview(deleteFile = true)
        binding.mediaPickerVoiceChooser.isVisible = false
        binding.mediaPickerVoice.isActivated = false
        if (!binding.mediaPickerPhotosGrid.isVisible && !binding.mediaPickerCameraChooser.isVisible) {
            binding.mediaPickerContent.isVisible = false
        }
    }

    private fun updateVoiceButtonAppearance(isRecording: Boolean) {
        val bg = binding.voiceRecordButtonVisual.background as? GradientDrawable
        val colorPrimary = resolveThemeColor(androidx.appcompat.R.attr.colorPrimary, R.color.colorPrimary)
        if (isRecording) {
            binding.voiceRecordButtonVisual.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP)
            bg?.setColor(colorPrimary)
        } else {
            binding.voiceRecordButtonVisual.setColorFilter(
                colorPrimary,
                PorterDuff.Mode.SRC_ATOP,
            )
            bg?.setColor(Color.WHITE)
        }
    }

    private fun resolveThemeColor(attr: Int, fallbackColorRes: Int): Int {
        return AppColorResolver.resolve(requireContext(), attr, fallbackColorRes)
    }

    private fun updateVoiceRecorderIdleUi() {
        if (voiceMemoRecording) return
        binding.voiceTimerText.visibility = View.GONE
        binding.voiceHintText.visibility = if (binding.voicePreviewLayout.isVisible) View.GONE else View.VISIBLE
        binding.voiceHintText.setTypeface(null, Typeface.NORMAL)
    }

    private fun showVoiceMemoPreview(file: File) {
        voiceMemoFile = file
        releaseVoiceMemoPreviewPlayer()
        voiceMemoPreviewDurationMs = 0
        val player = MediaPlayer()
        voiceMemoPlayer = player
        runCatching {
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener { prepared ->
                voiceMemoPreviewDurationMs = max(prepared.duration, 0)
                binding.voicePreviewLayout.visibility = View.VISIBLE
                updateVoicePreviewUi(0, voiceMemoPreviewDurationMs)
                setVoicePreviewPlaying(false)
            }
            player.setOnCompletionListener { done ->
                done.seekTo(0)
                updateVoicePreviewUi(0, voiceMemoPreviewDurationMs)
                setVoicePreviewPlaying(false)
            }
            player.setOnErrorListener { _, _, _ ->
                clearVoiceMemoPreview(deleteFile = true)
                true
            }
            player.prepareAsync()
        }.onFailure {
            clearVoiceMemoPreview(deleteFile = true)
            Toast.makeText(requireContext(), R.string.media_picker_voice_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleVoiceMemoPreviewPlayback() {
        val player = voiceMemoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            setVoicePreviewPlaying(false)
        } else {
            player.start()
            setVoicePreviewPlaying(true)
        }
    }

    private fun setVoicePreviewPlaying(playing: Boolean) {
        binding.voicePreviewPlayPauseButton.setImageResource(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
        )
        if (playing) {
            voicePreviewUiHandler.removeCallbacks(updateVoicePreviewProgress)
            voicePreviewUiHandler.post(updateVoicePreviewProgress)
        } else {
            voicePreviewUiHandler.removeCallbacks(updateVoicePreviewProgress)
        }
    }

    private fun updateVoicePreviewUi(positionMs: Int, durationMs: Int) {
        binding.voicePreviewTimer.text = formatAudioProgress(positionMs, durationMs)
        binding.voicePreviewSeekBar.max = max(durationMs, 1)
        binding.voicePreviewSeekBar.progress = positionMs.coerceAtMost(binding.voicePreviewSeekBar.max)
        binding.voicePreviewSeekBar.isEnabled = durationMs > 0
        val progressDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.audio_progress_bar_progress)
        if (progressDrawable != null) {
            binding.voicePreviewSeekBar.progressDrawable =
                ClipDrawable(progressDrawable, android.view.Gravity.START, ClipDrawable.HORIZONTAL)
        }
        binding.voicePreviewSeekBar.background =
            ContextCompat.getDrawable(requireContext(), R.drawable.audio_progress_bar_background_outgoing)
    }

    private fun sendVoiceMemoPreview() {
        val file = voiceMemoFile ?: return
        if (!file.exists() || file.length() == 0L) {
            clearVoiceMemoPreview(deleteFile = true)
            return
        }
        viewModel.setActiveChat(PublicKey(contactPubKey))
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file,
        )
        viewModel.createFt(uri)
        clearVoiceMemoPreview(deleteFile = false)
        voiceMemoFile = null
        binding.mediaPickerPanel.isVisible = false
        hideVoiceRecorderPanel()
    }

    private fun clearVoiceMemoPreview(deleteFile: Boolean) {
        setVoicePreviewPlaying(false)
        releaseVoiceMemoPreviewPlayer()
        binding.voicePreviewLayout.visibility = View.GONE
        voiceMemoPreviewDurationMs = 0
        if (deleteFile) {
            voiceMemoFile?.delete()
            voiceMemoFile = null
        }
    }

    private fun releaseVoiceMemoPreviewPlayer() {
        voicePreviewUiHandler.removeCallbacks(updateVoicePreviewProgress)
        voiceMemoPlayer?.release()
        voiceMemoPlayer = null
        voiceMemoPreviewSeeking = false
    }

    private fun formatAudioProgress(positionMs: Int, durationMs: Int): String {
        if (durationMs <= 0) return formatAudioTime(positionMs)
        return "${formatAudioTime(positionMs)} / ${formatAudioTime(durationMs)}"
    }

    private fun formatAudioTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    private fun navigateToCallScreen() {
        view?.let { WindowInsetsControllerCompat(requireActivity().window, it).hide(WindowInsetsCompat.Type.ime()) }
        findNavController().navigate(
            R.id.action_chatFragment_to_callFragment,
            bundleOf(CONTACT_PUBLIC_KEY to contactPubKey),
        )
    }

    private fun applyComposerUiStyle() = binding.run {
        if (viewModel.useMaterial3Ui()) {
            outgoingMessage.setBackgroundResource(R.drawable.msg_bubble_input_m3)
        } else {
            outgoingMessage.setBackgroundResource(R.drawable.msg_bubble_input)
            outgoingMessage.background?.mutate()?.setColorFilter(
                ContextCompat.getColor(requireContext(), R.color.mg_input_background),
                PorterDuff.Mode.SRC_IN,
            )
        }
    }

    private fun applyChatBackground(backgroundUri: String) = binding.run {
        Log.d(TAG, "Applying chat background: '$backgroundUri'")
        lastAppliedBackgroundUri = backgroundUri  // Track what we're applying
        
        if (backgroundUri.isBlank()) {
            Log.d(TAG, "Background URI is blank, clearing background")
            Picasso.get().cancelRequest(chatBackgroundImage)
            chatBackgroundImage.setImageDrawable(null)
            chatBackgroundImage.visibility = View.GONE
            return
        }

        val uri = Uri.parse(backgroundUri)
        Log.d(TAG, "Background URI parsed: $uri, scheme: ${uri.scheme}")
        chatBackgroundImage.visibility = View.VISIBLE
        
        // Invalidate Picasso cache for this URI to ensure we load the latest image
        // This is important when the user changes the background since the filename stays the same
        Log.d(TAG, "Invalidating Picasso cache for URI: $uri")
        Picasso.get().invalidate(uri)
        
        val request = Picasso.get().load(uri)
        Log.d(TAG, "Loading background image with Picasso from: $uri")
        request
            .fit()
            .centerCrop()
            .noFade()
            .into(chatBackgroundImage, object : Callback {
                override fun onSuccess() {
                    Log.d(TAG, "Background image loaded successfully")
                }
                override fun onError(e: Exception?) {
                    Log.e(TAG, "Failed to load chat background: $backgroundUri", e)
                    Log.d(TAG, "Attempting fallback with setImageURI")
                    chatBackgroundImage.setImageURI(uri)
                }
            })
    }

    private fun copyChatBackgroundToPrivateStorage(source: Uri): String? {
        return runCatching {
            val dir = File(requireContext().filesDir, "chat_bg").apply { mkdirs() }
            val target = File(dir, "${contactPubKey.lowercase(Locale.getDefault())}.jpg")
            Log.d(TAG, "Copying background from $source to ${target.absolutePath}")
            requireContext().contentResolver.openInputStream(source).use { input ->
                if (input == null) {
                    throw IllegalStateException("Unable to open selected image")
                }
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            val targetUri = Uri.fromFile(target).toString()
            Log.d(TAG, "Background copied successfully to: $targetUri")
            // Invalidate Picasso cache to ensure the new image is loaded, not a cached old one
            Picasso.get().invalidate(Uri.parse(targetUri))
            Log.d(TAG, "Picasso cache invalidated for: $targetUri")
            targetUri
        }.onFailure {
            Log.e(TAG, "Failed to copy chat background", it)
            Toast.makeText(requireContext(), R.string.chat_background_permission_error, Toast.LENGTH_LONG).show()
        }.getOrNull()
    }

    private fun deleteLocalChatBackgroundIfOwned(uriString: String) {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "file") return
        val path = uri.path ?: return
        val ownedDir = File(requireContext().filesDir, "chat_bg").absolutePath
        if (path.startsWith(ownedDir)) {
            runCatching { File(path).delete() }
        }
    }

    private fun collapseMediaPickerForTyping() {
        if (!binding.mediaPickerPanel.isVisible) return
        if (voiceMemoRecording) {
            stopVoiceMemoRecording(keepForPreview = false)
        }
        clearVoiceMemoPreview(deleteFile = true)
        hideCameraPanel()
        hideVoiceRecorderPanel()
        hidePhotosPanel()
        binding.mediaPickerPanel.isVisible = false
    }
}
