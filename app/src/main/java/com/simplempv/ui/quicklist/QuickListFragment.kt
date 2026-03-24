package com.simplempv.ui.quicklist

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simplempv.adapter.QuickListAdapter
import com.simplempv.databinding.FragmentQuickListBinding
import com.simplempv.model.Video

class QuickListFragment : Fragment() {

    interface OnVideoSelectedListener {
        fun onVideoSelected(video: Video)
    }

    private var _binding: FragmentQuickListBinding? = null
    private val binding get() = _binding!!

    private lateinit var recyclerView: RecyclerView
    private lateinit var editTextSearch: EditText
    private lateinit var textViewEmpty: TextView

    private lateinit var adapter: QuickListAdapter
    private var listener: OnVideoSelectedListener? = null
    private var fullVideoList: List<Video> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQuickListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = binding.recyclerViewQuickList
        editTextSearch = binding.editTextSearch
        textViewEmpty = binding.textViewEmpty

        setupRecyclerView()
        setupSearch()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::editTextSearch.isInitialized) {
            outState.putString(KEY_SEARCH_TEXT, editTextSearch.text?.toString())
        }
        if (::recyclerView.isInitialized) {
            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
            layoutManager?.let {
                outState.putInt(KEY_SCROLL_POSITION, it.findFirstVisibleItemPosition())
            }
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        savedInstanceState?.let { state ->
            val searchText = state.getString(KEY_SEARCH_TEXT, "")
            val scrollPosition = state.getInt(KEY_SCROLL_POSITION, 0)
            
            if (searchText.isNotEmpty() && ::editTextSearch.isInitialized) {
                editTextSearch.setText(searchText)
            }
            
            view?.post {
                if (::recyclerView.isInitialized && ::adapter.isInitialized) {
                    if (scrollPosition < adapter.itemCount) {
                        recyclerView.scrollToPosition(scrollPosition)
                    }
                }
            }
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? OnVideoSelectedListener
            ?: parentFragment as? OnVideoSelectedListener
            ?: throw IllegalArgumentException(
                "Context or parent fragment must implement OnVideoSelectedListener"
            )
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    private fun setupRecyclerView() {
        adapter = QuickListAdapter { position ->
            val video = adapter.getCurrentList()[position]
            listener?.onVideoSelected(video)
        }

        recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@QuickListFragment.adapter
            setHasFixedSize(true)
        }
    }

    private fun setupSearch() {
        editTextSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterVideos(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun filterVideos(query: String) {
        val filtered = if (query.isBlank()) {
            fullVideoList
        } else {
            fullVideoList.filter { video ->
                video.displayName.contains(query, ignoreCase = true)
            }
        }

        adapter.submitList(filtered)
        updateEmptyState(filtered.isEmpty(), query)
    }

    private fun updateEmptyState(isEmpty: Boolean, searchQuery: String) {
        when {
            isEmpty && searchQuery.isNotEmpty() -> {
                textViewEmpty.visibility = View.VISIBLE
                textViewEmpty.text = "No results for \"$searchQuery\""
                recyclerView.visibility = View.GONE
            }
            isEmpty -> {
                textViewEmpty.visibility = View.VISIBLE
                textViewEmpty.text = "No videos found"
                recyclerView.visibility = View.GONE
            }
            else -> {
                textViewEmpty.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
            }
        }
    }

    fun setVideos(videos: List<Video>) {
        fullVideoList = videos
        if (::adapter.isInitialized) {
            adapter.submitFullList(videos)
        }
        if (_binding != null) {
            updateEmptyState(videos.isEmpty(), "")
        }
    }

    fun setCurrentVideo(uri: String?) {
        if (::adapter.isInitialized) {
            adapter.setCurrentVideo(uri)
        }
    }

    fun clearSearch() {
        if (::editTextSearch.isInitialized) {
            editTextSearch.text?.clear()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "QuickListFragment"
        private const val KEY_SEARCH_TEXT = "key_search_text"
        private const val KEY_SCROLL_POSITION = "key_scroll_position"

        fun newInstance(): QuickListFragment {
            return QuickListFragment()
        }
    }
}
