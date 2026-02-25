// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2021-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.dismal.btox.ui.contactlist

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.view.ContextMenu
import android.view.LayoutInflater
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ActionMode
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.navigation.NavigationView
import kotlin.math.max
import com.dismal.btox.R
import com.dismal.btox.databinding.ContactListViewItemBinding
import com.dismal.btox.databinding.FragmentContactListBinding
import com.dismal.btox.databinding.FriendRequestItemBinding
import com.dismal.btox.databinding.NavHeaderContactListBinding
import com.dismal.btox.settings.AppColorResolver
import com.dismal.btox.hasPermission
import com.dismal.btox.truncated
import com.dismal.btox.ui.BaseFragment
import com.dismal.btox.ui.ReceiveShareDialogFragment
import com.dismal.btox.ui.chat.CONTACT_PUBLIC_KEY
import com.dismal.btox.ui.colorFromStatus
import com.dismal.btox.ui.contactListSorter
import com.dismal.btox.ui.friendrequest.FRIEND_REQUEST_PUBLIC_KEY
import com.dismal.btox.vmFactory
import ltd.evilcorp.core.vo.ConnectionStatus
import ltd.evilcorp.core.vo.Contact
import ltd.evilcorp.core.vo.FriendRequest
import ltd.evilcorp.core.vo.PublicKey
import ltd.evilcorp.core.vo.User
import ltd.evilcorp.domain.tox.ToxID
import ltd.evilcorp.domain.tox.ToxSaveStatus

const val ARG_ADD_CONTACT = "add_contact"
const val ARG_SHARE = "share"
const val ARG_CONTACT_LIST_MODE = "contact_list_mode"
const val CONTACT_LIST_MODE_INBOX = "inbox"
const val CONTACT_LIST_MODE_ARCHIVED = "archived"
const val CONTACT_LIST_MODE_BLOCKED = "blocked"
private const val MAX_CONFIRM_DELETE_STRING_LENGTH = 32
private enum class ContactListMode {
    Inbox,
    Archived,
    Blocked,
}

private fun User.online(): Boolean = connectionStatus != ConnectionStatus.None

