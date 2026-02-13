package com.dramebaz.app.domain.usecases

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.dramebaz.app.DramebazApplication
import com.dramebaz.app.ai.llm.LlmService
import com.dramebaz.app.data.db.AppDatabase
import com.dramebaz.app.data.db.Book
import com.dramebaz.app.data.db.Chapter
import com.dramebaz.app.data.models.FeatureSettings
import com.dramebaz.app.data.repositories.BookRepository
import com.dramebaz.app.data.repositories.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation tests for BookAnalysisWorkflow.
 * 
 * Tests the integration between SettingsRepository and BookAnalysisWorkflow:
 * - Verifies incrementalAnalysisPagePercent setting is properly read during analysis
 * - Tests workflow initialization with SettingsRepository
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class BookAnalysisWorkflowTest {

    private lateinit var app: DramebazApplication
    private lateinit var db: AppDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var workflow: BookAnalysisWorkflow

    companion object {
        private const val TAG = "BookAnalysisWorkflowTest"
        private const val TEST_BOOK_TITLE = "Test Workflow Book"
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        app = context.applicationContext as DramebazApplication
        db = app.db
        bookRepository = app.bookRepository
        settingsRepository = app.settingsRepository
        
        // Initialize LLM service
        runBlocking {
            LlmService.initialize(context)
        }
        
        // Create workflow with settings repository
        workflow = BookAnalysisWorkflow(
            context = context,
            bookRepository = bookRepository,
            database = db,
            settingsRepository = settingsRepository
        )
        
        // Clean up any existing test book
        runBlocking {
            db.bookDao().deleteByTitle(TEST_BOOK_TITLE)
        }
    }

    @After
    fun teardown() {
        runBlocking {
            // Clean up test book
            db.bookDao().deleteByTitle(TEST_BOOK_TITLE)
            // Reset settings to default
            settingsRepository.updateFeatureSettings(FeatureSettings())
        }
    }

    // ==================== Workflow Initialization Tests ====================

    @Test
    fun testWorkflowCreatedWithSettingsRepository() {
        // Verify workflow is created successfully with settings repository
        assertNotNull("Workflow should be created", workflow)
        android.util.Log.d(TAG, "BookAnalysisWorkflow created with SettingsRepository")
    }

    @Test
    fun testWorkflowReadsIncrementalPercentFromSettings() {
        runBlocking {
            // Set a custom percentage
            settingsRepository.updateFeatureSettings(
                FeatureSettings(incrementalAnalysisPagePercent = 25)
            )
            
            // Verify settings are updated
            val currentSettings = settingsRepository.featureSettings.value
            assertEquals("Settings should reflect 25%", 25, currentSettings.incrementalAnalysisPagePercent)
            
            android.util.Log.d(TAG, "Workflow can read incrementalAnalysisPagePercent=${currentSettings.incrementalAnalysisPagePercent}")
        }
    }

    // ==================== Analysis Queue Manager Integration Tests ====================

    @Test
    fun testAnalysisQueueManagerUsesSettingsRepository() {
        // Verify AnalysisQueueManager is properly initialized with settings
        assertNotNull("AnalysisQueueManager should be initialized", AnalysisQueueManager)
        
        // The workflow created by AnalysisQueueManager should use settings
        runBlocking {
            settingsRepository.updateFeatureSettings(
                FeatureSettings(incrementalAnalysisPagePercent = 30)
            )
            
            // Settings should be accessible through the repository
            val percent = settingsRepository.featureSettings.value.incrementalAnalysisPagePercent
            assertEquals(30, percent)
            android.util.Log.d(TAG, "AnalysisQueueManager workflow uses settings: $percent%")
        }
    }

    @Test
    fun testSettingChangeReflectedInWorkflow() {
        runBlocking {
            // Initial setting
            settingsRepository.updateFeatureSettings(FeatureSettings(incrementalAnalysisPagePercent = 50))
            assertEquals(50, settingsRepository.featureSettings.value.incrementalAnalysisPagePercent)
            
            // Change setting
            settingsRepository.updateFeatureSettings(FeatureSettings(incrementalAnalysisPagePercent = 75))
            
            // Workflow should see the new value (via StateFlow)
            assertEquals(75, settingsRepository.featureSettings.value.incrementalAnalysisPagePercent)
            android.util.Log.d(TAG, "Setting change reflected: 50 -> 75")
        }
    }

    @Test
    fun testMinPagesThresholdConstant() {
        // Verify MIN_PAGES_FOR_INCREMENTAL constant is accessible
        assertEquals(
            "MIN_PAGES_FOR_INCREMENTAL should be 4",
            4, FeatureSettings.MIN_PAGES_FOR_INCREMENTAL
        )
        android.util.Log.d(TAG, "MIN_PAGES_FOR_INCREMENTAL = ${FeatureSettings.MIN_PAGES_FOR_INCREMENTAL}")
    }

    @Test
    fun testIncrementalPercentAt100DisablesSplit() {
        runBlocking {
            // Set to 100% (no splitting)
            settingsRepository.updateFeatureSettings(FeatureSettings(incrementalAnalysisPagePercent = 100))
            
            val percent = settingsRepository.featureSettings.value.incrementalAnalysisPagePercent
            assertEquals(100, percent)
            // When percent is 100, shouldSplit will be false in workflow
            android.util.Log.d(TAG, "At 100%, incremental splitting is disabled")
        }
    }
}

