// SPDX-FileCopyrightText: 2021-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2021-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.dismal.btox.ui.call

import android.Manifest
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.asLiveData
import androidx.navigation.fragment.findNavController
import kotlin.math.max
import com.dismal.btox.R
import com.dismal.btox.databinding.FragmentCallBinding
import com.dismal.btox.hasPermission
import com.dismal.btox.requireStringArg
import com.dismal.btox.ui.BaseFragment
import com.dismal.btox.ui.chat.CONTACT_PUBLIC_KEY
import com.dismal.btox.vmFactory
import ltd.evilcorp.core.vo.PublicKey
import ltd.evilcorp.domain.feature.CallState

private const val PERMISSION = Manifest.permission.RECORD_AUDIO

class CallFragment : BaseFragment<FragmentCallBinding>(FragmentCallBinding::inflate) {
    private val vm: CallViewModel by viewModels { vmFactory }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            vm.startSendingAudio()
        } else {
            Toast.makeText(requireContext(), getString(R.string.call_mic_permission_needed), Toast.LENGTH_LONG).show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        val baseBottomMargin = (controlContainer.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, compat ->
            val bars = compat.getInsets(WindowInsetsCompat.Type.systemBars())
            val gestures = compat.getInsets(WindowInsetsCompat.Type.systemGestures())
            val bottomInset = max(bars.bottom, gestures.bottom)
            controlContainer.updatePadding(left = bars.left, right = bars.right)
            controlContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = baseBottomMargin + bottomInset
            }
            compat
        }

        vm.setActiveContact(PublicKey(requireStringArg(CONTACT_PUBLIC_KEY)))
        vm.contact.observe(viewLifecycleOwner) {
            if (it != null) {
                avatarImageView.setFrom(it)
                callContactName.text = it.name.ifEmpty { getString(R.string.contact_default_name) }
            }
        }
        callStatusText.text = getString(R.string.connecting)
        callDuration.stop()
        callDuration.visibility = View.GONE

        endCall.setOnClickListener {
            vm.endCall()
            findNavController().popBackStack()
        }

        vm.sendingAudio.asLiveData().observe(viewLifecycleOwner) { sending ->
            if (sending) {
                microphoneControl.setImageResource(R.drawable.ic_mic)
            } else {
                microphoneControl.setImageResource(R.drawable.ic_mic_off)
            }
            microphoneControl.isSelected = sending
        }

        microphoneControl.setOnClickListener {
            if (vm.sendingAudio.value) {
                vm.stopSendingAudio()
            } else {
                if (requireContext().hasPermission(PERMISSION)) {
                    vm.startSendingAudio()
                } else {
                    requestPermissionLauncher.launch(PERMISSION)
                }
            }
        }

        updateSpeakerphoneIcon()
        speakerphone.setOnClickListener {
            vm.toggleSpeakerphone()
            updateSpeakerphoneIcon()
        }

        backToChat.setOnClickListener {
            findNavController().popBackStack()
        }

        if (vm.inCall.value is CallState.InCall) {
            vm.inCall.asLiveData().observe(viewLifecycleOwner) { inCall ->
                if (inCall == CallState.NotInCall) {
                    callDuration.stop()
                    callDuration.visibility = View.GONE
                    findNavController().popBackStack()
                } else if (inCall is CallState.InCall) {
                    callStatusText.text = getString(R.string.ongoing_call)
                    callDuration.base = inCall.startTime
                    callDuration.visibility = View.VISIBLE
                    callDuration.start()
                }
            }
            return
        }

        startCall()

        if (requireContext().hasPermission(PERMISSION)) {
            vm.startSendingAudio()
        }
    }

    private fun updateSpeakerphoneIcon() {
        val icon = if (vm.speakerphoneOn) R.drawable.ic_speakerphone else R.drawable.ic_speakerphone_off
        binding.speakerphone.setImageResource(icon)
        binding.speakerphone.isSelected = vm.speakerphoneOn
    }

    private fun startCall() {
        vm.startCall()
        binding.callStatusText.text = getString(R.string.call)
        vm.inCall.asLiveData().observe(viewLifecycleOwner) { inCall ->
            if (inCall == CallState.NotInCall) {
                binding.callDuration.stop()
                binding.callDuration.visibility = View.GONE
                findNavController().popBackStack()
            } else if (inCall is CallState.InCall) {
                binding.callStatusText.text = getString(R.string.ongoing_call)
                binding.callDuration.base = inCall.startTime
                binding.callDuration.visibility = View.VISIBLE
                binding.callDuration.start()
            }
        }
    }
}
