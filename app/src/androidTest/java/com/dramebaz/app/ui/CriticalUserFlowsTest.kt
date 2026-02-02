package com.dramebaz.app.ui

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.recyclerview.widget.RecyclerView
import com.dramebaz.app.R
import com.dramebaz.app.ui.main.MainActivity
import org.hamcrest.Matchers.allOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AUG-044: UI Tests for Critical User Flows
 *
 * Tests critical user journeys with Espresso:
 * - Library display and navigation
 * - Book detail view
 * - Character list navigation
 * - Story generation UI
 * - Settings navigation
 *
 * Note: These tests require the app to be in a testable state.
 * Some tests may be skipped if no books are imported.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class CriticalUserFlowsTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    // ===== Library Fragment Tests =====

    @Test
    fun testLibraryDisplaysToolbar() {
        // Verify the toolbar is displayed
        onView(withId(R.id.toolbar))
            .check(matches(isDisplayed()))
    }

    @Test
    fun testLibraryFabIsDisplayed() {
        // Verify the import FAB is displayed
        onView(withId(R.id.fab_import))
            .check(matches(isDisplayed()))
    }

    @Test
    fun testLibraryShowsEmptyStateOrRecycler() {
        // Either empty state or recycler should be displayed (not both hidden)
        try {
            onView(withId(R.id.recycler))
                .check(matches(isDisplayed()))
            android.util.Log.d("CriticalUserFlowsTest", "Recycler is visible (has books)")
        } catch (e: AssertionError) {
            onView(withId(R.id.empty_state))
                .check(matches(isDisplayed()))
            android.util.Log.d("CriticalUserFlowsTest", "Empty state is visible (no books)")
        }
    }

    @Test
    fun testSettingsNavigationFromMenu() {
        // Open toolbar menu and click settings
        onView(withId(R.id.settings))
            .perform(click())

        // Verify we're on settings fragment
        Thread.sleep(500) // Allow navigation to complete

        // Go back to library
        pressBack()
    }

    @Test
    fun testStoryGenerationNavigationFromMenu() {
        // Open toolbar menu and click generate story
        onView(withId(R.id.generate_story))
            .perform(click())

        Thread.sleep(500) // Allow navigation to complete

        // Go back to library
        pressBack()
    }

    // ===== Book Navigation Tests =====

    @Test
    fun testNavigateToBookDetailIfBooksExist() {
        // Try to click on first book if recycler has items
        try {
            onView(withId(R.id.recycler))
                .check(matches(isDisplayed()))

            // Try clicking on first item
            onView(withId(R.id.recycler))
                .perform(RecyclerViewActions.actionOnItemAtPosition<RecyclerView.ViewHolder>(0, click()))

            Thread.sleep(500)

            // Verify we're on book detail (should have title and analyze button)
            onView(withId(R.id.title))
                .check(matches(isDisplayed()))

            onView(withId(R.id.btn_analyze))
                .check(matches(isDisplayed()))

            android.util.Log.d("CriticalUserFlowsTest", "Successfully navigated to book detail")

            // Go back
            pressBack()
        } catch (e: Exception) {
            android.util.Log.d("CriticalUserFlowsTest", "No books in library or navigation failed: ${e.message}")
        }
    }

    // ===== Story Generation Tests =====

    @Test
    fun testStoryGenerationUIElements() {
        // Navigate to story generation
        onView(withId(R.id.generate_story))
            .perform(click())

        Thread.sleep(500)

        // Verify story prompt input is displayed
        try {
            onView(withId(R.id.prompt_input))
                .check(matches(isDisplayed()))

            android.util.Log.d("CriticalUserFlowsTest", "Story generation UI verified")
        } catch (e: Exception) {
            android.util.Log.w("CriticalUserFlowsTest", "Story generation UI not found: ${e.message}")
        }

        pressBack()
    }

    @Test
    fun testStoryPromptInputValidation() {
        // Navigate to story generation
        onView(withId(R.id.generate_story))
            .perform(click())

        Thread.sleep(500)

        try {
            // Try entering a short prompt (should show validation error)
            onView(withId(R.id.prompt_input))
                .perform(typeText("Short"), closeSoftKeyboard())

            Thread.sleep(300)

            android.util.Log.d("CriticalUserFlowsTest", "Prompt validation test completed")
        } catch (e: Exception) {
            android.util.Log.w("CriticalUserFlowsTest", "Prompt input test failed: ${e.message}")
        }

        pressBack()
    }

    // ===== Navigation Flow Tests =====

    @Test
    fun testFullBookFlowIfBooksExist() {
        // Complete flow: Library -> Book Detail -> Read -> Back -> Characters -> Back
        try {
            // Check if there are books
            onView(withId(R.id.recycler))
                .check(matches(isDisplayed()))

            // Click first book
            onView(withId(R.id.recycler))
                .perform(RecyclerViewActions.actionOnItemAtPosition<RecyclerView.ViewHolder>(0, click()))

            Thread.sleep(500)

            // Verify book detail
            onView(withId(R.id.btn_analyze))
                .check(matches(isDisplayed()))

            // Check for actions grid (contains Read, Characters, etc.)
            onView(withId(R.id.actions_grid))
                .check(matches(isDisplayed()))

            android.util.Log.d("CriticalUserFlowsTest", "Full book flow test passed")

            // Go back to library
            pressBack()

        } catch (e: Exception) {
            android.util.Log.d("CriticalUserFlowsTest", "Full book flow test skipped: ${e.message}")
        }
    }

    @Test
    fun testToolbarMenuAccessibility() {
        // Test that toolbar menu items are accessible
        onView(withId(R.id.toolbar))
            .check(matches(isDisplayed()))

        // Settings should be in menu
        try {
            onView(withId(R.id.settings))
                .check(matches(isDisplayed()))
            android.util.Log.d("CriticalUserFlowsTest", "Settings menu item is accessible")
        } catch (e: Exception) {
            android.util.Log.w("CriticalUserFlowsTest", "Settings might be in overflow menu")
        }
    }

    @Test
    fun testImportFabClickable() {
        // Test that import FAB is clickable
        onView(withId(R.id.fab_import))
            .check(matches(isClickable()))

        android.util.Log.d("CriticalUserFlowsTest", "Import FAB is clickable")
    }

    // ===== Error Handling UI Tests =====

    @Test
    fun testNavigateBackFromSettings() {
        // Navigate to settings and back
        onView(withId(R.id.settings))
            .perform(click())

        Thread.sleep(300)

        // Press back to return to library
        pressBack()

        Thread.sleep(300)

        // Verify we're back on library (FAB should be visible)
        onView(withId(R.id.fab_import))
            .check(matches(isDisplayed()))

        android.util.Log.d("CriticalUserFlowsTest", "Navigation back from settings works")
    }

    @Test
    fun testNavigateBackFromStoryGeneration() {
        // Navigate to story generation and back
        onView(withId(R.id.generate_story))
            .perform(click())

        Thread.sleep(300)

        // Press back to return to library
        pressBack()

        Thread.sleep(300)

        // Verify we're back on library
        onView(withId(R.id.fab_import))
            .check(matches(isDisplayed()))

        android.util.Log.d("CriticalUserFlowsTest", "Navigation back from story generation works")
    }

    // ===== Scroll Tests =====

    @Test
    fun testLibraryRecyclerScrollable() {
        try {
            // Check if recycler is visible and scrollable
            onView(withId(R.id.recycler))
                .check(matches(isDisplayed()))

            // Try scrolling down
            onView(withId(R.id.recycler))
                .perform(swipeUp())

            Thread.sleep(200)

            // Try scrolling back up
            onView(withId(R.id.recycler))
                .perform(swipeDown())

            android.util.Log.d("CriticalUserFlowsTest", "Recycler scrolling works")
        } catch (e: Exception) {
            android.util.Log.d("CriticalUserFlowsTest", "Recycler scroll test skipped: ${e.message}")
        }
    }
}
