package com.simplempv

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.simplempv.databinding.ActivityMainBinding
import com.simplempv.model.Video
import com.simplempv.repository.VideoRepository
import com.simplempv.repository.SortOrder
import com.simplempv.adapter.VideoAdapter

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var videoRepository: VideoRepository
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var prefs: SharedPreferences
    private lateinit var themeManager: ThemeManager
    
    private var sortOrder: SortOrder = SortOrder.DATE_DESC
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            loadVideos()
        } else {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        prefs = getSharedPreferences("simplempv_prefs", MODE_PRIVATE)
        sortOrder = SortOrder.valueOf(prefs.getString("video_sort_order", "DATE_DESC") ?: "DATE_DESC")
        
        themeManager = ThemeManager(this)
        
        setSupportActionBar(binding.toolbar)
        
        videoRepository = VideoRepository(this)
        setupRecyclerView()
        checkPermissionAndLoad()
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        updateThemeIcon(menu.findItem(R.id.action_theme))
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        updateThemeIcon(menu.findItem(R.id.action_theme))
        return super.onPrepareOptionsMenu(menu)
    }

    private fun updateThemeIcon(menuItem: MenuItem?) {
        menuItem?.setIcon(
            if (themeManager.isDarkMode()) R.drawable.ic_dark_mode else R.drawable.ic_light_mode
        )
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sort -> {
                showSortMenu()
                true
            }
            R.id.action_theme -> {
                toggleTheme()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun toggleTheme() {
        val currentDark = themeManager.isDarkMode()
        themeManager.setDarkMode(!currentDark)
        
        val message = if (!currentDark) "深色主题" else "浅色主题"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    private fun showSortMenu() {
        val anchor = findViewById<View>(R.id.action_sort) ?: return
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_sort, popup.menu)
        
        popup.setOnMenuItemClickListener { menuItem ->
            val newSortOrder = when (menuItem.itemId) {
                R.id.sort_date_desc -> SortOrder.DATE_DESC
                R.id.sort_date_asc -> SortOrder.DATE_ASC
                R.id.sort_name_asc -> SortOrder.NAME_ASC
                R.id.sort_name_desc -> SortOrder.NAME_DESC
                R.id.sort_size_desc -> SortOrder.SIZE_DESC
                R.id.sort_size_asc -> SortOrder.SIZE_ASC
                R.id.sort_duration_desc -> SortOrder.DURATION_DESC
                R.id.sort_duration_asc -> SortOrder.DURATION_ASC
                else -> sortOrder
            }
            
            if (newSortOrder != sortOrder) {
                sortOrder = newSortOrder
                prefs.edit().putString("video_sort_order", sortOrder.name).apply()
                loadVideos()
            }
            true
        }
        
        popup.show()
    }
    
    private fun setupRecyclerView() {
        videoAdapter = VideoAdapter { video ->
            openPlayer(video)
        }
        
        binding.recyclerViewVideos.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = videoAdapter
        }
    }
    
    private fun checkPermissionAndLoad() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        
        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                loadVideos()
            }
            shouldShowRequestPermissionRationale(permission) -> {
                Toast.makeText(this, "Permission needed to access videos", Toast.LENGTH_LONG).show()
                requestPermissionLauncher.launch(permission)
            }
            else -> {
                requestPermissionLauncher.launch(permission)
            }
        }
    }
    
    private fun loadVideos() {
        try {
            val videos = videoRepository.getVideos(sortOrder)
            Toast.makeText(this, "Found ${videos.size} videos", Toast.LENGTH_SHORT).show()
            
            if (videos.isEmpty()) {
                Toast.makeText(this, "No videos found - check permissions", Toast.LENGTH_LONG).show()
                binding.textViewEmpty.visibility = View.VISIBLE
                binding.recyclerViewVideos.visibility = View.GONE
            } else {
                binding.textViewEmpty.visibility = View.GONE
                binding.recyclerViewVideos.visibility = View.VISIBLE
                videoAdapter.submitList(videos)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Load error: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }
    
    private fun openPlayer(video: Video) {
        val filePath = videoRepository.getFilePathFromUri(video.uri) ?: video.uri.toString()
        
        val videoPosition = videoAdapter.currentList.indexOf(video)
        if (videoPosition < 0) {
            Toast.makeText(this, "Video not found in list", Toast.LENGTH_SHORT).show()
            return
        }
        
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_PATH, filePath)
            putExtra(PlayerActivity.EXTRA_VIDEO_URI, video.uri.toString())
            putExtra(PlayerActivity.EXTRA_VIDEO_POSITION, videoPosition)
        }
        startActivity(intent)
    }
}
