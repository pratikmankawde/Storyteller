package com.dramebaz.app.ui.test.library.designs

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.dramebaz.app.R
import com.dramebaz.app.data.db.Book
import com.dramebaz.app.ui.library.BookCoverLoader
import com.dramebaz.app.ui.test.library.BaseLibraryDesignFragment
import com.google.android.material.button.MaterialButton

/**
 * Design 10: Card Stack/Deck Style - Overlapping cards with peek preview.
 * Tinder-like stacked cards with swipe navigation.
 */
class CardStackFragment : BaseLibraryDesignFragment() {

    private var cardContainer: FrameLayout? = null
    private var emptyState: View? = null
    private var navControls: View? = null
    private var btnPrevious: MaterialButton? = null
    private var btnNext: MaterialButton? = null
    private var positionText: TextView? = null

    private var books: List<Book> = emptyList()
    private var currentIndex = 0
    private val cardViews = mutableListOf<View>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_library_cardstack, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        cardContainer = view.findViewById(R.id.card_stack_container)
        emptyState = view.findViewById(R.id.empty_state)
        navControls = view.findViewById(R.id.navigation_controls)
        btnPrevious = view.findViewById(R.id.btn_previous)
        btnNext = view.findViewById(R.id.btn_next)
        positionText = view.findViewById(R.id.card_position)

        btnPrevious?.setOnClickListener { navigateToPrevious() }
        btnNext?.setOnClickListener { navigateToNext() }

        super.onViewCreated(view, savedInstanceState)
    }

    override fun onBooksUpdated(books: List<Book>) {
        this.books = books
        currentIndex = 0
        
        if (books.isEmpty()) {
            emptyState?.visibility = View.VISIBLE
            navControls?.visibility = View.GONE
            cardContainer?.removeAllViews()
            return
        }
        
        emptyState?.visibility = View.GONE
        navControls?.visibility = View.VISIBLE
        buildCardStack()
        updatePosition()
    }

    private fun buildCardStack() {
        cardContainer?.removeAllViews()
        cardViews.clear()

        // Show up to 3 cards in stack (current + 2 peek cards)
        val cardsToShow = minOf(3, books.size)
        
        for (i in (cardsToShow - 1) downTo 0) {
            val bookIndex = (currentIndex + i) % books.size
            val card = createBookCard(books[bookIndex], i)
            cardContainer?.addView(card)
            cardViews.add(0, card)
        }
    }

    private fun createBookCard(book: Book, stackPosition: Int): View {
        val card = LayoutInflater.from(context).inflate(R.layout.item_book_cardstack, cardContainer, false)
        
        val cover: ImageView = card.findViewById(R.id.book_cover)
        val title: TextView = card.findViewById(R.id.book_title)
        val format: TextView = card.findViewById(R.id.book_format)
        val genre: TextView = card.findViewById(R.id.book_genre)
        val favoriteIndicator: ImageView = card.findViewById(R.id.favorite_indicator)
        val btnRead: MaterialButton = card.findViewById(R.id.btn_read)

        title.text = book.title
        format.text = book.format.uppercase()
        genre.text = book.detectedGenre ?: "Unknown genre"
        // COVER-SLIDESHOW: Enable slideshow for books being analyzed without genre
        BookCoverLoader.loadCoverInto(cover, book, enableSlideshow = true)
        favoriteIndicator.visibility = if (book.isFavorite) View.VISIBLE else View.GONE

        // Stack effect - scale and translate back cards
        val scale = 1f - (stackPosition * 0.05f)
        val translationY = stackPosition * 20f * resources.displayMetrics.density
        card.scaleX = scale
        card.scaleY = scale
        card.translationY = -translationY
        card.alpha = 1f - (stackPosition * 0.15f)

        card.setOnClickListener { onBookClick(book, cover) }
        card.setOnLongClickListener { onBookLongClick(book) }
        btnRead.setOnClickListener { onBookClick(book, cover) }

        return card
    }

    private fun navigateToNext() {
        if (books.size <= 1) return
        
        // Animate current card out
        val topCard = cardViews.firstOrNull() ?: return
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(topCard, "translationX", 0f, topCard.width.toFloat()),
                ObjectAnimator.ofFloat(topCard, "alpha", 1f, 0f)
            )
            duration = 200
            start()
        }

        currentIndex = (currentIndex + 1) % books.size
        topCard.postDelayed({
            buildCardStack()
            updatePosition()
        }, 200)
    }

    private fun navigateToPrevious() {
        if (books.size <= 1) return
        currentIndex = if (currentIndex > 0) currentIndex - 1 else books.size - 1
        buildCardStack()
        updatePosition()
    }

    private fun updatePosition() {
        positionText?.text = "${currentIndex + 1} / ${books.size}"
        btnPrevious?.isEnabled = books.size > 1
        btnNext?.isEnabled = books.size > 1
    }
}

