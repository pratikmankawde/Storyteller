package com.dramebaz.app.ui.story

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.ai.llm.LlmService
import com.dramebaz.app.data.db.Book
import com.dramebaz.app.domain.usecases.ImportBookUseCase
import com.dramebaz.app.ui.common.ErrorDialog
import com.dramebaz.app.utils.AppLogger
import com.dramebaz.app.utils.InputValidator
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter

/**
 * T12.1: Fragment for generating stories using LLM.
 * STORY-003: Added remix mode support.
 * User provides a prompt, LLM generates story content, then imports it into library.
 */
class StoryGenerationFragment : Fragment() {
    private val app get() = requireContext().applicationContext as DramebazApplication
    private val tag = "StoryGeneration"

    private lateinit var promptInput: EditText
    private lateinit var generateButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var progressCard: ViewGroup

    // STORY-003: Remix mode views
    private lateinit var modeToggle: MaterialButtonToggleGroup
    private lateinit var sourceBookCard: MaterialCardView
    private lateinit var btnSelectBook: MaterialButton
    private lateinit var selectedBookTitleView: TextView
    private lateinit var headerTitle: TextView
    private lateinit var headerDescription: TextView

    // STORY-002: Image mode views
    private lateinit var imagePickerCard: MaterialCardView
    private lateinit var imagePreviewContainer: View
    private lateinit var imagePreview: ImageView
    private lateinit var btnSelectImage: MaterialButton
    private lateinit var btnModeImage: MaterialButton // Reference to Image mode toggle button

    // Generation mode enum
    private enum class GenerationMode { NEW, REMIX, IMAGE }
    private var currentMode = GenerationMode.NEW

    // Model capabilities
    private var modelSupportsImage = false

    // STORY-003: Remix mode state
    private var selectedBookId: Long? = null
    private var selectedBookTitle: String? = null
    private var availableBooks: List<Book> = emptyList()

    // STORY-002: Image mode state
    private var selectedImageUri: Uri? = null
    private var selectedImagePath: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_story_generation, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        promptInput = view.findViewById(R.id.prompt_input)
        generateButton = view.findViewById(R.id.btn_generate)
        progressBar = view.findViewById(R.id.progress_bar)
        statusText = view.findViewById(R.id.status_text)
        progressCard = view.findViewById(R.id.progress_card)

        // STORY-003: Initialize remix mode views
        modeToggle = view.findViewById(R.id.mode_toggle)
        sourceBookCard = view.findViewById(R.id.source_book_card)
        btnSelectBook = view.findViewById(R.id.btn_select_book)
        selectedBookTitleView = view.findViewById(R.id.selected_book_title)
        headerTitle = view.findViewById(R.id.header_title)
        headerDescription = view.findViewById(R.id.header_description)

        // STORY-002: Initialize image mode views
        imagePickerCard = view.findViewById(R.id.image_picker_card)
        imagePreviewContainer = view.findViewById(R.id.image_preview_container)
        imagePreview = view.findViewById(R.id.image_preview)
        btnSelectImage = view.findViewById(R.id.btn_select_image)
        btnModeImage = view.findViewById(R.id.btn_mode_image)

        // Set initial mode (New Story)
        modeToggle.check(R.id.btn_mode_new)
        setupModeToggle()
        setupBookSelection()
        setupImageSelection()

        // Check model capabilities and update Image mode availability
        checkModelCapabilities()

        generateButton.setOnClickListener {
            when (currentMode) {
                GenerationMode.NEW -> generateStory()
                GenerationMode.REMIX -> remixStory()
                GenerationMode.IMAGE -> generateFromImage()
            }
        }

