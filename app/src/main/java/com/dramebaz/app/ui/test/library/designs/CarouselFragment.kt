package com.dramebaz.app.ui.test.library.designs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.CompositePageTransformer
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import com.dramebaz.app.R
import com.dramebaz.app.data.db.Book
import com.dramebaz.app.ui.library.BookCoverLoader
import com.dramebaz.app.ui.library.LibrarySection
import com.dramebaz.app.ui.test.library.BaseLibraryDesignFragment
import com.google.android.material.button.MaterialButton
import kotlin.math.abs

/**
 * Design 4: Carousel/Pager Style - Large swipeable cards.
 * ViewPager2 with page transformer for 3D carousel effect.
 */
class CarouselFragment : BaseLibraryDesignFragment() {

    private var sectionsContainer: LinearLayout? = null
    private var emptyState: View? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_library_horizontal, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        sectionsContainer = view.findViewById(R.id.sections_container)
        emptyState = view.findViewById(R.id.empty_state)
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onBooksUpdated(books: List<Book>) {
        sectionsContainer?.removeAllViews()
        
        if (books.isEmpty()) {
            emptyState?.visibility = View.VISIBLE
            return
        }
        emptyState?.visibility = View.GONE

        val sectionData = mapOf(
            LibrarySection.FAVOURITES to books.filter { it.isFavorite },
            LibrarySection.RECENTLY_ADDED to books.sortedByDescending { it.createdAt }.take(10)
        )

        sectionData.forEach { (section, sectionBooks) ->
            if (sectionBooks.isNotEmpty()) {
                addCarouselSection(section, sectionBooks)
            }
        }
    }

    private fun addCarouselSection(section: LibrarySection, books: List<Book>) {
        val sectionView = LayoutInflater.from(context).inflate(R.layout.item_carousel_section, sectionsContainer, false)
        
        sectionView.findViewById<ImageView>(R.id.section_icon).setImageResource(section.iconResId)
        sectionView.findViewById<TextView>(R.id.section_title).setText(section.titleResId)
        sectionView.findViewById<TextView>(R.id.section_count).text = books.size.toString()

        val viewPager = sectionView.findViewById<ViewPager2>(R.id.carousel_pager)
        viewPager.adapter = CarouselAdapter(books)
        viewPager.offscreenPageLimit = 3
        viewPager.clipToPadding = false
        viewPager.clipChildren = false
        viewPager.getChildAt(0)?.overScrollMode = RecyclerView.OVER_SCROLL_NEVER

        // Carousel transformation
        val transformer = CompositePageTransformer()
        transformer.addTransformer(MarginPageTransformer(40))
        transformer.addTransformer { page, position ->
            val scale = 1 - abs(position) * 0.15f
            page.scaleY = scale
            page.alpha = 0.5f + (1 - abs(position)) * 0.5f
        }
        viewPager.setPageTransformer(transformer)

        sectionsContainer?.addView(sectionView)
    }

    private inner class CarouselAdapter(private val books: List<Book>) : 
        RecyclerView.Adapter<CarouselAdapter.CarouselViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CarouselViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book_carousel, parent, false)
            return CarouselViewHolder(view)
        }

        override fun onBindViewHolder(holder: CarouselViewHolder, position: Int) {
            holder.bind(books[position])
        }

        override fun getItemCount() = books.size

        inner class CarouselViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cover: ImageView = itemView.findViewById(R.id.book_cover)
            private val title: TextView = itemView.findViewById(R.id.book_title)
            private val format: TextView = itemView.findViewById(R.id.book_format)
            private val btnFavorite: MaterialButton = itemView.findViewById(R.id.btn_favorite)

            fun bind(book: Book) {
                title.text = book.title
                format.text = "${book.format.uppercase()} • ${book.detectedGenre ?: "Unknown"}"
                BookCoverLoader.loadCoverInto(cover, book)
                
                btnFavorite.setIconResource(if (book.isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
                btnFavorite.text = if (book.isFavorite) "Favorited" else "Favorite"
                
                itemView.setOnClickListener { onBookClick(book, cover) }
                itemView.setOnLongClickListener { onBookLongClick(book) }
                btnFavorite.setOnClickListener { onFavoriteClick(book) }
            }
        }
    }
}

