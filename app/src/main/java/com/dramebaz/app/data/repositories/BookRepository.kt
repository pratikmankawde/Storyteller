package com.dramebaz.app.data.repositories

import com.dramebaz.app.data.db.Book
import com.dramebaz.app.data.db.BookDao
import com.dramebaz.app.data.db.Chapter
import com.dramebaz.app.data.db.ChapterDao
import kotlinx.coroutines.flow.Flow

class BookRepository(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao
) {
    fun allBooks(): Flow<List<Book>> = bookDao.getAll()
    suspend fun getBook(id: Long) = bookDao.getById(id)
    suspend fun findBookByTitle(title: String) = bookDao.getByTitle(title)
    suspend fun insertBook(book: Book): Long = bookDao.insert(book)
    fun chapters(bookId: Long): Flow<List<Chapter>> = chapterDao.getByBookId(bookId)
    suspend fun getChapter(id: Long) = chapterDao.getById(id)
    suspend fun insertChapters(chapters: List<Chapter>) = chapterDao.insertAll(chapters)
    suspend fun updateChapter(chapter: Chapter) = chapterDao.update(chapter)
    
    /**
     * Delete a book and all its chapters by book ID.
     */
    suspend fun deleteBookWithChapters(bookId: Long) {
        chapterDao.deleteByBookId(bookId)
        bookDao.deleteById(bookId)
    }
    
    /**
     * Delete a book and all its chapters by title.
     */
    suspend fun deleteBookByTitle(title: String) {
        val book = bookDao.getByTitle(title)
        if (book != null) {
            chapterDao.deleteByBookId(book.id)
            bookDao.deleteById(book.id)
        }
    }
}
