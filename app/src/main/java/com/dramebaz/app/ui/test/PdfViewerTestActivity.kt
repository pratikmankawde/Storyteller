package com.dramebaz.app.ui.test

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dramebaz.app.R
import com.dramebaz.app.pdf.PdfPageRenderer
import com.dramebaz.app.ui.widget.PageCurlView
import com.dramebaz.app.utils.AppLogger
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Test activity for PDF viewing with page curl animation.
 * Allows opening PDF files from storage or loading the bundled demo PDF.
 */
class PdfViewerTestActivity : AppCompatActivity() {

    private val tag = "PdfViewerTestActivity"

    private lateinit var pageCurlView: PageCurlView
    private lateinit var textStatus: TextView
    private lateinit var textPageInfo: TextView
    private lateinit var loadingOverlay: FrameLayout

    private var pdfRenderer: PdfPageRenderer? = null
    private var currentPdfFile: File? = null

    private val openPdfLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                loadPdfFromUri(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_viewer_test)

        // Setup toolbar
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        // Get views
        pageCurlView = findViewById(R.id.page_curl_view)
        textStatus = findViewById(R.id.text_status)
        textPageInfo = findViewById(R.id.text_page_info)
        loadingOverlay = findViewById(R.id.loading_overlay)

        // Setup buttons
        findViewById<MaterialButton>(R.id.btn_open_pdf).setOnClickListener {
            openPdfPicker()
        }

        findViewById<MaterialButton>(R.id.btn_load_demo).setOnClickListener {
            loadDemoPdf()
        }
    }

    private fun openPdfPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        openPdfLauncher.launch(intent)
    }

    private fun loadPdfFromUri(uri: Uri) {
        showLoading(true)
        textStatus.text = "Loading PDF..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Copy PDF to cache directory
                val cacheFile = File(cacheDir, "temp_pdf_${System.currentTimeMillis()}.pdf")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }

                if (cacheFile.exists() && cacheFile.length() > 0) {
                    loadPdfFile(cacheFile)
                } else {
                    withContext(Dispatchers.Main) {
                        showLoading(false)
                        textStatus.text = "Failed to copy PDF file"
                        Toast.makeText(this@PdfViewerTestActivity, "Failed to load PDF", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(tag, "Error loading PDF from URI", e)
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    textStatus.text = "Error: ${e.message}"
                    Toast.makeText(this@PdfViewerTestActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadDemoPdf() {
        showLoading(true)
        textStatus.text = "Loading demo PDF..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Copy demo PDF from assets
                val demoFile = File(cacheDir, "SpaceStory.pdf")
                assets.open("demo/SpaceStory.pdf").use { input ->
                    FileOutputStream(demoFile).use { output ->
                        input.copyTo(output)
                    }
                }
                loadPdfFile(demoFile)
            } catch (e: Exception) {
                AppLogger.e(tag, "Error loading demo PDF", e)
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    textStatus.text = "Error loading demo: ${e.message}"
                    Toast.makeText(this@PdfViewerTestActivity, "Demo PDF not found in assets", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun loadPdfFile(file: File) {
        // Close previous renderer
        pdfRenderer?.close()

        // Create new renderer
        val renderer = PdfPageRenderer.create(this@PdfViewerTestActivity, file)
        if (renderer == null) {
            withContext(Dispatchers.Main) {
                showLoading(false)
                textStatus.text = "Failed to open PDF"
                Toast.makeText(this@PdfViewerTestActivity, "Cannot open PDF file", Toast.LENGTH_SHORT).show()
            }
            return
        }

        pdfRenderer = renderer
        currentPdfFile = file
        val pageCount = renderer.getPageCount()

        withContext(Dispatchers.Main) {
            textStatus.text = "Loaded: ${file.name}"
            AppLogger.i(tag, "PDF loaded: ${file.name}, $pageCount pages")

            // Setup PageCurlView with listener
            pageCurlView.listener = object : PageCurlView.PageCurlListener {
                override fun onPageChanged(newPageIndex: Int) {
                    updatePageInfo(newPageIndex)
                }

                override fun getPageCount(): Int = pageCount

                override fun getPageBitmap(pageIndex: Int, width: Int, height: Int, callback: (Bitmap?) -> Unit) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val bitmap = renderer.renderPage(pageIndex, width)
                        withContext(Dispatchers.Main) {
                            callback(bitmap)
                        }
                    }
                }
            }

            // Set initial page
            pageCurlView.setCurrentPage(0)
            updatePageInfo(0)
            showLoading(false)

            Toast.makeText(
                this@PdfViewerTestActivity,
                "PDF loaded: $pageCount pages. Swipe to curl pages!",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updatePageInfo(position: Int) {
        val total = pdfRenderer?.getPageCount() ?: 0
        textPageInfo.text = "Page: ${position + 1} / $total"
    }

    private fun showLoading(show: Boolean) {
        loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        pdfRenderer?.close()
        pdfRenderer = null
        // Clean up temp files
        currentPdfFile?.let { file ->
            if (file.absolutePath.contains("temp_pdf_")) {
                file.delete()
            }
        }
    }
}