        // STORY-003: Load available books for remix
        loadAvailableBooks()
    }

    /**
     * Check LLM model capabilities and update UI accordingly.
     * Disables Image mode if the current model doesn't support vision/multimodal.
     */
    private fun checkModelCapabilities() {
        val capabilities = LlmService.getModelCapabilities()
        modelSupportsImage = capabilities.supportsImage

        AppLogger.i(tag, "Model capabilities: ${capabilities.modelName}, supportsImage=${capabilities.supportsImage}, supportsAudio=${capabilities.supportsAudio}")

        if (!modelSupportsImage) {
            // Disable Image mode button and show unavailable state
            btnModeImage.isEnabled = false
            btnModeImage.alpha = 0.5f
            btnModeImage.text = "Image (N/A)"
            AppLogger.d(tag, "Image mode disabled - model doesn't support vision input")
        } else {
            // Enable Image mode button
            btnModeImage.isEnabled = true
            btnModeImage.alpha = 1.0f
            btnModeImage.text = "Image"
        }
    }

    // Setup mode toggle listener (STORY-003/STORY-002)
    private fun setupModeToggle() {
        modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                // Check if Image mode is being selected but not supported
                if (checkedId == R.id.btn_mode_image && !modelSupportsImage) {
                    // Revert to previous mode and show message
                    Toast.makeText(
                        requireContext(),
                        "Image mode unavailable: Current model doesn't support vision input. Switch to Gemma 3n model.",
                        Toast.LENGTH_LONG
                    ).show()
                    // Revert to New mode
                    modeToggle.check(R.id.btn_mode_new)
                    return@addOnButtonCheckedListener
                }

                currentMode = when (checkedId) {
                    R.id.btn_mode_new -> GenerationMode.NEW
                    R.id.btn_mode_remix -> GenerationMode.REMIX
                    R.id.btn_mode_image -> GenerationMode.IMAGE
                    else -> GenerationMode.NEW
                }
                updateUIForMode()
            }
        }
    }

    // Update UI based on current mode (STORY-003/STORY-002)
    private fun updateUIForMode() {
        // Hide all mode-specific cards first
        sourceBookCard.visibility = View.GONE
        imagePickerCard.visibility = View.GONE

        when (currentMode) {
            GenerationMode.REMIX -> {
                sourceBookCard.visibility = View.VISIBLE
                headerTitle.text = "Remix Story with AI"
                headerDescription.text = "Select a book from your library and describe how you want to remix it."
                generateButton.text = "Remix Story"
                promptInput.hint = "How would you like to remix this story? (e.g., make it scary, from villain's POV)"
            }
            GenerationMode.IMAGE -> {
                imagePickerCard.visibility = View.VISIBLE
                headerTitle.text = "Generate Story from Image"
                headerDescription.text = "Select an inspiration image and optionally provide story direction."
                generateButton.text = "Generate from Image"
                promptInput.hint = "Optional: Describe the kind of story you want (e.g., adventure, mystery)"
            }
            GenerationMode.NEW -> {
                headerTitle.text = "Generate Story with AI"
                headerDescription.text = "Enter a prompt to generate a story. The AI will create a complete story based on your prompt."
                generateButton.text = "Generate Story"
                promptInput.hint = ""
                // Reset selections
                selectedBookId = null
                selectedBookTitle = null
                btnSelectBook.text = "Select a book to remix..."
                selectedBookTitleView.visibility = View.GONE
                selectedImageUri = null
                selectedImagePath = null
                imagePreviewContainer.visibility = View.GONE
                btnSelectImage.text = "Select an image..."
            }
        }
    }

    // STORY-003: Setup book selection button
    private fun setupBookSelection() {
        btnSelectBook.setOnClickListener {
            showBookSelectionDialog()
        }
    }

    // STORY-002: Image picker launcher
    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            // Copy image to app cache for stable access
            copyImageToCache(uri)
            // Show preview
            imagePreviewContainer.visibility = View.VISIBLE
            imagePreview.setImageURI(uri)
            btnSelectImage.text = "Change image..."
            AppLogger.d(tag, "Selected image: $uri")
        }
    }

    // STORY-002: Setup image selection button
    private fun setupImageSelection() {
        btnSelectImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }
    }

    // STORY-002: Copy selected image to app cache for stable path
    private fun copyImageToCache(uri: Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            if (inputStream != null) {
                val cacheFile = File(requireContext().cacheDir, "inspiration_image_${System.currentTimeMillis()}.jpg")
                cacheFile.outputStream().use { output ->
                    inputStream.copyTo(output)
                }
                inputStream.close()
                selectedImagePath = cacheFile.absolutePath
                AppLogger.d(tag, "Copied image to cache: $selectedImagePath")
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to copy image to cache", e)
            Toast.makeText(requireContext(), "Failed to load image", Toast.LENGTH_SHORT).show()
        }
    }

    // STORY-003: Load available books from library
    private fun loadAvailableBooks() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                availableBooks = withContext(Dispatchers.IO) {
                    app.bookRepository.allBooks().first()
                }
                AppLogger.d(tag, "Loaded ${availableBooks.size} books for remix selection")
            } catch (e: Exception) {
                AppLogger.e(tag, "Failed to load books", e)
            }
        }
    }

    // STORY-003: Show book selection dialog
    private fun showBookSelectionDialog() {
        if (availableBooks.isEmpty()) {
            Toast.makeText(requireContext(), "No books available. Import books first.", Toast.LENGTH_SHORT).show()
            return
        }

        val bookTitles = availableBooks.map { it.title }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Source Book")
            .setItems(bookTitles) { _, which ->
                val book = availableBooks[which]
                selectedBookId = book.id
                selectedBookTitle = book.title
                btnSelectBook.text = book.title
                selectedBookTitleView.text = "Selected: ${book.title}"
                selectedBookTitleView.visibility = View.VISIBLE
                AppLogger.d(tag, "Selected book for remix: ${book.title} (id=${book.id})")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun generateStory() {
        val rawPrompt = promptInput.text.toString()

        // AUG-040: Validate story prompt
        val validationResult = InputValidator.validateStoryPrompt(rawPrompt)
        if (validationResult.isFailure) {
            ErrorDialog.show(
                context = requireContext(),
                title = "Invalid Prompt",
                message = InputValidator.getErrorMessage(validationResult)
            )
            return
        }

        val prompt = validationResult.getOrThrow()

        generateButton.isEnabled = false
        progressCard.visibility = View.VISIBLE
        statusText.text = "Generating story..."

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val storyContent = withContext(Dispatchers.IO) {
                    AppLogger.d(tag, "Generating story with prompt: ${prompt.take(50)}...")
                    // AUG-040: Sanitize prompt before sending to LLM
                    val sanitizedPrompt = InputValidator.sanitizeLlmPrompt(prompt)
                    LlmService.generateStory(sanitizedPrompt)
                }

                if (storyContent.isNotEmpty()) {
                    statusText.text = "Story generated! Importing..."
                    AppLogger.d(tag, "Generated story length: ${storyContent.length} characters")

                    // Save story to temporary file and import
                    val tempFile = File(requireContext().cacheDir, "generated_story_${System.currentTimeMillis()}.txt")
                    FileWriter(tempFile).use { writer ->
                        writer.write(storyContent)
                    }

                    AppLogger.d(tag, "Saved story to: ${tempFile.absolutePath}")

                    // Import the story
                    val importUseCase = ImportBookUseCase(app.bookRepository)
                    val bookId = importUseCase.importFromFile(requireContext(), tempFile.absolutePath, "txt")

                    if (bookId != null && bookId > 0) {
                        statusText.text = "Story imported successfully!"
                        Toast.makeText(requireContext(), "Story generated and imported!", Toast.LENGTH_SHORT).show()

                        // Clear prompt
                        promptInput.text.clear()

                        // Navigate back to library
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            requireActivity().onBackPressedDispatcher.onBackPressed()
                        } else {
                            @Suppress("DEPRECATION")
                            requireActivity().onBackPressed()
                        }
                    } else {
                        statusText.text = "Failed to import story"
                        Toast.makeText(requireContext(), "Failed to import story", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    statusText.text = "Failed to generate story"
                    Toast.makeText(requireContext(), "Failed to generate story", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                AppLogger.e(tag, "Error generating story", e)
                statusText.text = "Error: ${e.message}"
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                generateButton.isEnabled = true
                progressCard.visibility = View.GONE
            }
        }
    }

    // STORY-003: Remix an existing story
    private fun remixStory() {
        // Validate book selection
        val bookId = selectedBookId
        if (bookId == null) {
            Toast.makeText(requireContext(), "Please select a book to remix", Toast.LENGTH_SHORT).show()
            return
        }

        val rawInstruction = promptInput.text.toString()

        // Validate remix instruction
        val validationResult = InputValidator.validateStoryPrompt(rawInstruction)
        if (validationResult.isFailure) {
            ErrorDialog.show(
                context = requireContext(),
                title = "Invalid Instruction",
                message = InputValidator.getErrorMessage(validationResult)
            )
            return
        }

        val instruction = validationResult.getOrThrow()

        generateButton.isEnabled = false
        progressCard.visibility = View.VISIBLE
        statusText.text = "Loading source story..."

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Load source book chapters
                val sourceStory = withContext(Dispatchers.IO) {
                    val chapters = app.bookRepository.chapters(bookId).first()
                    // Combine all chapter bodies as source story
                    chapters.sortedBy { it.orderIndex }
                        .mapNotNull { ch -> ch.body.takeIf { it.isNotBlank() } }
                        .joinToString("\n\n")
                }

                if (sourceStory.isBlank()) {
                    withContext(Dispatchers.Main) {
                        statusText.text = "Source book has no content"
                        Toast.makeText(requireContext(), "Selected book has no content to remix", Toast.LENGTH_SHORT).show()
                        generateButton.isEnabled = true
                        progressCard.visibility = View.GONE
                    }
                    return@launch
                }

                AppLogger.d(tag, "Loaded source story: ${sourceStory.length} characters from bookId=$bookId")
                statusText.text = "Remixing story..."

                // Remix the story
                val remixedContent = withContext(Dispatchers.IO) {
                    val sanitizedInstruction = InputValidator.sanitizeLlmPrompt(instruction)
                    LlmService.remixStory(sanitizedInstruction, sourceStory)
                }

                if (remixedContent.isNotEmpty()) {
                    statusText.text = "Remix complete! Importing..."
                    AppLogger.d(tag, "Remixed story length: ${remixedContent.length} characters")

                    // Save remixed story to temporary file and import
                    val tempFile = File(requireContext().cacheDir, "remixed_story_${System.currentTimeMillis()}.txt")
                    FileWriter(tempFile).use { writer ->
                        writer.write(remixedContent)
                    }

                    AppLogger.d(tag, "Saved remixed story to: ${tempFile.absolutePath}")

                    // Import the remixed story
                    val importUseCase = ImportBookUseCase(app.bookRepository)
                    val newBookId = importUseCase.importFromFile(requireContext(), tempFile.absolutePath, "txt")

                    if (newBookId != null && newBookId > 0) {
                        statusText.text = "Remixed story imported!"
                        Toast.makeText(requireContext(), "Story remixed and imported!", Toast.LENGTH_SHORT).show()

                        // Clear prompt and selection
                        promptInput.text.clear()
                        selectedBookId = null
                        selectedBookTitle = null
                        btnSelectBook.text = "Select a book to remix..."
                        selectedBookTitleView.visibility = View.GONE

                        // Navigate back to library
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            requireActivity().onBackPressedDispatcher.onBackPressed()
                        } else {
                            @Suppress("DEPRECATION")
                            requireActivity().onBackPressed()
                        }
                    } else {
                        statusText.text = "Failed to import remixed story"
                        Toast.makeText(requireContext(), "Failed to import remixed story", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    statusText.text = "Failed to remix story"
                    Toast.makeText(requireContext(), "Failed to remix story", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                AppLogger.e(tag, "Error remixing story", e)
                statusText.text = "Error: ${e.message}"
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                generateButton.isEnabled = true
                progressCard.visibility = View.GONE
            }
        }
    }

    // STORY-002: Generate a story from an inspiration image
    private fun generateFromImage() {
        // Validate image selection
        val imagePath = selectedImagePath
        if (imagePath == null) {
            Toast.makeText(requireContext(), "Please select an image first", Toast.LENGTH_SHORT).show()
            return
        }

        val userPrompt = promptInput.text.toString().trim()

        generateButton.isEnabled = false
        progressCard.visibility = View.VISIBLE
        statusText.text = "Analyzing image and generating story..."

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                AppLogger.d(tag, "Generating story from image: $imagePath, prompt: ${userPrompt.take(50)}...")

                val storyContent = withContext(Dispatchers.IO) {
                    LlmService.generateStoryFromImage(imagePath, userPrompt)
                }

                if (storyContent.isNotEmpty()) {
                    statusText.text = "Story generated! Importing..."
                    AppLogger.d(tag, "Generated story from image, length: ${storyContent.length} characters")

                    // Save story to temporary file and import
                    val tempFile = File(requireContext().cacheDir, "image_story_${System.currentTimeMillis()}.txt")
                    FileWriter(tempFile).use { writer ->
                        writer.write(storyContent)
                    }

                    AppLogger.d(tag, "Saved image-based story to: ${tempFile.absolutePath}")

                    // Import the story
                    val importUseCase = ImportBookUseCase(app.bookRepository)
                    val bookId = importUseCase.importFromFile(requireContext(), tempFile.absolutePath, "txt")

                    if (bookId != null && bookId > 0) {
                        statusText.text = "Story imported successfully!"
                        Toast.makeText(requireContext(), "Story generated from image!", Toast.LENGTH_SHORT).show()

                        // Clear prompt and image selection
                        promptInput.text.clear()
                        selectedImageUri = null
                        selectedImagePath = null
                        imagePreviewContainer.visibility = View.GONE
                        btnSelectImage.text = "Select an image..."

                        // Navigate back to library
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            requireActivity().onBackPressedDispatcher.onBackPressed()
                        } else {
                            @Suppress("DEPRECATION")
                            requireActivity().onBackPressed()
                        }
                    } else {
                        statusText.text = "Failed to import story"
                        Toast.makeText(requireContext(), "Failed to import story", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    statusText.text = "Failed to generate story from image"
                    Toast.makeText(requireContext(), "Failed to generate story from image", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                AppLogger.e(tag, "Error generating story from image", e)
                statusText.text = "Error: ${e.message}"
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                generateButton.isEnabled = true
                progressCard.visibility = View.GONE
            }
        }
    }
}
