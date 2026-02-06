package com.dramebaz.app.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.dramebaz.app.R
import com.dramebaz.app.utils.AppLogger
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import androidx.viewpager2.widget.ViewPager2

/**
 * SETTINGS-001: Settings Bottom Sheet with tabbed interface.
 * 
 * From NovelReaderWeb settings-sheet.component.ts:
 * - Tabs: Display, Audio, Features, About
 * - Persists settings changes via SettingsRepository
 */
class SettingsBottomSheet : BottomSheetDialogFragment() {
    
    companion object {
        private const val TAG = "SettingsBottomSheet"
        
        fun newInstance(): SettingsBottomSheet = SettingsBottomSheet()
    }
    
    /**
     * Listener interface for settings changes.
     */
    interface SettingsChangeListener {
        fun onSettingsChanged()
    }
    
    private var listener: SettingsChangeListener? = null
    
    fun setListener(listener: SettingsChangeListener) {
        this.listener = listener
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_settings, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppLogger.d(TAG, "Settings bottom sheet created")
        
        val tabLayout = view.findViewById<TabLayout>(R.id.tab_layout)
        val viewPager = view.findViewById<ViewPager2>(R.id.view_pager)
        
        // Set up ViewPager with adapter
        viewPager.adapter = SettingsTabAdapter(this)
        
        // Connect TabLayout with ViewPager
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.settings_display)
                1 -> getString(R.string.settings_audio)
                2 -> getString(R.string.settings_features)
                3 -> getString(R.string.settings_llm)
                4 -> getString(R.string.settings_about)
                else -> ""
            }
        }.attach()
    }
    
    /**
     * Notify listener when settings change.
     * Called by tab fragments.
     */
    fun notifySettingsChanged() {
        listener?.onSettingsChanged()
    }
    
    /**
     * ViewPager adapter for settings tabs.
     */
    private inner class SettingsTabAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 5

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> DisplaySettingsFragment()
                1 -> AudioSettingsFragment()
                2 -> FeaturesSettingsFragment()
                3 -> LlmSettingsFragment()
                4 -> AboutSettingsFragment()
                else -> throw IllegalArgumentException("Invalid position: $position")
            }
        }
    }
}

