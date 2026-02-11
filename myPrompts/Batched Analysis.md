Modify the batched chapter analysis workflow to support incremental processing with the following requirements:                         
                                                                                                                                        
**1. First Chapter Analysis on Book Load (analyzeFirstChapter):**                                                                       
   - Process only the first 50% of pages(broken on paragraph boundary) initially (foreground), if more than 4 pages in the first chapter.                                                                                                                                
   - After first half completes:                                                                                                        
     - Immediately trigger(enqueue) audio generation for the analyzed portion                                                           
     - Move remaining 50% of pages to background processing                                                                             
   - Summary generation: Only trigger after 100% of chapter is analyzed                                                                 
   - Audio generation: Trigger(enqueue) incrementally after each batch completes (don't wait for full chapter)                          
                                                                                                                                        
**2. Next Chapter Pre-Analysis (ReaderFragment):**                                                                                      
   - Trigger condition: Current chapter is 100% analyzed AND user has read =50% of current chapter pages                                
   - Processing strategy:                                                                                                               
     - Run analysis in background                                                                                                       
     - After EACH batch completes: immediately trigger(enqueue) audio generation for that batch's dialogs                               
     - Do NOT wait for full chapter analysis before generating audio                                                                    
   - This enables audio to be ready as user progresses through current chapter                                                          
   - If user changes speaker of any of the characters or narrator and a new audio generation is being triggered for any segments, make sure any audio-generation jobs already enqueued for those segments are cancelled.                                                       
                                                                                                                                        
**3. Implementation Changes Needed:**                                                                                                   
   - Modify `BatchedChapterAnalysisTask` to support:                                                                                    
     - `maxPagesToProcess` parameter (for 50% limit on first load. Make this a setting available on the Settings->Analysis panel.)      
     - `onBatchComplete` callback to trigger incremental audio generation                                                               
   - Update `BookAnalysisWorkflow.analyzeFirstChapter()`:                                                                               
     - Calculate 50% page count                                                                                                         
     - Pass callback to generate audio after each batch                                                                                 
     - Queue remaining 50% as background job                                                                                            
   - Update `ReaderFragment.triggerNextChapterPreAnalysis()`:                                                                           
     - Check both conditions (current chapter 100% analyzed + 50% read)                                                                 
     - Use background analysis with audio generation callback                                                                           
                                                                                                                                        
**4. Audio Generation Integration:**                                                                                                    
   - Use `SegmentAudioGenerator.generatePageAudio()` after each batch                                                                   
   - Map batch paragraphs to page numbers                                                                                               
   - Generate audio only for pages with matched dialogs                                                                                 
                                                                                                                                        
Do NOT modify the summary/theme analysis passes - those remain separate and trigger only after full chapter analysis. 


---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

Create a new optimized chapter analysis pipeline that processes paragraphs in batches. This pipeline should 
    coexist with the existing 3-pass pipeline (CharacterExtractionPass -> DialogExtractionPass -> VoiceProfilePass). I should be able to switch and select the pipeline I want to run. So, make the code modular and resuable.          
                                                                                                                                            
**Pipeline Architecture:**                                                                                                              
																																		
1. **Input Preparation (use existing code):**                                                                                           
   - Use existing PDF page cleaning and paragraph boundary detection                                                                    
   - Group multiple paragraphs into batches based on token budget (see budget constraints below)                                        
   - Truncate batches ONLY at paragraph boundaries, never mid-paragraph                                                                 
																																		
2. **Single-Pass Extraction (new unified pass):**                                                                                       
   - Create a new `BatchedChapterAnalysisPass` that extracts ALL of the following in ONE LLM call per batch:                            
	 - Characters (names only)                                                                                                          
	 - Character Traits and Personalities (MBTI, temperament, or similar framework, use existing parameters as extablished in Trait+Personality Passes. Remove duplicates.)
	 - Voice Profiles (pitch, speed, accent)                                                                             
	 - Array of Dialogs (exact text, emotion, intensity, timbre, and prosody)                                                                                      
   - Process each paragraph batch sequentially through the chapter                                                                      
   - Each LLM call should use a concise prompt (3-4 lines max) explaining what to extract                                               
   - Output format: flat JSON structure with minimal nesting (avoid deeply nested objects)                                              
																																		
3. **Incremental Merge & Checkpoint System:**                                                                                           
   - After each batch is processed, merge the new extraction results with accumulated data. Remove duplicates, resolve full vs just last names, synonyms etc.                                          
   - Implement a background merge job (use existing `AnalysisBackgroundRunner` or similar)                                              
   - Create checkpoint after each merge containing:                                                                                     
	 - All accumulated extraction data (characters, traits, dialogs, etc.)                                                              
	 - Session metadata: `contentHash`, `lastProcessedParagraphIndex`, `totalParagraphs`, `timestamp`                                   
	 - Checkpoint should enable resume from exact paragraph where analysis stopped (or got interruped)                                                     
   - Save checkpoints to storage (use existing checkpoint mechanism in `ChapterAnalysisTask` or maybe separate checkpointing to new class for reuse.)                                           
																																		
4. **Database Persistence:**                                                                                                            
   - After all paragraph batches are processed, save final merged data to database                                                      
   - Use existing DAOs: `CharacterDao`, `DialogDao`, etc.                                                                               
   - Mark analysis as complete in database                                                                                              
																																		
5. **Post-Analysis Summary Pass (new separate pass):**                                                                                  
   - After main analysis completes, run a second pass with the FULL chapter text (all paragraphs combined)                              
   - Extract: chapter summary, themes, genre                                                                             
   - Run this pass in background (use `AnalysisBackgroundRunner`)                                                                       
   - Save results to database and trigger UI update via LiveData/Flow                                                                   
																																		
6. **Audio Rendering Integration:**                                                                                                     
   - Immediately after analysis pass completes, trigger speaker assignment(using extracted Voice profile data) and background audio rendering for:                                                 
	 - Narrator voice                                                                                                                   
	 - All character voices (based on extracted voice profiles).                                                                   
   - Show unified progress bar at top of app for all background tasks (analysis + audio rendering)                                      
   - Reuse existing progress bar implementation from reading page if available                                                          
																																		
**Token Budget Constraints:**                                                                                                           
- **Analysis Pass (per batch):**                                                                                                        
  - System Prompt + User Prompt + Input Text = 4000 tokens max                                                                          
  - Output = 1000 tokens max                                                                                                            
  - Calculate input text size dynamically: `inputTokens = 4000 - promptTokens`                                                          
  - Use `TokenBudget` class and `CHARS_PER_TOKEN = 4` for estimation                                                                    
  - Truncate input ONLY at paragraph boundaries                                                                                         
																																		
- **Summary Pass:**                                                                                                                     
  - Define separate budget (suggest 6000 input + 500 output)                                                                            
																																		
**Prompt Design:**                                                                                                                      
- Keep extraction prompt to 3-4 lines maximum
Example(improve upon):
```          
Extract all characters, their traits and personalities, voice characteristics, and dialogs from this story excerpt. For each speaking character:
Output: Valid JSON, No commentary
{"character-name": { "dialogs":["exact line 1","exact line 2"],"traits":["physical", "skills", "temprament"], "voice": { "gender": "male|female", "age": "child|young|middle-aged|elderly", "accent": "neutral|british|american|asian|indian|middle-eastern|southern|etc", "pitch": 0.1-1.0, "speed": 0.1-2.0}}}

Rules:
- dialogs: Copy exact quoted speech only
- traits: Mentioned facts (tall, scarred, good with swords). Behavior patterns (cautious, hot-tempered, kind). Do not invent.
- voice: Only requested params. Pitch and speed are in the given range.

Excerpt:
<INPUT TEXT>
```
																																		
**Code Modularity Requirements:**                                                                                                       
- Create new files in `app/src/main/java/com/dramebaz/app/ai/llm/pipeline/`:                                                            
  - `BatchedChapterAnalysisPass.kt` - Main extraction pass                                                                              
  - `BatchedAnalysisPrompt.kt` - Prompt definition in `prompts/` folder                                                                 
  - `ParagraphBatcher.kt` - Logic to group paragraphs by token budget                                                                   
  - `IncrementalMerger.kt` - Merge logic for combining batch results                                                                    
  - `BatchedAnalysisCheckpoint.kt` or a `Checkpointer.kt` class for reuse - Checkpoint data class with session metadata                                                        
- Reuse existing: `BaseExtractionPass`, `PromptDefinition`, `PassConfig`, `ChapterAnalysisTask` structure                               
- Follow existing patterns: `CharacterExtractionPassV2`, `DialogExtractionPassV2` as reference                                          
																																		
**Integration Points:**                                                                                                                 
- Hook into `BookAnalysisWorkflow.analyzeChapter()` method                                      
- Use existing `AnalysisExecutor` for task execution                                                                                    
- Use existing `LlmModel` interface for LLM calls                                                                                       
- Leverage existing checkpoint system in `ChapterAnalysisTask`                                                                          
																																		
**Success Criteria:**                                                                                                                   
- Reduce total LLM calls from 66 (3 passes × 22 pages) to ~8-10 (1 pass × 8-10 batches)                                                 
- Reduce total analysis time from ~30 minutes to ~5-8 minutes per chapter                                                               
- Support resume from any paragraph batch if interrupted                                                                                
- Maintain data quality (same extraction accuracy as 3-pass pipeline)