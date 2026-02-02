package com.dramebaz.app

import com.dramebaz.app.ai.llm.QwenModelLoadingTest
import com.dramebaz.app.ai.llm.QwenTextAnalysisTest
import com.dramebaz.app.ai.tts.TtsGenerationTest
import com.dramebaz.app.playback.AudioMixerTest
import com.dramebaz.app.playback.AudioPlaybackTest
import com.dramebaz.app.ui.CriticalUserFlowsTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * Test suite that runs all tests.
 *
 * This suite includes:
 * - Model loading tests (QwenModelLoadingTest)
 * - Text analysis tests (QwenTextAnalysisTest)
 * - TTS generation tests (TtsGenerationTest)
 * - Audio playback tests (AudioPlaybackTest)
 * - Audio mixing tests (AudioMixerTest)
 * - UI tests (CriticalUserFlowsTest) - AUG-044
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    QwenModelLoadingTest::class,
    QwenTextAnalysisTest::class,
    TtsGenerationTest::class,
    AudioPlaybackTest::class,
    AudioMixerTest::class,
    CriticalUserFlowsTest::class
)
class AllModelTests
