// SPDX-FileCopyrightText: 2026 bTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.dismal.btox.ui.chat

import android.app.Dialog
import android.graphics.drawable.ClipDrawable
import android.view.Gravity
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.dismal.btox.R
import com.dismal.btox.databinding.DialogAudioAttachmentPlayerBinding
import java.io.File
import java.util.Locale
import kotlin.math.max

class AudioAttachmentPlayerDialogFragment : DialogFragment() {
    private var _binding: DialogAudioAttachmentPlayerBinding? = null
    private val binding get() = _binding!!

    private var player: MediaPlayer? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var userSeeking = false

    private val updateProgress = object : Runnable {
        override fun run() {
            val mediaPlayer = player ?: return
            if (!userSeeking) {
                val pos = mediaPlayer.currentPosition
                binding.seekBar.progress = pos
                binding.timer.text = formatProgress(pos, binding.seekBar.max)
            }
            if (mediaPlayer.isPlaying) {
                uiHandler.postDelayed(this, 250L)
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogAudioAttachmentPlayerBinding.inflate(layoutInflater)

        val filePath = requireArguments().getString(ARG_FILE_PATH).orEmpty()
        binding.playPauseButton.isEnabled = false
        binding.seekBar.isEnabled = false
        binding.timer.text = formatTime(0)
        applyMessagingGoplayProgressStyle()

        setupPlayer(filePath)
        setupUiEvents()

        return AlertDialog.Builder(requireContext())
            .setView(binding.root)
            .create()
    }

    override fun onDestroyView() {
        uiHandler.removeCallbacks(updateProgress)
        player?.release()
        player = null
        _binding = null
        super.onDestroyView()
    }

    private fun setupUiEvents() {
        binding.playPauseButton.setOnClickListener {
            val mediaPlayer = player ?: return@setOnClickListener
            if (mediaPlayer.isPlaying) {
                mediaPlayer.pause()
                binding.playPauseButton.setImageResource(R.drawable.ic_audio_play)
                uiHandler.removeCallbacks(updateProgress)
            } else {
                mediaPlayer.start()
                binding.playPauseButton.setImageResource(R.drawable.ic_audio_pause)
                uiHandler.post(updateProgress)
            }
        }

        binding.seekBar.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        binding.timer.text = formatProgress(progress, binding.seekBar.max)
                    }
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                    userSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                    val mediaPlayer = player ?: return
                    val p = seekBar?.progress ?: 0
                    mediaPlayer.seekTo(p)
                    binding.timer.text = formatProgress(p, binding.seekBar.max)
                    userSeeking = false
                }
            },
        )
    }

    private fun setupPlayer(filePath: String) {
        runCatching {
            val file = File(filePath)
            val mediaPlayer = MediaPlayer()
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.setOnPreparedListener {
                val duration = max(it.duration, 0)
                binding.seekBar.max = duration
                binding.seekBar.progress = 0
                binding.seekBar.isEnabled = true
                binding.timer.text = formatProgress(0, duration)
                binding.playPauseButton.isEnabled = true
                binding.playPauseButton.setImageResource(R.drawable.ic_audio_play)
            }
            mediaPlayer.setOnCompletionListener {
                binding.playPauseButton.setImageResource(R.drawable.ic_audio_play)
                binding.seekBar.progress = binding.seekBar.max
                binding.timer.text = formatProgress(binding.seekBar.max, binding.seekBar.max)
                uiHandler.removeCallbacks(updateProgress)
            }
            mediaPlayer.setOnErrorListener { _, _, _ ->
                dismissAllowingStateLoss()
                true
            }
            mediaPlayer.prepareAsync()
            player = mediaPlayer
        }.getOrElse {
            dismissAllowingStateLoss()
        }
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    private fun formatProgress(positionMs: Int, durationMs: Int): String {
        if (durationMs <= 0) return formatTime(positionMs)
        return "${formatTime(positionMs)} / ${formatTime(durationMs)}"
    }

    private fun applyMessagingGoplayProgressStyle() {
        val progress = requireContext().getDrawable(R.drawable.audio_progress_bar_progress) ?: return
        val clip = ClipDrawable(progress, Gravity.START, ClipDrawable.HORIZONTAL)
        binding.seekBar.progressDrawable = clip
        binding.seekBar.background = requireContext().getDrawable(R.drawable.audio_progress_bar_background_outgoing)
    }

    companion object {
        private const val ARG_FILE_PATH = "file_path"
        private const val ARG_FILE_NAME = "file_name"
        private const val TAG = "AudioAttachmentPlayer"

        fun show(
            fragment: androidx.fragment.app.Fragment,
            filePath: String,
            fileName: String,
        ) {
            AudioAttachmentPlayerDialogFragment().apply {
                arguments = bundleOf(
                    ARG_FILE_PATH to filePath,
                    ARG_FILE_NAME to fileName,
                )
            }.show(fragment.parentFragmentManager, TAG)
        }
    }
}
