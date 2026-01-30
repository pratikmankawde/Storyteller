package com.dramebaz.app.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileInputStream

/**
 * Extracts plain text from PDF pages for display and for feeding to Qwen LLM.
 * Uses PDFBox (same as Dramebaz) – do not feed raw PDF/XML to the LLM.
 */
class PdfExtractor(private val context: Context) {

    private var initialized = false

    init {
        try {
            PDFBoxResourceLoader.init(context)
            initialized = true
        } catch (e: Exception) {
            e.printStackTrace()
            initialized = false
        }
    }

    private fun ensureInitialized() {
        if (!initialized) {
            try {
                PDFBoxResourceLoader.init(context)
                initialized = true
            } catch (e: Exception) {
                throw RuntimeException("Failed to initialize PDFBox: ${e.message}", e)
            }
        }
    }

    /**
     * Extract text from each page of the PDF. Returns list of page texts (plain text only).
     */
    fun extractText(pdfFile: File): List<String> {
        ensureInitialized()
        val pages = mutableListOf<String>()
        if (!pdfFile.exists()) {
            android.util.Log.e("PdfExtractor", "PDF file does not exist: ${pdfFile.absolutePath}")
            throw IllegalArgumentException("PDF file does not exist: ${pdfFile.absolutePath}")
        }
        if (pdfFile.length() == 0L) {
            android.util.Log.e("PdfExtractor", "PDF file is empty: ${pdfFile.absolutePath}")
            throw IllegalArgumentException("PDF file is empty: ${pdfFile.absolutePath}")
        }
        android.util.Log.i("PdfExtractor", "Starting PDF extraction from: ${pdfFile.absolutePath}, size=${pdfFile.length()}")
        var document: PDDocument? = null
        try {
            document = PDDocument.load(FileInputStream(pdfFile))
            val numPages = document.numberOfPages
            android.util.Log.i("PdfExtractor", "PDF loaded successfully: $numPages pages")
            if (numPages == 0) {
                throw IllegalArgumentException("PDF has no pages")
            }
            val stripper = PDFTextStripper()
            for (pageNum in 1..numPages) {
                stripper.startPage = pageNum
                stripper.endPage = pageNum
                val pageText = stripper.getText(document)
                val preview = pageText.take(200).replace("\n", " ").trim()
                android.util.Log.d("PdfExtractor", "Page $pageNum: ${pageText.length} chars, preview=\"$preview...\"")
                pages.add(pageText)
            }
            android.util.Log.i("PdfExtractor", "PDF extraction complete: ${pages.size} pages extracted")
        } catch (e: Exception) {
            android.util.Log.e("PdfExtractor", "Failed to extract text from PDF", e)
            throw RuntimeException("Failed to extract text from PDF: ${e.message}", e)
        } finally {
            document?.close()
        }
        return pages
    }
}
