package com.astra.assistant.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.astra.assistant.data.dao.CorrectionRuleDao
import com.astra.assistant.data.dao.KnowledgeDao
import com.astra.assistant.data.dao.MemoryDao
import com.astra.assistant.data.entity.CorrectionRuleEntity
import com.astra.assistant.data.entity.KnowledgeEntity
import com.astra.assistant.data.entity.MemoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        MemoryEntity::class,
        CorrectionRuleEntity::class,
        KnowledgeEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AstraDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao
    abstract fun correctionRuleDao(): CorrectionRuleDao
    abstract fun knowledgeDao(): KnowledgeDao

    companion object {
        @Volatile
        private var INSTANCE: AstraDatabase? = null

        fun getDatabase(context: Context, scope: CoroutineScope): AstraDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AstraDatabase::class.java,
                    "astra_assistant_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
