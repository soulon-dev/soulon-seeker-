-- Memory AI 后台管理系统数据库架构
-- Cloudflare D1 SQL

-- ============================================
-- 用户表
-- ============================================
CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    wallet_address TEXT UNIQUE NOT NULL,
    created_at INTEGER DEFAULT (unixepoch()),
    last_active_at INTEGER DEFAULT (unixepoch()),
    memo_balance REAL DEFAULT 0,
    current_tier INTEGER DEFAULT 1,
    subscription_type TEXT DEFAULT 'FREE',
    subscription_expiry INTEGER,
    staked_amount REAL DEFAULT 0,
    is_founder INTEGER DEFAULT 0,
    is_expert INTEGER DEFAULT 0,
    is_banned INTEGER DEFAULT 0,
    ban_reason TEXT,
    total_tokens_used INTEGER DEFAULT 0,
    memories_count INTEGER DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_users_wallet ON users(wallet_address);
CREATE INDEX IF NOT EXISTS idx_users_subscription ON users(subscription_type);
CREATE INDEX IF NOT EXISTS idx_users_created ON users(created_at);

-- ============================================
-- 管理员操作日志表
-- ============================================
CREATE TABLE IF NOT EXISTS admin_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    admin_email TEXT NOT NULL,
    action TEXT NOT NULL,
    target_type TEXT,
    target_id TEXT,
    details TEXT,
    ip_address TEXT,
    created_at INTEGER DEFAULT (unixepoch())
);

CREATE INDEX IF NOT EXISTS idx_admin_logs_email ON admin_logs(admin_email);
CREATE INDEX IF NOT EXISTS idx_admin_logs_action ON admin_logs(action);
CREATE INDEX IF NOT EXISTS idx_admin_logs_created ON admin_logs(created_at);

-- ============================================
-- 系统配置表
-- ============================================
CREATE TABLE IF NOT EXISTS system_config (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    description TEXT,
    updated_at INTEGER DEFAULT (unixepoch())
);

-- 初始化默认配置
INSERT OR IGNORE INTO system_config (key, value, description) VALUES
    ('daily_token_limit_free', '100000', '免费用户每日 Token 限额'),
    ('daily_token_limit_subscriber', '500000', '订阅用户每日 Token 限额'),
    ('min_staking_amount', '100', '最低质押数量 (MEMO)'),
    ('referral_bonus', '50', '推荐奖励 (MEMO)');

-- ============================================
-- 功能开关表
-- ============================================
CREATE TABLE IF NOT EXISTS feature_flags (
    key TEXT PRIMARY KEY,
    enabled INTEGER DEFAULT 1,
    description TEXT,
    updated_at INTEGER DEFAULT (unixepoch())
);

-- 初始化默认功能开关
INSERT OR IGNORE INTO feature_flags (key, enabled, description) VALUES
    ('staking_enabled', 1, '生态质押功能'),
    ('nft_staking_enabled', 0, 'NFT 质押功能'),
    ('referral_enabled', 1, '推荐奖励功能'),
    ('maintenance_mode', 0, '维护模式');

-- ============================================
-- 订阅方案表
-- ============================================
CREATE TABLE IF NOT EXISTS subscription_plans (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    price REAL NOT NULL,
    currency TEXT DEFAULT 'SOL',
    duration_days INTEGER NOT NULL,
    features TEXT,  -- JSON 数组
    is_active INTEGER DEFAULT 1,
    created_at INTEGER DEFAULT (unixepoch()),
    updated_at INTEGER DEFAULT (unixepoch())
);

-- 初始化默认订阅方案
INSERT OR IGNORE INTO subscription_plans (id, name, price, currency, duration_days, features) VALUES
    ('monthly', '月费会员', 0.5, 'SOL', 30, '["解锁生态质押功能","每日 Token 限额提升 2 倍","积分累积加速 1.5x","专属客服支持"]'),
    ('quarterly', '季度会员', 1.2, 'SOL', 90, '["包含月费会员所有权益","每日 Token 限额提升 3 倍","积分累积加速 2x","优先体验新功能"]'),
    ('yearly', '年费会员', 4, 'SOL', 365, '["包含季度会员所有权益","每日 Token 限额提升 5 倍","积分累积加速 3x","专属空投资格","治理投票权"]');

-- ============================================
-- 订阅记录表
-- ============================================
CREATE TABLE IF NOT EXISTS subscription_records (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    wallet_address TEXT NOT NULL,
    plan_id TEXT NOT NULL,
    start_date INTEGER NOT NULL,
    end_date INTEGER NOT NULL,
    status TEXT DEFAULT 'active',
    amount REAL NOT NULL,
    transaction_id TEXT,
    created_at INTEGER DEFAULT (unixepoch()),
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (plan_id) REFERENCES subscription_plans(id)
);

CREATE INDEX IF NOT EXISTS idx_subscription_records_user ON subscription_records(user_id);
CREATE INDEX IF NOT EXISTS idx_subscription_records_status ON subscription_records(status);

-- ============================================
-- 质押项目表
-- ============================================
CREATE TABLE IF NOT EXISTS staking_projects (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    token TEXT NOT NULL,
    icon TEXT,
    apy REAL NOT NULL,
    tvl TEXT,
    min_stake TEXT,
    max_stake TEXT,
    description TEXT,
    long_description TEXT,
    status TEXT DEFAULT 'ACTIVE',
    lock_period_days INTEGER DEFAULT 0,
    risk_level TEXT DEFAULT '中等',
    features TEXT,  -- JSON 数组
    participants INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT (unixepoch()),
    updated_at INTEGER DEFAULT (unixepoch())
);

