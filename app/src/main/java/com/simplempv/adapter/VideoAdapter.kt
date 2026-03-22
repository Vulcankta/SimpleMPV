package com.simplempv.adapter

import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.simplempv.databinding.ItemVideoBinding
import com.simplempv.model.Video
import com.simplempv.utils.TimeUtils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class VideoAdapter(
    private val onVideoClick: (Video) -> Unit
) : ListAdapter<Video, VideoAdapter.VideoViewHolder>(VideoDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemVideoBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VideoViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val video = getItem(position)
        holder.bind(video)
        holder.setOnClickListener {
            onVideoClick(video)
        }
    }
    
    class VideoViewHolder(
        private val binding: ItemVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        private val context: Context = binding.root.context
        private var viewHolderScope: CoroutineScope? = null
        private var thumbnailJob: Job? = null
        private var currentBitmap: Bitmap? = null
        
        private fun getOrCreateScope(): CoroutineScope {
            return viewHolderScope ?: CoroutineScope(Dispatchers.Main + SupervisorJob()).also {
                viewHolderScope = it
            }
        }
        
        fun setOnClickListener(listener: (() -> Unit)?) {
            binding.root.setOnClickListener { listener?.invoke() }
        }
        
        fun cancelThumbnailLoad() {
            thumbnailJob?.cancel()
            thumbnailJob = null
            viewHolderScope?.cancel()
            viewHolderScope = null
            // Recycle bitmap when cancelled
            currentBitmap?.recycle()
            currentBitmap = null
        }
        
        fun bind(video: Video) {
            binding.textViewName.text = video.displayName
            binding.textViewDuration.text = TimeUtils.formatDuration(video.duration)
            binding.textViewSize.text = formatSize(video.size)
            
            loadThumbnail(video.id)
        }
        
        private fun loadThumbnail(videoId: Long) {
            binding.imageViewThumbnail.setImageResource(android.R.color.darker_gray)
            
            // Cancel previous thumbnail loading job
            thumbnailJob?.cancel()
            
            thumbnailJob = getOrCreateScope().launch {
                try {
                    val thumbnail = withContext(Dispatchers.IO) {
                        MediaStore.Video.Thumbnails.getThumbnail(
                            context.contentResolver,
                            videoId,
                            MediaStore.Video.Thumbnails.MINI_KIND,
                            null
                        )
                    }
                    
                    if (thumbnail != null) {
                        // Recycle old bitmap before setting new one
                        currentBitmap?.recycle()
                        currentBitmap = thumbnail
                        binding.imageViewThumbnail.setImageBitmap(thumbnail)
                    }
                } catch (e: Exception) {
                    // Ignore cancellation exception
                    if (e !is kotlinx.coroutines.CancellationException) {
                        Toast.makeText(context, "Thumbnail error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        

        
        private fun formatSize(sizeBytes: Long): String {
            val mb = sizeBytes / (1024.0 * 1024.0)
            return String.format("%.1f MB", mb)
        }
    }
    
    class VideoDiffCallback : DiffUtil.ItemCallback<Video>() {
        override fun areItemsTheSame(oldItem: Video, newItem: Video): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: Video, newItem: Video): Boolean {
            return oldItem == newItem
        }
    }
    
    override fun onViewRecycled(holder: VideoViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelThumbnailLoad()
    }
}
