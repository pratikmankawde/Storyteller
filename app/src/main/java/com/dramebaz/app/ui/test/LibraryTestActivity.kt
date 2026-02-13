package com.dramebaz.app.ui.test

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.R
import com.dramebaz.app.ui.library.LibraryViewModel
import com.dramebaz.app.ui.test.library.designs.*
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import androidx.viewpager2.widget.ViewPager2

/**
 * Test activity showcasing 10+ different design variations for the library page.
 * Uses ViewPager2 + TabLayout to switch between designs.
 */
class LibraryTestActivity : AppCompatActivity() {

    private val app get() = applicationContext as DramebazApplication
    private lateinit var vm: LibraryViewModel

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.importFromUri(this, it) }
    }

    // Design variations with names
    private val designVariations = listOf(
        "Grid 2-Col" to { Grid2ColumnFragment() },
        "Grid 3-Col" to { Grid3ColumnFragment() },
        "Horizontal" to { HorizontalSectionsFragment() },
        "Carousel" to { CarouselFragment() },
        "Masonry" to { MasonryFragment() },
        "Glass" to { GlassmorphismFragment() },
        "Neumorphic" to { NeumorphismFragment() },
        "Minimal" to { MinimalistFragment() },
        "Magazine" to { MagazineFragment() },
        "Card Stack" to { CardStackFragment() }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library_test)

        // Initialize ViewModel
        vm = ViewModelProvider(this, LibraryViewModel.Factory(app))[LibraryViewModel::class.java]

        // Setup toolbar
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        // Setup ViewPager2 with design fragments
        val viewPager = findViewById<ViewPager2>(R.id.view_pager)
        viewPager.adapter = DesignPagerAdapter(this)

        // Link tabs to ViewPager2
        val tabLayout = findViewById<TabLayout>(R.id.tab_layout)
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = designVariations[position].first
        }.attach()

        // Setup FAB for import
        findViewById<FloatingActionButton>(R.id.fab_import).setOnClickListener {
            picker.launch("*/*")
        }
    }

    private inner class DesignPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = designVariations.size
        override fun createFragment(position: Int): Fragment = designVariations[position].second()
    }
}

