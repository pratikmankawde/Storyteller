package com.dramebaz.app.epub

import com.dramebaz.app.utils.AppLogger
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Extracts text content from EPUB files.
 * EPUB files are ZIP archives containing:
 * - META-INF/container.xml (points to content.opf)
 * - content.opf (manifest listing all content files)
 * - XHTML/HTML files with the actual text content
 */
class EpubExtractor {
    
    companion object {
        private const val TAG = "EpubExtractor"
        private const val CONTAINER_PATH = "META-INF/container.xml"
    }
    
    /**
     * Data class representing an EPUB chapter/section.
     */
    data class EpubChapter(
        val title: String,
        val content: String,
        val href: String // Original file path in EPUB
    )
    
    /**
     * Extract all chapters from an EPUB file.
     * @param epubFile The EPUB file to extract
     * @return List of chapters with titles and content
     */
    fun extractChapters(epubFile: File): List<EpubChapter> {
        if (!epubFile.exists()) {
            throw IllegalArgumentException("EPUB file does not exist: ${epubFile.absolutePath}")
        }
        if (epubFile.length() == 0L) {
            throw IllegalArgumentException("EPUB file is empty: ${epubFile.absolutePath}")
        }
        
        AppLogger.i(TAG, "Extracting EPUB: ${epubFile.name}, size=${epubFile.length()}")
        
        return ZipFile(epubFile).use { zip ->
            // 1. Find the rootfile path from container.xml
            val rootfilePath = findRootfilePath(zip)
            AppLogger.d(TAG, "Rootfile path: $rootfilePath")
            
            // 2. Parse the OPF file to get spine order and manifest
            val (manifest, spine) = parseOpf(zip, rootfilePath)
            AppLogger.d(TAG, "Manifest: ${manifest.size} items, Spine: ${spine.size} items")
            
            // 3. Extract content from each spine item in order
            val basePath = rootfilePath.substringBeforeLast("/", "")
            val chapters = mutableListOf<EpubChapter>()
            
            for (idref in spine) {
                val href = manifest[idref] ?: continue
                val fullPath = if (basePath.isNotEmpty()) "$basePath/$href" else href
                
                try {
                    val entry = zip.getEntry(fullPath) ?: zip.getEntry(href)
                    if (entry != null) {
                        val content = zip.getInputStream(entry).bufferedReader().readText()
                        val text = extractTextFromHtml(content)
                        if (text.isNotBlank()) {
                            val title = extractTitleFromHtml(content) ?: "Section ${chapters.size + 1}"
                            chapters.add(EpubChapter(title, text, href))
                            AppLogger.d(TAG, "Extracted: $href -> ${text.length} chars, title='$title'")
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to extract $fullPath: ${e.message}")
                }
            }
            
            AppLogger.i(TAG, "Extracted ${chapters.size} chapters from EPUB")
            chapters
        }
    }
    
    /**
     * Extract all text as a single string (for simple imports).
     */
    fun extractText(epubFile: File): String {
        return extractChapters(epubFile).joinToString("\n\n") { 
            "# ${it.title}\n\n${it.content}" 
        }
    }
    
    private fun findRootfilePath(zip: ZipFile): String {
        val containerEntry = zip.getEntry(CONTAINER_PATH)
            ?: throw IllegalArgumentException("Invalid EPUB: missing $CONTAINER_PATH")
        
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(zip.getInputStream(containerEntry))
        
        val rootfiles = doc.getElementsByTagName("rootfile")
        if (rootfiles.length == 0) {
            throw IllegalArgumentException("Invalid EPUB: no rootfile in container.xml")
        }
        
        return (rootfiles.item(0) as Element).getAttribute("full-path")
    }
    
    private fun parseOpf(zip: ZipFile, opfPath: String): Pair<Map<String, String>, List<String>> {
        val opfEntry = zip.getEntry(opfPath)
            ?: throw IllegalArgumentException("Invalid EPUB: OPF not found at $opfPath")
        
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(zip.getInputStream(opfEntry))
        
        // Parse manifest: id -> href mapping
        val manifest = mutableMapOf<String, String>()
        val manifestItems = doc.getElementsByTagName("item")
        for (i in 0 until manifestItems.length) {
            val item = manifestItems.item(i) as Element
            val id = item.getAttribute("id")
            val href = item.getAttribute("href")
            val mediaType = item.getAttribute("media-type")
            // Only include XHTML/HTML content
            if (mediaType.contains("html") || mediaType.contains("xml")) {
                manifest[id] = href
            }
        }
        
        // Parse spine: ordered list of idrefs
        val spine = mutableListOf<String>()
        val spineItems = doc.getElementsByTagName("itemref")
        for (i in 0 until spineItems.length) {
            val itemref = spineItems.item(i) as Element
            spine.add(itemref.getAttribute("idref"))
        }
        
        return manifest to spine
    }
    
    private fun extractTextFromHtml(html: String): String {
        // Simple HTML tag stripping - handles most EPUB content
        return html
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<head[^>]*>.*?</head>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("&quot;"), "\"")
            .replace(Regex("&#\\d+;")) { match ->
                val code = match.value.drop(2).dropLast(1).toIntOrNull()
                if (code != null) code.toChar().toString() else ""
            }
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractTitleFromHtml(html: String): String? {
        // Try <title> tag first
        val titleMatch = Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE).find(html)
        if (titleMatch != null) {
            val title = titleMatch.groupValues[1].trim()
            if (title.isNotBlank() && !title.equals("untitled", ignoreCase = true)) {
                return title
            }
        }

        // Try <h1> tag
        val h1Match = Regex("<h1[^>]*>([^<]+)</h1>", RegexOption.IGNORE_CASE).find(html)
        if (h1Match != null) {
            val title = h1Match.groupValues[1].trim()
            if (title.isNotBlank()) {
                return title
            }
        }

        // Try <h2> tag
        val h2Match = Regex("<h2[^>]*>([^<]+)</h2>", RegexOption.IGNORE_CASE).find(html)
        if (h2Match != null) {
            val title = h2Match.groupValues[1].trim()
            if (title.isNotBlank()) {
                return title
            }
        }

        return null
    }
}

