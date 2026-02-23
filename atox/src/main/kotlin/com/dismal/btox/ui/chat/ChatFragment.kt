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
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ContextMenu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.activity.result.contract.ActivityResultContracts
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
import com.google.android.material.math.MathUtils.lerp
import java.io.File
import java.net.URLConnection
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import com.dismal.btox.R
import com.dismal.btox.databinding.FragmentChatBinding
import com.dismal.btox.requireStringArg
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?): Unit = binding.run {
        contactPubKey = requireStringArg(CONTACT_PUBLIC_KEY)
        viewModel.setActiveChat(PublicKey(contactPubKey))

        ViewCompat.setOnApplyWindowInsetsListener(view) { _, compat ->
            val bars = compat.getInsets(WindowInsetsCompat.Type.systemBars())
            val gestures = compat.getInsets(WindowInsetsCompat.Type.systemGestures())
            val ime = compat.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = max(bars.bottom, max(gestures.bottom, ime.bottom))
            appBarLayout.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            bottomBar.updatePadding(left = bars.left, right = bars.right, bottom = bottomInset)
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
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

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
                    Toast.makeText(requireContext(), R.string.archive_not_supported, Toast.LENGTH_SHORT).show()
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

        val adapter = ChatAdapter(layoutInflater, resources)
        messages.adapter = adapter
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
            adapter.activeContact = it
            adapter.notifyDataSetChanged()

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
            adapter.messages = it
            adapter.notifyDataSetChanged()
            emptyMessagesView.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.fileTransfers.observe(viewLifecycleOwner) {
            fts = it
            adapter.fileTransfers = it
            adapter.notifyDataSetChanged()
        }

        messages.setOnItemClickListener { _, view, position, _ ->
            when (view.id) {
                R.id.accept -> viewModel.acceptFt(adapter.messages[position].correlationId)
                R.id.reject, R.id.cancel -> viewModel.rejectFt(adapter.messages[position].correlationId)
                R.id.fileTransfer -> {
                    val id = adapter.messages[position].correlationId
                    val ft = adapter.fileTransfers.find { it.id == id } ?: return@setOnItemClickListener
                    if (ft.outgoing) return@setOnItemClickListener
                    if (!ft.isComplete()) return@setOnItemClickListener
                    if (!ft.destination.startsWith("file://")) return@setOnItemClickListener
                    val contentType = URLConnection.guessContentTypeFromName(ft.fileName)
                    val uri = FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.fileprovider",
                        File(ft.destination.toUri().path!!),
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
            val message = adapter.messages.getOrNull(position) ?: return@setOnItemLongClickListener false
            startMessageActionMode(message)
            true
        }

        registerForContextMenu(send)
        send.setOnClickListener { send(MessageType.Normal) }

        attach.setOnClickListener {
            WindowInsetsControllerCompat(requireActivity().window, view).hide(WindowInsetsCompat.Type.ime())
            attachFilesLauncher.launch(arrayOf("*/*"))
        }

        outgoingMessage.doAfterTextChanged {
            viewModel.setTyping(outgoingMessage.text.isNotEmpty())
            updateActions()
        }

        updateActions()

        if (arguments?.getBoolean(FOCUS_ON_MESSAGE_BOX) == true) {
            outgoingMessage.requestFocus()
        }
    }

    override fun onPause() {
        messageActionMode?.finish()
        viewModel.setDraft(binding.outgoingMessage.text.toString())
        viewModel.setActiveChat(PublicKey(""))
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
    }

    private fun navigateToCallScreen() {
        view?.let { WindowInsetsControllerCompat(requireActivity().window, it).hide(WindowInsetsCompat.Type.ime()) }
        findNavController().navigate(
            R.id.action_chatFragment_to_callFragment,
            bundleOf(CONTACT_PUBLIC_KEY to contactPubKey),
        )
    }
}
