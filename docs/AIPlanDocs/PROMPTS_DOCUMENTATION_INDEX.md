# Storyteller Prompts Documentation - Complete Index

## 📚 Documentation Files Created

This comprehensive documentation set covers all prompts, workflows, and analysis patterns used in the Storyteller app.

### 1. **PROMPTS_AND_WORKFLOW_ANALYSIS.md** (Main Reference)
**Complete prompt specifications with system prompts, user templates, and expected outputs**

Contains:
- ✅ Character Extraction Prompt (system + user template)
- ✅ Dialog Extraction Prompt (system + user template)
- ✅ Voice Profile Prompt (system + user template)
- ✅ Batched Analysis Prompt (system + user template)
- ✅ Chapter Analysis Task Workflow
- ✅ PDF Extraction Process
- ✅ Expected Results Format
- ✅ Key Implementation Details

**Use this when:** You need the exact prompt text, configuration, or expected JSON format.

---

### 2. **ANALYSIS_WORKFLOW_DETAILS.md** (Architecture & Data Structures)
**Deep dive into pipeline architecture, data flow, and internal structures**

Contains:
- ✅ Three-Pass Analysis Pipeline (detailed flow)
- ✅ Batched Analysis Pipeline (alternative approach)
- ✅ All Input/Output Data Classes (with field descriptions)
- ✅ Accumulated Character Data Structures
- ✅ Checkpoint Persistence System (save/load/validation)
- ✅ Response Parsing Logic (JSON extraction strategy)
- ✅ Token Budget Configuration
- ✅ Error Handling and Recovery Mechanisms
- ✅ Integration Points (BookAnalysisWorkflow, AnalysisExecutor, Database)

**Use this when:** You need to understand the architecture, data flow, or implement new features.

---

### 3. **PROMPT_EXAMPLES_AND_USAGE.md** (Practical Examples)
**Real-world examples with actual input/output for each prompt**

Contains:
- ✅ Example 1: Character Extraction (input → LLM call → output → parsed result)
- ✅ Example 2: Dialog Extraction (complete walkthrough)
- ✅ Example 3: Voice Profile Suggestion (with detailed output)
- ✅ Example 4: Batched Analysis (comprehensive example)
- ✅ Usage in ChapterAnalysisTask (step-by-step execution)
- ✅ Checkpoint Save/Load Example
- ✅ Common LLM Response Variations (all supported formats)

**Use this when:** You need to see concrete examples or understand how to use the prompts.

---

### 4. **QUICK_REFERENCE.md** (Cheat Sheet)
**Fast lookup tables and quick reference information**

Contains:
- ✅ File Locations Table (all source files)
- ✅ Prompt Summary Table (all prompts at a glance)
- ✅ Input/Output Classes Quick Map
- ✅ JSON Output Formats (all 4 prompts)
- ✅ Key Implementation Details (features, checkpoint, token budget, temperature)
- ✅ Analysis Workflow Steps (both three-pass and batched)
- ✅ Common Patterns (code snippets)
- ✅ Debugging Tips and Common Issues
- ✅ Validation Checks

**Use this when:** You need quick answers or want to look something up fast.

---

### 5. **COMPLETE_PROMPTS_TEXT.md** (Copy/Paste Ready)
**All prompt text in copy-paste friendly format**

Contains:
- ✅ Character Extraction Prompt (system + user template)
- ✅ Dialog Extraction Prompt (system + user template)
- ✅ Voice Profile Prompt (system + user template)
- ✅ Batched Analysis Prompt (system + user template)
- ✅ Expected JSON Outputs (all 4 prompts with examples)
- ✅ Response Parsing Notes (variations, markdown handling, error recovery)
- ✅ Integration Example (complete code snippet)
- ✅ Token Budget Reference Table

**Use this when:** You need to copy prompt text or integrate with external systems.

---

## 🎯 Quick Navigation

### By Task
- **"I need the exact prompt text"** → COMPLETE_PROMPTS_TEXT.md
- **"I need to understand the architecture"** → ANALYSIS_WORKFLOW_DETAILS.md
- **"I need a quick lookup"** → QUICK_REFERENCE.md
- **"I need to see examples"** → PROMPT_EXAMPLES_AND_USAGE.md
- **"I need complete reference"** → PROMPTS_AND_WORKFLOW_ANALYSIS.md

### By Component
- **Character Extraction:** All 5 docs
- **Dialog Extraction:** All 5 docs
- **Voice Profile:** All 5 docs
- **Batched Analysis:** All 5 docs
- **Workflow/Pipeline:** ANALYSIS_WORKFLOW_DETAILS.md, PROMPT_EXAMPLES_AND_USAGE.md
- **PDF Extraction:** PROMPTS_AND_WORKFLOW_ANALYSIS.md
- **Checkpoint System:** ANALYSIS_WORKFLOW_DETAILS.md, QUICK_REFERENCE.md

