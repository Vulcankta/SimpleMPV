package com.simplempv.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.simplempv.databinding.ItemQuickVideoBinding
import com.simplempv.model.Video

class QuickListAdapter(
    private val onVideoClick: (Int) -> Unit
) : ListAdapter<Video, QuickListAdapter.ViewHolder>(DiffCallback()) {

    private var currentVideoUri: String? = null
    private var fullList: List<Video> = emptyList()

    class ViewHolder(
        private val binding: ItemQuickVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(video: Video, isCurrent: Boolean) {
            binding.textViewName.text = video.displayName
            binding.textViewDuration.text = formatDuration(video.duration)

            binding.root.alpha = if (isCurrent) 1.0f else 0.7f
            binding.textViewName.setTextColor(
                if (isCurrent) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt()
            )
        }

        private fun formatDuration(durationMs: Long): String {
            val seconds = (durationMs / 1000) % 60
            val minutes = (durationMs / 1000) / 60
            return String.format("%02d:%02d", minutes, seconds)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Video>() {
        override fun areItemsTheSame(oldItem: Video, newItem: Video) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Video, newItem: Video) = oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemQuickVideoBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val video = getItem(position)
        val isCurrent = video.uri.toString() == currentVideoUri
        holder.bind(video, isCurrent)
        holder.itemView.setOnClickListener { onVideoClick(holder.bindingAdapterPosition) }
    }

    fun setCurrentVideo(uri: String?) {
        currentVideoUri = uri
        notifyDataSetChanged()
    }

    fun submitFullList(list: List<Video>) {
        fullList = list
        submitList(list)
    }
}
