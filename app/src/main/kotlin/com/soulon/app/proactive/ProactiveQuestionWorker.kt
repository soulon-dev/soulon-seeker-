package com.soulon.app.proactive

import android.content.Context
import androidx.work.*
import com.soulon.app.i18n.AppStrings
import com.soulon.app.i18n.LocaleManager
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 奇遇定时任务 Worker
 * 
 * 功能：
 * 1. 定时检查是否需要生成新奇遇
 * 2. 发送通知提醒用户探索奇遇
 * 3. 清理过期奇遇
 */
class ProactiveQuestionWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    
    companion object {
        private const val TAG = "AdventureWorker"
        const val WORK_NAME = "adventure_work"
        
        // 工作类型
        const val WORK_TYPE_KEY = "work_type"
        const val WORK_TYPE_CHECK_AND_NOTIFY = "check_and_notify"
        const val WORK_TYPE_GENERATE_QUESTIONS = "generate_questions"
        const val WORK_TYPE_CLEANUP = "cleanup"
        
        /**
         * 启动定期检查工作
         * 
         * 每日三次奇遇通知：早上9点、下午2点、晚上8点
         * 使用定期任务每小时检查一次，判断是否到达发送时间
         */
        fun schedulePeriodicWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            
            // 定期任务：每小时检查一次是否到达奇遇发送时间
            val periodicRequest = PeriodicWorkRequestBuilder<ProactiveQuestionWorker>(
                1, TimeUnit.HOURS,
                15, TimeUnit.MINUTES // flex interval
            )
                .setConstraints(constraints)
                .setInputData(
                    Data.Builder()
                        .putString(WORK_TYPE_KEY, WORK_TYPE_CHECK_AND_NOTIFY)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
            
            // 首次延迟任务：1 分钟后发送第一个奇遇通知（用于测试）
            val initialDelayRequest = OneTimeWorkRequestBuilder<ProactiveQuestionWorker>()
                .setInitialDelay(1, TimeUnit.MINUTES)
                .setInputData(
                    Data.Builder()
                        .putString(WORK_TYPE_KEY, WORK_TYPE_CHECK_AND_NOTIFY)
                        .build()
                )
                .build()
            
            WorkManager.getInstance(context).enqueue(initialDelayRequest)
            
            Timber.i("$TAG: 奇遇定期任务已调度 - 每日三次(9:00, 14:00, 20:00)")
        }
        
        /**
         * 立即执行一次检查
         */
        fun runImmediateCheck(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<ProactiveQuestionWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(WORK_TYPE_KEY, WORK_TYPE_CHECK_AND_NOTIFY)
                        .build()
                )
                .build()
            
            WorkManager.getInstance(context).enqueue(workRequest)
            Timber.d("$TAG: 立即检查奇遇")
        }
        
        /**
         * 生成新奇遇
         */
        fun triggerQuestionGeneration(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<ProactiveQuestionWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(WORK_TYPE_KEY, WORK_TYPE_GENERATE_QUESTIONS)
                        .build()
                )
                .build()
            
            WorkManager.getInstance(context).enqueue(workRequest)
            Timber.d("$TAG: 奇遇生成已触发")
        }
        
        /**
         * 取消所有工作
         */
        fun cancelAllWork(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Timber.i("$TAG: 奇遇任务已取消")
        }
    }
    
    override suspend fun doWork(): Result {
        LocaleManager.getSavedLanguageCode(applicationContext)?.let { AppStrings.setLanguage(it) }
        val workType = inputData.getString(WORK_TYPE_KEY) ?: WORK_TYPE_CHECK_AND_NOTIFY
        
        Timber.d("$TAG: 开始执行工作 - 类型: $workType")
        
        return try {
            when (workType) {
                WORK_TYPE_CHECK_AND_NOTIFY -> checkAndNotify()
                WORK_TYPE_GENERATE_QUESTIONS -> generateNewQuestions()
                WORK_TYPE_CLEANUP -> cleanupExpiredQuestions()
                else -> Result.success()
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: 工作执行失败")
            Result.retry()
        }
    }
    
    /**
     * 检查并发送奇遇通知
     * 
     * 每日三次发送时间：9:00, 14:00, 20:00
     * 每次只有 1/20 (5%) 的用户会收到奇遇任务
     */
    private suspend fun checkAndNotify(): Result {
        val questionManager = ProactiveQuestionManager(applicationContext)
        val notificationManager = ProactiveQuestionNotificationManager(applicationContext)
        
        // 检查功能是否解锁
        if (!questionManager.isFeatureUnlocked()) {
            Timber.d("$TAG: 奇遇功能尚未解锁，跳过")
            return Result.success()
        }
        
        // 检查通知权限
        if (!notificationManager.hasNotificationPermission()) {
            Timber.w("$TAG: 没有通知权限，跳过")
            return Result.success()
        }
        
        // 🎲 1/20 概率检查 - 每次只有 5% 的用户会收到奇遇任务
        val randomChance = (1..20).random()
        if (randomChance != 1) {
            Timber.d("$TAG: 本次未命中奇遇 (概率: 1/20, 结果: $randomChance)")
            return Result.success()
        }
        Timber.i("$TAG: 🎯 命中奇遇！(概率: 1/20)")
        
        // 清理过期奇遇
        questionManager.cleanupExpiredQuestions()
        
        // 检查今日是否已完成所有奇遇
        if (questionManager.isTodayCompleted()) {
            Timber.d("$TAG: 今日奇遇已全部完成")
            return Result.success()
        }
        
        // 检查当前时间是否是奇遇发送时间
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val isAdventureTime = ProactiveQuestionManager.ADVENTURE_HOURS.any { hour ->
            currentHour >= hour && currentHour < hour + 1
        }
        
        // 获取今日已发送的奇遇数量
        val todaySentCount = questionManager.getTodayCompletedCount() + questionManager.getPendingCount()
        val targetCount = ProactiveQuestionManager.ADVENTURE_HOURS.count { it <= currentHour }
        
        Timber.d("$TAG: 当前时间=$currentHour, 是否奇遇时间=$isAdventureTime, 今日已发送=$todaySentCount, 目标数量=$targetCount")
        
        // 如果今日发送数量少于应发送数量，则发送新奇遇
        if (todaySentCount < targetCount) {
            val pendingCount = questionManager.getPendingCount()
            
            if (pendingCount == 0) {
                // 没有待探索奇遇，生成新奇遇
                Timber.d("$TAG: 生成新奇遇 (今日第 ${todaySentCount + 1} 个)")
                val newQuestions = questionManager.generateQuestions(count = 1)
                
                if (newQuestions.isNotEmpty()) {
                    val question = newQuestions.first()
                    if (notificationManager.sendQuestionNotification(question)) {
                        questionManager.markQuestionAsNotified(question.id)
                        Timber.i("$TAG: ✨ 今日第 ${todaySentCount + 1}/${ProactiveQuestionManager.DAILY_ADVENTURE_COUNT} 个奇遇已发送")
                    }
                }
            } else {
                // 有待探索奇遇，发送通知
                val nextQuestion = questionManager.getNextQuestionForNotification()
                
                if (nextQuestion != null) {
                    if (notificationManager.sendQuestionNotification(nextQuestion)) {
                        questionManager.markQuestionAsNotified(nextQuestion.id)
                        Timber.i("$TAG: ✨ 奇遇通知已发送")
                    }
                } else {
                    // 发送提醒通知
                    notificationManager.sendReminderNotification(pendingCount)
                }
            }
        }
        
        return Result.success()
    }
    
    /**
     * 生成新奇遇
     */
    private suspend fun generateNewQuestions(): Result {
        val questionManager = ProactiveQuestionManager(applicationContext)
        
        if (!questionManager.isFeatureUnlocked()) {
            return Result.success()
        }
        
        questionManager.generateQuestions(count = 3)
        return Result.success()
    }
    
    /**
     * 清理过期奇遇
     */
    private suspend fun cleanupExpiredQuestions(): Result {
        val questionManager = ProactiveQuestionManager(applicationContext)
        questionManager.cleanupExpiredQuestions()
        return Result.success()
    }
}
