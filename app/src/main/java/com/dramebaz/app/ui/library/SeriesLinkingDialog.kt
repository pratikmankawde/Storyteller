package com.dramebaz.app.ui.library

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.data.db.Book
import com.dramebaz.app.data.db.BookSeries
import com.dramebaz.app.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SUMMARY-002: Dialog for linking books to a series.
 */
object SeriesLinkingDialog {
    private const val TAG = "SeriesLinkingDialog"

    /**
     * Show dialog to link a book to a series.
     * @param context The context
     * @param book The book to link
     * @param onSeriesLinked Callback when series is linked (seriesId, seriesOrder)
     */
    fun show(
        context: Context,
        book: Book,
        onSeriesLinked: (seriesId: Long, seriesOrder: Int) -> Unit
    ) {
        val app = context.applicationContext as DramebazApplication
        val seriesDao = app.bookSeriesDao

        CoroutineScope(Dispatchers.Main).launch {
            // Load existing series
            val seriesList = withContext(Dispatchers.IO) {
                seriesDao.getAll().first()
            }

            showDialogWithSeries(context, book, seriesList, seriesDao, onSeriesLinked)
        }
    }

    private fun showDialogWithSeries(
        context: Context,
        book: Book,
        seriesList: List<BookSeries>,
        seriesDao: com.dramebaz.app.data.db.BookSeriesDao,
        onSeriesLinked: (seriesId: Long, seriesOrder: Int) -> Unit
    ) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }

        // Series selection spinner
        val seriesLabel = TextView(context).apply {
            text = "Select Series (or create new):"
        }
        layout.addView(seriesLabel)

        val options = mutableListOf("-- Create New Series --")
        options.addAll(seriesList.map { it.name })
        
        val spinner = Spinner(context)
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        layout.addView(spinner)

        // New series name input (shown when "Create New" is selected)
        val newSeriesLabel = TextView(context).apply {
            text = "New Series Name:"
        }
        layout.addView(newSeriesLabel)
        
        val newSeriesInput = EditText(context).apply {
            hint = "e.g., Harry Potter"
        }
        layout.addView(newSeriesInput)

        // Series order input
        val orderLabel = TextView(context).apply {
            text = "Book Number in Series:"
        }
        layout.addView(orderLabel)

        val orderInput = EditText(context).apply {
            hint = "e.g., 1, 2, 3..."
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(book.seriesOrder?.toString() ?: "1")
        }
        layout.addView(orderInput)

        AlertDialog.Builder(context)
            .setTitle("Link to Series")
            .setView(layout)
            .setPositiveButton("Link") { _, _ ->
                CoroutineScope(Dispatchers.Main).launch {
                    handleLinkAction(
                        context, spinner, seriesList, newSeriesInput, orderInput,
                        seriesDao, onSeriesLinked
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Remove from Series") { _, _ ->
                onSeriesLinked(0, 0) // 0 means remove from series
            }
            .show()
    }

    private suspend fun handleLinkAction(
        context: Context,
        spinner: Spinner,
        seriesList: List<BookSeries>,
        newSeriesInput: EditText,
        orderInput: EditText,
        seriesDao: com.dramebaz.app.data.db.BookSeriesDao,
        onSeriesLinked: (seriesId: Long, seriesOrder: Int) -> Unit
    ) {
        val selectedIndex = spinner.selectedItemPosition
        val order = orderInput.text.toString().toIntOrNull() ?: 1

        val seriesId = if (selectedIndex == 0) {
            // Create new series
            val newName = newSeriesInput.text.toString().trim()
            if (newName.isBlank()) {
                Toast.makeText(context, "Please enter a series name", Toast.LENGTH_SHORT).show()
                return
            }
            withContext(Dispatchers.IO) {
                val newSeries = BookSeries(name = newName)
                seriesDao.insert(newSeries)
            }
        } else {
            // Use existing series
            seriesList[selectedIndex - 1].id
        }

        AppLogger.d(TAG, "Linked book to series: id=$seriesId, order=$order")
        onSeriesLinked(seriesId, order)
    }
}

