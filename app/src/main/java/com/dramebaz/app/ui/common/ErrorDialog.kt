package com.dramebaz.app.ui.common

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.dramebaz.app.R
import com.dramebaz.app.domain.exceptions.AppException
import com.dramebaz.app.domain.exceptions.ErrorType
import com.dramebaz.app.utils.ErrorHandler
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * AUG-039: Reusable error dialog component.
 * Provides consistent error display across the app with optional retry action.
 */
object ErrorDialog {
    
    /**
     * Show a simple error dialog with just a message.
     */
    fun show(
        context: Context,
        title: String = "Error",
        message: String,
        onDismiss: (() -> Unit)? = null
    ): AlertDialog {
        return MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                onDismiss?.invoke()
            }
            .show()
    }
    
    /**
     * Show an error dialog with optional retry button.
     */
    fun showWithRetry(
        context: Context,
        title: String = "Error",
        message: String,
        onRetry: () -> Unit,
        onCancel: (() -> Unit)? = null
    ): AlertDialog {
        return MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Retry") { dialog, _ ->
                dialog.dismiss()
                onRetry()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                onCancel?.invoke()
            }
            .show()
    }
    
    /**
     * Show a detailed error dialog for AppException with type-specific handling.
     */
    fun showDetailed(
        context: Context,
        error: Throwable,
        onRetry: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
        showTechnicalDetails: Boolean = true
    ): AlertDialog {
        val appException = if (error is AppException) error else ErrorHandler.classify(error)
        val title = getTitleForErrorType(appException.errorType)
        val message = appException.userMessage
        val technicalDetails = "${appException.errorType}: ${error.message ?: "Unknown error"}"
        
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_error, null)
        setupDialogView(view, title, message, technicalDetails, showTechnicalDetails)
        
        val builder = MaterialAlertDialogBuilder(context)
            .setView(view)
        
        if (onRetry != null && appException.isRetryable) {
            builder.setPositiveButton("Retry") { dialog, _ ->
                dialog.dismiss()
                onRetry()
            }
            builder.setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                onDismiss?.invoke()
            }
        } else {
            builder.setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                onDismiss?.invoke()
            }
        }
        
        return builder.show()
    }
    
    private fun setupDialogView(
        view: View,
        title: String,
        message: String,
        technicalDetails: String,
        showTechnicalDetails: Boolean
    ) {
        view.findViewById<TextView>(R.id.error_title).text = title
        view.findViewById<TextView>(R.id.error_message).text = message
        
        val detailsCard = view.findViewById<MaterialCardView>(R.id.technical_details_card)
        val detailsText = view.findViewById<TextView>(R.id.technical_details)
        val showDetailsButton = view.findViewById<MaterialButton>(R.id.show_details_button)
        
        if (showTechnicalDetails && technicalDetails.isNotBlank()) {
            detailsText.text = technicalDetails
            showDetailsButton.visibility = View.VISIBLE
            
            showDetailsButton.setOnClickListener {
                if (detailsCard.visibility == View.GONE) {
                    detailsCard.visibility = View.VISIBLE
                    showDetailsButton.text = "Hide Details"
                } else {
                    detailsCard.visibility = View.GONE
                    showDetailsButton.text = "Show Details"
                }
            }
        }
    }
    
    private fun getTitleForErrorType(errorType: ErrorType): String {
        return when (errorType) {
            ErrorType.FILE_IO -> "File Error"
            ErrorType.DATABASE -> "Database Error"
            ErrorType.LLM -> "AI Analysis Error"
            ErrorType.TTS -> "Voice Synthesis Error"
            ErrorType.OUT_OF_MEMORY -> "Memory Error"
            ErrorType.VALIDATION -> "Invalid Input"
            ErrorType.UNKNOWN -> "Error"
        }
    }
}

