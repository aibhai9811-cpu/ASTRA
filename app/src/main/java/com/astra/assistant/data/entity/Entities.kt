package com.astra.assistant.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val key: String,
    val value: String,
    val category: String,
    val isApproved: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "correction_rules")
data class CorrectionRuleEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val triggerPhrase: String,
    val normalizedPhrase: String,
    val actionType: String,
    val actionTarget: String,
    val actionLabel: String,
    val hitCount: Int = 0,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "knowledge")
data class KnowledgeEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val sourceType: String,
    val sourceName: String? = null,
    val sourceUrl: String? = null,
    val tags: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
