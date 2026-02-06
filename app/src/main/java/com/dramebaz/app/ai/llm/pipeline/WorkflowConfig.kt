package com.dramebaz.app.ai.llm.pipeline

/**
 * Configuration for a complete analysis workflow.
 * Defines which passes to run and their configurations.
 * 
 * Design Pattern: Builder Pattern - allows flexible composition of workflows.
 */
data class WorkflowConfig(
    /** Human-readable name for this workflow */
    val name: String,
    
    /** Whether to run character extraction (Pass 1) */
    val runCharacterExtraction: Boolean = true,
    
    /** Whether to run dialog extraction (Pass 2) */
    val runDialogExtraction: Boolean = true,
    
    /** Whether to run traits extraction (Pass 3) */
    val runTraitsExtraction: Boolean = true,
    
    /** Configuration for Pass 1 */
    val pass1Config: PassConfig = PassConfig.PASS1_CHARACTER_EXTRACTION,
    
    /** Configuration for Pass 2 */
    val pass2Config: PassConfig = PassConfig.PASS2_DIALOG_EXTRACTION,
    
    /** Configuration for Pass 3 */
    val pass3Config: PassConfig = PassConfig.PASS3_TRAITS_EXTRACTION,
    
    /** Maximum characters per text segment for Pass 1/2 */
    val segmentSizePass1: Int = 12_000,
    
    /** Maximum characters per text segment for Pass 2 */
    val segmentSizePass2: Int = 6_000,
    
    /** Maximum context characters per character for Pass 3 */
    val maxContextPerCharacter: Int = 10_000
) {
    
    /** Number of passes in this workflow */
    val passCount: Int
        get() = listOf(runCharacterExtraction, runDialogExtraction, runTraitsExtraction).count { it }
    
    companion object {
        /**
         * 2-Pass workflow: Character extraction + Dialog extraction.
         * Faster, suitable for quick analysis or weaker models.
         */
        val TWO_PASS = WorkflowConfig(
            name = "2-Pass Workflow",
            runCharacterExtraction = true,
            runDialogExtraction = true,
            runTraitsExtraction = false,
            segmentSizePass1 = 12_000,
            segmentSizePass2 = 6_000
        )
        
        /**
         * 3-Pass workflow: Character + Dialog + Traits extraction.
         * Richer analysis, suitable for capable models.
         */
        val THREE_PASS = WorkflowConfig(
            name = "3-Pass Workflow",
            runCharacterExtraction = true,
            runDialogExtraction = true,
            runTraitsExtraction = true,
            segmentSizePass1 = 10_000,
            segmentSizePass2 = 10_000,
            maxContextPerCharacter = 10_000
        )
        
        /**
         * Character-only workflow: Just extract character names.
         * Fastest, useful for quick character listing.
         */
        val CHARACTER_ONLY = WorkflowConfig(
            name = "Character-Only Workflow",
            runCharacterExtraction = true,
            runDialogExtraction = false,
            runTraitsExtraction = false
        )
        
        /**
         * Create config optimized for Gemma/LiteRT-LM model.
         * Uses faster settings with slightly lower quality.
         */
        fun forGemmaModel() = WorkflowConfig(
            name = "Gemma 2-Pass Workflow",
            runCharacterExtraction = true,
            runDialogExtraction = true,
            runTraitsExtraction = false,
            pass1Config = PassConfig(maxTokens = 100, temperature = 0.1f, maxSegmentChars = 12_000),
            pass2Config = PassConfig(maxTokens = 2100, temperature = 0.35f, maxSegmentChars = 6_000),
            segmentSizePass1 = 12_000,
            segmentSizePass2 = 6_000
        )
        
        /**
         * Create config optimized for Qwen/GGUF model.
         * Uses full 3-pass with richer analysis.
         */
        fun forQwenModel() = WorkflowConfig(
            name = "Qwen 3-Pass Workflow",
            runCharacterExtraction = true,
            runDialogExtraction = true,
            runTraitsExtraction = true,
            pass1Config = PassConfig(maxTokens = 256, temperature = 0.1f, maxSegmentChars = 10_000),
            pass2Config = PassConfig(maxTokens = 512, temperature = 0.15f, maxSegmentChars = 10_000),
            pass3Config = PassConfig(maxTokens = 384, temperature = 0.1f, maxSegmentChars = 10_000),
            segmentSizePass1 = 10_000,
            segmentSizePass2 = 10_000,
            maxContextPerCharacter = 10_000
        )
    }
}

/**
 * Builder for creating custom workflow configurations.
 */
class WorkflowConfigBuilder {
    private var name: String = "Custom Workflow"
    private var runCharacterExtraction: Boolean = true
    private var runDialogExtraction: Boolean = true
    private var runTraitsExtraction: Boolean = false
    private var pass1Config: PassConfig = PassConfig.PASS1_CHARACTER_EXTRACTION
    private var pass2Config: PassConfig = PassConfig.PASS2_DIALOG_EXTRACTION
    private var pass3Config: PassConfig = PassConfig.PASS3_TRAITS_EXTRACTION
    private var segmentSizePass1: Int = 12_000
    private var segmentSizePass2: Int = 6_000
    private var maxContextPerCharacter: Int = 10_000
    
    fun name(name: String) = apply { this.name = name }
    fun withCharacterExtraction(enabled: Boolean = true) = apply { runCharacterExtraction = enabled }
    fun withDialogExtraction(enabled: Boolean = true) = apply { runDialogExtraction = enabled }
    fun withTraitsExtraction(enabled: Boolean = true) = apply { runTraitsExtraction = enabled }
    fun pass1Config(config: PassConfig) = apply { this.pass1Config = config }
    fun pass2Config(config: PassConfig) = apply { this.pass2Config = config }
    fun pass3Config(config: PassConfig) = apply { this.pass3Config = config }
    fun segmentSizePass1(size: Int) = apply { this.segmentSizePass1 = size }
    fun segmentSizePass2(size: Int) = apply { this.segmentSizePass2 = size }
    fun maxContextPerCharacter(size: Int) = apply { this.maxContextPerCharacter = size }
    
    fun build() = WorkflowConfig(
        name = name,
        runCharacterExtraction = runCharacterExtraction,
        runDialogExtraction = runDialogExtraction,
        runTraitsExtraction = runTraitsExtraction,
        pass1Config = pass1Config,
        pass2Config = pass2Config,
        pass3Config = pass3Config,
        segmentSizePass1 = segmentSizePass1,
        segmentSizePass2 = segmentSizePass2,
        maxContextPerCharacter = maxContextPerCharacter
    )
}

