package com.dramebaz.app.ui.test.library

import com.dramebaz.app.data.db.Book
import com.dramebaz.app.ui.library.LibrarySection

/**
 * Items for the sectioned library list used in design variations.
 */
sealed class LibraryItem {
    data class Header(
        val section: LibrarySection,
        val count: Int,
        val isExpanded: Boolean = true
    ) : LibraryItem()
    
    data class BookItem(
        val book: Book,
        val section: LibrarySection
    ) : LibraryItem()
}

