// SPDX-FileCopyrightText: 2019-2024 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package com.dismal.btox.ui.chat

import android.media.MediaPlayer
import android.content.res.Resources
import android.graphics.drawable.ClipDrawable
import android.graphics.PorterDuff
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.squareup.picasso.Picasso
import java.io.File
import java.net.URLConnection
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import com.dismal.btox.R
import com.dismal.btox.settings.AppColorResolver
import com.dismal.btox.ui.AvatarImageView
import ltd.evilcorp.core.vo.Contact
import ltd.evilcorp.core.vo.FileTransfer
import ltd.evilcorp.core.vo.Message
import ltd.evilcorp.core.vo.MessageType
import ltd.evilcorp.core.vo.Sender
import ltd.evilcorp.core.vo.isComplete
import ltd.evilcorp.core.vo.isRejected
import ltd.evilcorp.core.vo.isStarted

private const val TAG = "ChatAdapter"
private const val IMAGE_TO_SCREEN_RATIO = 0.9

private fun resolveThemeColor(context: android.content.Context, attr: Int): Int {
    return AppColorResolver.resolve(context, attr, R.color.colorPrimary)
}

private fun FileTransfer.isImage() = try {
    URLConnection.guessContentTypeFromName(fileName).startsWith("image/")
} catch (e: Exception) {
    Log.e(TAG, e.toString())
    false
}

private fun inflateView(type: ChatItemType, inflater: LayoutInflater): View = inflater.inflate(
    when (type) {
        ChatItemType.SentMessage -> R.layout.chat_message_sent
        ChatItemType.ReceivedMessage -> R.layout.chat_message_received
        ChatItemType.SentAction -> R.layout.chat_action_sent
        ChatItemType.ReceivedAction -> R.layout.chat_action_received
        ChatItemType.SentFileTransfer, ChatItemType.ReceivedFileTransfer -> R.layout.chat_filetransfer
    },
    null,
    true,
)

private enum class ChatItemType {
    ReceivedMessage,
    SentMessage,
    ReceivedAction,
    SentAction,
    ReceivedFileTransfer,
    SentFileTransfer,
}

private class MessageViewHolder(row: View) {
    val message: TextView = row.findViewById(R.id.message)
    val timestamp: TextView = row.findViewById(R.id.timestamp)
    val incomingAvatar: AvatarImageView? = row.findViewById(R.id.incomingAvatar)
}

private class FileTransferViewHolder(row: View) {
    val container: RelativeLayout = row.findViewById(R.id.fileTransfer)
    val fileName: TextView = row.findViewById(R.id.fileName)
    val fileSize: TextView = row.findViewById(R.id.fileSize)
    val progress: ProgressBar = row.findViewById(R.id.progress)
    val state: TextView = row.findViewById(R.id.state)
    val timestamp: TextView = row.findViewById(R.id.timestamp)
    val acceptLayout: View = row.findViewById(R.id.acceptLayout)
    val accept: Button = row.findViewById(R.id.accept)
    val reject: Button = row.findViewById(R.id.reject)
    val cancelLayout: View = row.findViewById(R.id.cancelLayout)
    val cancel: Button = row.findViewById(R.id.cancel)
    val completedLayout: View = row.findViewById(R.id.completedLayout)
    val imagePreview: ImageView = row.findViewById(R.id.imagePreview)
    val audioPlayerLayout: View = row.findViewById(R.id.audioPlayerLayout)
    val audioPlayPauseButton: ImageButton = row.findViewById(R.id.audioPlayPauseButton)
    val audioTimer: TextView = row.findViewById(R.id.audioTimer)
    val audioSeekBar: SeekBar = row.findViewById(R.id.audioSeekBar)
}

class ChatAdapter(private val inflater: LayoutInflater, private val resources: Resources) : BaseAdapter() {
    var messages: List<Message> = listOf()
    var fileTransfers: List<FileTransfer> = listOf()
    var activeContact: Contact? = null
    var material3StyleEnabled: Boolean = false

    private var audioPlayer: MediaPlayer? = null
    private var activeAudioTransferId: Int? = null
    private var activeAudioDurationMs: Int = 0
    private var userSeekingAudio = false
    private val audioUiHandler = Handler(Looper.getMainLooper())
    private val updateAudioProgress = object : Runnable {
        override fun run() {
            val player = audioPlayer ?: return
            if (player.isPlaying && !userSeekingAudio) {
                notifyDataSetChanged()
                audioUiHandler.postDelayed(this, 250L)
            }
        }
    }

