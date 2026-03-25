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
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock

class VideoAdapter(
    private val onVideoClick: (Video) -> Unit
) : ListAdapter<Video, VideoAdapter.VideoViewHolder>(VideoDiffCallback()) {

    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
        holder.bind(video, position)
        holder.setOnClickListener {
            onVideoClick(video)
        }
    }

    class VideoViewHolder(
        private val binding: ItemVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val context: Context = binding.root.context
        private var thumbnailJob: Job? = null
        private val bitmapLock = ReentrantLock()
        private var currentBitmap: Bitmap? = null

        fun setOnClickListener(listener: (() -> Unit)?) {
            binding.root.setOnClickListener { listener?.invoke() }
        }

        private fun setBitmap(newBitmap: Bitmap?) {
            bitmapLock.lock()
            try {
                currentBitmap?.recycle()
                currentBitmap = newBitmap
            } finally {
                bitmapLock.unlock()
            }
        }

        private fun getBitmap(): Bitmap? {
            bitmapLock.lock()
            return try {
                currentBitmap
            } finally {
                bitmapLock.unlock()
            }
        }

        fun cancelThumbnailLoad() {
            thumbnailJob?.cancel()
            thumbnailJob = null
            bitmapLock.lock()
            try {
                currentBitmap?.recycle()
                currentBitmap = null
            } finally {
                bitmapLock.unlock()
            }
        }

        fun bind(video: Video, position: Int) {
            binding.textViewName.text = video.displayName
            binding.textViewDuration.text = TimeUtils.formatDuration(video.duration)
            binding.textViewSize.text = formatSize(video.size)

            loadThumbnail(video.id, position)
        }

        private fun loadThumbnail(videoId: Long, position: Int) {
            binding.imageViewThumbnail.setImageResource(android.R.color.darker_gray)

            thumbnailJob?.cancel()

            thumbnailJob = launchThumbnailLoad(videoId, position)
        }

        private fun launchThumbnailLoad(videoId: Long, position: Int): Job {
            val scope = VideoViewHolder.getAdapterScope()
            return scope.launch {
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
                        setBitmap(thumbnail)
                        binding.imageViewThumbnail.setImageBitmap(thumbnail)
                    }
                } catch (e: Exception) {
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

        companion object {
            @Volatile
            private var adapterScope: CoroutineScope? = null

            fun setAdapterScope(scope: CoroutineScope) {
                adapterScope = scope
            }

            fun getAdapterScope(): CoroutineScope {
                return adapterScope ?: throw IllegalStateException("AdapterScope not initialized")
            }
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

    init {
        VideoViewHolder.setAdapterScope(adapterScope)
    }
}
