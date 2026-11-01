package com.astra.assistant.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.astra.assistant.data.entity.CorrectionRuleEntity
import com.astra.assistant.data.entity.KnowledgeEntity
import com.astra.assistant.data.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY createdAt DESC")
    fun getAllMemories(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE isApproved = 1")
    suspend fun getApprovedMemories(): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemory(id: String)

    @Query("DELETE FROM memories")
    suspend fun clearAll()
}

@Dao
interface CorrectionRuleDao {
    @Query("SELECT * FROM correction_rules ORDER BY createdAt DESC")
    fun getAllRules(): Flow<List<CorrectionRuleEntity>>

    @Query("SELECT * FROM correction_rules WHERE normalizedPhrase = :phrase AND isActive = 1 LIMIT 1")
    suspend fun findExactMatch(phrase: String): CorrectionRuleEntity?

    @Query("SELECT * FROM correction_rules WHERE isActive = 1")
    suspend fun getActiveRules(): List<CorrectionRuleEntity>

    @Query("UPDATE correction_rules SET hitCount = hitCount + 1 WHERE id = :id")
    suspend fun incrementHitCount(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: CorrectionRuleEntity)

    @Query("DELETE FROM correction_rules WHERE id = :id")
    suspend fun deleteRule(id: String)
}

@Dao
interface KnowledgeDao {
    @Query("SELECT * FROM knowledge ORDER BY createdAt DESC")
    fun getAllKnowledge(): Flow<List<KnowledgeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKnowledge(item: KnowledgeEntity)

    @Query("DELETE FROM knowledge WHERE id = :id")
    suspend fun deleteKnowledge(id: String)
}
