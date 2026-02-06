package com.dramebaz.app.ui.common

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.dramebaz.app.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * UI-006: Shimmer Loading Dialog.
 * 
 * A dialog that displays shimmer loading animation instead of a circular spinner.
 * Use this as a drop-in replacement for ProgressDialog during LLM analysis.
 * 
 * From NovelReaderWeb docs/UI.md - "LoadingShimmer"
 */
class ShimmerLoadingDialog : DialogFragment() {
    
    companion object {
        private const val TAG = "ShimmerLoadingDialog"
        private const val ARG_MESSAGE = "message"
        private const val ARG_CANCELABLE = "cancelable"
        
        fun newInstance(message: String, cancelable: Boolean = false): ShimmerLoadingDialog {
            return ShimmerLoadingDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_MESSAGE, message)
                    putBoolean(ARG_CANCELABLE, cancelable)
                }
            }
        }
        
        /**
         * Show a shimmer loading dialog with the given message.
         * Returns the dialog instance for later dismissal.
         */
        fun show(
            fragmentManager: androidx.fragment.app.FragmentManager,
            message: String,
            cancelable: Boolean = false
        ): ShimmerLoadingDialog {
            val dialog = newInstance(message, cancelable)
            dialog.show(fragmentManager, TAG)
            return dialog
        }
    }
    
    private var message: String = "Loading..."
    private var onCancelListener: (() -> Unit)? = null
    private var messageView: TextView? = null
    private var progressView: TextView? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            message = it.getString(ARG_MESSAGE, "Loading...")
            isCancelable = it.getBoolean(ARG_CANCELABLE, false)
        }
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.dialog_shimmer_loading, null)
        
        messageView = dialogView.findViewById(R.id.shimmer_message)
        progressView = dialogView.findViewById(R.id.shimmer_progress)
        messageView?.text = message
        
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setCancelable(isCancelable)
            .apply {
                if (isCancelable) {
                    setNegativeButton(android.R.string.cancel) { _, _ ->
                        onCancelListener?.invoke()
                        dismiss()
                    }
                }
            }
            .create()
        
        return dialog
    }
    
    /**
     * Update the loading message.
     */
    fun setMessage(newMessage: String) {
        message = newMessage
        messageView?.text = newMessage
    }
    
    /**
     * Update progress text (e.g., "3 of 10 chapters").
     */
    fun setProgress(progressText: String) {
        progressView?.text = progressText
        progressView?.visibility = View.VISIBLE
    }
    
    /**
     * Set a listener for when the dialog is cancelled.
     */
    fun setOnCancelListener(listener: () -> Unit) {
        onCancelListener = listener
    }
}