### By Role
- **LLM Engineer:** COMPLETE_PROMPTS_TEXT.md, PROMPTS_AND_WORKFLOW_ANALYSIS.md
- **Backend Developer:** ANALYSIS_WORKFLOW_DETAILS.md, QUICK_REFERENCE.md
- **Integration Engineer:** PROMPT_EXAMPLES_AND_USAGE.md, COMPLETE_PROMPTS_TEXT.md
- **Debugger:** QUICK_REFERENCE.md (Debugging Tips section)

---

## 📋 Summary of Findings

### Prompts Documented
1. **CharacterExtractionPrompt** - Extracts character names
2. **DialogExtractionPrompt** - Extracts dialogs with speaker attribution
3. **VoiceProfilePrompt** - Suggests voice profiles for characters
4. **BatchedAnalysisPrompt** - Unified extraction (characters, dialogs, traits, voices)

### Workflows Documented
1. **Three-Pass Analysis** - Sequential character → dialog → voice extraction
2. **Batched Analysis** - Single-pass extraction for large texts
3. **Checkpoint System** - Resume capability with 24-hour TTL

### Data Structures Documented
- 8 Input/Output data classes
- 5 Serializable checkpoint classes
- 3 Voice profile variations
- 2 Dialog format variations

### Key Features
- ✅ Robust JSON parsing with multiple format support
- ✅ Markdown code block removal
- ✅ Multiple JSON object merging (JSONL format)
- ✅ Duplicate key truncation (LLM repetition handling)
- ✅ Field name flexibility (D/d/dialogs, T/t/traits, V/v/voice)
- ✅ Checkpoint persistence with validation
- ✅ Token budget management with paragraph-aware truncation
- ✅ Temperature-based determinism (0.1f to 0.2f)

---

## 🔗 Source Files Referenced

| Component | File Path |
|-----------|-----------|
| Character Extraction | `app/src/main/java/com/dramebaz/app/ai/llm/prompts/CharacterExtractionPrompt.kt` |
| Dialog Extraction | `app/src/main/java/com/dramebaz/app/ai/llm/prompts/DialogExtractionPrompt.kt` |
| Voice Profile | `app/src/main/java/com/dramebaz/app/ai/llm/prompts/VoiceProfilePrompt.kt` |
| Batched Analysis | `app/src/main/java/com/dramebaz/app/ai/llm/prompts/BatchedAnalysisPrompt.kt` |
| Chapter Analysis Task | `app/src/main/java/com/dramebaz/app/ai/llm/tasks/ChapterAnalysisTask.kt` |
| PDF Extractor | `app/src/main/java/com/dramebaz/app/pdf/PdfExtractor.kt` |
| Data Classes | `app/src/main/java/com/dramebaz/app/ai/llm/prompts/PromptInputOutput.kt` |
| Example Results | `app/src/main/assets/demo/SpaceStoryAnalysis.json` |

---

## 📊 Documentation Statistics

- **Total Files:** 5 comprehensive markdown documents
- **Total Content:** ~2000 lines of documentation
- **Prompts Documented:** 4 complete prompts
- **Code Examples:** 20+ practical examples
- **Data Classes:** 15+ documented structures
- **Tables:** 10+ reference tables
- **Diagrams:** Pipeline flow diagrams

---

## ✨ What's Included

### For Each Prompt
- ✅ System prompt (exact text)
- ✅ User prompt template (with variables)
- ✅ Configuration (temperature, token budget)
- ✅ Input/Output classes
- ✅ Expected JSON format
- ✅ Parsing logic
- ✅ Error handling
- ✅ Real examples

### For Workflows
- ✅ Pipeline architecture
- ✅ Step-by-step execution flow
- ✅ Data flow diagrams
- ✅ Integration points
- ✅ Checkpoint system details
- ✅ Resume capability

### For Implementation
- ✅ Code snippets
- ✅ Usage patterns
- ✅ Integration examples
- ✅ Debugging tips
- ✅ Common issues and solutions
- ✅ Validation checks

---

## 🚀 Getting Started

1. **Start here:** QUICK_REFERENCE.md (2-minute overview)
2. **Then read:** PROMPTS_AND_WORKFLOW_ANALYSIS.md (complete reference)
3. **For examples:** PROMPT_EXAMPLES_AND_USAGE.md (practical usage)
4. **For deep dive:** ANALYSIS_WORKFLOW_DETAILS.md (architecture)
5. **For copy/paste:** COMPLETE_PROMPTS_TEXT.md (ready-to-use text)

---

## 📝 Notes

- All prompt text is extracted directly from source code
- All examples are based on actual SpaceStory demo data
- All data structures are documented with field descriptions
- All workflows are explained with step-by-step flows
- All configurations are current as of the latest codebase


