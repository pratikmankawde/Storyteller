package com.dramebaz.app.ui.story

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.ai.llm.QwenStub
import com.dramebaz.app.domain.usecases.ImportBookUseCase
import com.dramebaz.app.ui.common.ErrorDialog
import com.dramebaz.app.utils.AppLogger
import com.dramebaz.app.utils.InputValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter

/**
 * T12.1: Fragment for generating stories using LLM.
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

        generateButton.setOnClickListener {
            generateStory()
        }
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
                    QwenStub.generateStory(sanitizedPrompt)
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
}
