// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2020 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.dismal.btox.ui.addcontact

import android.app.Activity.RESULT_OK
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import android.nfc.tech.Ndef
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import kotlin.math.max
import com.dismal.btox.R
import com.dismal.btox.databinding.FragmentAddContactBinding
import com.dismal.btox.ui.BaseFragment
import com.dismal.btox.ui.chat.CONTACT_PUBLIC_KEY
import com.dismal.btox.vmFactory
import ltd.evilcorp.core.vo.Contact
import ltd.evilcorp.domain.tox.ToxID
import ltd.evilcorp.domain.tox.ToxIdValidator

class AddContactFragment : BaseFragment<FragmentAddContactBinding>(FragmentAddContactBinding::inflate) {
    private val viewModel: AddContactViewModel by viewModels { vmFactory }

    private var toxIdValid: Boolean = false
    private var messageValid: Boolean = true
    private var nfcScanArmed: Boolean = false
    private var nfcEnabled: Boolean = false

    private var contacts: List<Contact> = listOf()
    private var nfcAdapter: NfcAdapter? = null

    private fun isAddAllowed(): Boolean = toxIdValid && messageValid

    private val nfcReaderCallback = NfcAdapter.ReaderCallback { tag ->
        val toxId = extractToxIdFromTag(tag) ?: return@ReaderCallback
        activity?.runOnUiThread {
            binding.toxId.setText(toxId)
            playNfcResultTone(success = true)
            Toast.makeText(requireContext(), R.string.nfc_scan_success, Toast.LENGTH_SHORT).show()
            nfcScanArmed = false
            updateNfcMode()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!viewModel.isToxRunning() && !viewModel.tryLoadTox()) findNavController().navigateUp()
    }

