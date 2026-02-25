package com.dismal.btox.ui.chat

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.dismal.btox.R
import com.squareup.picasso.Picasso

class MediaPhotoGridAdapter(
    private val onClicked: (Uri) -> Unit,
) : RecyclerView.Adapter<MediaPhotoGridAdapter.VH>() {

    private var items: List<Uri> = emptyList()

    fun submitItems(newItems: List<Uri>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_media_picker_photo, parent, false)
        return VH(view, onClicked)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class VH(
        itemView: View,
        private val onClicked: (Uri) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        private val thumb: ImageView = itemView.findViewById(R.id.photoThumb)
        private var itemUri: Uri? = null

        init {
            itemView.setOnClickListener {
                itemUri?.let(onClicked)
            }
        }

        fun bind(uri: Uri) {
            itemUri = uri
            Picasso.get()
                .load(uri)
                .placeholder(R.drawable.attachment_image_placeholder_background)
                .fit()
                .centerCrop()
                .into(thumb)
        }
    }
}
