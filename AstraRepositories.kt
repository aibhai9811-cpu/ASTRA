package com.astra.assistant.data.repository

import com.astra.assistant.data.dao.CorrectionRuleDao
import com.astra.assistant.data.dao.KnowledgeDao
import com.astra.assistant.data.dao.MemoryDao
import com.astra.assistant.data.entity.CorrectionRuleEntity
import com.astra.assistant.data.entity.KnowledgeEntity
import com.astra.assistant.data.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow
import java.util.regex.Pattern

object SensitiveDataGuard {
    private val PATTERNS = listOf(
        Pattern.compile("(?i)\\b(?:password|passwd|pwd|passcode|secret|api[_-]?key|token)\\s*[:=]\\s*\\S+"),
        Pattern.compile("\\b(?:\\d{4}[-\\s]?){3}\\d{4}\\b"), // 16 digit card
        Pattern.compile("(?i)\\b(?:cvv|cvc|security code)\\s*[:=]?\\s*\\d{3,4}\\b"),
        Pattern.compile("(?i)\\b(?:otp|one[- ]time[- ]password|verification code)\\s*[:=]?\\s*\\d{4,8}\\b")
    )

    fun containsSensitiveData(text: String): Boolean {
        for (p in PATTERNS) {
            if (p.matcher(text).find()) return true
        }
        return false
    }
}

class AstraMemoryRepository(private val memoryDao: MemoryDao) {
    val allMemories: Flow<List<MemoryEntity>> = memoryDao.getAllMemories()

    suspend fun getApprovedContextSummary(): String {
        val approved = memoryDao.getApprovedMemories()
        if (approved.isEmpty()) return ""
        return approved.joinToString("\n") { "- [${it.category.uppercase()}]: ${it.key} = ${it.value}" }
    }

    suspend fun saveMemory(key: String, value: String, category: String): Result<Unit> {
        val combined = "$key $value"
        if (SensitiveDataGuard.containsSensitiveData(combined)) {
            return Result.failure(SecurityException("Sensitive data detected."))
        }
        memoryDao.insertMemory(MemoryEntity(key = key, value = value, category = category))
        return Result.success(Unit)
    }

    suspend fun deleteMemory(id: String) {
        memoryDao.deleteMemory(id)
    }

    suspend fun clearAll() {
        memoryDao.clearAll()
    }
}

class AstraCorrectionRepository(private val correctionDao: CorrectionRuleDao) {
    val allRules: Flow<List<CorrectionRuleEntity>> = correctionDao.getAllRules()

    suspend fun findMatchingRule(input: String): CorrectionRuleEntity? {
        val normalized = input.lowercase().trim()
        val exact = correctionDao.findExactMatch(normalized)
        if (exact != null) {
            correctionDao.incrementHitCount(exact.id)
            return exact
        }
        val active = correctionDao.getActiveRules()
        val partial = active.firstOrNull { normalized.contains(it.normalizedPhrase) }
        if (partial != null) {
            correctionDao.incrementHitCount(partial.id)
            return partial
        }
        return null
    }

    suspend fun saveRule(trigger: String, actionType: String, actionTarget: String, actionLabel: String) {
        correctionDao.insertRule(
            CorrectionRuleEntity(
                triggerPhrase = trigger.trim(),
                normalizedPhrase = trigger.lowercase().trim(),
                actionType = actionType,
                actionTarget = actionTarget,
                actionLabel = actionLabel
            )
        )
    }

    suspend fun deleteRule(id: String) {
        correctionDao.deleteRule(id)
    }
}

class AstraKnowledgeRepository(private val knowledgeDao: KnowledgeDao) {
    val allKnowledge: Flow<List<KnowledgeEntity>> = knowledgeDao.getAllKnowledge()

    suspend fun addKnowledge(title: String, content: String, sourceType: String, sourceName: String?, sourceUrl: String?, tags: String) {
        knowledgeDao.insertKnowledge(
            KnowledgeEntity(title = title, content = content, sourceType = sourceType, sourceName = sourceName, sourceUrl = sourceUrl, tags = tags)
        )
    }

    suspend fun deleteKnowledge(id: String) {
        knowledgeDao.deleteKnowledge(id)
    }
}