    private val scanQrLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode != RESULT_OK) return@registerForActivityResult
        val toxId = it.data?.getStringExtra("SCAN_RESULT") ?: return@registerForActivityResult
        binding.toxId.setText(toxId.removePrefix("tox:"))
    }

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

        viewModel.contacts.observe(viewLifecycleOwner) {
            contacts = it
        }

        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener {
            WindowInsetsControllerCompat(requireActivity().window, view)
                .hide(WindowInsetsCompat.Type.ime())
            findNavController().navigateUp()
        }

        toxId.doAfterTextChanged { s ->
            val input = ToxID(s?.toString() ?: "")
            val inputLength = s?.toString()?.length ?: 0
            var toxIdError: String? = when (ToxIdValidator.validate(input)) {
                ToxIdValidator.Result.INCORRECT_LENGTH -> getString(R.string.tox_id_error_length, inputLength)
                ToxIdValidator.Result.INVALID_CHECKSUM -> getString(R.string.tox_id_error_checksum)
                ToxIdValidator.Result.NOT_HEX -> getString(R.string.tox_id_error_hex)
                ToxIdValidator.Result.NO_ERROR -> null
            }

            if (input == viewModel.toxId) {
                toxIdError = getString(R.string.tox_id_error_self_add)
            }

            if (toxIdError == null) {
                if (contacts.find { it.publicKey == input.toPublicKey().string() } != null) {
                    toxIdError = getString(R.string.tox_id_error_already_exists)
                }
            }

            toxId.error = null
            toxIdErrorIcon.visibility = if (toxIdError == null) View.GONE else View.VISIBLE
            toxIdErrorText.visibility = if (toxIdError == null) View.GONE else View.VISIBLE
            toxIdErrorText.text = toxIdError.orEmpty()
            toxIdValid = toxIdError == null
            add.isEnabled = isAddAllowed()
        }

        message.doAfterTextChanged { s ->
            val content = s?.toString() ?: ""
            message.error = if (content.isNotEmpty()) {
                null
            } else {
                getString(R.string.add_contact_message_error_empty)
            }

            messageValid = message.error == null
            add.isEnabled = isAddAllowed()
        }

        add.setOnClickListener {
            val id = ToxID(toxId.text.toString())
            viewModel.addContact(id, message.text.toString())
            WindowInsetsControllerCompat(requireActivity().window, view)
                .hide(WindowInsetsCompat.Type.ime())
            findNavController().navigate(
                R.id.chatFragment,
                bundleOf(CONTACT_PUBLIC_KEY to id.toPublicKey().string()),
                navOptions { popUpTo(R.id.contactListFragment) },
            )
        }

        if (requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            readQr.setOnClickListener {
                try {
                    scanQrLauncher.launch(
                        Intent("com.google.zxing.client.android.SCAN").apply {
                            putExtra("SCAN_FORMATS", "QR_CODE")
                            putExtra("SCAN_ORIENTATION_LOCKED", false)
                            putExtra("BEEP_ENABLED", false)
                        },
                    )
                } catch (_: ActivityNotFoundException) {
                    val uri = "https://f-droid.org/en/packages/com.google.zxing.client.android/".toUri()
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
            }
        } else {
            readQr.visibility = View.GONE
        }

        nfcEnabled = viewModel.isNfcFriendAddEnabled()
        nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())
        if (!nfcEnabled) {
            readNfc.visibility = View.GONE
        } else {
            readNfc.visibility = View.VISIBLE
            readNfc.setOnClickListener {
                if (nfcAdapter == null) {
                    playNfcResultTone(success = false)
                    Toast.makeText(requireContext(), R.string.nfc_not_available, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                nfcScanArmed = true
                updateNfcMode()
                Toast.makeText(requireContext(), R.string.nfc_scan_prompt, Toast.LENGTH_LONG).show()
            }
        }

        add.isEnabled = false

        toxId.setText(arguments?.getString("toxId"), TextView.BufferType.EDITABLE)
    }

    override fun onResume() {
        super.onResume()
        updateNfcMode()
    }

    override fun onPause() {
        nfcScanArmed = false
        updateNfcMode()
        super.onPause()
    }

    private fun updateNfcMode() {
        val activity = activity ?: return
        val adapter = nfcAdapter ?: return

        if (nfcEnabled && nfcScanArmed) {
            adapter.enableReaderMode(
                activity,
                nfcReaderCallback,
                NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_NFC_BARCODE,
                null,
            )
        } else {
            adapter.disableReaderMode(activity)
        }

    }

    private fun extractToxIdFromTag(tag: Tag): String? {
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            activity?.runOnUiThread {
                playNfcResultTone(success = false)
                context?.let { safeContext ->
                    Toast.makeText(safeContext, R.string.nfc_scan_invalid, Toast.LENGTH_LONG).show()
                }
            }
            return null
        }
        return runCatching {
            ndef.connect()
            val message = ndef.cachedNdefMessage ?: ndef.ndefMessage
            ndef.close()
            extractToxIdFromMessage(message)
        }.getOrElse {
            activity?.runOnUiThread {
                playNfcResultTone(success = false)
                context?.let { safeContext ->
                    Toast.makeText(safeContext, R.string.nfc_scan_invalid, Toast.LENGTH_LONG).show()
                }
            }
            null
        }
    }

    private fun extractToxIdFromMessage(message: NdefMessage?): String? {
        if (message == null) {
            activity?.runOnUiThread {
                playNfcResultTone(success = false)
                context?.let { safeContext ->
                    Toast.makeText(safeContext, R.string.nfc_scan_invalid, Toast.LENGTH_LONG).show()
                }
            }
            return null
        }
        for (record in message.records) {
            extractToxIdFromRecord(record)?.let { return it }
        }
        activity?.runOnUiThread {
            playNfcResultTone(success = false)
            context?.let { safeContext ->
                Toast.makeText(safeContext, R.string.nfc_scan_invalid, Toast.LENGTH_LONG).show()
            }
        }
        return null
    }

    private fun extractToxIdFromRecord(record: NdefRecord): String? {
        val fromUri = record.toUri()?.toString()?.let(::normalizeCandidate)
        if (fromUri != null) return fromUri

        val payload = runCatching { String(record.payload ?: byteArrayOf(), Charsets.UTF_8) }.getOrNull()
        return payload?.let(::normalizeCandidate)
    }

    private fun normalizeCandidate(raw: String): String? {
        val full = raw.trim()
        val direct = full.removePrefix("tox:").removePrefix("TOX:")
        if (isValidToxId(direct)) return direct.uppercase()

        val match = Regex("(?i)(tox:)?([0-9a-f]{76})").find(full) ?: return null
        val id = match.groupValues[2]
        return if (isValidToxId(id)) id.uppercase() else null
    }

    private fun isValidToxId(candidate: String): Boolean =
        ToxIdValidator.validate(ToxID(candidate)) == ToxIdValidator.Result.NO_ERROR

    private fun playNfcResultTone(success: Boolean) {
        val safeContext = context ?: return
        val sound = if (success) R.raw.nfc_success else R.raw.nfc_failure
        val player = MediaPlayer.create(safeContext, sound) ?: return
        player.setOnCompletionListener { it.release() }
        player.setOnErrorListener { mp, _, _ ->
            mp.release()
            true
        }
        player.start()
    }
}