    private fun formatMessageTimestamp(timestamp: Long): CharSequence {
        val context = inflater.context
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        val hourFlag = if (android.text.format.DateFormat.is24HourFormat(context)) {
            @Suppress("deprecation")
            DateUtils.FORMAT_24HOUR
        } else {
            @Suppress("deprecation")
            DateUtils.FORMAT_12HOUR
        }

        if (diff < DateUtils.MINUTE_IN_MILLIS) {
            return resources.getString(R.string.posted_now)
        }
        if (diff < DateUtils.HOUR_IN_MILLIS) {
            val mins = (diff / DateUtils.MINUTE_IN_MILLIS).toInt()
            return resources.getQuantityString(R.plurals.num_minutes_ago, mins, mins)
        }

        val thenCal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val nowCal = Calendar.getInstance()
        val sameDay = thenCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
            thenCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)
        if (sameDay) {
            return DateUtils.formatDateTime(context, timestamp, DateUtils.FORMAT_SHOW_TIME or hourFlag)
        }

        if (diff < DateUtils.WEEK_IN_MILLIS) {
            return DateUtils.formatDateTime(
                context,
                timestamp,
                DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or
                    DateUtils.FORMAT_SHOW_TIME or hourFlag,
            )
        }

        if (diff < DateUtils.YEAR_IN_MILLIS) {
            return DateUtils.formatDateTime(
                context,
                timestamp,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
                    DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR or hourFlag,
            )
        }