-- 初始化默认质押项目
INSERT OR IGNORE INTO staking_projects (id, name, token, icon, apy, tvl, min_stake, max_stake, description, long_description, status, lock_period_days, risk_level, features) VALUES
    ('memo_stake', 'MEMO 质押池', 'MEMO', '🪙', 18.5, '1.2M MEMO', '100 MEMO', '100,000 MEMO', '质押 MEMO 获取平台收益分成', 'MEMO 质押池是平台的核心质押产品。通过质押 MEMO 代币，您可以获得平台收益分成，同时获得治理投票权。质押越多，收益越高。', 'ACTIVE', 30, '低', '["每日收益发放","7天解锁冷却","复利自动质押","治理投票权"]'),
    ('sol_stake', 'SOL 生态质押', 'SOL', '◎', 12.0, '50K SOL', '1 SOL', '1,000 SOL', '支持生态发展，获取 MEMO 奖励', 'SOL 生态质押帮助我们建设更强大的 Solana 生态。您的 SOL 将用于支持网络验证，同时获得 MEMO 代币奖励。', 'ACTIVE', 14, '低', '["SOL 原生质押","MEMO 奖励","14天锁定期"]'),
    ('lp_stake', 'LP 流动性挖矿', 'LP', '💧', 35.0, '800K USD', '50 USD', '50,000 USD', '提供 MEMO/SOL 流动性获取高收益', '流动性挖矿是为 MEMO/SOL 交易对提供流动性的高收益产品。高风险高收益，适合有经验的用户。', 'ACTIVE', 7, '高', '["双币质押","高 APY","无常损失风险"]');

-- ============================================
-- 质押记录表
-- ============================================
CREATE TABLE IF NOT EXISTS staking_records (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    wallet_address TEXT NOT NULL,
    project_id TEXT NOT NULL,
    amount REAL NOT NULL,
    start_time INTEGER NOT NULL,
    unlock_time INTEGER NOT NULL,
    status TEXT DEFAULT 'active',
    rewards REAL DEFAULT 0,
    created_at INTEGER DEFAULT (unixepoch()),
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (project_id) REFERENCES staking_projects(id)
);

CREATE INDEX IF NOT EXISTS idx_staking_records_user ON staking_records(user_id);
CREATE INDEX IF NOT EXISTS idx_staking_records_project ON staking_records(project_id);
CREATE INDEX IF NOT EXISTS idx_staking_records_status ON staking_records(status);

-- ============================================
-- 记忆数据表（元数据，实际内容存储在 IPFS/Irys）
-- ============================================
CREATE TABLE IF NOT EXISTS memories (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    wallet_address TEXT NOT NULL,
    type TEXT DEFAULT 'text',
    irys_id TEXT,  -- Irys 存储 ID
    size INTEGER DEFAULT 0,
    is_flagged INTEGER DEFAULT 0,
    flag_reason TEXT,
    created_at INTEGER DEFAULT (unixepoch()),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_memories_user ON memories(user_id);
CREATE INDEX IF NOT EXISTS idx_memories_flagged ON memories(is_flagged);

-- ============================================
-- 聊天日志表（元数据）
-- ============================================
CREATE TABLE IF NOT EXISTS chat_logs (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    wallet_address TEXT NOT NULL,
    user_message_preview TEXT,  -- 脱敏预览
    tokens_used INTEGER DEFAULT 0,
    is_flagged INTEGER DEFAULT 0,
    flag_reason TEXT,
    timestamp INTEGER DEFAULT (unixepoch()),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_chat_logs_user ON chat_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_chat_logs_flagged ON chat_logs(is_flagged);
CREATE INDEX IF NOT EXISTS idx_chat_logs_timestamp ON chat_logs(timestamp);

-- ============================================
-- 每日统计表（用于 Dashboard）
-- ============================================
CREATE TABLE IF NOT EXISTS daily_stats (
    date TEXT PRIMARY KEY,
    total_users INTEGER DEFAULT 0,
    new_users INTEGER DEFAULT 0,
    active_users INTEGER DEFAULT 0,
    total_subscribers INTEGER DEFAULT 0,
    new_subscribers INTEGER DEFAULT 0,
    total_tvl REAL DEFAULT 0,
    total_stakers INTEGER DEFAULT 0,
    total_memories INTEGER DEFAULT 0,
    total_chats INTEGER DEFAULT 0,
    total_tokens_used INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT (unixepoch())
);

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

-- ============================================
-- Genesis 试用兑换记录（7天体验卡）
-- ============================================
CREATE TABLE IF NOT EXISTS genesis_redemptions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    wallet_address TEXT NOT NULL,
    redeemed_at INTEGER NOT NULL,
    ip_address TEXT,
    device_id TEXT,
    transaction_signature TEXT,
    created_at INTEGER DEFAULT (unixepoch())
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_genesis_redemptions_wallet ON genesis_redemptions(wallet_address);
CREATE UNIQUE INDEX IF NOT EXISTS idx_genesis_redemptions_signature ON genesis_redemptions(transaction_signature);

-- ============================================
-- 钱包地址管理表（Genesis redeem 依赖 recipient 配置）
-- ============================================
CREATE TABLE IF NOT EXISTS wallet_addresses (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    address TEXT UNIQUE NOT NULL,
    type TEXT NOT NULL,
    network TEXT DEFAULT 'mainnet',
    description TEXT,
    is_active INTEGER DEFAULT 1,
    balance_sol REAL DEFAULT 0,
    balance_usdc REAL DEFAULT 0,
    last_balance_check INTEGER,
    created_by TEXT,
    created_at INTEGER DEFAULT (unixepoch()),
    updated_at INTEGER DEFAULT (unixepoch())
);

CREATE INDEX IF NOT EXISTS idx_wallet_addresses_type_active ON wallet_addresses(type, is_active);
