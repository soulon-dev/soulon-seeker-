package com.soulon.app.proactive

import android.content.Context
import androidx.room.*
import com.soulon.app.i18n.AppStrings
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * 主动提问问题类别
 */
enum class QuestionCategory(
    private val displayNameZh: String,
    private val displayNameEn: String,
    private val descriptionZh: String,
    private val descriptionEn: String
) {
    // 生活维度
    DAILY_LIFE("日常生活", "Daily life", "了解您的日常习惯和生活方式", "Learn your daily habits and lifestyle"),
    RELATIONSHIPS("人际关系", "Relationships", "了解您与他人的互动方式", "Learn how you interact with others"),
    HOBBIES("兴趣爱好", "Hobbies", "了解您的兴趣和热爱", "Learn your interests and passions"),
    
    // 思想维度
    VALUES("价值观念", "Values", "了解您的价值取向", "Learn your values and beliefs"),
    GOALS("目标规划", "Goals", "了解您的目标和期望", "Learn your goals and aspirations"),
    EMOTIONS("情感体验", "Emotions", "了解您的情感状态和表达方式", "Learn your emotional experience and expression"),
    
    // 人格强化维度
    OPENNESS("开放性", "Openness", "探索您对新事物的态度", "Explore your attitude toward novelty"),
    CONSCIENTIOUSNESS("尽责性", "Conscientiousness", "了解您的自律和责任感", "Learn your self-discipline and responsibility"),
    EXTRAVERSION("外向性", "Extraversion", "了解您的社交偏好", "Learn your social preferences"),
    AGREEABLENESS("宜人性", "Agreeableness", "了解您的合作与包容程度", "Learn your cooperation and compassion"),
    EMOTIONAL_STABILITY("情绪稳定性", "Emotional stability", "了解您的情绪调节能力", "Learn your emotional regulation ability")
    ;

    val displayName: String
        get() = AppStrings.tr(displayNameZh, displayNameEn)

    val description: String
        get() = AppStrings.tr(descriptionZh, descriptionEn)
}

/**
 * 问题状态
 */
enum class QuestionStatus {
    PENDING,        // 待发送
    NOTIFIED,       // 已通知
    ANSWERED,       // 已回答
    SKIPPED,        // 已跳过
    EXPIRED         // 已过期
}

/**
 * 主动提问实体
 */
@Entity(tableName = "proactive_questions")
data class ProactiveQuestionEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    
    @ColumnInfo(name = "question_text")
    val questionText: String,
    
    @ColumnInfo(name = "category")
    val category: String, // QuestionCategory.name
    
    @ColumnInfo(name = "status")
    val status: String = QuestionStatus.PENDING.name,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "notified_at")
    val notifiedAt: Long? = null,
    
    @ColumnInfo(name = "answered_at")
    val answeredAt: Long? = null,
    
    @ColumnInfo(name = "answer_text")
    val answerText: String? = null,
    
    @ColumnInfo(name = "persona_impact")
    val personaImpact: String? = null, // JSON: 对人格各维度的影响
    
    @ColumnInfo(name = "priority")
    val priority: Int = 0, // 优先级，数字越大越优先
    
    @ColumnInfo(name = "context_hint")
    val contextHint: String? = null, // AI 生成时的上下文提示
    
    @ColumnInfo(name = "follow_up_question_id")
    val followUpQuestionId: String? = null // 追问的原始问题 ID
)

/**
 * 主动提问 DAO
 */
@Dao
interface ProactiveQuestionDao {
    
    // ========== 查询操作 ==========
    
    /**
     * 获取所有问题（按创建时间倒序）
     */
    @Query("SELECT * FROM proactive_questions ORDER BY created_at DESC")
    fun getAllQuestions(): Flow<List<ProactiveQuestionEntity>>
    
    /**
     * 获取待回答的问题
     */
    @Query("""
        SELECT * FROM proactive_questions 
        WHERE status IN ('PENDING', 'NOTIFIED') 
        ORDER BY priority DESC, created_at ASC
    """)
    fun getPendingQuestions(): Flow<List<ProactiveQuestionEntity>>
    
    /**
     * 获取待回答的问题（一次性）
     */
    @Query("""
        SELECT * FROM proactive_questions 
        WHERE status IN ('PENDING', 'NOTIFIED') 
        ORDER BY priority DESC, created_at ASC
    """)
    suspend fun getPendingQuestionsOnce(): List<ProactiveQuestionEntity>
    
    /**
     * 获取待发送通知的问题
     */
    @Query("""
        SELECT * FROM proactive_questions 
        WHERE status = 'PENDING' 
        ORDER BY priority DESC, created_at ASC 
        LIMIT 1
    """)
    suspend fun getNextPendingQuestion(): ProactiveQuestionEntity?
    
    /**
     * 获取已回答的问题
     */
    @Query("""
        SELECT * FROM proactive_questions 
        WHERE status = 'ANSWERED' 
        ORDER BY answered_at DESC
    """)
    fun getAnsweredQuestions(): Flow<List<ProactiveQuestionEntity>>
    
