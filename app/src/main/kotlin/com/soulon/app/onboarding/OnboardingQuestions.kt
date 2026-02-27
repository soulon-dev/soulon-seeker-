package com.soulon.app.onboarding

import com.soulon.app.i18n.AppStrings

/**
 * 初始化问卷系统
 * 
 * 用途：
 * 1. 首次使用时收集用户基础信息
 * 2. 建立初始记忆库
 * 3. 进行人格分析（OCEAN 模型）
 */
object OnboardingQuestions {
    
    /**
     * 获取所有初始化问题
     * 
     * 覆盖 OCEAN 五大维度：
     * - Openness（开放性）：创造力、好奇心、想象力
     * - Conscientiousness（尽责性）：组织性、责任感、自律
     * - Extraversion（外向性）：社交性、活力、积极情绪
     * - Agreeableness（宜人性）：合作性、信任、利他
     * - Neuroticism（神经质）：情绪稳定性、焦虑、压力应对
     */
    fun getAllQuestions(): List<OnboardingQuestion> {
        return listOf(
            // 开放性 (Openness) - 4 题
            OnboardingQuestion(
                id = 1,
                question = AppStrings.tr("你平时喜欢做什么事情来放松？", "What do you usually do to relax?"),
                dimension = PersonalityDimension.OPENNESS,
                type = QuestionType.OPEN_TEXT,
                placeholder = AppStrings.tr("例如：看书、旅行、绘画、看电影等", "e.g. reading, traveling, painting, watching movies")
            ),
            OnboardingQuestion(
                id = 2,
                question = AppStrings.tr("你对新事物的态度如何？", "How do you feel about new things?"),
                dimension = PersonalityDimension.OPENNESS,
                type = QuestionType.SINGLE_CHOICE,
                options = listOf(
                    AppStrings.tr("非常喜欢尝试新鲜事物，充满好奇", "Love trying new things, full of curiosity"),
                    AppStrings.tr("比较喜欢尝试，但会谨慎选择", "Like trying, but choose carefully"),
                    AppStrings.tr("一般，偶尔会尝试", "Average, occasionally try"),
                    AppStrings.tr("不太喜欢，更喜欢熟悉的事物", "Prefer familiar things over new ones")
                )
            ),
            OnboardingQuestion(
                id = 3,
                question = AppStrings.tr("你有什么特别的兴趣爱好或专长？", "Do you have any special hobbies or skills?"),
                dimension = PersonalityDimension.OPENNESS,
                type = QuestionType.OPEN_TEXT,
                placeholder = AppStrings.tr("例如：摄影、编程、音乐、运动等", "e.g. photography, programming, music, sports")
            ),
            OnboardingQuestion(
                id = 4,
                question = AppStrings.tr("你更喜欢哪种类型的思考方式？", "Which thinking style do you prefer?"),
                dimension = PersonalityDimension.OPENNESS,
                type = QuestionType.SINGLE_CHOICE,
                options = listOf(
                    AppStrings.tr("抽象的、理论的概念（如哲学、理论）", "Abstract, theoretical concepts"),
                    AppStrings.tr("两者都喜欢，看情况而定", "Like both, depends on situation"),
                    AppStrings.tr("具体的、实际的事物（如技能、操作）", "Concrete, practical things")
                )
            ),
            
            // 尽责性 (Conscientiousness) - 4 题
            OnboardingQuestion(
                id = 5,
                question = AppStrings.tr("你通常如何管理自己的时间和任务？", "How do you manage your time and tasks?"),
                dimension = PersonalityDimension.CONSCIENTIOUSNESS,
                type = QuestionType.SINGLE_CHOICE,
                options = listOf(
                    AppStrings.tr("有详细的计划和清单，严格执行", "Detailed plans and lists, strictly followed"),
                    AppStrings.tr("有大致的计划，灵活调整", "Rough plans, adjusted flexibly"),
                    AppStrings.tr("随性而为，凭感觉安排", "Spontaneous, arranged by feeling"),
                    AppStrings.tr("经常拖延，最后赶工完成", "Often procrastinate, rush at the end")
                )
            ),
            OnboardingQuestion(
                id = 6,
                question = AppStrings.tr("你的工作或学习环境是什么样的？", "What is your work or study environment like?"),
                dimension = PersonalityDimension.CONSCIENTIOUSNESS,
                type = QuestionType.SINGLE_CHOICE,
                options = listOf(
                    AppStrings.tr("非常整洁，物品分类摆放", "Very tidy, items categorized"),
                    AppStrings.tr("基本整洁，偶尔有点乱", "Basically tidy, occasionally messy"),
                    AppStrings.tr("比较凌乱，但能找到东西", "Messy, but can find things"),
                    AppStrings.tr("很乱，经常找不到东西", "Very messy, often lose things")
                )
            ),
            OnboardingQuestion(
                id = 7,
                question = AppStrings.tr("面对重要任务的截止日期，你的准备方式是？", "How do you prepare for important deadlines?"),
                dimension = PersonalityDimension.CONSCIENTIOUSNESS,
                type = QuestionType.SINGLE_CHOICE,
                options = listOf(
                    AppStrings.tr("提前很多天开始，分步骤完成", "Start days ahead, step by step"),
                    AppStrings.tr("提前几天开始准备", "Start a few days early"),
                    AppStrings.tr("临近截止日期才开始", "Start near the deadline"),
                    AppStrings.tr("经常需要延期或匆忙完成", "Often need extension or rush")
                )
            ),
            OnboardingQuestion(
                id = 8,
                question = AppStrings.tr("你的自律程度如何？", "How disciplined are you?"),
                dimension = PersonalityDimension.CONSCIENTIOUSNESS,
                type = QuestionType.SINGLE_CHOICE,
                options = listOf(
                    AppStrings.tr("非常自律，能坚持既定计划", "Very disciplined, stick to plans"),
                    AppStrings.tr("比较自律，大部分时候能坚持", "Quite disciplined, stick to plans mostly"),
                    AppStrings.tr("一般，容易受外界影响", "Average, easily influenced"),
                    AppStrings.tr("自律性较弱，经常半途而废", "Weak discipline, often give up")
                )
            ),
            
            // 外向性 (Extraversion) - 4 题
            OnboardingQuestion(
                id = 9,
                question = AppStrings.tr("你更喜欢独处还是和朋友一起？", "Prefer being alone or with friends?"),
                dimension = PersonalityDimension.EXTRAVERSION,
                type = QuestionType.SINGLE_CHOICE,
                options = listOf(
                    AppStrings.tr("非常喜欢和朋友在一起，越热闹越好", "Love being with friends, the livelier the better"),
                    AppStrings.tr("喜欢社交，但也需要独处时间", "Like socializing, but need alone time"),
                    AppStrings.tr("两者都可以，看心情而定", "Both are fine, depends on mood"),
                    AppStrings.tr("更喜欢独处，社交会让我疲惫", "Prefer being alone, socializing is tiring")
                )
            ),
            OnboardingQuestion(
                id = 10,
                question = AppStrings.tr("在社交场合中，你通常扮演什么角色？", "What role do you play in social settings?"),
                dimension = PersonalityDimension.EXTRAVERSION,
                type = QuestionType.SINGLE_CHOICE,
                options = listOf(
                    AppStrings.tr("主动者，喜欢带动气氛", "Initiator, like to liven up atmosphere"),
                    AppStrings.tr("积极参与者，会主动聊天", "Active participant, chat proactively"),
                    AppStrings.tr("观察者，适度参与交流", "Observer, participate moderately"),
                    AppStrings.tr("倾听者，较少主动发言", "Listener, speak less proactively")
                )
            ),
            OnboardingQuestion(
                id = 11,
                question = AppStrings.tr("什么活动能让你充满活力？", "What activities energize you?"),
                dimension = PersonalityDimension.EXTRAVERSION,
                type = QuestionType.SINGLE_CHOICE,
                options = listOf(
                    AppStrings.tr("社交活动（聚会、交流）", "Social activities (parties, networking)"),
                    AppStrings.tr("两者都可以，取决于状态", "Both, depends on state"),
                    AppStrings.tr("独处活动（阅读、思考、独自爱好）", "Solitary activities (reading, thinking, hobbies)")
                )
            ),
            OnboardingQuestion(
                id = 12,
                question = AppStrings.tr("你的朋友圈和社交习惯是？", "What are your social circle and habits?"),
                dimension = PersonalityDimension.EXTRAVERSION,
                type = QuestionType.SINGLE_CHOICE,
                options = listOf(
                    AppStrings.tr("朋友很多，喜欢认识新朋友", "Many friends, like meeting new people"),
                    AppStrings.tr("有一定数量的朋友，偶尔结交新朋友", "Some friends, occasionally meet new ones"),
                    AppStrings.tr("朋友不多，但关系紧密", "Few friends, but close relationships"),
                    AppStrings.tr("朋友很少，不太喜欢结交新朋友", "Very few friends, dislike meeting new people")
                )
            ),
            
            // 宜人性 (Agreeableness) - 4 题
            OnboardingQuestion(
                id = 13,
                question = AppStrings.tr("当与他人意见不合时，你通常如何处理？", "How do you handle disagreements?"),
                dimension = PersonalityDimension.AGREEABLENESS,
                type = QuestionType.SINGLE_CHOICE,
                options = listOf(
                    AppStrings.tr("耐心倾听，寻求共识和妥协", "Listen patiently, seek consensus"),
                    AppStrings.tr("表达自己的观点，但尊重对方", "Express views, but respect others"),
                    AppStrings.tr("坚持自己的立场，据理力争", "Insist on own stance, argue"),
                    AppStrings.tr("避免冲突，保持沉默或退让", "Avoid conflict, remain silent or yield")
                )
            ),
            OnboardingQuestion(
                id = 14,
                question = AppStrings.tr("你对待需要帮助的人的态度是？", "Attitude towards those needing help?"),
                dimension = PersonalityDimension.AGREEABLENESS,
                type = QuestionType.SINGLE_CHOICE,
                options = listOf(
                    AppStrings.tr("非常乐意帮助，主动提供帮助", "Very willing, offer help proactively"),
                    AppStrings.tr("愿意帮助，但视情况而定", "Willing, but depends on situation"),
                    AppStrings.tr("看心情和关系，有选择地帮助", "Depends on mood/relationship, selective"),
                    AppStrings.tr("更注重自己的事情，较少帮助他人", "Focus on self, help others less")
                )
            ),
            OnboardingQuestion(
                id = 15,
                question = AppStrings.tr("在团队合作中，你更注重什么？", "What do you value in teamwork?"),
                dimension = PersonalityDimension.AGREEABLENESS,
                type = QuestionType.SINGLE_CHOICE,
                options = listOf(
                    AppStrings.tr("团队和谐与协作", "Team harmony and collaboration"),
                    AppStrings.tr("两者平衡，都很重要", "Balance of both, both important"),
                    AppStrings.tr("个人贡献与成就", "Individual contribution and achievement")
                )
            ),
            OnboardingQuestion(
                id = 16,
                question = AppStrings.tr("你看待他人的方式倾向于？", "How do you tend to view others?"),
                dimension = PersonalityDimension.AGREEABLENESS,
                type = QuestionType.SINGLE_CHOICE,
                options = listOf(
                    AppStrings.tr("容易看到别人的优点，比较信任他人", "See strengths, trust others"),
                    AppStrings.tr("客观看待，优缺点都会注意", "Objective, notice both pros/cons"),
                    AppStrings.tr("容易注意到缺点，保持警惕", "Notice flaws, remain vigilant")
                )
            ),
            
            // 神经质 (Neuroticism) - 4 题
            OnboardingQuestion(
                id = 17,
                question = AppStrings.tr("面对压力或挑战时，你的典型反应是？", "Typical reaction to stress/challenges?"),
                dimension = PersonalityDimension.NEUROTICISM,
                type = QuestionType.SINGLE_CHOICE,
                options = listOf(
                    AppStrings.tr("保持冷静，理性分析应对", "Stay calm, analyze rationally"),
                    AppStrings.tr("有些紧张，但能逐渐调整", "A bit nervous, but can adjust"),
                    AppStrings.tr("容易焦虑，需要时间缓解", "Anxious, need time to relieve"),
                    AppStrings.tr("非常焦虑，难以应对", "Very anxious, hard to cope")
                )
            ),
            OnboardingQuestion(
                id = 18,
                question = AppStrings.tr("你的情绪稳定性如何？", "How stable are your emotions?"),
                dimension = PersonalityDimension.NEUROTICISM,
                type = QuestionType.SINGLE_CHOICE,
                options = listOf(
                    AppStrings.tr("情绪非常稳定，很少波动", "Very stable, rarely fluctuate"),
                    AppStrings.tr("比较稳定，偶尔会有起伏", "Quite stable, occasional ups/downs"),
                    AppStrings.tr("情绪波动较大，容易受影响", "Fluctuate, easily influenced"),
                    AppStrings.tr("情绪很不稳定，经常起伏", "Unstable, frequent ups/downs")
                )
            ),
            OnboardingQuestion(
                id = 19,
                question = AppStrings.tr("遇到不如意的事情，你的恢复速度是？", "Recovery speed from setbacks?"),
                dimension = PersonalityDimension.NEUROTICISM,
                type = QuestionType.SINGLE_CHOICE,
                options = listOf(
                    AppStrings.tr("很快恢复，几乎不受影响", "Recover quickly, barely affected"),
                    AppStrings.tr("几小时到一天内能调整过来", "Hours to a day to adjust"),
                    AppStrings.tr("需要几天时间才能恢复", "Need a few days to recover"),
                    AppStrings.tr("需要很长时间，难以走出", "Take long time, hard to move on")
                )
            ),
            OnboardingQuestion(
                id = 20,
                question = AppStrings.tr("你应对生活中不确定性和变化的方式是？", "How do you handle uncertainty/change?"),
                dimension = PersonalityDimension.NEUROTICISM,
                type = QuestionType.SINGLE_CHOICE,
                options = listOf(
                    AppStrings.tr("灵活适应，享受变化带来的新鲜感", "Adapt flexibly, enjoy novelty"),
                    AppStrings.tr("能够适应，虽然需要一些时间", "Can adapt, though takes time"),
                    AppStrings.tr("感到不安，但勉强能应对", "Uneasy, but can cope barely"),
                    AppStrings.tr("非常不安，很难适应变化", "Very uneasy, hard to adapt")
                )
            )
        )
    }
}

/**
 * 问题类型
 */
enum class QuestionType {
    OPEN_TEXT,      // 开放式文本
    SINGLE_CHOICE   // 单选题
}

/**
 * 初始化问题
 */
data class OnboardingQuestion(
    val id: Int,
    val question: String,
    val dimension: PersonalityDimension,
    val type: QuestionType,
    val placeholder: String = "",
    val options: List<String> = emptyList()  // 选项列表（用于选择题）
)

/**
 * 人格维度
 */
enum class PersonalityDimension {
    OPENNESS,          // 开放性
    CONSCIENTIOUSNESS, // 尽责性
    EXTRAVERSION,      // 外向性
    AGREEABLENESS,     // 宜人性
    NEUROTICISM        // 神经质
}

/**
 * 用户答案
 */
data class OnboardingAnswer(
    val questionId: Int,
    val answer: String,
    val dimension: PersonalityDimension
)
