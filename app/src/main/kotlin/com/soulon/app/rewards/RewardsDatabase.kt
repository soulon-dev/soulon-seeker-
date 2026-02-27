package com.soulon.app.rewards

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.soulon.app.wallet.WalletScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Rewards 数据库
 * 
 * 管理 $MEMO 积分系统和用户档案
 * 
 * Phase 3 Week 2: Task_Tier_System
 */
@Database(
    entities = [
        UserProfile::class,
        MemoTransaction::class,
        MemoTransactionLog::class,
        com.soulon.app.rag.MemoryVector::class,
        com.soulon.app.chat.ChatSessionEntity::class,
        com.soulon.app.chat.ChatMessageEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class RewardsDatabase : RoomDatabase() {
    
    abstract fun rewardsDao(): RewardsDao
    abstract fun vectorDao(): com.soulon.app.rag.VectorDao
    abstract fun chatDao(): com.soulon.app.chat.ChatDao
    
    companion object {
        private const val DATABASE_NAME = "rewards_database"
        private val INSTANCES: ConcurrentHashMap<String, RewardsDatabase> = ConcurrentHashMap()
        
        /**
         * 迁移 3 -> 4：添加用户级别和对话分类字段
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 添加 UserProfile 用户级别相关字段
                database.execSQL("ALTER TABLE user_profile ADD COLUMN subscriptionType TEXT NOT NULL DEFAULT 'FREE'")
                database.execSQL("ALTER TABLE user_profile ADD COLUMN subscriptionExpiry INTEGER")
                database.execSQL("ALTER TABLE user_profile ADD COLUMN stakedAmount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_profile ADD COLUMN stakingStartTime INTEGER")
                database.execSQL("ALTER TABLE user_profile ADD COLUMN isFounder INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_profile ADD COLUMN founderSince INTEGER")
                database.execSQL("ALTER TABLE user_profile ADD COLUMN isExpert INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_profile ADD COLUMN expertSince INTEGER")
                database.execSQL("ALTER TABLE user_profile ADD COLUMN dailyTokensUsed INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_profile ADD COLUMN lastTokenResetDate TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE user_profile ADD COLUMN totalLifetimeTokens INTEGER NOT NULL DEFAULT 0")
                
                // 添加 ChatMessageEntity 人格相关性字段
                database.execSQL("ALTER TABLE chat_messages ADD COLUMN is_persona_relevant INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chat_messages ADD COLUMN relevance_score REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE chat_messages ADD COLUMN irys_transaction_id TEXT")
                database.execSQL("ALTER TABLE chat_messages ADD COLUMN detected_traits TEXT NOT NULL DEFAULT ''")
            }
        }
        
        /**
         * 迁移 4 -> 5：V1 积分系统 - 添加每日对话计数、签到等字段
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 添加 V1 积分系统新字段
                database.execSQL("ALTER TABLE user_profile ADD COLUMN dailyDialogueCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_profile ADD COLUMN lastDialogueResetDate TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE user_profile ADD COLUMN hasFirstChatToday INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_profile ADD COLUMN consecutiveCheckInDays INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_profile ADD COLUMN lastCheckInDate TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE user_profile ADD COLUMN weeklyCheckInProgress INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE user_profile ADD COLUMN totalCheckInDays INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE user_profile ADD COLUMN personaProfileV2 TEXT")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS memo_transaction_logs (
                        remoteId INTEGER NOT NULL,
                        walletAddress TEXT NOT NULL,
                        transactionType TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        description TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        metadataJson TEXT,
                        PRIMARY KEY(remoteId)
                    )
                    """.trimIndent()
                )
            }
        }
        
        /**
         * 获取数据库实例（单例）
         */
        fun getInstance(context: Context): RewardsDatabase {
            val wallet = WalletScope.currentWalletAddress(context)
            val scopeName = WalletScope.scopedName(DATABASE_NAME, wallet)
            return INSTANCES[scopeName] ?: synchronized(this) {
                INSTANCES[scopeName] ?: Room.databaseBuilder(
                    context.applicationContext,
                    RewardsDatabase::class.java,
                    scopeName
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCES[scopeName] = it }
            }
        }
        
        /**
         * 清除实例（仅用于测试）
         */
        fun clearInstance() {
            INSTANCES.values.forEach { runCatching { it.close() } }
            INSTANCES.clear()
        }
    }
}