    /**
     * 获取按类别统计
     */
    @Query("""
        SELECT category, COUNT(*) as count 
        FROM proactive_questions 
        WHERE status = 'ANSWERED' 
        GROUP BY category
    """)
    suspend fun getCategoryStats(): List<CategoryCount>
    
    /**
     * 获取今日已通知数量
     */
    @Query("""
        SELECT COUNT(*) FROM proactive_questions 
        WHERE status = 'NOTIFIED' AND notified_at >= :todayStart
    """)
    suspend fun getTodayNotifiedCount(todayStart: Long): Int
    
    /**
     * 获取问题
     */
    @Query("SELECT * FROM proactive_questions WHERE id = :questionId")
    suspend fun getQuestion(questionId: String): ProactiveQuestionEntity?
    
    /**
     * 获取待回答问题数量
     */
    @Query("SELECT COUNT(*) FROM proactive_questions WHERE status IN ('PENDING', 'NOTIFIED')")
    suspend fun getPendingCount(): Int
    
    /**
     * 获取指定时间之后已回答的问题数量（用于统计今日完成数）
     */
    @Query("SELECT COUNT(*) FROM proactive_questions WHERE status = 'ANSWERED' AND answered_at >= :since")
    suspend fun getAnsweredCountSince(since: Long): Int
    
    /**
     * 获取指定时间之后创建的问题数量（用于统计今日已发送数）
     */
    @Query("SELECT COUNT(*) FROM proactive_questions WHERE created_at >= :since")
    suspend fun getCreatedCountSince(since: Long): Int
    
    /**
     * 获取指定时间之后创建的问题列表
     */
    @Query("""
        SELECT * FROM proactive_questions 
        WHERE created_at >= :since 
        ORDER BY created_at ASC
    """)
    suspend fun getQuestionsSince(since: Long): List<ProactiveQuestionEntity>
    
    // ========== 写入操作 ==========
    
    /**
     * 插入问题
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuestion(question: ProactiveQuestionEntity)
    
    /**
     * 批量插入问题
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuestions(questions: List<ProactiveQuestionEntity>)
    
    /**
     * 更新问题
     */
    @Update
    suspend fun updateQuestion(question: ProactiveQuestionEntity)
    
    /**
     * 标记为已通知
     */
    @Query("""
        UPDATE proactive_questions 
        SET status = 'NOTIFIED', notified_at = :notifiedAt 
        WHERE id = :questionId
    """)
    suspend fun markAsNotified(questionId: String, notifiedAt: Long = System.currentTimeMillis())
    
    /**
     * 标记为已回答
     */
    @Query("""
        UPDATE proactive_questions 
        SET status = 'ANSWERED', answered_at = :answeredAt, answer_text = :answerText, persona_impact = :personaImpact 
        WHERE id = :questionId
    """)
    suspend fun markAsAnswered(
        questionId: String, 
        answerText: String, 
        personaImpact: String?,
        answeredAt: Long = System.currentTimeMillis()
    )
    
    /**
     * 标记为已跳过
     */
    @Query("UPDATE proactive_questions SET status = 'SKIPPED' WHERE id = :questionId")
    suspend fun markAsSkipped(questionId: String)
    
    /**
     * 标记过期问题
     */
    @Query("""
        UPDATE proactive_questions 
        SET status = 'EXPIRED' 
        WHERE status = 'NOTIFIED' AND notified_at < :expireTime
    """)
    suspend fun markExpiredQuestions(expireTime: Long)
    
    /**
     * 删除问题
     */
    @Query("DELETE FROM proactive_questions WHERE id = :questionId")
    suspend fun deleteQuestion(questionId: String)
    
    /**
     * 删除所有已过期或已跳过的问题
     */
    @Query("DELETE FROM proactive_questions WHERE status IN ('EXPIRED', 'SKIPPED')")
    suspend fun cleanupOldQuestions()
}

/**
 * 类别统计数据类
 */
data class CategoryCount(
    val category: String,
    val count: Int
)

/**
 * 主动提问数据库
 */
@Database(
    entities = [ProactiveQuestionEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ProactiveQuestionDatabase : RoomDatabase() {
    
    abstract fun proactiveQuestionDao(): ProactiveQuestionDao
    
    companion object {
        private const val DATABASE_NAME = "proactive_questions.db"
        private val INSTANCES: java.util.concurrent.ConcurrentHashMap<String, ProactiveQuestionDatabase> = java.util.concurrent.ConcurrentHashMap()
        
        fun getInstance(context: Context): ProactiveQuestionDatabase {
            val wallet = com.soulon.app.wallet.WalletScope.currentWalletAddress(context)
            val scopeName = com.soulon.app.wallet.WalletScope.scopedName(DATABASE_NAME, wallet)
            
            return INSTANCES[scopeName] ?: synchronized(this) {
                INSTANCES[scopeName] ?: Room.databaseBuilder(
                    context.applicationContext,
                    ProactiveQuestionDatabase::class.java,
                    scopeName
                )
                .build()
                .also { INSTANCES[scopeName] = it }
            }
        }
    }
}
