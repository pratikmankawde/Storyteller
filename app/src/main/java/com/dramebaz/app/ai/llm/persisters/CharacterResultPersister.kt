package com.dramebaz.app.ai.llm.persisters

import com.dramebaz.app.ai.llm.pipeline.AccumulatedCharacterData
import com.dramebaz.app.ai.llm.tasks.ChapterAnalysisTask
import com.dramebaz.app.data.db.Character
import com.dramebaz.app.data.db.CharacterDao
import com.dramebaz.app.utils.AppLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persists character analysis results to the database.
 * 
 * Implements TaskResultPersister to handle results from ChapterAnalysisTask.
 */
class CharacterResultPersister(
    private val characterDao: CharacterDao
) : TaskResultPersister {
    
    companion object {
        private const val TAG = "CharacterResultPersister"
    }
    
    private val gson = Gson()
    
    override suspend fun persist(resultData: Map<String, Any>): Int {
        val bookId = (resultData[ChapterAnalysisTask.KEY_BOOK_ID] as? Long)
            ?: (resultData[ChapterAnalysisTask.KEY_BOOK_ID] as? Number)?.toLong()
            ?: return 0
        
        val charactersJson = resultData[ChapterAnalysisTask.KEY_CHARACTERS] as? String
            ?: return 0
        
        // Deserialize characters from JSON
        val characterType = object : TypeToken<List<AccumulatedCharacterData>>() {}.type
        val characters: List<AccumulatedCharacterData> = try {
            gson.fromJson(charactersJson, characterType)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to deserialize characters", e)
            return 0
        }
        
        var savedCount = 0
        
        for (charData in characters) {
            try {
                saveCharacter(bookId, charData)
                savedCount++
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to save character ${charData.name}", e)
            }
        }
        
        AppLogger.i(TAG, "Persisted $savedCount characters for book $bookId")
        return savedCount
    }
    
    private suspend fun saveCharacter(bookId: Long, charData: AccumulatedCharacterData) {
        val existing = characterDao.getByBookIdAndName(bookId, charData.name)
        
        val traitsStr = charData.traits.joinToString(",")
        val voiceProfileJson = charData.voiceProfile?.let { gson.toJson(it) }
        val dialogsJson = if (charData.dialogs.isNotEmpty()) {
            gson.toJson(charData.dialogs.map { d ->
                mapOf(
                    "pageNumber" to d.pageNumber,
                    "text" to d.text,
                    "emotion" to d.emotion,
                    "intensity" to d.intensity
                )
            })
        } else null
        
        if (existing != null) {
            // Update existing character
            characterDao.update(existing.copy(
                traits = if (traitsStr.isNotBlank()) traitsStr else existing.traits,
                voiceProfileJson = voiceProfileJson ?: existing.voiceProfileJson,
                speakerId = charData.speakerId ?: existing.speakerId,
                dialogsJson = dialogsJson ?: existing.dialogsJson
            ))
            AppLogger.d(TAG, "Updated character: ${charData.name}")
        } else {
            // Insert new character
            characterDao.insert(Character(
                bookId = bookId,
                name = charData.name,
                traits = traitsStr,
                personalitySummary = "",
                voiceProfileJson = voiceProfileJson,
                speakerId = charData.speakerId,
                dialogsJson = dialogsJson
            ))
            AppLogger.d(TAG, "Inserted character: ${charData.name}")
        }
    }
}

