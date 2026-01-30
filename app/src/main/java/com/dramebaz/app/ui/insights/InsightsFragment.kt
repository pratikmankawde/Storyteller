package com.dramebaz.app.ui.insights

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** T9.3: Insights tab – emotional graph placeholder, themes list, vocabulary builder. */
class InsightsFragment : Fragment() {
    private val app get() = requireContext().applicationContext as DramebazApplication
    private val vm: InsightsViewModel by viewModels { InsightsViewModel.Factory(app.bookRepository) }
    private var bookId: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = arguments?.getLong("bookId", 0L) ?: 0L
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_insights, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val themes = view.findViewById<TextView>(R.id.themes)
        val vocabulary = view.findViewById<TextView>(R.id.vocabulary)
        viewLifecycleOwner.lifecycleScope.launch {
            val data = vm.insightsForBook(bookId)
            themes.text = data.themes.ifEmpty { "No themes yet (run chapter analysis)." }
            vocabulary.text = data.vocabulary.ifEmpty { "No vocabulary yet (run chapter analysis)." }
        }
    }
}