        return DateUtils.formatDateTime(
            context,
            timestamp,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
                DateUtils.FORMAT_NUMERIC_DATE or DateUtils.FORMAT_SHOW_YEAR or hourFlag,
        )
    }

    override fun getCount(): Int = messages.size
    override fun getItem(position: Int): Any = messages[position]
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getViewTypeCount(): Int = ChatItemType.entries.size
    override fun getItemViewType(position: Int): Int = with(messages[position]) {
        when (type) {
            MessageType.Normal -> when (sender) {
                Sender.Sent -> ChatItemType.SentMessage.ordinal
                Sender.Received -> ChatItemType.ReceivedMessage.ordinal
            }
            MessageType.Action -> when (sender) {
                Sender.Sent -> ChatItemType.SentAction.ordinal
                Sender.Received -> ChatItemType.ReceivedAction.ordinal
            }
            MessageType.FileTransfer -> when (sender) {
                Sender.Sent -> ChatItemType.SentFileTransfer.ordinal
                Sender.Received -> ChatItemType.ReceivedFileTransfer.ordinal
            }
        }
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        when (val type = ChatItemType.entries[getItemViewType(position)]) {
            ChatItemType.ReceivedMessage, ChatItemType.SentMessage,
            ChatItemType.ReceivedAction, ChatItemType.SentAction,
            -> {
                val message = messages[position]
                val view: View
                val vh: MessageViewHolder

                if (convertView != null) {
                    view = convertView
                    vh = view.tag as MessageViewHolder
                } else {
                    view = inflateView(type, inflater)
                    vh = MessageViewHolder(view)
                    view.tag = vh
                }

                val unsent = message.timestamp == 0L
                vh.message.text = message.message
                if (type == ChatItemType.ReceivedMessage) {
                    activeContact?.let { vh.incomingAvatar?.setFrom(it) }
                }
                // Messaging-goplay keeps bubble geometry from red 9-patch assets and tints them at runtime.
                val bubble = vh.message.parent as? View
                when (type) {
                    ChatItemType.ReceivedMessage -> {
                        if (material3StyleEnabled) {
                            bubble?.setBackgroundResource(R.drawable.msg_bubble_incoming_m3)
                        } else {
                            bubble?.setBackgroundResource(R.drawable.msg_bubble_incoming)
                            bubble?.background?.mutate()?.setColorFilter(
                                resolveThemeColor(inflater.context, androidx.appcompat.R.attr.colorPrimary),
                                PorterDuff.Mode.SRC_IN,
                            )
                        }
                        vh.message.setTextColor(ContextCompat.getColor(inflater.context, android.R.color.white))
                        vh.message.setLinkTextColor(ContextCompat.getColor(inflater.context, android.R.color.white))
                        vh.timestamp.setTextColor(ContextCompat.getColor(inflater.context, R.color.timestamp_text_incoming))
                    }
                    ChatItemType.SentMessage -> {
                        if (material3StyleEnabled) {
                            bubble?.setBackgroundResource(R.drawable.msg_bubble_outgoing_m3)
                        } else {
                            bubble?.setBackgroundResource(R.drawable.msg_bubble_outgoing)
                            bubble?.background?.mutate()?.setColorFilter(
                                ContextCompat.getColor(inflater.context, R.color.message_bubble_outgoing_bg),
                                PorterDuff.Mode.SRC_IN,
                            )
                        }
                        vh.message.setTextColor(ContextCompat.getColor(inflater.context, android.R.color.black))
                        vh.message.setLinkTextColor(ContextCompat.getColor(inflater.context, android.R.color.black))
                        vh.timestamp.setTextColor(ContextCompat.getColor(inflater.context, R.color.timestamp_text_outgoing))
                    }
                    else -> Unit
                }
                vh.timestamp.text = if (!unsent) {
                    formatMessageTimestamp(message.timestamp)
                } else {
                    resources.getText(R.string.sending)
                }

                vh.timestamp.visibility = if (position == messages.lastIndex || unsent) {
                    View.VISIBLE
                } else {
                    val next = messages[position + 1]
                    if (next.timestamp != 0L &&
                        next.sender == message.sender &&
                        next.timestamp - message.timestamp < 60_000
                    ) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }
                }

                view
            }
            ChatItemType.ReceivedFileTransfer, ChatItemType.SentFileTransfer -> {
                val message = messages[position]
                val fileTransfer = fileTransfers.find { it.id == message.correlationId } ?: run {
                    Log.e(TAG, "Unable to find ft ${message.correlationId} for ${message.publicKey} required for view")
                    FileTransfer("", 0, 0, 0, "", message.sender == Sender.Sent)
                }

                val view: View
                val vh: FileTransferViewHolder

                if (convertView != null) {
                    view = convertView
                    vh = view.tag as FileTransferViewHolder
                } else {
                    view = inflateView(type, inflater)
                    vh = FileTransferViewHolder(view)
                    view.tag = vh
                }

                // TODO(robinlinden)
                // Updating the file transfer progress refreshes this so often that onClick-listeners never trigger
                // for some reason. Will revisit this once I've replaced the ListView with a RecyclerView.
                val touchListener = View.OnTouchListener { v, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        (parent as ListView).performItemClick(v, position, position.toLong())
                    }
                    false
                }
                vh.accept.setOnTouchListener(touchListener)
                vh.reject.setOnTouchListener(touchListener)
                vh.cancel.setOnTouchListener(touchListener)

                val playableAudio = isPlayableAudio(fileTransfer)

                if (!playableAudio && fileTransfer.isImage() && (fileTransfer.isComplete() || fileTransfer.outgoing)) {
                    vh.completedLayout.visibility = View.VISIBLE
                    val targetWidth = Resources.getSystem().displayMetrics.widthPixels * IMAGE_TO_SCREEN_RATIO
                    Picasso.get()
                        .load(fileTransfer.destination)
                        .resize(targetWidth.roundToInt(), 0)
                        .centerInside()
                        .into(vh.imagePreview)
                } else {
                    vh.completedLayout.visibility = View.GONE
                }
                vh.audioPlayerLayout.visibility = if (playableAudio) View.VISIBLE else View.GONE

                vh.state.visibility = View.GONE
                if (fileTransfer.isRejected() || fileTransfer.isComplete()) {
                    vh.acceptLayout.visibility = View.GONE
                    vh.cancelLayout.visibility = View.GONE
                    vh.progress.visibility = View.GONE
                    vh.state.visibility =
                        if ((fileTransfer.isImage() || playableAudio) && fileTransfer.isComplete()) View.GONE else View.VISIBLE
                } else if (!fileTransfer.isStarted()) {
                    if (fileTransfer.outgoing) {
                        vh.acceptLayout.visibility = View.GONE
                        vh.cancelLayout.visibility = View.VISIBLE
                        vh.progress.visibility = View.VISIBLE
                    } else {
                        vh.acceptLayout.visibility = View.VISIBLE
                        vh.cancelLayout.visibility = View.GONE
                        vh.progress.visibility = View.GONE
                    }
                } else {
                    vh.acceptLayout.visibility = View.GONE
                    vh.cancelLayout.visibility = View.VISIBLE
                    vh.progress.visibility = View.VISIBLE
                }

                vh.fileName.text = fileTransfer.fileName
                vh.fileSize.text = Formatter.formatFileSize(inflater.context, fileTransfer.fileSize)
                vh.progress.max = fileTransfer.fileSize.toInt()
                vh.progress.progress = fileTransfer.progress.toInt()
                // TODO(robinlinden): paused, but that requires a database update and a release is overdue.
                val stateId = if (fileTransfer.isRejected()) R.string.cancelled else R.string.completed
                vh.state.text = resources.getString(stateId).lowercase(Locale.getDefault())
                vh.timestamp.text = formatMessageTimestamp(message.timestamp)
                if (playableAudio) {
                    bindAudioControls(vh, fileTransfer)
                } else {
                    vh.audioSeekBar.setOnSeekBarChangeListener(null)
                    vh.audioPlayPauseButton.setOnClickListener(null)
                }

                vh.timestamp.visibility = if (position == messages.lastIndex) {
                    View.VISIBLE
                } else {
                    val next = messages[position + 1]
                    if (next.sender == message.sender && next.timestamp - message.timestamp < 60_000) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }
                }

                vh.container.gravity = if (fileTransfer.outgoing) {
                    Gravity.END
                } else {
                    Gravity.START
                }

                view
            }
        }

    fun onFileTransferClicked(ft: FileTransfer): Boolean {
        if (!isPlayableAudio(ft)) return false
        toggleAudio(ft)
        return true
    }

    fun releaseAudio() {
        stopAudioProgressUpdates()
        audioPlayer?.release()
        audioPlayer = null
        activeAudioTransferId = null
        activeAudioDurationMs = 0
        notifyDataSetChanged()
    }

    private fun bindAudioControls(vh: FileTransferViewHolder, ft: FileTransfer) {
        val isActive = activeAudioTransferId == ft.id
        val player = if (isActive) audioPlayer else null
        val position = player?.currentPosition ?: 0
        val duration = if (isActive) max(activeAudioDurationMs, 0) else 0

        vh.audioPlayPauseButton.setImageResource(
            if (player?.isPlaying == true) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
        )

        vh.audioTimer.text = formatAudioProgress(position, duration)
        vh.audioSeekBar.max = max(duration, 1)
        vh.audioSeekBar.progress = position.coerceAtMost(vh.audioSeekBar.max)
        vh.audioSeekBar.isEnabled = isActive && duration > 0

        val progressDrawable = ContextCompat.getDrawable(inflater.context, R.drawable.audio_progress_bar_progress)
        if (progressDrawable != null) {
            vh.audioSeekBar.progressDrawable =
                ClipDrawable(progressDrawable, Gravity.START, ClipDrawable.HORIZONTAL)
        }
        vh.audioSeekBar.background =
            ContextCompat.getDrawable(inflater.context, R.drawable.audio_progress_bar_background_outgoing)

        vh.audioPlayPauseButton.setOnClickListener { toggleAudio(ft) }
        vh.audioSeekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && isActive) {
                        vh.audioTimer.text = formatAudioProgress(progress, duration)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    if (isActive) userSeekingAudio = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (!isActive) return
                    val p = seekBar?.progress ?: 0
                    audioPlayer?.seekTo(p)
                    userSeekingAudio = false
                }
            },
        )
    }

    private fun toggleAudio(ft: FileTransfer) {
        if (activeAudioTransferId != ft.id) {
            startAudio(ft)
            return
        }

        val player = audioPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            stopAudioProgressUpdates()
        } else {
            player.start()
            startAudioProgressUpdates()
        }
        notifyDataSetChanged()
    }

    private fun startAudio(ft: FileTransfer) {
        val path = ft.destination.toUri().path ?: return
        val file = File(path)
        if (!file.exists()) return

        releaseAudio()

        val mp = MediaPlayer()
        activeAudioTransferId = ft.id
        activeAudioDurationMs = 0
        audioPlayer = mp

        runCatching {
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { prepared ->
                activeAudioDurationMs = max(prepared.duration, 0)
                prepared.start()
                startAudioProgressUpdates()
                notifyDataSetChanged()
            }
            mp.setOnCompletionListener { done ->
                done.seekTo(0)
                stopAudioProgressUpdates()
                notifyDataSetChanged()
            }
            mp.setOnErrorListener { _, _, _ ->
                releaseAudio()
                true
            }
            mp.prepareAsync()
            notifyDataSetChanged()
        }.onFailure {
            releaseAudio()
        }
    }

    private fun startAudioProgressUpdates() {
        audioUiHandler.removeCallbacks(updateAudioProgress)
        audioUiHandler.post(updateAudioProgress)
    }

    private fun stopAudioProgressUpdates() {
        audioUiHandler.removeCallbacks(updateAudioProgress)
        userSeekingAudio = false
    }

    private fun isAudioTransfer(ft: FileTransfer): Boolean =
        URLConnection.guessContentTypeFromName(ft.fileName)?.startsWith("audio/") == true

    private fun isPlayableAudio(ft: FileTransfer): Boolean {
        if (!ft.isComplete()) return false
        if (!isAudioTransfer(ft)) return false
        if (!ft.destination.startsWith("file://")) return false
        val path = ft.destination.toUri().path ?: return false
        return File(path).exists()
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
}