class ContactListFragment :
    BaseFragment<FragmentContactListBinding>(FragmentContactListBinding::inflate),
    NavigationView.OnNavigationItemSelectedListener {

    private val viewModel: ContactListViewModel by viewModels { vmFactory }

    private var navHeader: NavHeaderContactListBinding? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> }

    private var passwordDialog: AlertDialog? = null
    private var contactActionMode: ActionMode? = null
    private val selectedContacts = linkedSetOf<String>()
    private lateinit var contactAdapter: ContactAdapter
    private var allContacts: List<Contact> = emptyList()
    private var allFriendRequests: List<FriendRequest> = emptyList()
    private var listMode = ContactListMode.Inbox
    private var useMaterial3Ui = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = super.onCreateView(inflater, container, savedInstanceState)
        navHeader = NavHeaderContactListBinding.bind(binding.navView.getHeaderView(0))
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?): Unit = binding.run {
        if (!viewModel.isToxRunning()) return@run
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!requireContext().hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        useMaterial3Ui = viewModel.useMaterial3Ui()
        setStartConversationFabVisible(listMode == ContactListMode.Inbox)
        val activeFab = if (useMaterial3Ui) startNewConversationButtonM3 else startNewConversationButton

        val baseListPaddingBottom = contactList.paddingBottom
        val baseFabMarginBottom =
            (activeFab.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        val baseFabMarginEnd =
            (activeFab.layoutParams as ViewGroup.MarginLayoutParams).marginEnd

        ViewCompat.setOnApplyWindowInsetsListener(view) { _, compat ->
            val bars = compat.getInsets(WindowInsetsCompat.Type.systemBars())
            val gestures = compat.getInsets(WindowInsetsCompat.Type.systemGestures())
            val ime = compat.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = max(bars.bottom, max(gestures.bottom, ime.bottom))

            toolbar.updatePadding(left = bars.left, right = bars.right)
            navView.updatePadding(left = bars.left)
            contactList.updatePadding(left = bars.left, right = bars.right, bottom = baseListPaddingBottom + bottomInset)
            activeFab.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = baseFabMarginBottom + bottomInset
                marginEnd = baseFabMarginEnd + bars.right
            }
            compat
        }

        toolbar.title = getText(R.string.conversation_list_title)
        toolbar.inflateMenu(R.menu.contact_list_overflow_menu)
        toolbar.setOnMenuItemClickListener {
            onNavigationItemSelected(it)
        }
        AppColorResolver.applyToToolbar(toolbar)
        AppColorResolver.applyToFab(if (useMaterial3Ui) startNewConversationButtonM3 else startNewConversationButton)
        arguments?.getString(ARG_CONTACT_LIST_MODE)?.let { mode ->
            listMode = when (mode) {
                CONTACT_LIST_MODE_ARCHIVED -> ContactListMode.Archived
                CONTACT_LIST_MODE_BLOCKED -> ContactListMode.Blocked
                else -> ContactListMode.Inbox
            }
            arguments?.remove(ARG_CONTACT_LIST_MODE)
        }
        updateListModeUi()

        viewModel.user.observe(viewLifecycleOwner) { user ->
            if (user == null) return@observe

            navHeader!!.apply {
                profileName.text = user.name
                profileStatusMessage.text = user.statusMessage
                profilePhoto.setFrom(user)

                if (user.online()) {
                    statusIndicator.setColorFilter(colorFromStatus(requireContext(), user.status))
                } else {
                    statusIndicator.setColorFilter(R.color.statusOffline)
                }
                root.setBackgroundColor(AppColorResolver.primary(requireContext()))
            }

            toolbar.subtitle = null
        }

        navView.setNavigationItemSelectedListener(this@ContactListFragment)

        contactAdapter = ContactAdapter(layoutInflater, requireContext())
        contactList.adapter = contactAdapter
        registerForContextMenu(contactList)
        val emptyConversationsView = noConversationsView
        emptyConversationsView.setImageHint(R.drawable.ic_oobe_conv_list)
        emptyConversationsView.setTextHint(R.string.conversation_list_empty_text)
        emptyConversationsView.setIsImageVisible(true)
        emptyConversationsView.setIsVerticallyCentered(true)

        viewModel.friendRequests.observe(viewLifecycleOwner) { friendRequests ->
            allFriendRequests = friendRequests
            refreshContactList()
        }

        viewModel.contacts.observe(viewLifecycleOwner) { contacts ->
            allContacts = contacts
            refreshContactList()
        }

        contactList.setOnItemClickListener { _, _, position, _ ->
            if (selectedContacts.isNotEmpty()) {
                val item = contactList.getItemAtPosition(position)
                if (item is Contact) {
                    toggleContactSelection(item, contactAdapter)
                }
                return@setOnItemClickListener
            }
            when (contactList.adapter.getItemViewType(position)) {
                ContactListItemType.FriendRequest.ordinal -> {
                    openFriendRequest(contactList.getItemAtPosition(position) as FriendRequest)
                }
                ContactListItemType.Contact.ordinal -> {
                    openChat(contactList.getItemAtPosition(position) as Contact)
                }
            }
        }

        contactList.setOnItemLongClickListener { _, _, position, _ ->
            if (contactList.adapter.getItemViewType(position) != ContactListItemType.Contact.ordinal) {
                return@setOnItemLongClickListener false
            }
            val contact = contactList.getItemAtPosition(position) as Contact
            if (contactActionMode == null) {
                startContactActionMode(contactAdapter)
                if (contactActionMode == null) {
                    return@setOnItemLongClickListener false
                }
            }
            toggleContactSelection(contact, contactAdapter)
            true
        }

        activeFab.setOnClickListener {
            findNavController().navigate(R.id.addContactFragment)
        }

        drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (contactActionMode != null) {
                contactActionMode?.finish()
                return@addCallback
            }
            if (listMode != ContactListMode.Inbox) {
                listMode = ContactListMode.Inbox
                updateListModeUi()
                refreshContactList()
                return@addCallback
            }
            if (!findNavController().popBackStack()) {
                activity?.finish()
            }
        }

        arguments?.getString(ARG_ADD_CONTACT)?.let { toxId ->
            arguments?.remove(ARG_ADD_CONTACT)
            val id = ToxID(toxId)
            val pk = id.toPublicKey()
            if (viewModel.contactAdded(pk)) {
                openChat(pk.string())
            } else {
                findNavController().navigate(R.id.addContactFragment, bundleOf("toxId" to toxId))
            }
        }

        arguments?.getString(ARG_SHARE)?.let { share ->
            ReceiveShareDialogFragment(
                viewModel.contacts,
                share,
                onContactSelected = {
                    viewModel.onShareText(share, it)
                    openChat(it)
                },
                onDialogDismissed = {
                    arguments?.remove(ARG_SHARE)
                },
            ).show(childFragmentManager, null)
        }
    }

    override fun onDestroyView() {
        contactActionMode?.finish()
        navHeader = null
        super.onDestroyView()
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) {
        if (selectedContacts.isNotEmpty()) {
            return
        }
        super.onCreateContextMenu(menu, v, menuInfo)

        val inflater: MenuInflater = requireActivity().menuInflater
        val info = menuInfo as AdapterView.AdapterContextMenuInfo

        when (binding.contactList.adapter.getItemViewType(info.position)) {
            ContactListItemType.FriendRequest.ordinal -> {
                val f = FriendRequestItemBinding.bind(info.targetView)
                menu.setHeaderTitle(f.publicKey.text)
                inflater.inflate(R.menu.friend_request_context_menu, menu)
            }
            ContactListItemType.Contact.ordinal -> {
                val c = ContactListViewItemBinding.bind(info.targetView)
                menu.setHeaderTitle(c.name.text)
                inflater.inflate(R.menu.contact_list_context_menu, menu)
            }
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        if (selectedContacts.isNotEmpty()) {
            return false
        }
        val info = item.menuInfo as AdapterView.AdapterContextMenuInfo

        return when (info.targetView.id) {
            R.id.friendRequestItem -> {
                val friendRequest = binding.contactList.adapter.getItem(info.position) as FriendRequest
                when (item.itemId) {
                    R.id.accept -> {
                        viewModel.acceptFriendRequest(friendRequest)
                    }
                    R.id.reject -> {
                        viewModel.rejectFriendRequest(friendRequest)
                    }
                }
                true
            }
            R.id.contactListItem -> {
                when (item.itemId) {
                    R.id.profile -> {
                        val contact = binding.contactList.adapter.getItem(info.position) as Contact
                        openProfile(contact)
                    }
                    R.id.delete -> {
                        val contact = binding.contactList.adapter.getItem(info.position) as Contact

                        AlertDialog.Builder(requireContext())
                            .setTitle(R.string.delete_contact)
                            .setMessage(
                                getString(
                                    R.string.contact_list_delete_contact_confirm,
                                    contact.name.truncated(MAX_CONFIRM_DELETE_STRING_LENGTH),
                                ),
                            )
                            .setPositiveButton(R.string.delete) { _, _ ->
                                viewModel.deleteContact(PublicKey(contact.publicKey))
                            }
                            .setNegativeButton(android.R.string.cancel, null).show()
                    }
                }
                true
            }
            else -> super.onContextItemSelected(item)
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.drawer_profile -> {
                findNavController().navigate(R.id.action_contactListFragment_to_userProfileFragment)
            }
            R.id.add_contact -> findNavController().navigate(R.id.action_contactListFragment_to_addContactFragment)
            R.id.settings -> findNavController().navigate(R.id.action_contactListFragment_to_settingsFragment)
            R.id.show_archived -> {
                listMode = if (listMode == ContactListMode.Archived) ContactListMode.Inbox else ContactListMode.Archived
                updateListModeUi()
                refreshContactList()
            }
            R.id.quit_tox -> {
                if (!viewModel.quittingNeedsConfirmation()) {
                    viewModel.quitTox()
                    activity?.finishAffinity()
                    return false
                }

                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.quit_confirm)
                    .setPositiveButton(R.string.quit) { _, _ ->
                        viewModel.quitTox()
                        activity?.finishAffinity()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
        return false
    }

    private fun startContactActionMode(contactAdapter: ContactAdapter) {
        val host = requireActivity() as? AppCompatActivity ?: return
        contactActionMode = host.startSupportActionMode(
            object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: android.view.Menu): Boolean {
                    mode.title = null
                    mode.subtitle = null
                    mode.menuInflater.inflate(R.menu.contact_list_action_mode_menu, menu)
                    updateArchiveActionTitle(mode)
                    updateNotificationActionTitle(mode)
                    updateBlockActionTitle(mode)
                    binding.toolbar.visibility = View.GONE
                    (host.findViewById<View?>(androidx.appcompat.R.id.action_mode_bar)
                        ?: host.findViewById(androidx.appcompat.R.id.action_bar))
                        ?.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.ui_white))
                    val dark = ContextCompat.getColor(requireContext(), R.color.ui_icon_dark)
                    host.findViewById<android.widget.ImageView?>(androidx.appcompat.R.id.action_mode_close_button)
                        ?.setColorFilter(dark)
                    for (i in 0 until menu.size()) {
                        menu.getItem(i).icon?.mutate()?.setTint(dark)
                    }
                    return true
                }

                override fun onPrepareActionMode(mode: ActionMode, menu: android.view.Menu): Boolean = false

                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                    return when (item.itemId) {
                        R.id.action_delete -> {
                            val targets = viewModel.contacts.value.orEmpty()
                                .filter { selectedContacts.contains(it.publicKey) }
                            if (targets.isEmpty()) {
                                mode.finish()
                                true
                            } else {
                                val targetName = targets.first().name.truncated(MAX_CONFIRM_DELETE_STRING_LENGTH)
                                val message = if (targets.size == 1) {
                                    getString(R.string.contact_list_delete_contact_confirm, targetName)
                                } else {
                                    getString(R.string.delete_contacts_confirm, targets.size)
                                }
                                AlertDialog.Builder(requireContext())
                                    .setTitle(R.string.delete_contact)
                                    .setMessage(message)
                                    .setPositiveButton(R.string.delete) { _, _ ->
                                        targets.forEach { viewModel.deleteContact(PublicKey(it.publicKey)) }
                                        mode.finish()
                                    }
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .show()
                                true
                            }
                        }
                        R.id.action_notification_off -> {
                            val targets = viewModel.contacts.value.orEmpty()
                                .filter { selectedContacts.contains(it.publicKey) }
                            if (targets.isEmpty()) {
                                mode.finish()
                                return true
                            }

                            val shouldMute = !targets.all { viewModel.isMuted(PublicKey(it.publicKey)) }
                            targets.forEach { viewModel.setMuted(PublicKey(it.publicKey), shouldMute) }
                            contactAdapter.notifyDataSetChanged()
                            updateNotificationActionTitle(mode)
                            true
                        }
                        R.id.action_archive -> {
                            val targets = viewModel.contacts.value.orEmpty()
                                .filter { selectedContacts.contains(it.publicKey) }
                            if (targets.isEmpty()) {
                                mode.finish()
                                return true
                            }
                            val shouldArchive = !targets.all { it.archived }
                            targets.forEach { viewModel.setArchived(PublicKey(it.publicKey), shouldArchive) }
                            Toast.makeText(
                                requireContext(),
                                getString(if (shouldArchive) R.string.conversation_archived else R.string.conversation_unarchived),
                                Toast.LENGTH_SHORT,
                            ).show()
                            mode.finish()
                            true
                        }
                        R.id.action_block -> {
                            val targets = viewModel.contacts.value.orEmpty()
                                .filter { selectedContacts.contains(it.publicKey) }
                            if (targets.isEmpty()) {
                                mode.finish()
                                return true
                            }
                            val shouldBlock = !targets.all { viewModel.isBlocked(PublicKey(it.publicKey)) }
                            targets.forEach { viewModel.setBlocked(PublicKey(it.publicKey), shouldBlock) }
                            Toast.makeText(
                                requireContext(),
                                getString(if (shouldBlock) R.string.conversation_blocked else R.string.conversation_unblocked),
                                Toast.LENGTH_SHORT,
                            ).show()
                            refreshContactList()
                            mode.finish()
                            true
                        }
                        R.id.action_add_contact -> {
                            Toast.makeText(requireContext(), R.string.action_not_supported, Toast.LENGTH_SHORT).show()
                            true
                        }
                        else -> false
                    }
                }

                override fun onDestroyActionMode(mode: ActionMode) {
                    selectedContacts.clear()
                    contactAdapter.selectedPublicKeys = emptySet()
                    contactAdapter.notifyDataSetChanged()
                    binding.toolbar.visibility = View.VISIBLE
                    setStartConversationFabVisible(listMode == ContactListMode.Inbox)
                    if (contactActionMode === mode) {
                        contactActionMode = null
                    }
                }
            },
        )
        setStartConversationFabVisible(false)
    }

    private fun toggleContactSelection(contact: Contact, contactAdapter: ContactAdapter) {
        if (!selectedContacts.add(contact.publicKey)) {
            selectedContacts.remove(contact.publicKey)
        }

        if (selectedContacts.isEmpty()) {
            contactActionMode?.finish()
            return
        }

        contactAdapter.selectedPublicKeys = selectedContacts
        contactAdapter.notifyDataSetChanged()
        contactActionMode?.let {
            updateNotificationActionTitle(it)
            updateArchiveActionTitle(it)
            updateBlockActionTitle(it)
        }
    }

    private fun updateNotificationActionTitle(mode: ActionMode) {
        val targets = viewModel.contacts.value.orEmpty().filter { selectedContacts.contains(it.publicKey) }
        val allMuted = targets.isNotEmpty() && targets.all { viewModel.isMuted(PublicKey(it.publicKey)) }
        mode.menu.findItem(R.id.action_notification_off)?.apply {
            title = getString(if (allMuted) R.string.action_notification_on else R.string.action_notification_off)
            setIcon(if (allMuted) R.drawable.ic_notifications_on_dark else R.drawable.ic_notifications_off_dark)
            icon?.mutate()?.setTint(ContextCompat.getColor(requireContext(), R.color.ui_icon_dark))
        }
    }

    private fun updateArchiveActionTitle(mode: ActionMode) {
        val targets = viewModel.contacts.value.orEmpty().filter { selectedContacts.contains(it.publicKey) }
        val allArchived = targets.isNotEmpty() && targets.all { it.archived }
        mode.menu.findItem(R.id.action_archive)?.title = getString(
            if (allArchived) R.string.action_unarchive else R.string.action_archive,
        )
    }

    private fun updateBlockActionTitle(mode: ActionMode) {
        val targets = viewModel.contacts.value.orEmpty().filter { selectedContacts.contains(it.publicKey) }
        val allBlocked = targets.isNotEmpty() && targets.all { viewModel.isBlocked(PublicKey(it.publicKey)) }
        mode.menu.findItem(R.id.action_block)?.apply {
            title = getString(if (allBlocked) R.string.action_unblock else R.string.action_block)
            icon?.mutate()?.setTint(ContextCompat.getColor(requireContext(), R.color.ui_icon_dark))
        }
    }

    private fun refreshContactList() {
        val blocked = viewModel.blockedKeys()
        contactAdapter.friendRequests = if (listMode == ContactListMode.Inbox) allFriendRequests else emptyList()
        contactAdapter.contacts = allContacts
            .asSequence()
            .filter {
                when (listMode) {
                    ContactListMode.Inbox -> !it.archived && it.publicKey !in blocked
                    ContactListMode.Archived -> it.archived && it.publicKey !in blocked
                    ContactListMode.Blocked -> it.publicKey in blocked
                }
            }
            .sortedWith(
                compareByDescending<Contact> { it.starred }
                    .thenByDescending { contactListSorter(it) },
            )
            .toList()
        contactAdapter.notifyDataSetChanged()
        binding.noConversationsView.setTextHint(when (listMode) {
            ContactListMode.Inbox -> R.string.conversation_list_empty_text
            ContactListMode.Archived -> R.string.archived_empty_text
            ContactListMode.Blocked -> R.string.blocked_empty_text
        })
        binding.noConversationsView.visibility = if (contactAdapter.isEmpty) View.VISIBLE else View.GONE
    }

    private fun updateListModeUi() {
        binding.toolbar.menu.findItem(R.id.show_archived)?.title = getString(
            if (listMode == ContactListMode.Archived) R.string.show_inbox else R.string.show_archived,
        )
        binding.toolbar.title = getString(
            when (listMode) {
                ContactListMode.Inbox -> R.string.conversation_list_title
                ContactListMode.Archived -> R.string.archived_conversations_title
                ContactListMode.Blocked -> R.string.blocked_conversations_title
            },
        )
        setStartConversationFabVisible(listMode == ContactListMode.Inbox)
    }

    private fun setStartConversationFabVisible(visible: Boolean) {
        val value = if (visible) View.VISIBLE else View.GONE
        binding.startNewConversationButton.visibility = if (useMaterial3Ui) View.GONE else value
        binding.startNewConversationButtonM3.visibility = if (useMaterial3Ui) value else View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!viewModel.isToxRunning()) viewModel.tryLoadTox(null)
    }

    override fun onStart() {
        super.onStart()
        if (!viewModel.isToxRunning()) {
            when (val status = viewModel.tryLoadTox(null)) {
                ToxSaveStatus.BadProxyHost, ToxSaveStatus.BadProxyPort,
                ToxSaveStatus.BadProxyType, ToxSaveStatus.ProxyNotFound,
                -> {
                    Toast.makeText(requireContext(), getString(R.string.warn_proxy_broken), Toast.LENGTH_LONG).show()
                    findNavController().navigate(R.id.action_contactListFragment_to_settingsFragment)
                }
                ToxSaveStatus.SaveNotFound ->
                    findNavController().navigate(R.id.action_contactListFragment_to_profileFragment)
                ToxSaveStatus.Encrypted -> {
                    view?.visibility = View.INVISIBLE
                    if (passwordDialog != null) return
                    val passwordEdit = EditText(requireContext()).apply {
                        hint = getString(R.string.password)
                        inputType = EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
                        setSingleLine()
                        transformationMethod = PasswordTransformationMethod()
                    }
                    val passwordLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        LinearLayout(requireContext()).apply {
                            val attr = context.obtainStyledAttributes(intArrayOf(android.R.attr.dialogPreferredPadding))
                            val padding = attr.getDimensionPixelSize(0, 0)
                            attr.recycle()
                            setPadding(padding, 0, padding, 0)
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            )
                            addView(passwordEdit)
                        }
                    } else {
                        null
                    }
                    passwordDialog = AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.unlock_profile))
                        .setView(passwordLayout ?: passwordEdit)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val password = passwordEdit.text.toString()
                            if (viewModel.tryLoadTox(password) == ToxSaveStatus.Ok) {
                                // Hack to reload fragment.
                                parentFragmentManager.beginTransaction().detach(this).commitAllowingStateLoss()
                                parentFragmentManager.beginTransaction().attach(this).commitAllowingStateLoss()
                            } else {
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.incorrect_password),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .setOnDismissListener {
                            passwordDialog = null
                            if (!viewModel.isToxRunning()) {
                                activity?.finishAffinity()
                            }
                        }
                        .show()
                }
                ToxSaveStatus.Ok -> {
                }
                else -> throw Exception("Unhandled tox save error $status")
            }
        }
    }

    private fun openChat(contact: Contact) = openChat(contact.publicKey)
    private fun openChat(pk: String) = findNavController().navigate(
        R.id.action_contactListFragment_to_chatFragment,
        bundleOf(CONTACT_PUBLIC_KEY to pk),
    )

    private fun openFriendRequest(friendRequest: FriendRequest) = findNavController().navigate(
        R.id.action_contactListFragment_to_friendRequestFragment,
        bundleOf(FRIEND_REQUEST_PUBLIC_KEY to friendRequest.publicKey),
    )

    private fun openProfile(contact: Contact) = findNavController().navigate(
        R.id.action_contactListFragment_to_contactProfileFragment,
        bundleOf(CONTACT_PUBLIC_KEY to contact.publicKey),
    )
}
