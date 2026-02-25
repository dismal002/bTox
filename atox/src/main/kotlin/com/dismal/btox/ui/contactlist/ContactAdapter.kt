// SPDX-FileCopyrightText: 2019-2024 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2021-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.dismal.btox.ui.contactlist

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import java.text.DateFormat
import java.util.concurrent.TimeUnit
import com.dismal.btox.R
import com.dismal.btox.databinding.ContactListViewItemBinding
import com.dismal.btox.databinding.FriendRequestItemBinding
import com.dismal.btox.settings.AppColorResolver
import com.dismal.btox.ui.AvatarImageView
import ltd.evilcorp.core.vo.Contact
import ltd.evilcorp.core.vo.FriendRequest

enum class ContactListItemType {
    FriendRequest,
    Contact,
}

private fun resolveThemeColor(context: Context, attr: Int, fallbackRes: Int): Int {
    return AppColorResolver.resolve(context, attr, fallbackRes)
}

class ContactAdapter(private val inflater: LayoutInflater, private val context: Context) : BaseAdapter() {
    var friendRequests: List<FriendRequest> = listOf()
    var contacts: List<Contact> = listOf()
    var selectedPublicKeys: Set<String> = emptySet()

    override fun getCount(): Int = friendRequests.size + contacts.size
    override fun getItem(position: Int): Any = when {
        position < friendRequests.size -> friendRequests[position]
        else -> contacts[position - friendRequests.size]
    }

    override fun getItemId(position: Int): Long = position.toLong()
    override fun getViewTypeCount(): Int = 2
    override fun getItemViewType(position: Int): Int = when {
        position < friendRequests.size -> ContactListItemType.FriendRequest.ordinal
        else -> ContactListItemType.Contact.ordinal
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        when (ContactListItemType.entries[getItemViewType(position)]) {
            ContactListItemType.FriendRequest -> {
                val view: View
                val vh: FriendRequestViewHolder

                if (convertView == null) {
                    view = inflater.inflate(R.layout.friend_request_item, parent, false)
                    vh = FriendRequestViewHolder(FriendRequestItemBinding.bind(view))
                    view.tag = vh
                } else {
                    view = convertView
                    vh = view.tag as FriendRequestViewHolder
                }

                friendRequests[position].run {
                    vh.publicKey.text = publicKey
                    vh.message.text = message
                }

                view
            }
            ContactListItemType.Contact -> {
                val view: View
                val vh: ContactViewHolder

                if (convertView == null) {
                    view = inflater.inflate(R.layout.contact_list_view_item, parent, false)
                    vh = ContactViewHolder(ContactListViewItemBinding.bind(view))
                    view.tag = vh
                } else {
                    view = convertView
                    vh = view.tag as ContactViewHolder
                }

                contacts[position - friendRequests.size].run {
                    name = name.ifEmpty { context.getString(R.string.contact_default_name) }
                    val selected = selectedPublicKeys.contains(publicKey)

                    vh.name.text = name
                    vh.lastMessage.text = if (lastMessage != 0L) {
                        val ageMs = System.currentTimeMillis() - lastMessage
                        if (ageMs in 0 until TimeUnit.DAYS.toMillis(1)) {
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(lastMessage)
                        } else {
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(lastMessage)
                        }
                    } else {
                        context.getText(R.string.never)
                    }
                    when {
                        typing -> {
                            vh.statusMessage.text = context.getString(R.string.contact_typing)
                            vh.statusMessage.setTextColor(vh.lastMessage.currentTextColor)
                        }
                        draftMessage.isNotEmpty() -> {
                            vh.statusMessage.text = context.getString(R.string.draft_message, draftMessage)
                            vh.statusMessage.setTextColor(
                                resolveThemeColor(context, androidx.appcompat.R.attr.colorAccent, R.color.colorAccent),
                            )
                        }
                        else -> {
                            vh.statusMessage.text = statusMessage
                            vh.statusMessage.setTextColor(vh.lastMessage.currentTextColor)
                        }
                    }
                    vh.avatarImageView.setFrom(this)
                    vh.avatarImageView.visibility = if (selected) View.INVISIBLE else View.VISIBLE
                    vh.selectedAvatar.visibility = if (selected) View.VISIBLE else View.GONE
                    vh.unreadIndicator.visibility = if (hasUnreadMessages) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                    vh.starredIndicator.visibility = if (starred) View.VISIBLE else View.GONE
                }

                view
            }
        }

    private class FriendRequestViewHolder(row: FriendRequestItemBinding) {
        val publicKey: TextView = row.publicKey
        val message: TextView = row.message
    }

    private class ContactViewHolder(row: ContactListViewItemBinding) {
        val name: TextView = row.name
        val statusMessage: TextView = row.statusMessage
        val lastMessage: TextView = row.lastMessage
        val avatarImageView: AvatarImageView = row.avatarImageView
        val selectedAvatar: ImageView = row.selectedAvatar
        val unreadIndicator: ImageView = row.unreadIndicator
        val starredIndicator: ImageView = row.starredIndicator
    }
}
