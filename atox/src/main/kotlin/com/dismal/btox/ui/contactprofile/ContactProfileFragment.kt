// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2021-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.dismal.btox.ui.contactprofile

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.NavHostFragment
import kotlin.math.max
import com.dismal.btox.R
import com.dismal.btox.databinding.FragmentContactProfileBinding
import com.dismal.btox.requireStringArg
import com.dismal.btox.ui.BaseFragment
import com.dismal.btox.ui.chat.CONTACT_PUBLIC_KEY
import com.dismal.btox.vmFactory
import ltd.evilcorp.core.vo.ConnectionStatus
import ltd.evilcorp.core.vo.PublicKey

class ContactProfileFragment : BaseFragment<FragmentContactProfileBinding>(FragmentContactProfileBinding::inflate) {
    private val viewModel: ContactProfileViewModel by viewModels { vmFactory }
    private var isStarred = false
    private fun navigateBack() {
        NavHostFragment.findNavController(this).navigateUp()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, compat ->
            val bars = compat.getInsets(WindowInsetsCompat.Type.systemBars())
            val gestures = compat.getInsets(WindowInsetsCompat.Type.systemGestures())
            val ime = compat.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = max(bars.bottom, max(gestures.bottom, ime.bottom))
            appBar.updatePadding(left = bars.left, right = bars.right)
            content.updatePadding(left = bars.left, right = bars.right, bottom = bottomInset)
            compat
        }

        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener {
            navigateBack()
        }
        toolbar.inflateMenu(R.menu.contact_detail_menu)
        toolbar.setOnMenuItemClickListener { item -> onToolbarItemSelected(item) }
        for (i in 0 until toolbar.menu.size()) {
            toolbar.menu.getItem(i).icon?.mutate()?.setTint(android.graphics.Color.WHITE)
        }

        viewModel.publicKey = PublicKey(requireStringArg(CONTACT_PUBLIC_KEY))
        viewModel.contact.observe(viewLifecycleOwner) { contact ->
            if (contact == null) {
                navigateBack()
                return@observe
            }
            val displayName = contact.name.ifBlank { getString(R.string.contact_default_name) }

            headerMainText.text = displayName
            headerMainText.visibility = View.VISIBLE
            headerMainText.bringToFront()
            headerAvatar.setFrom(contact)
            aboutHeader.text = getString(R.string.about_contact, displayName)

            contactPublicKey.text = contact.publicKey
            contactStatusMessage.text = contact.statusMessage.ifEmpty { getString(R.string.status_note_fallback) }
            contactConnectionStatus.text = when (contact.connectionStatus) {
                ConnectionStatus.None -> getText(R.string.atox_offline)
                ConnectionStatus.TCP -> getText(R.string.atox_connected_with_tcp)
                ConnectionStatus.UDP -> getText(R.string.atox_connected_with_udp)
            }
            isStarred = contact.starred
            updateStarIcon()
        }
    }

    private fun onToolbarItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_favorite -> {
                isStarred = !isStarred
                viewModel.setStarred(isStarred)
                updateStarIcon()
                true
            }
            R.id.action_edit_contact, R.id.action_contact_more -> {
                Toast.makeText(requireContext(), R.string.action_not_supported, Toast.LENGTH_SHORT).show()
                true
            }
            else -> false
        }
    }

    private fun updateStarIcon() {
        binding.toolbar.menu.findItem(R.id.action_favorite)?.setIcon(
            if (isStarred) R.drawable.quantum_ic_star_vd_theme_24
            else R.drawable.quantum_ic_star_border_vd_theme_24,
        )
    }
}
