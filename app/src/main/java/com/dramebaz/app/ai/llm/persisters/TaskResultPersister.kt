package com.dramebaz.app.ai.llm.persisters

/**
 * Interface for persisting task results to database.
 * 
 * Different tasks produce different result types, so implementations
 * interpret the result data map according to the task type.
 * 
 * Design Pattern: Strategy Pattern - different persistence strategies
 * for different task types.
 */
interface TaskResultPersister {
    /**
     * Persist task results to database.
     * 
     * @param resultData Map of result data from task execution
     * @return Number of items persisted
     */
    suspend fun persist(resultData: Map<String, Any>): Int
}

