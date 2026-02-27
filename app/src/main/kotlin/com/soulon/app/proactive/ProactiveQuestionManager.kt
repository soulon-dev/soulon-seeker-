package com.soulon.app.proactive

import android.content.Context
import com.soulon.app.ai.QwenCloudManager
import com.soulon.app.data.CloudDataRepository
import com.soulon.app.data.ProactiveQuestionData
import com.soulon.app.i18n.LocaleManager
import com.soulon.app.onboarding.OnboardingState
import com.soulon.app.persona.PersonaTraits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import java.util.Calendar
import java.util.UUID

/**
 * 奇遇管理器
 * 
 * 功能：
 * 1. 完成问卷后解锁奇遇功能
 * 2. 基于人格画像状态生成针对性的奇遇问题
 * 3. 管理奇遇的生命周期（创建、通知、探索）
 * 4. 分析回答并更新人格画像
 * 
 * 数据流向（云端优先）：
 * - 问题生成：本地生成 + 后端同步
 * - 问题获取：后端优先，后备本地
 * - 回答提交：本地 + 后端（异步）
 */
class ProactiveQuestionManager(
    private val context: Context,
    private val qwenCloudManager: QwenCloudManager? = null
) {
    private val database = ProactiveQuestionDatabase.getInstance(context)
    private val dao = database.proactiveQuestionDao()
    private val cloudRepo by lazy { CloudDataRepository.getInstance(context) }
    private val scope = CoroutineScope(Dispatchers.IO)
    
    // 当前钱包地址（用于后端同步）
    private var currentWallet: String? = null
    
    /**
     * 设置当前钱包地址
     */
    fun setWalletAddress(walletAddress: String?) {
        currentWallet = walletAddress
    }
    
    companion object {
        private const val TAG = "AdventureManager"
        
        // 每日奇遇次数
        const val DAILY_ADVENTURE_COUNT = 3
        
        // 奇遇有效期（24小时，当日有效）
        const val QUESTION_EXPIRE_HOURS = 24
        
        // 奇遇发送时间点（小时）：早上9点、下午2点、晚上8点
        val ADVENTURE_HOURS = listOf(9, 14, 20)
    }
    
    // ========== 功能解锁检查 ==========
    
    /**
     * 检查奇遇功能是否已解锁
     * 解锁条件：完成初始化问卷调查
     */
    fun isFeatureUnlocked(): Boolean {
        return OnboardingState.isCompleted(context)
    }
    
    // ========== 问题生成 ==========
    
    /**
     * 生成新的奇遇
     * 
     * @param personaTraits 当前人格特征（可选，用于针对性提问）
     * @param count 生成奇遇数量
     * @return 生成的奇遇列表
     */
    suspend fun generateQuestions(
        personaTraits: PersonaTraits? = null,
        count: Int = 3
    ): List<ProactiveQuestionEntity> {
        if (!isFeatureUnlocked()) {
            Timber.w("$TAG: 奇遇功能尚未解锁")
            return emptyList()
        }
        
        // 获取已回答问题的类别分布，避免重复类别
        val categoryStats = dao.getCategoryStats()
        val leastAskedCategories = findLeastAskedCategories(categoryStats)
        
        // 根据人格特征确定需要强化的维度
        val weakDimensions = personaTraits?.let { findWeakDimensions(it) } ?: emptyList()
        
        // 使用 AI 生成问题
        val questions = if (qwenCloudManager != null) {
            generateQuestionsWithAI(personaTraits, leastAskedCategories, weakDimensions, count)
        } else {
            generatePresetQuestions(leastAskedCategories, weakDimensions, count)
        }
        
        // 保存到数据库
        dao.insertQuestions(questions)
        Timber.i("$TAG: 生成了 ${questions.size} 个奇遇")
        
        return questions
    }
    
    /**
     * 使用 AI 生成奇遇问题
     */
    private suspend fun generateQuestionsWithAI(
        personaTraits: PersonaTraits?,
        targetCategories: List<QuestionCategory>,
        weakDimensions: List<String>,
        count: Int
    ): List<ProactiveQuestionEntity> {
        val questions = mutableListOf<ProactiveQuestionEntity>()
        
        try {
            val prompt = buildQuestionGenerationPrompt(personaTraits, targetCategories, weakDimensions, count)
            
            val response = qwenCloudManager?.generateStream(prompt)?.first()
            
            if (response != null) {
                // 解析 AI 生成的问题
                val generatedQuestions = parseGeneratedQuestions(response, targetCategories)
                questions.addAll(generatedQuestions)
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: AI 生成问题失败，使用预设问题")
            questions.addAll(generatePresetQuestions(targetCategories, weakDimensions, count))
        }
        
        // 确保有足够的问题
        if (questions.size < count) {
            questions.addAll(generatePresetQuestions(targetCategories, weakDimensions, count - questions.size))
        }
        
        return questions.take(count)
    }

    /**
     * 构建奇遇问题生成提示词
     */
    private fun buildQuestionGenerationPrompt(
        personaTraits: PersonaTraits?,
        targetCategories: List<QuestionCategory>,
        weakDimensions: List<String>,
        count: Int
    ): String {
        val languageCode = com.soulon.app.i18n.AppStrings.getCurrentLanguage()
        val baseLang = languageCode.substringBefore('-').ifBlank { "en" }

        val categoriesDesc = targetCategories.joinToString(", ") { it.name }
        val categoryLegend = QuestionCategory.values().joinToString("\n") { "- ${it.name}: ${it.displayName}" }
        val dimensionsDesc = if (weakDimensions.isNotEmpty()) {
            if (baseLang == "zh") {
                "特别关注以下需要强化的人格维度：${weakDimensions.joinToString("、")}"
            } else {
                "Pay extra attention to these traits: ${weakDimensions.joinToString(", ")}"
            }
        } else ""

        return if (baseLang == "zh") {
            """
你是一位温暖的 AI 伙伴，正在为用户设计有趣的"奇遇"问题，帮助他们探索自己的内心世界。
请生成 $count 个开放性问题，这些问题应该像一次有趣的心灵探险。

目标类别（请使用枚举名，不要翻译括号里的类别）：
$categoriesDesc

类别对照：
$categoryLegend

$dimensionsDesc

要求：
1. 语气亲切温暖，像朋友间的闲聊
2. 问题要能引发思考，但不要过于沉重
3. 每个问题不超过 50 字
4. 只输出问题列表，不要输出解释

输出格式：每行一个问题，严格使用：
[CATEGORY_ENUM]问题内容

例如：
[DAILY_LIFE]如果今天有一件小事让你微笑了，那会是什么？
            """.trimIndent()
        } else {
            """
You are a warm AI companion designing fun “Adventure” questions to help the user explore themselves.
Generate $count open-ended questions.

Target categories (use the enum name in brackets; do not translate the bracket part):
$categoriesDesc

Category legend:
$categoryLegend

$dimensionsDesc

Requirements:
1. Friendly and warm tone, like chatting with a friend
2. Thought-provoking but not too heavy
3. Each question <= 25 words
4. Output questions only, no explanation
5. Write the question text in ${languageCode}

Output format: one per line, strictly:
[CATEGORY_ENUM]Question text

Example:
[DAILY_LIFE]What small thing made you smile today?
            """.trimIndent()
        }
    }
    
    /**
     * 解析 AI 生成的问题
     */
    private fun parseGeneratedQuestions(
        response: String,
        defaultCategories: List<QuestionCategory>
    ): List<ProactiveQuestionEntity> {
        val questions = mutableListOf<ProactiveQuestionEntity>()
        val lines = response.lines().filter { it.isNotBlank() }
        
        val categoryMap = buildMap<String, QuestionCategory> {
            QuestionCategory.values().forEach { cat ->
                put(cat.name, cat)
                put(cat.displayName, cat)
            }
        }
        
        lines.forEachIndexed { index, line ->
            val trimmedLine = line.trim()
            
            // 尝试解析 [类别]问题 格式
            val match = Regex("\\[(.+?)](.+)").find(trimmedLine)
            
            val (category, questionText) = if (match != null) {
                val categoryName = match.groupValues[1]
                val question = match.groupValues[2].trim()
                val cat = categoryMap[categoryName] ?: defaultCategories.getOrNull(index) ?: QuestionCategory.DAILY_LIFE
                Pair(cat, question)
            } else if (trimmedLine.length > 5 && !trimmedLine.startsWith("请") && !trimmedLine.startsWith("你是")) {
                // 没有类别标记的问题
                val cat = defaultCategories.getOrNull(index) ?: QuestionCategory.DAILY_LIFE
                Pair(cat, trimmedLine)
            } else {
                return@forEachIndexed
            }
            
            if (questionText.length >= 5) {
                questions.add(
                    ProactiveQuestionEntity(
                        questionText = questionText,
                        category = category.name,
                        priority = defaultCategories.indexOf(category).let { if (it >= 0) it else 0 }
                    )
                )
            }
        }
        
        return questions
    }
    
    /**
     * 生成预设问题（AI 不可用时的回退方案）
     */
    private fun generatePresetQuestions(
        targetCategories: List<QuestionCategory>,
        weakDimensions: List<String>,
        count: Int
    ): List<ProactiveQuestionEntity> {
        val allQuestions = getPresetQuestionBank()
        val questions = mutableListOf<ProactiveQuestionEntity>()
        
        // 优先选择目标类别的问题
        for (category in targetCategories) {
            val categoryQuestions = allQuestions[category] ?: continue
            categoryQuestions.shuffled().take(1).forEach { questionText ->
                questions.add(
                    ProactiveQuestionEntity(
                        questionText = questionText,
                        category = category.name,
                        priority = targetCategories.indexOf(category)
                    )
                )
            }
            if (questions.size >= count) break
        }
        
        // 如果不够，从其他类别补充
        if (questions.size < count) {
            val remaining = count - questions.size
            val otherQuestions = allQuestions.flatMap { (cat, qs) ->
                qs.map { Pair(cat, it) }
            }.shuffled().take(remaining)
            
            otherQuestions.forEach { (cat, questionText) ->
                questions.add(
                    ProactiveQuestionEntity(
                        questionText = questionText,
                        category = cat.name
                    )
                )
            }
        }
        
        return questions.take(count)
    }
    
    /**
     * 预设问题库 (动态多语言)
     */
    private fun getPresetQuestionBank(): Map<QuestionCategory, List<String>> {
        val lang = com.soulon.app.i18n.AppStrings.getCurrentLanguage()
        val isZh = lang.startsWith("zh")
        
        return if (isZh) {
            mapOf(
                QuestionCategory.DAILY_LIFE to listOf(
                    "最近有什么让你感到特别开心的小事吗？",
                    "你通常是怎么度过周末的？",
                    "今天有什么事情让你印象深刻吗？",
                    "你最喜欢一天中的哪个时间段？为什么？",
                    "最近有什么新的发现或体验吗？"
                ),
                QuestionCategory.RELATIONSHIPS to listOf(
                    "最近和谁聊天让你感觉特别舒服？",
                    "你觉得什么样的相处方式让你最放松？",
                    "有没有一个人最近给了你特别的启发？",
                    "你更喜欢一对一的深度交流还是热闹的聚会？",
                    "当朋友遇到困难时，你通常会怎么做？"
                ),
                QuestionCategory.HOBBIES to listOf(
                    "最近有什么让你特别投入的事情吗？",
                    "如果有一整天自由时间，你会怎么安排？",
                    "有没有一直想尝试但还没开始的事情？",
                    "什么事情能让你忘记时间的流逝？",
                    "你最近学会或正在学习什么新技能？"
                ),
                QuestionCategory.VALUES to listOf(
                    "你觉得什么对你来说是最重要的？",
                    "有什么原则是你一直坚持的？",
                    "什么事情会让你感到真正的满足？",
                    "你希望别人怎么记住你？",
                    "什么样的选择对你来说是困难的？"
                ),
                QuestionCategory.GOALS to listOf(
                    "你最近在为什么目标努力？",
                    "一年后的你希望是什么样子？",
                    "有什么梦想是你一直放在心里的？",
                    "你觉得什么阻碍了你实现目标？",
                    "如果可以改变一件事，你会选择什么？"
                ),
                QuestionCategory.EMOTIONS to listOf(
                    "最近有什么事情让你感到感动？",
                    "当你感到压力大时，通常会怎么调节？",
                    "什么事情最容易影响你的心情？",
                    "你会怎么描述自己最近的心情？",
                    "有没有一首歌或一部电影最近特别打动你？"
                ),
                QuestionCategory.OPENNESS to listOf(
                    "最近有没有接触什么新鲜的想法或观点？",
                    "你对未知的事物是好奇还是谨慎？",
                    "有什么领域是你特别想探索的？",
                    "你喜欢尝试新的方式做事还是坚持熟悉的方法？"
                ),
                QuestionCategory.CONSCIENTIOUSNESS to listOf(
                    "你通常是怎么规划你的时间的？",
                    "面对重要任务，你会提前准备还是临时发挥？",
                    "有没有什么习惯是你一直在坚持的？",
                    "当计划被打乱时，你通常会怎么应对？"
                ),
                QuestionCategory.EXTRAVERSION to listOf(
                    "你更喜欢热闹的环境还是安静的空间？",
                    "在社交场合，你是主动出击还是等待机会？",
                    "独处的时候，你通常会做什么？",
                    "什么样的社交活动会让你感到充满能量？"
                ),
                QuestionCategory.AGREEABLENESS to listOf(
                    "当别人的观点和你不同时，你会怎么处理？",
                    "你觉得妥协是一种什么样的品质？",
                    "在团队中，你更倾向于协调还是领导？",
                    "什么情况下你会坚持自己的立场？"
                ),
                QuestionCategory.EMOTIONAL_STABILITY to listOf(
                    "面对不确定性，你通常是什么心态？",
                    "什么事情最容易让你焦虑？",
                    "你有什么方式来保持情绪的平稳？",
                    "遇到挫折时，你多久能够恢复？"
                )
            )
        } else {
            mapOf(
                QuestionCategory.DAILY_LIFE to listOf(
                    "What small thing made you happy recently?",
                    "How do you usually spend your weekends?",
                    "Did anything impressive happen today?",
                    "What's your favorite time of day? Why?",
                    "Have you discovered or experienced anything new recently?"
                ),
                QuestionCategory.RELATIONSHIPS to listOf(
                    "Who did you enjoy chatting with recently?",
                    "What kind of interaction makes you feel most relaxed?",
                    "Has anyone inspired you recently?",
                    "Do you prefer deep one-on-one conversations or lively parties?",
                    "What do you usually do when a friend is in trouble?"
                ),
                QuestionCategory.HOBBIES to listOf(
                    "Is there anything you've been particularly engrossed in lately?",
                    "If you had a whole free day, how would you spend it?",
                    "Is there something you've always wanted to try but haven't started yet?",
                    "What makes you forget the passage of time?",
                    "What new skill have you learned or are you learning recently?"
                ),
                QuestionCategory.VALUES to listOf(
                    "What do you think is most important to you?",
                    "Is there a principle you always stick to?",
                    "What gives you true satisfaction?",
                    "How do you want to be remembered?",
                    "What kind of choices are difficult for you?"
                ),
                QuestionCategory.GOALS to listOf(
                    "What goal have you been working towards recently?",
                    "What do you hope to be like in a year?",
                    "Is there a dream you've always kept in your heart?",
                    "What do you think is hindering you from achieving your goals?",
                    "If you could change one thing, what would it be?"
                ),
                QuestionCategory.EMOTIONS to listOf(
                    "What has moved you recently?",
                    "How do you usually regulate yourself when stressed?",
                    "What affects your mood the most?",
                    "How would you describe your mood recently?",
                    "Has a song or movie touched you particularly recently?"
                ),
                QuestionCategory.OPENNESS to listOf(
                    "Have you encountered any fresh ideas or perspectives recently?",
                    "Are you curious or cautious about the unknown?",
                    "Is there a field you particularly want to explore?",
                    "Do you like trying new ways or sticking to familiar methods?"
                ),
                QuestionCategory.CONSCIENTIOUSNESS to listOf(
                    "How do you usually plan your time?",
                    "For important tasks, do you prepare in advance or improvise?",
                    "Is there a habit you've been keeping up with?",
                    "How do you usually cope when plans are disrupted?"
                ),
                QuestionCategory.EXTRAVERSION to listOf(
                    "Do you prefer lively environments or quiet spaces?",
                    "In social situations, do you take the initiative or wait for opportunities?",
                    "What do you usually do when you are alone?",
                    "What kind of social activities make you feel energized?"
                ),
                QuestionCategory.AGREEABLENESS to listOf(
                    "How do you handle it when others have different opinions?",
                    "What kind of quality do you think compromise is?",
                    "In a team, do you tend to coordinate or lead?",
                    "In what situations do you insist on your own position?"
                ),
                QuestionCategory.EMOTIONAL_STABILITY to listOf(
                    "What is your mindset when facing uncertainty?",
                    "What makes you anxious most easily?",
                    "How do you keep your emotions stable?",
                    "How long does it take you to recover from setbacks?"
                )
            )
        }
    }
    
    /**
     * 找出最少被问到的类别
     */
    private fun findLeastAskedCategories(categoryStats: List<CategoryCount>): List<QuestionCategory> {
        val allCategories = QuestionCategory.values().toMutableList()
        val statsMap = categoryStats.associate { it.category to it.count }
        
        // 按问过的次数排序，次数少的优先
        return allCategories.sortedBy { statsMap[it.name] ?: 0 }.take(5)
    }
    
    /**
     * 找出需要强化的人格维度
     */
    private fun findWeakDimensions(traits: PersonaTraits): List<String> {
        val dimensions = listOf(
            "开放性" to traits.openness,
            "尽责性" to traits.conscientiousness,
            "外向性" to traits.extraversion,
            "宜人性" to traits.agreeableness,
            "情绪稳定性" to traits.emotionalStability
        )
        
        // 找出完整度低或置信度低的维度
        return dimensions
            .filter { (_, score) -> score.completeness < 0.6f || score.confidence < 0.5f }
            .sortedBy { (_, score) -> score.completeness + score.confidence }
            .take(3)
            .map { it.first }
    }
    
    // ========== 问题管理 ==========
    
    /**
     * 获取待回答的问题（本地 Flow）
     */
    fun getPendingQuestions(): Flow<List<ProactiveQuestionEntity>> {
        return dao.getPendingQuestions()
    }
    
    /**
     * 获取待回答的问题（云端优先）
     */
    suspend fun getPendingQuestionsCloud(): List<ProactiveQuestionEntity> {
        // 优先从后端获取
        if (currentWallet != null) {
            try {
                val cloudQuestions = cloudRepo.getPendingQuestions(forceRefresh = true)
                if (cloudQuestions.isNotEmpty()) {
                    return cloudQuestions.map { it.toEntity() }
                }
            } catch (e: Exception) {
                Timber.w(e, "从后端获取待回答问题失败，使用本地数据")
            }
        }
        // 后备使用本地数据
        return dao.getPendingQuestionsOnce()
    }
    
    /**
     * 获取下一个要通知的问题
     */
    suspend fun getNextQuestionForNotification(): ProactiveQuestionEntity? {
        // 检查每日通知限额
        val todayStart = getTodayStartTimestamp()
        val todayCount = dao.getTodayNotifiedCount(todayStart)
        
        if (todayCount >= DAILY_ADVENTURE_COUNT) {
            Timber.d("$TAG: 今日通知已达上限 ($DAILY_ADVENTURE_COUNT)")
            return null
        }
        
        return dao.getNextPendingQuestion()
    }
    
    /**
     * 标记奇遇为已通知
     */
    suspend fun markQuestionAsNotified(questionId: String) {
        dao.markAsNotified(questionId)
        Timber.d("$TAG: 奇遇已发送通知: $questionId")
    }
    
    /**
     * 完成奇遇探索
     * 
     * @param questionId 奇遇 ID
     * @param answer 用户回答
     * @param personaImpact 对人格的影响（可选，由分析得出）
     * @param rewardedMemo 奖励积分
     */
    suspend fun answerQuestion(
        questionId: String,
        answer: String,
        personaImpact: PersonaImpact? = null,
        rewardedMemo: Int = 0
    ) {
        val impactJson = personaImpact?.toJson()
        
        // 1. 保存到本地
        dao.markAsAnswered(questionId, answer, impactJson)
        Timber.i("$TAG: 奇遇探索完成: $questionId")
        
        // 2. 异步同步到后端
        if (currentWallet != null) {
            scope.launch {
                try {
                    val personaImpactMap = personaImpact?.let {
                        mapOf(
                            "openness" to it.opennessDelta,
                            "conscientiousness" to it.conscientiousnessDelta,
                            "extraversion" to it.extraversionDelta,
                            "agreeableness" to it.agreeablenessDelta,
                            "neuroticism" to it.emotionalStabilityDelta
                        )
                    }
                    cloudRepo.answerQuestion(questionId, answer, personaImpactMap, rewardedMemo)
                    Timber.d("$TAG: 奇遇回答已同步到后端")
                } catch (e: Exception) {
                    Timber.w(e, "$TAG: 奇遇回答同步到后端失败")
                }
            }
        }
    }
    
    /**
     * 跳过奇遇
     */
    suspend fun skipQuestion(questionId: String) {
        dao.markAsSkipped(questionId)
        Timber.d("$TAG: 奇遇已跳过: $questionId")
        // TODO: 同步到后端
    }
    
    /**
     * 同步本地问题到后端
     */
    suspend fun syncLocalQuestionsToCloud(): Boolean {
        val wallet = currentWallet ?: return false
        
        try {
            val localQuestions = dao.getPendingQuestionsOnce()
            for (question in localQuestions) {
                cloudRepo.createQuestion(
                    questionText = question.questionText,
                    category = question.category,
                    priority = question.priority
                )
            }
            Timber.i("$TAG: 同步了 ${localQuestions.size} 个问题到后端")
            return true
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 同步问题到后端失败")
            return false
        }
    }
    
    /**
     * 清理过期奇遇
     */
    suspend fun cleanupExpiredQuestions() {
        val expireTime = System.currentTimeMillis() - (QUESTION_EXPIRE_HOURS * 60 * 60 * 1000)
        dao.markExpiredQuestions(expireTime)
        dao.cleanupOldQuestions()
        Timber.d("$TAG: 已清理过期奇遇")
    }
    
    /**
     * 获取待回答问题数量
     */
    suspend fun getPendingCount(): Int {
        return dao.getPendingCount()
    }
    
    /**
     * 获取今日奇遇进度
     * 
     * @return Pair(已完成数量, 总数量)
     */
    suspend fun getTodayProgress(): Pair<Int, Int> {
        val todayStart = getTodayStartTimestamp()
        val completedToday = dao.getAnsweredCountSince(todayStart)
        return Pair(completedToday, DAILY_ADVENTURE_COUNT)
    }
    
    /**
     * 获取今日已完成奇遇数量
     */
    suspend fun getTodayCompletedCount(): Int {
        val todayStart = getTodayStartTimestamp()
        return dao.getAnsweredCountSince(todayStart)
    }
    
    /**
     * 检查今日奇遇是否已全部完成
     */
    suspend fun isTodayCompleted(): Boolean {
        return getTodayCompletedCount() >= DAILY_ADVENTURE_COUNT
    }
    
    /**
     * 获取下一个奇遇发送时间
     * 
     * @return 下一个奇遇应该发送的时间戳，如果今日已全部发送则返回明天的第一个时间
     */
    fun getNextAdventureTime(): Long {
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        
        // 找到今天还没到的发送时间
        for (hour in ADVENTURE_HOURS) {
            if (currentHour < hour) {
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                return calendar.timeInMillis
            }
        }
        
        // 今天的时间都过了，返回明天的第一个时间
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        calendar.set(Calendar.HOUR_OF_DAY, ADVENTURE_HOURS.first())
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
    
    /**
     * 获取今日第几个奇遇（用于显示 1/3, 2/3, 3/3）
     */
    suspend fun getCurrentAdventureIndex(): Int {
        val todayStart = getTodayStartTimestamp()
        val todayTotal = dao.getCreatedCountSince(todayStart)
        return minOf(todayTotal + 1, DAILY_ADVENTURE_COUNT)
    }
    
    // ========== 辅助方法 ==========
    
    private fun getTodayStartTimestamp(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}

/**
 * 人格影响数据类
 */
data class PersonaImpact(
    val opennessDelta: Float = 0f,
    val conscientiousnessDelta: Float = 0f,
    val extraversionDelta: Float = 0f,
    val agreeablenessDelta: Float = 0f,
    val emotionalStabilityDelta: Float = 0f,
    val confidenceBoost: Float = 0f,
    val analysisNotes: String = ""
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("opennessDelta", opennessDelta)
            put("conscientiousnessDelta", conscientiousnessDelta)
            put("extraversionDelta", extraversionDelta)
            put("agreeablenessDelta", agreeablenessDelta)
            put("emotionalStabilityDelta", emotionalStabilityDelta)
            put("confidenceBoost", confidenceBoost)
            put("analysisNotes", analysisNotes)
        }.toString()
    }
}
