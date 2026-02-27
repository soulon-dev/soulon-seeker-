-- ============================================
-- Schema V3: 数据云端化扩展表
-- 用于支持 App 数据完全云端存储
-- ============================================

-- ============================================
-- 聊天会话表（云端存储）
-- ============================================
CREATE TABLE IF NOT EXISTS chat_sessions (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    wallet_address TEXT NOT NULL,
    title TEXT DEFAULT '新对话',
    created_at INTEGER DEFAULT (unixepoch()),
    updated_at INTEGER DEFAULT (unixepoch()),
    message_count INTEGER DEFAULT 0,
    total_tokens INTEGER DEFAULT 0,
    total_memo_earned INTEGER DEFAULT 0,
    is_deleted INTEGER DEFAULT 0,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_chat_sessions_user ON chat_sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_wallet ON chat_sessions(wallet_address);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_updated ON chat_sessions(updated_at DESC);

-- ============================================
-- 聊天消息表（云端存储）
-- ============================================
CREATE TABLE IF NOT EXISTS chat_messages (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    wallet_address TEXT NOT NULL,
    text TEXT NOT NULL,
    is_user INTEGER NOT NULL,              -- 1=用户消息, 0=AI消息
    timestamp INTEGER NOT NULL,
    tokens_used INTEGER DEFAULT 0,          -- 本条消息使用的 token 数
    rewarded_memo INTEGER DEFAULT 0,        -- 奖励的积分
    is_persona_relevant INTEGER DEFAULT 0,  -- 是否涉及人格
    relevance_score REAL DEFAULT 0,         -- 人格相关度分数 (0-1)
    detected_traits TEXT,                   -- 检测到的特质 (JSON)
    retrieved_memories TEXT,                -- 检索到的记忆 ID 列表 (JSON)
    is_error INTEGER DEFAULT 0,             -- 是否错误消息
    irys_tx_id TEXT,                        -- Irys 交易 ID（已上传时非空）
    is_deleted INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT (unixepoch()),
    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_chat_messages_session ON chat_messages(session_id);
CREATE INDEX IF NOT EXISTS idx_chat_messages_user ON chat_messages(user_id);
CREATE INDEX IF NOT EXISTS idx_chat_messages_timestamp ON chat_messages(timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_chat_messages_persona ON chat_messages(is_persona_relevant) WHERE is_persona_relevant = 1;

-- ============================================
-- 用户人格数据表
-- 存储 OCEAN 五大人格维度
-- ============================================
CREATE TABLE IF NOT EXISTS user_persona (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT UNIQUE NOT NULL,
    wallet_address TEXT NOT NULL,
    
    -- OCEAN 五大人格维度 (0.0 - 1.0)
    openness REAL DEFAULT 0.5,              -- 开放性
    conscientiousness REAL DEFAULT 0.5,     -- 尽责性
    extraversion REAL DEFAULT 0.5,          -- 外向性
    agreeableness REAL DEFAULT 0.5,         -- 宜人性
    neuroticism REAL DEFAULT 0.5,           -- 神经质
    
    -- 分析元数据
    sample_size INTEGER DEFAULT 0,          -- 分析样本数量
    analyzed_at INTEGER,                    -- 上次分析时间
    sync_rate REAL DEFAULT 0,               -- 同步率 (0-1)
    
    -- 问卷数据
    questionnaire_completed INTEGER DEFAULT 0,  -- 是否完成问卷
    questionnaire_answers TEXT,                 -- 问卷答案 (JSON)
    questionnaire_completed_at INTEGER,         -- 问卷完成时间
    
    created_at INTEGER DEFAULT (unixepoch()),
    updated_at INTEGER DEFAULT (unixepoch()),
    
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_user_persona_wallet ON user_persona(wallet_address);

-- ============================================
-- 用户人格画像 V2（证据链/置信度）
-- 独立表避免破坏性迁移
-- ============================================
CREATE TABLE IF NOT EXISTS user_persona_profile_v2 (
    wallet_address TEXT PRIMARY KEY,
    profile_json TEXT NOT NULL,
    updated_at INTEGER DEFAULT (unixepoch())
);

CREATE INDEX IF NOT EXISTS idx_persona_profile_v2_wallet ON user_persona_profile_v2(wallet_address);

-- ============================================
-- 记忆向量表（云端 RAG）
-- 用于语义搜索和个性化检索
-- ============================================
CREATE TABLE IF NOT EXISTS memory_vectors (
    memory_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    wallet_address TEXT NOT NULL,
    
    -- 向量数据
    vector_json TEXT NOT NULL,              -- 向量数据 (JSON 数组，1024维)
    vector_dimension INTEGER DEFAULT 1024,  -- 向量维度
    model TEXT DEFAULT 'text-embedding-v3', -- Embedding 模型版本
    
    -- 元数据
    text_preview TEXT,                      -- 原始文本预览（前200字符）
    text_length INTEGER,                    -- 原始文本长度
    memory_type TEXT DEFAULT 'chat',        -- 记忆类型: chat, persona, onboarding
    
    -- 来源信息
    source_id TEXT,                         -- 来源 ID（如消息 ID、Irys TX ID）
    source_type TEXT,                       -- 来源类型
    
    created_at INTEGER DEFAULT (unixepoch()),
    
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_memory_vectors_user ON memory_vectors(user_id);
CREATE INDEX IF NOT EXISTS idx_memory_vectors_wallet ON memory_vectors(wallet_address);

-- ============================================
-- 支持-Bug 报告表
-- ============================================
CREATE TABLE IF NOT EXISTS support_bug_reports (
    id TEXT PRIMARY KEY,
    wallet_address TEXT,
    contact_email TEXT,
    severity TEXT NOT NULL DEFAULT 'UNTRIAGED',
    description TEXT NOT NULL,
    include_device_info INTEGER DEFAULT 1,
    device_info TEXT,
    app_version TEXT,
    platform TEXT,
    status TEXT DEFAULT 'OPEN',
    estimated_reward INTEGER DEFAULT 0,
    expert_candidate INTEGER DEFAULT 0,
    reward_granted INTEGER,
    expert_granted INTEGER DEFAULT 0,
    admin_notes TEXT,
    processed_at INTEGER,
    created_at INTEGER DEFAULT (unixepoch()),
    updated_at INTEGER DEFAULT (unixepoch())
);

CREATE INDEX IF NOT EXISTS idx_support_bug_reports_created ON support_bug_reports(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_support_bug_reports_status ON support_bug_reports(status);
CREATE INDEX IF NOT EXISTS idx_support_bug_reports_wallet ON support_bug_reports(wallet_address);
CREATE INDEX IF NOT EXISTS idx_memory_vectors_type ON memory_vectors(memory_type);

-- ============================================
-- 主动提问表（奇遇任务）
-- ============================================
CREATE TABLE IF NOT EXISTS proactive_questions (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    wallet_address TEXT NOT NULL,
    
    -- 问题内容
    question_text TEXT NOT NULL,
    category TEXT NOT NULL,                 -- 问题类别
    subcategory TEXT,                       -- 子类别
    
    -- 状态
    status TEXT DEFAULT 'PENDING',          -- PENDING/NOTIFIED/ANSWERED/SKIPPED/EXPIRED
    priority INTEGER DEFAULT 0,             -- 优先级（数字越大越优先）
    
    -- 时间戳
    created_at INTEGER DEFAULT (unixepoch()),
    scheduled_at INTEGER,                   -- 计划通知时间
    notified_at INTEGER,                    -- 实际通知时间
    answered_at INTEGER,                    -- 回答时间
    expires_at INTEGER,                     -- 过期时间
    
    -- 回答相关
    answer_text TEXT,                       -- 用户的回答
    persona_impact TEXT,                    -- 对人格各维度的影响 (JSON)
    rewarded_memo INTEGER DEFAULT 0,        -- 奖励的积分
    
    -- 上下文
    context_hint TEXT,                      -- AI 生成时的上下文提示
    follow_up_question_id TEXT,             -- 追问的原始问题 ID
    
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_proactive_questions_user ON proactive_questions(user_id);
CREATE INDEX IF NOT EXISTS idx_proactive_questions_status ON proactive_questions(status);
CREATE INDEX IF NOT EXISTS idx_proactive_questions_scheduled ON proactive_questions(scheduled_at);

-- ============================================
-- 用户签到记录表
-- ============================================
CREATE TABLE IF NOT EXISTS user_checkins (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL,
    wallet_address TEXT NOT NULL,
    
    checkin_date TEXT NOT NULL,             -- 签到日期 (YYYY-MM-DD)
    streak_day INTEGER DEFAULT 1,           -- 连续签到第几天 (1-7 循环)
    memo_earned INTEGER DEFAULT 0,          -- 获得的积分
    
    created_at INTEGER DEFAULT (unixepoch()),
    
    UNIQUE(user_id, checkin_date),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_user_checkins_user ON user_checkins(user_id);
CREATE INDEX IF NOT EXISTS idx_user_checkins_date ON user_checkins(checkin_date DESC);

-- ============================================
-- 用户每日统计表
-- 用于限制和追踪每日使用情况
-- ============================================
CREATE TABLE IF NOT EXISTS user_daily_stats (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL,
    wallet_address TEXT NOT NULL,
    
    stat_date TEXT NOT NULL,                -- 统计日期 (YYYY-MM-DD)
    
    -- 对话统计
    dialogue_count INTEGER DEFAULT 0,       -- 今日对话次数
    tokens_used INTEGER DEFAULT 0,          -- 今日使用 token
    memo_earned INTEGER DEFAULT 0,          -- 今日获得积分
    
    -- 奇遇任务
    questions_answered INTEGER DEFAULT 0,   -- 今日回答问题数
    questions_notified INTEGER DEFAULT 0,   -- 今日通知问题数
    
    -- 首聊标记
    has_first_chat INTEGER DEFAULT 0,       -- 今日是否已首聊
    first_chat_memo INTEGER DEFAULT 0,      -- 首聊奖励积分
    
    created_at INTEGER DEFAULT (unixepoch()),
    updated_at INTEGER DEFAULT (unixepoch()),
    
    UNIQUE(user_id, stat_date),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_user_daily_stats_user ON user_daily_stats(user_id);
CREATE INDEX IF NOT EXISTS idx_user_daily_stats_date ON user_daily_stats(stat_date DESC);

-- ============================================
-- 扩展 users 表字段（如果不存在）
-- 注意：SQLite 不支持 IF NOT EXISTS 添加列
-- 需要通过应用层检查
-- ============================================

-- 用于追踪月度 token 使用
-- ALTER TABLE users ADD COLUMN monthly_tokens_used INTEGER DEFAULT 0;
-- ALTER TABLE users ADD COLUMN monthly_tokens_reset_date TEXT;

-- ============================================
-- 初始化默认配置
-- ============================================

-- 聊天相关配置
INSERT OR IGNORE INTO app_config (config_key, config_value, value_type, category, display_name, description, default_value)
VALUES 
    ('chat.max_sessions_per_user', '100', 'int', 'chat', '每用户最大会话数', '每个用户允许创建的最大聊天会话数', '100'),
    ('chat.max_messages_per_session', '1000', 'int', 'chat', '每会话最大消息数', '每个会话允许的最大消息数', '1000'),
    ('chat.message_retention_days', '365', 'int', 'chat', '消息保留天数', '聊天消息保留的天数，0表示永久', '365'),
    ('chat.sync_batch_size', '50', 'int', 'chat', '同步批次大小', 'App 同步消息时的批次大小', '50');

-- 人格分析配置
INSERT OR IGNORE INTO app_config (config_key, config_value, value_type, category, display_name, description, default_value)
VALUES 
    ('persona.min_samples_for_analysis', '5', 'int', 'persona', '最小分析样本数', '进行人格分析所需的最小对话样本数', '5'),
    ('persona.analysis_interval_hours', '24', 'int', 'persona', '分析间隔小时', '两次人格分析之间的最小间隔', '24'),
    ('persona.questionnaire_questions', '20', 'int', 'persona', '问卷题目数', '人格问卷的题目数量', '20');

-- 奇遇任务配置
INSERT OR IGNORE INTO app_config (config_key, config_value, value_type, category, display_name, description, default_value)
VALUES 
    ('questions.daily_limit', '3', 'int', 'questions', '每日问题上限', '每日推送给用户的最大问题数', '3'),
    ('questions.expire_hours', '24', 'int', 'questions', '问题过期小时', '问题推送后多少小时过期', '24'),
    ('questions.answer_reward_base', '30', 'int', 'questions', '回答基础奖励', '回答问题的基础 MEMO 奖励', '30');

-- 向量配置
INSERT OR IGNORE INTO app_config (config_key, config_value, value_type, category, display_name, description, default_value)
VALUES 
    ('vectors.max_per_user', '10000', 'int', 'vectors', '每用户最大向量数', '每个用户允许存储的最大向量数', '10000'),
    ('vectors.similarity_threshold', '0.7', 'float', 'vectors', '相似度阈值', 'RAG 检索的最小相似度阈值', '0.7'),
    ('vectors.top_k', '5', 'int', 'vectors', '检索数量', 'RAG 检索返回的最大结果数', '5');

-- ============================================
-- Auth Challenge 表（登录防重放）
-- ============================================
CREATE TABLE IF NOT EXISTS auth_challenges (
    wallet_address TEXT PRIMARY KEY,
    challenge TEXT NOT NULL,
    message TEXT NOT NULL,
    issued_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_auth_challenges_issued_at ON auth_challenges(issued_at);
-- 补丁：添加自动续费相关表
-- 这些表在代码中被引用，但缺失于 schema 文件中

CREATE TABLE IF NOT EXISTS auto_renew_subscriptions (
    id TEXT PRIMARY KEY,
    wallet_address TEXT NOT NULL,
    plan_type INTEGER NOT NULL,           -- 1=月费, 2=季度, 3=年费
    amount_usdc REAL NOT NULL,
    period_seconds INTEGER NOT NULL,
    next_payment_at INTEGER NOT NULL,
    is_active INTEGER DEFAULT 1,
    token_account_pda TEXT,               -- 链上 PDA 地址
    created_at INTEGER DEFAULT (unixepoch()),
    updated_at INTEGER DEFAULT (unixepoch())
);

CREATE INDEX IF NOT EXISTS idx_auto_renew_wallet ON auto_renew_subscriptions(wallet_address);
CREATE INDEX IF NOT EXISTS idx_auto_renew_payment_time ON auto_renew_subscriptions(next_payment_at);
CREATE INDEX IF NOT EXISTS idx_auto_renew_active ON auto_renew_subscriptions(is_active);

CREATE TABLE IF NOT EXISTS auto_renew_payment_logs (
    id TEXT PRIMARY KEY,
    subscription_id TEXT NOT NULL,
    success INTEGER NOT NULL,             -- 1=成功, 0=失败
    transaction_id TEXT,
    error_message TEXT,
    created_at INTEGER DEFAULT (unixepoch()),
    FOREIGN KEY (subscription_id) REFERENCES auto_renew_subscriptions(id)
);

CREATE INDEX IF NOT EXISTS idx_payment_logs_sub ON auto_renew_payment_logs(subscription_id);
CREATE INDEX IF NOT EXISTS idx_payment_logs_created ON auto_renew_payment_logs(created_at);
