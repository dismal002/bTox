// SPDX-FileCopyrightText: 2020-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.dismal.btox.ui.friendrequest

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import kotlin.math.max
import com.dismal.btox.R
import com.dismal.btox.databinding.FragmentFriendRequestBinding
import com.dismal.btox.requireStringArg
import com.dismal.btox.ui.BaseFragment
import com.dismal.btox.vmFactory
import ltd.evilcorp.core.vo.FriendRequest
import ltd.evilcorp.core.vo.PublicKey

const val FRIEND_REQUEST_PUBLIC_KEY = "FRIEND_REQUEST_PUBLIC_KEY"

class FriendRequestFragment : BaseFragment<FragmentFriendRequestBinding>(FragmentFriendRequestBinding::inflate) {
    private val vm: FriendRequestViewModel by viewModels { vmFactory }
    private var friendRequest: FriendRequest? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, compat ->
            val bars = compat.getInsets(WindowInsetsCompat.Type.systemBars())
            val gestures = compat.getInsets(WindowInsetsCompat.Type.systemGestures())
            val ime = compat.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = max(bars.bottom, max(gestures.bottom, ime.bottom))
            toolbar.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            content.updatePadding(left = bars.left, right = bars.right, bottom = bottomInset)
            compat
        }

        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.title = getString(R.string.friend_request)
        toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        reject.isEnabled = false
        accept.isEnabled = false

        vm.byId(PublicKey(requireStringArg(FRIEND_REQUEST_PUBLIC_KEY))).observe(viewLifecycleOwner) {
            val request = it
            if (request == null) {
                findNavController().popBackStack()
                return@observe
            }

            friendRequest = request
            from.text = request.publicKey
            message.text = request.message
            reject.isEnabled = true
            accept.isEnabled = true
        }

        accept.setOnClickListener {
            friendRequest?.let(vm::accept)
            findNavController().popBackStack()
        }

        reject.setOnClickListener {
            friendRequest?.let(vm::reject)
            findNavController().popBackStack()
        }
    }
}
