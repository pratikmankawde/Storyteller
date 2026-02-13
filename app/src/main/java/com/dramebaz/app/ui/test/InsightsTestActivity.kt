package com.dramebaz.app.ui.test

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.dramebaz.app.R
import com.dramebaz.app.ui.test.insights.designs.*
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import androidx.viewpager2.widget.ViewPager2

/**
 * Test activity showcasing 10 different Insights page design variations.
 * Each design presents the same data (statistics, emotional arc, sentiment, etc.)
 * with different visual approaches.
 */
class InsightsTestActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout

    private val designTitles = listOf(
        "Cards",           // 1. Card-based sections
        "Dashboard",       // 2. Dashboard grid
        "Timeline",        // 3. Vertical timeline
        "Tabs",            // 4. Tabbed sections
        "Carousel",        // 5. Carousel cards
        "Glassmorphism",   // 6. Frosted glass effect
        "Dark Mode",       // 7. Dark theme
        "Minimal",         // 8. Clean minimal
        "Magazine",        // 9. Magazine editorial
        "Gradient"         // 10. Gradient backgrounds
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_insights_test)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        viewPager = findViewById(R.id.view_pager)
        tabLayout = findViewById(R.id.tab_layout)

        viewPager.adapter = InsightsDesignAdapter(this)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = designTitles[position]
        }.attach()
    }

    private inner class InsightsDesignAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 10

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> CardsInsightsFragment()
            1 -> DashboardInsightsFragment()
            2 -> TimelineInsightsFragment()
            3 -> TabbedInsightsFragment()
            4 -> CarouselInsightsFragment()
            5 -> GlassInsightsFragment()
            6 -> DarkInsightsFragment()
            7 -> MinimalInsightsFragment()
            8 -> MagazineInsightsFragment()
            9 -> GradientInsightsFragment()
            else -> CardsInsightsFragment()
        }
    }
}

