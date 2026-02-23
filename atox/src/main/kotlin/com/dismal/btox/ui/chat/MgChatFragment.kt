package com.dismal.btox.ui.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.observe
import com.dismal.btox.R
import com.dismal.btox.vmFactory
import kotlin.math.max
import ltd.evilcorp.core.vo.MessageType
import ltd.evilcorp.core.vo.PublicKey
import ltd.evilcorp.core.vo.isComplete

private const val TAG = "MgChatFragment"

class MgChatFragment : Fragment() {
    private val viewModel: ChatViewModel by viewModels { vmFactory }

    private lateinit var contactPubKey: String

    private lateinit var messagesList: ListView
    private lateinit var outgoingMessage: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var attachButton: ImageButton

    private val attachFilesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { files ->
            viewModel.setActiveChat(PublicKey(contactPubKey))
            for (file in files) {
                activity?.contentResolver?.takePersistableUriPermission(file, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                viewModel.createFt(file)
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.mg_stage1_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        contactPubKey = requireArguments().getString(CONTACT_PUBLIC_KEY) ?: ""
        viewModel.setActiveChat(PublicKey(contactPubKey))

        messagesList = view.findViewById(R.id.messages)
        outgoingMessage = view.findViewById(R.id.outgoingMessage)
        sendButton = view.findViewById(R.id.send)
        attachButton = view.findViewById(R.id.attach)
        outgoingMessage.background?.mutate()?.setColorFilter(
            ContextCompat.getColor(requireContext(), R.color.mg_input_background),
            PorterDuff.Mode.SRC_IN,
        )
        val bottomBar = view.findViewById<View>(R.id.bottomBar)
        val appBarLayout = view.findViewById<View>(R.id.appBarLayout)

        ViewCompat.setOnApplyWindowInsetsListener(view) { _, compat ->
            val bars = compat.getInsets(WindowInsetsCompat.Type.systemBars())
            val gestures = compat.getInsets(WindowInsetsCompat.Type.systemGestures())
            val ime = compat.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = max(bars.bottom, max(gestures.bottom, ime.bottom))

            appBarLayout.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            bottomBar.updatePadding(left = bars.left, right = bars.right, bottom = bottomInset)
            messagesList.updatePadding(left = bars.left, right = bars.right)
            compat
        }

        val adapter = ChatAdapter(layoutInflater, resources)
        messagesList.adapter = adapter
        registerForContextMenu(messagesList)

        viewModel.messages.observe(viewLifecycleOwner) { list ->
            adapter.messages = list
            adapter.notifyDataSetChanged()
            messagesList.setSelection(adapter.count - 1)
        }

        viewModel.fileTransfers.observe(viewLifecycleOwner) { fts ->
            adapter.fileTransfers = fts
            adapter.notifyDataSetChanged()
        }

        messagesList.setOnItemClickListener { parent, v, position, id ->
            when (v.id) {
                R.id.accept -> viewModel.acceptFt(adapter.messages[position].correlationId)
                R.id.reject, R.id.cancel -> viewModel.rejectFt(adapter.messages[position].correlationId)
                R.id.fileTransfer -> {
                    val idc = adapter.messages[position].correlationId
                    val ft = adapter.fileTransfers.find { it.id == idc } ?: return@setOnItemClickListener
                    if (ft.outgoing) return@setOnItemClickListener
                    if (!ft.isComplete()) return@setOnItemClickListener
                    if (!ft.destination.startsWith("file://")) return@setOnItemClickListener
                    val contentType = java.net.URLConnection.guessContentTypeFromName(ft.fileName)
                    val uri = androidx.core.content.FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", java.io.File(ft.destination.toUri().path!!))
                    val shareIntent = Intent(Intent.ACTION_VIEW).apply {
                        putExtra(Intent.EXTRA_TITLE, ft.fileName)
                        setDataAndType(uri, contentType)
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    try {
                        WindowInsetsControllerCompat(requireActivity().window, view).hide(android.view.WindowInsets.Type.ime())
                        startActivity(Intent.createChooser(shareIntent, null))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(requireContext(), getString(R.string.mimetype_handler_not_found, contentType), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        registerForContextMenu(sendButton)
        sendButton.setOnClickListener { send() }

        attachButton.setOnClickListener {
            WindowInsetsControllerCompat(requireActivity().window, view).hide(android.view.WindowInsets.Type.ime())
            attachFilesLauncher.launch(arrayOf("*/*"))
        }

        outgoingMessage.doAfterTextChanged {
            viewModel.setTyping(outgoingMessage.text.isNotEmpty())
            updateActions()
        }

        updateActions()
    }

    override fun onPause() {
        viewModel.setDraft(outgoingMessage.text.toString())
        viewModel.setActiveChat(PublicKey(""))
        super.onPause()
    }

    override fun onResume() {
        viewModel.setActiveChat(PublicKey(contactPubKey))
        viewModel.setTyping(outgoingMessage.text.isNotEmpty())
        super.onResume()
    }

    private fun send() {
        viewModel.clearDraft()
        viewModel.send(outgoingMessage.text.toString(), MessageType.Normal)
        outgoingMessage.text.clear()
    }

    private fun updateActions() {
        sendButton.visibility = if (outgoingMessage.text.isEmpty()) View.GONE else View.VISIBLE
        attachButton.visibility = if (sendButton.isVisible) View.GONE else View.VISIBLE
        attachButton.isEnabled = viewModel.contactOnline
        attachButton.setColorFilter(
            androidx.core.content.ContextCompat.getColor(requireContext(), if (attachButton.isEnabled) R.color.colorPrimary else android.R.color.darker_gray)
        )
    }
}
