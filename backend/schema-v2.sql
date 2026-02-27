- Memory AI 后台管理系统数据库架构 V2
-- Cloudflare D1 SQL
-- 扩展版本：包含业务管理功能

-- ============================================
-- 原有表保持不变，以下为新增表
-- ============================================

-- ============================================
-- 统一配置表（替代简单的 system_config）
-- ============================================
CREATE TABLE IF NOT EXISTS app_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    config_key TEXT UNIQUE NOT NULL,
    config_value TEXT NOT NULL,
    value_type TEXT DEFAULT 'string',     -- string/number/boolean/json
    category TEXT NOT NULL,               -- ai/memo/tier/subscription/staking/blockchain/storage/security/app
    sub_category TEXT,                    -- 子分类
    display_name TEXT NOT NULL,           -- 显示名称
    description TEXT,
    default_value TEXT,
    min_value TEXT,                       -- 数值类型的最小值
    max_value TEXT,                       -- 数值类型的最大值
    options TEXT,                         -- JSON: 可选值列表
    is_sensitive INTEGER DEFAULT 0,       -- 是否敏感（需加密）
    requires_restart INTEGER DEFAULT 0,   -- 是否需要重启生效
    is_active INTEGER DEFAULT 1,
    updated_by TEXT,
    created_at INTEGER DEFAULT (unixepoch()),
    updated_at INTEGER DEFAULT (unixepoch())
);

CREATE INDEX IF NOT EXISTS idx_app_config_category ON app_config(category);
CREATE INDEX IF NOT EXISTS idx_app_config_key ON app_config(config_key);

-- ============================================
-- 配置变更历史表
-- ============================================
CREATE TABLE IF NOT EXISTS config_change_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    config_key TEXT NOT NULL,
    old_value TEXT,
    new_value TEXT,
    changed_by TEXT NOT NULL,
    change_reason TEXT,
    ip_address TEXT,
    created_at INTEGER DEFAULT (unixepoch())
);

CREATE INDEX IF NOT EXISTS idx_config_history_key ON config_change_history(config_key);
CREATE INDEX IF NOT EXISTS idx_config_history_created ON config_change_history(created_at);

-- ============================================
-- API 密钥管理表
-- ============================================
CREATE TABLE IF NOT EXISTS api_keys (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,                   -- 显示名称
    service TEXT NOT NULL,                -- qwen/openai/solana_rpc/helius/irys/other
    key_preview TEXT,                     -- 密钥预览（脱敏: sk-xxx...xxx）
    encrypted_key TEXT NOT NULL,          -- 加密后的密钥
    endpoint_url TEXT,                    -- API 端点 URL
    rate_limit INTEGER,                   -- 速率限制
    monthly_budget REAL,                  -- 月度预算
    usage_count INTEGER DEFAULT 0,
    total_cost REAL DEFAULT 0,
    last_used_at INTEGER,
    last_error TEXT,
    is_primary INTEGER DEFAULT 0,         -- 是否为主密钥
    is_active INTEGER DEFAULT 1,
    expires_at INTEGER,
    created_by TEXT,
    created_at INTEGER DEFAULT (unixepoch()),
    updated_at INTEGER DEFAULT (unixepoch())
);

CREATE INDEX IF NOT EXISTS idx_api_keys_service ON api_keys(service);

-- ============================================
-- AI 服务用量记录表
-- ============================================
CREATE TABLE IF NOT EXISTS ai_usage_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT,
    wallet_address TEXT,
    api_key_id INTEGER,                   -- 使用的密钥 ID
    model TEXT NOT NULL,                  -- 使用的模型
    function_type TEXT NOT NULL,          -- conversation/analysis/questionnaire/persona
    prompt_tokens INTEGER NOT NULL,
    completion_tokens INTEGER NOT NULL,
    total_tokens INTEGER NOT NULL,
    cost_usd REAL,                        -- 估算成本
    latency_ms INTEGER,                   -- 响应延迟
    success INTEGER DEFAULT 1,
    error_message TEXT,
    request_id TEXT,                      -- 请求追踪 ID
    created_at INTEGER DEFAULT (unixepoch()),
    FOREIGN KEY (api_key_id) REFERENCES api_keys(id)
);

CREATE INDEX IF NOT EXISTS idx_ai_usage_user ON ai_usage_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_ai_usage_created ON ai_usage_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_ai_usage_function ON ai_usage_logs(function_type);

-- ============================================
-- 云服务预算表
-- ============================================
CREATE TABLE IF NOT EXISTS cloud_budgets (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    service TEXT NOT NULL,                -- ai/storage/rpc/total
    month TEXT NOT NULL,                  -- 2024-01
    monthly_budget_usd REAL NOT NULL,
    alert_threshold REAL DEFAULT 0.8,     -- 80% 时告警
    current_usage_usd REAL DEFAULT 0,
    alert_sent INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT (unixepoch()),
    updated_at INTEGER DEFAULT (unixepoch()),
    UNIQUE(service, month)
);

-- ============================================
-- MEMO 积分操作记录表
-- ============================================
CREATE TABLE IF NOT EXISTS memo_transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL,
    wallet_address TEXT,
    type TEXT NOT NULL,                   -- earn/spend/admin_add/admin_subtract/admin_set/airdrop/reward
    amount REAL NOT NULL,
    balance_before REAL NOT NULL,
    balance_after REAL NOT NULL,
    source TEXT,                          -- conversation/checkin/quest/admin/airdrop/referral/tier_upgrade
    description TEXT,
    admin_id TEXT,                        -- 管理员操作时记录
    admin_reason TEXT,                    -- 管理员操作原因
    reference_id TEXT,                    -- 关联ID（如对话ID、空投ID）
    reference_type TEXT,                  -- 关联类型
    created_at INTEGER DEFAULT (unixepoch()),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_memo_tx_user ON memo_transactions(user_id);
CREATE INDEX IF NOT EXISTS idx_memo_tx_type ON memo_transactions(type);
CREATE INDEX IF NOT EXISTS idx_memo_tx_created ON memo_transactions(created_at);

-- ============================================
-- 积分规则配置表
-- ============================================
CREATE TABLE IF NOT EXISTS memo_rules (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    rule_key TEXT UNIQUE NOT NULL,
    rule_value TEXT NOT NULL,
    value_type TEXT DEFAULT 'number',     -- number/json/string
    category TEXT NOT NULL,               -- base/checkin/resonance/behavior/tier/activity
    display_name TEXT NOT NULL,
    description TEXT,
    is_active INTEGER DEFAULT 1,
    updated_by TEXT,
    created_at INTEGER DEFAULT (unixepoch()),
    updated_at INTEGER DEFAULT (unixepoch())
);

-- ============================================
-- 用户 Tier 历史表
-- ============================================
CREATE TABLE IF NOT EXISTS user_tier_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL,
    old_tier INTEGER,
    new_tier INTEGER NOT NULL,
    old_memo_balance REAL,
    new_memo_balance REAL,
    change_reason TEXT,                   -- natural/admin/promotion/demotion
    admin_id TEXT,
    admin_note TEXT,
    expires_at INTEGER,                   -- 临时调整时设置
    created_at INTEGER DEFAULT (unixepoch()),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_tier_history_user ON user_tier_history(user_id);

-- ============================================
-- 代币注册表
-- ============================================
CREATE TABLE IF NOT EXISTS token_registry (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol TEXT NOT NULL,
    name TEXT NOT NULL,
    mint_address TEXT UNIQUE NOT NULL,
    decimals INTEGER NOT NULL,
    logo_uri TEXT,
    network TEXT DEFAULT 'mainnet',       -- mainnet/devnet
    token_type TEXT DEFAULT 'spl',        -- native/spl/nft
    is_payment_accepted INTEGER DEFAULT 0,
    is_stakable INTEGER DEFAULT 0,
    is_airdrop_enabled INTEGER DEFAULT 0,
    is_active INTEGER DEFAULT 1,
    coingecko_id TEXT,
    price_usd REAL,
    price_updated_at INTEGER,
    created_at INTEGER DEFAULT (unixepoch()),
    updated_at INTEGER DEFAULT (unixepoch())
);

-- ============================================
-- 代币铸造记录表
-- ============================================
CREATE TABLE IF NOT EXISTS token_mint_records (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    token_id INTEGER NOT NULL,
    mint_address TEXT NOT NULL,
    recipient_address TEXT NOT NULL,
    amount REAL NOT NULL,
    raw_amount TEXT NOT NULL,             -- 精确数值
    tx_signature TEXT UNIQUE,
    status TEXT DEFAULT 'pending',        -- pending/confirmed/failed
    purpose TEXT,                         -- airdrop/reward/team/marketing/other
    admin_id TEXT NOT NULL,
    admin_reason TEXT NOT NULL,
    approved_by TEXT,
    approved_at INTEGER,
    confirmed_at INTEGER,
    created_at INTEGER DEFAULT (unixepoch()),
    FOREIGN KEY (token_id) REFERENCES token_registry(id)
);

CREATE INDEX IF NOT EXISTS idx_mint_records_token ON token_mint_records(token_id);
CREATE INDEX IF NOT EXISTS idx_mint_records_status ON token_mint_records(status);

-- ============================================
-- 代币经济配置表
-- ============================================
CREATE TABLE IF NOT EXISTS token_economics (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    token_id INTEGER NOT NULL UNIQUE,
    total_supply TEXT,
    circulating_supply TEXT,
    locked_supply TEXT,
    burned_supply TEXT,
    team_allocation TEXT,
    community_allocation TEXT,
    treasury_balance TEXT,
    exchange_rate_sol REAL,
    exchange_rate_usdc REAL,
    last_rate_update INTEGER,
    updated_at INTEGER DEFAULT (unixepoch()),
    FOREIGN KEY (token_id) REFERENCES token_registry(id)
);

-- ============================================
-- 钱包地址管理表
-- ============================================
CREATE TABLE IF NOT EXISTS wallet_addresses (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,                   -- 显示名称
    address TEXT UNIQUE NOT NULL,         -- Solana 地址
    type TEXT NOT NULL,                   -- recipient/staking_pool/treasury/fee/team
    network TEXT DEFAULT 'mainnet',       -- mainnet/devnet
    description TEXT,
    is_active INTEGER DEFAULT 1,
    balance_sol REAL DEFAULT 0,
    balance_usdc REAL DEFAULT 0,
    last_balance_check INTEGER,
    created_by TEXT,
    created_at INTEGER DEFAULT (unixepoch()),
    updated_at INTEGER DEFAULT (unixepoch())
);

-- ============================================
-- 智能合约管理表
-- ============================================
CREATE TABLE IF NOT EXISTS smart_contracts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    program_id TEXT UNIQUE NOT NULL,      -- Solana Program ID
    type TEXT NOT NULL,                   -- staking/payment/airdrop/token/nft/other
    network TEXT DEFAULT 'mainnet',
    version TEXT DEFAULT '1.0.0',
    description TEXT,
    idl TEXT,                             -- JSON IDL
    is_active INTEGER DEFAULT 1,
    is_upgradeable INTEGER DEFAULT 1,
    upgrade_authority TEXT,
    deploy_tx TEXT,
    deployed_by TEXT,
    deployed_at INTEGER,
    created_at INTEGER DEFAULT (unixepoch()),
    updated_at INTEGER DEFAULT (unixepoch())
);

-- ============================================
-- RPC 节点管理表
-- ============================================
CREATE TABLE IF NOT EXISTS rpc_nodes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    url TEXT NOT NULL,
    network TEXT DEFAULT 'mainnet',
    provider TEXT,                        -- solana/helius/quicknode/alchemy/custom
    is_primary INTEGER DEFAULT 0,
    is_active INTEGER DEFAULT 1,
    priority INTEGER DEFAULT 0,           -- 优先级，数字越小优先级越高
    rate_limit INTEGER,
    avg_latency_ms INTEGER,
    success_rate REAL DEFAULT 1.0,
    last_health_check INTEGER,
    last_error TEXT,
    created_at INTEGER DEFAULT (unixepoch()),
    updated_at INTEGER DEFAULT (unixepoch())
);

-- ============================================
-- 支付交易记录表
-- ============================================
CREATE TABLE IF NOT EXISTS payment_transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL,
    wallet_address TEXT NOT NULL,
    signature TEXT UNIQUE NOT NULL,       -- Solana 交易签名
    type TEXT NOT NULL,                   -- subscription/staking/purchase/other
    amount REAL NOT NULL,
    token TEXT NOT NULL,                  -- SOL/USDC/SKR
    token_mint TEXT,
    status TEXT DEFAULT 'pending',        -- pending/confirmed/failed/refunded
    related_id TEXT,                      -- 关联的订阅/质押记录 ID
    related_type TEXT,
    block_time INTEGER,
    slot INTEGER,
    fee REAL,
    verified_at INTEGER,
    verified_by TEXT,                     -- auto/admin
    admin_note TEXT,
    created_at INTEGER DEFAULT (unixepoch()),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_payment_tx_user ON payment_transactions(user_id);
CREATE INDEX IF NOT EXISTS idx_payment_tx_status ON payment_transactions(status);
CREATE INDEX IF NOT EXISTS idx_payment_tx_signature ON payment_transactions(signature);

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

-- ============================================
-- 空投活动表
-- ============================================
CREATE TABLE IF NOT EXISTS airdrops (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    description TEXT,
    token_id INTEGER NOT NULL,
    total_amount TEXT NOT NULL,           -- 总空投量
    distributed_amount TEXT DEFAULT '0',
    distribution_mode TEXT NOT NULL,      -- push/claim/merkle
    target_criteria TEXT,                 -- JSON: 筛选条件
    amount_formula TEXT NOT NULL,         -- fixed/tier/activity/custom
    amount_config TEXT,                   -- JSON: 金额配置
    merkle_root TEXT,                     -- Merkle 模式
    contract_address TEXT,                -- 空投合约地址
    recipient_count INTEGER DEFAULT 0,
    claimed_count INTEGER DEFAULT 0,
    status TEXT DEFAULT 'draft',          -- draft/active/executing/paused/completed/cancelled
    start_at INTEGER,
    end_at INTEGER,
    claim_deadline INTEGER,
    created_by TEXT,
    created_at INTEGER DEFAULT (unixepoch()),
    updated_at INTEGER DEFAULT (unixepoch()),
    FOREIGN KEY (token_id) REFERENCES token_registry(id)
);

CREATE INDEX IF NOT EXISTS idx_airdrops_status ON airdrops(status);
CREATE INDEX IF NOT EXISTS idx_airdrops_token ON airdrops(token_id);

-- ============================================
-- 空投受益人表
-- ============================================
CREATE TABLE IF NOT EXISTS airdrop_recipients (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    airdrop_id INTEGER NOT NULL,
    user_id TEXT,
    wallet_address TEXT NOT NULL,
    calculated_amount TEXT NOT NULL,
    tier_at_snapshot INTEGER,
    activity_score REAL,
    memo_balance_snapshot REAL,
    merkle_proof TEXT,                    -- Merkle 证明
    status TEXT DEFAULT 'pending',        -- pending/distributed/claimed/failed/expired
    tx_signature TEXT,
    distributed_at INTEGER,
    claimed_at INTEGER,
    error_message TEXT,
    retry_count INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT (unixepoch()),
    FOREIGN KEY (airdrop_id) REFERENCES airdrops(id),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_airdrop_recipients_airdrop ON airdrop_recipients(airdrop_id);
CREATE INDEX IF NOT EXISTS idx_airdrop_recipients_status ON airdrop_recipients(status);
CREATE INDEX IF NOT EXISTS idx_airdrop_recipients_wallet ON airdrop_recipients(wallet_address);

-- ============================================
-- 空投公式模板表
-- ============================================
CREATE TABLE IF NOT EXISTS airdrop_formulas (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    formula_type TEXT NOT NULL,           -- tier/activity/custom
    config TEXT NOT NULL,                 -- JSON: 配置
    description TEXT,
    is_default INTEGER DEFAULT 0,
    created_by TEXT,
    created_at INTEGER DEFAULT (unixepoch())
);

-- ============================================
-- 奖励类型表
-- ============================================
CREATE TABLE IF NOT EXISTS reward_types (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    code TEXT UNIQUE NOT NULL,            -- memo/skr/nft/physical/privilege
    name TEXT NOT NULL,
    description TEXT,
    icon TEXT,
    is_active INTEGER DEFAULT 1,
    created_at INTEGER DEFAULT (unixepoch())
);

-- ============================================
-- 奖励规则表
-- ============================================
CREATE TABLE IF NOT EXISTS reward_rules (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    trigger_type TEXT NOT NULL,           -- tier_upgrade/activity/behavior/manual/campaign
    trigger_condition TEXT,               -- JSON: 触发条件
    reward_type_id INTEGER NOT NULL,
    reward_amount TEXT NOT NULL,          -- 数量或配置
    reward_config TEXT,                   -- JSON: 额外配置
    user_limit INTEGER,                   -- 单用户上限
    total_limit INTEGER,                  -- 总量上限
    current_count INTEGER DEFAULT 0,
    is_active INTEGER DEFAULT 1,
    start_at INTEGER,
    end_at INTEGER,
    created_by TEXT,
    created_at INTEGER DEFAULT (unixepoch()),
    updated_at INTEGER DEFAULT (unixepoch()),
    FOREIGN KEY (reward_type_id) REFERENCES reward_types(id)
);

-- ============================================
-- 奖励发放记录表
-- ============================================
CREATE TABLE IF NOT EXISTS reward_distributions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL,
    wallet_address TEXT,
    reward_rule_id INTEGER,
    reward_type TEXT NOT NULL,
    reward_amount TEXT NOT NULL,
    status TEXT DEFAULT 'pending',        -- pending/claimed/expired/cancelled
    claim_method TEXT,                    -- auto/manual/airdrop
    tx_signature TEXT,
    claimed_at INTEGER,
    expires_at INTEGER,
    admin_id TEXT,
    admin_note TEXT,
    created_at INTEGER DEFAULT (unixepoch()),
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (reward_rule_id) REFERENCES reward_rules(id)
);

CREATE INDEX IF NOT EXISTS idx_reward_dist_user ON reward_distributions(user_id);
CREATE INDEX IF NOT EXISTS idx_reward_dist_status ON reward_distributions(status);

-- ============================================
-- 活动管理表
-- ============================================
CREATE TABLE IF NOT EXISTS campaigns (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    description TEXT,
    type TEXT NOT NULL,                   -- lottery/task/referral/milestone/checkin
    status TEXT DEFAULT 'draft',          -- draft/active/paused/ended
    config TEXT NOT NULL,                 -- JSON: 活动配置
    prize_pool TEXT NOT NULL,             -- JSON: 奖池配置
    participation_count INTEGER DEFAULT 0,
    winner_count INTEGER DEFAULT 0,
    total_distributed TEXT DEFAULT '0',
    start_at INTEGER NOT NULL,
    end_at INTEGER NOT NULL,
    created_by TEXT,
    created_at INTEGER DEFAULT (unixepoch()),
    updated_at INTEGER DEFAULT (unixepoch())
);

CREATE INDEX IF NOT EXISTS idx_campaigns_status ON campaigns(status);

-- ============================================
-- 推送通知表
-- ============================================
CREATE TABLE IF NOT EXISTS push_notifications (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    type TEXT NOT NULL,                   -- adventure/reward/system/marketing
    target_audience TEXT DEFAULT 'all',   -- all/subscribers/stakers/tier_x/specific
    target_user_ids TEXT,                 -- JSON array for specific users
    target_criteria TEXT,                 -- JSON: 筛选条件
    data TEXT,                            -- JSON: 额外数据
    scheduled_at INTEGER,
    sent_at INTEGER,
    sent_count INTEGER DEFAULT 0,
    delivered_count INTEGER DEFAULT 0,
    click_count INTEGER DEFAULT 0,
    status TEXT DEFAULT 'draft',          -- draft/scheduled/sending/sent/cancelled
    created_by TEXT,
    created_at INTEGER DEFAULT (unixepoch()),
    updated_at INTEGER DEFAULT (unixepoch())
);

CREATE INDEX IF NOT EXISTS idx_notifications_status ON push_notifications(status);
CREATE INDEX IF NOT EXISTS idx_notifications_scheduled ON push_notifications(scheduled_at);

-- ============================================
-- App 版本管理表
-- ============================================
CREATE TABLE IF NOT EXISTS app_versions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    version_code INTEGER NOT NULL,
    version_name TEXT NOT NULL,
    platform TEXT NOT NULL,               -- android/ios
    release_notes TEXT,
    download_url TEXT,
    file_size INTEGER,
    is_force_update INTEGER DEFAULT 0,
    min_supported_version INTEGER,
    status TEXT DEFAULT 'draft',          -- draft/beta/released/deprecated
    released_at INTEGER,
    released_by TEXT,
    created_at INTEGER DEFAULT (unixepoch())
);

CREATE INDEX IF NOT EXISTS idx_app_versions_platform ON app_versions(platform);
CREATE INDEX IF NOT EXISTS idx_app_versions_status ON app_versions(status);

-- ============================================
-- 实时监控指标表
-- ============================================
CREATE TABLE IF NOT EXISTS realtime_metrics (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    metric_name TEXT NOT NULL,
    metric_value REAL NOT NULL,
    tags TEXT,                            -- JSON tags
    recorded_at INTEGER DEFAULT (unixepoch())
);

CREATE INDEX IF NOT EXISTS idx_metrics_name ON realtime_metrics(metric_name);
CREATE INDEX IF NOT EXISTS idx_metrics_recorded ON realtime_metrics(recorded_at);

-- ============================================
-- 告警规则表
-- ============================================
CREATE TABLE IF NOT EXISTS alert_rules (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    metric_name TEXT NOT NULL,
    condition TEXT NOT NULL,              -- gt/lt/eq/gte/lte/contains
    threshold REAL NOT NULL,
    window_minutes INTEGER DEFAULT 5,     -- 检查窗口
    severity TEXT DEFAULT 'warning',      -- info/warning/critical
    notification_channels TEXT,           -- JSON: email/webhook/telegram
    is_active INTEGER DEFAULT 1,
    last_triggered_at INTEGER,
    trigger_count INTEGER DEFAULT 0,
    created_by TEXT,
    created_at INTEGER DEFAULT (unixepoch()),
    updated_at INTEGER DEFAULT (unixepoch())
);

-- ============================================
-- 告警历史表
-- ============================================
CREATE TABLE IF NOT EXISTS alert_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    rule_id INTEGER NOT NULL,
    rule_name TEXT NOT NULL,
    metric_value REAL NOT NULL,
    threshold REAL NOT NULL,
    severity TEXT NOT NULL,
    message TEXT,
    acknowledged INTEGER DEFAULT 0,
    acknowledged_by TEXT,
    acknowledged_at INTEGER,
    resolved INTEGER DEFAULT 0,
    resolved_at INTEGER,
    created_at INTEGER DEFAULT (unixepoch()),
    FOREIGN KEY (rule_id) REFERENCES alert_rules(id)
);

CREATE INDEX IF NOT EXISTS idx_alert_history_rule ON alert_history(rule_id);
CREATE INDEX IF NOT EXISTS idx_alert_history_created ON alert_history(created_at);

-- ============================================
-- 用户配额自定义表
-- ============================================
CREATE TABLE IF NOT EXISTS user_quota_overrides (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL UNIQUE,
    monthly_token_limit INTEGER,
    daily_conversation_limit INTEGER,
    custom_multiplier REAL,
    reason TEXT,
    admin_id TEXT,
    expires_at INTEGER,
    created_at INTEGER DEFAULT (unixepoch()),
    updated_at INTEGER DEFAULT (unixepoch()),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

-- ============================================
-- 初始化默认数据
-- ============================================

-- 默认 App 配置
INSERT OR IGNORE INTO app_config (config_key, config_value, value_type, category, sub_category, display_name, description, default_value) VALUES
-- AI 配置
('ai.primary_model', 'qwen-turbo', 'string', 'ai', 'model', '主 AI 模型', '默认使用的AI模型', 'qwen-turbo'),
('ai.fallback_model', 'qwen-plus', 'string', 'ai', 'model', '备用 AI 模型', '主模型不可用时使用', 'qwen-plus'),
('ai.analysis_model', 'qwen-max', 'string', 'ai', 'model', '分析模型', '人格分析使用的模型', 'qwen-max'),
('ai.temperature', '0.7', 'number', 'ai', 'inference', 'Temperature', 'AI创造性参数 (0-1)', '0.7'),
('ai.top_p', '0.9', 'number', 'ai', 'inference', 'Top-P', 'Top-P采样参数', '0.9'),
('ai.max_tokens', '2000', 'number', 'ai', 'inference', '最大Token', '单次生成最大Token数', '2000'),
('ai.monthly_budget_usd', '1000', 'number', 'ai', 'budget', '月度预算(USD)', 'AI服务月度预算', '1000'),

-- Token 限额配置
('quota.free', '1000000', 'number', 'quota', 'monthly', '免费用户月限额', '普通用户每月Token限额', '1000000'),
('quota.subscriber', '5000000', 'number', 'quota', 'monthly', '订阅用户月限额', '订阅用户每月Token限额', '5000000'),
('quota.staker', '20000000', 'number', 'quota', 'monthly', '质押用户月限额', '质押用户每月Token限额', '20000000'),
('quota.warning_threshold', '0.8', 'number', 'quota', 'alert', '限额预警阈值', '剩余此比例时警告', '0.8'),

-- 积分配置
('memo.base_score', '10', 'number', 'memo', 'base', '基础分', '每次对话基础积分', '10'),
('memo.memo_per_token', '1', 'number', 'memo', 'base', 'Token系数', '每Token奖励MEMO', '1'),
('memo.max_token_count', '200', 'number', 'memo', 'base', 'Token上限', '单次计算Token上限', '200'),
('memo.daily_full_reward_limit', '50', 'number', 'memo', 'base', '每日全额次数', '每日获得全额积分的对话次数', '50'),
('memo.over_limit_reward', '1', 'number', 'memo', 'base', '超限奖励', '超出每日限制后的固定积分', '1'),
('memo.first_chat_reward', '30', 'number', 'memo', 'base', '首聊奖励', '每日首次对话奖励', '30'),
('memo.checkin_rewards', '[20,20,20,50,50,50,150]', 'json', 'memo', 'checkin', '签到奖励', '7天签到奖励数组', '[20,20,20,50,50,50,150]'),
('memo.resonance_s_threshold', '90', 'number', 'memo', 'resonance', 'S级阈值', 'S级共鸣评分阈值', '90'),
('memo.resonance_s_bonus', '100', 'number', 'memo', 'resonance', 'S级奖励', 'S级共鸣奖励', '100'),
('memo.resonance_a_threshold', '70', 'number', 'memo', 'resonance', 'A级阈值', 'A级共鸣评分阈值', '70'),
('memo.resonance_a_bonus', '30', 'number', 'memo', 'resonance', 'A级奖励', 'A级共鸣奖励', '30'),
('memo.resonance_b_threshold', '40', 'number', 'memo', 'resonance', 'B级阈值', 'B级共鸣评分阈值', '40'),
('memo.resonance_b_bonus', '10', 'number', 'memo', 'resonance', 'B级奖励', 'B级共鸣奖励', '10'),

-- Tier 配置
('tier.points', '[0,2500,12000,50000,200000]', 'json', 'tier', 'threshold', 'Tier积分门槛', '各Tier所需积分', '[0,2500,12000,50000,200000]'),
('tier.sovereign', '[0,20,40,60,80]', 'json', 'tier', 'threshold', 'Sovereign门槛', '各Tier所需Sovereign比率(%)', '[0,20,40,60,80]'),
('tier.multiplier', '[1.0,1.5,2.0,3.0,5.0]', 'json', 'tier', 'multiplier', 'Tier倍数', '各Tier积分倍数', '[1.0,1.5,2.0,3.0,5.0]'),
('tier.names', '["Bronze","Silver","Gold","Platinum","Diamond"]', 'json', 'tier', 'display', 'Tier名称', '各Tier显示名称', '["Bronze","Silver","Gold","Platinum","Diamond"]'),

-- 订阅配置
('subscription.monthly_sol', '0.1', 'number', 'subscription', 'price', '月付价格(SOL)', '订阅月付SOL价格', '0.1'),
('subscription.monthly_usdc', '10', 'number', 'subscription', 'price', '月付价格(USDC)', '订阅月付USDC价格', '10'),
('subscription.yearly_discount', '0.8', 'number', 'subscription', 'price', '年付折扣', '年付折扣比例', '0.8'),

-- 质押配置
('staking.min_amount_sol', '10', 'number', 'staking', 'basic', '最低质押(SOL)', 'BASIC等级最低质押', '10'),
('staking.tiers', '[{"name":"BASIC","min":10,"apy":0.08,"multiplier":1.2},{"name":"ADVANCED","min":50,"apy":0.12,"multiplier":1.5},{"name":"CORE","min":200,"apy":0.18,"multiplier":2.0},{"name":"FOUNDER","min":1000,"apy":0.25,"multiplier":3.0}]', 'json', 'staking', 'tiers', '质押等级', '质押等级配置', ''),
('staking.time_bonus', '{"30":0.05,"90":0.15,"180":0.30,"365":0.50}', 'json', 'staking', 'bonus', '时间加成', '锁定时间加成配置', ''),

-- 防刷配置
('ratelimit.max_message_length', '2000', 'number', 'app', 'ratelimit', '消息长度上限', '单条消息最大字符数', '2000'),
('ratelimit.warning_length', '1500', 'number', 'app', 'ratelimit', '长度警告阈值', '接近上限时警告', '1500'),
('ratelimit.max_per_minute', '10', 'number', 'app', 'ratelimit', '每分钟上限', '每分钟最大消息数', '10'),
('ratelimit.max_per_hour', '60', 'number', 'app', 'ratelimit', '每小时上限', '每小时最大消息数', '60'),
('ratelimit.min_interval_ms', '1000', 'number', 'app', 'ratelimit', '最小间隔(ms)', '消息最小间隔', '1000'),
('ratelimit.cooldown_ms', '30000', 'number', 'app', 'ratelimit', '冷却时间(ms)', '限制后冷却时间', '30000'),

-- 区块链配置
('blockchain.network', 'mainnet', 'string', 'blockchain', 'network', '网络', '当前网络环境', 'mainnet'),
('blockchain.rpc_url', 'https://api.mainnet-beta.solana.com', 'string', 'blockchain', 'rpc', 'RPC URL', 'Solana RPC节点', 'https://api.mainnet-beta.solana.com'),

-- 空投配置
('airdrop.base_skr', '100', 'number', 'airdrop', 'skr', '基础SKR', 'SKR空投基础数量', '100'),
('airdrop.tier_multiplier', '[1.0,1.5,2.5,4.0,6.0]', 'json', 'airdrop', 'skr', 'Tier倍数', 'SKR空投Tier倍数', '[1.0,1.5,2.5,4.0,6.0]'),
('airdrop.activity_weights', '{"ai_calls":0.4,"memo_points":0.3,"active_days":0.2,"persona_sync":0.1}', 'json', 'airdrop', 'activity', '活跃度权重', '活跃度评分权重', '');

-- 默认代币
INSERT OR IGNORE INTO token_registry (symbol, name, mint_address, decimals, network, token_type, is_payment_accepted, is_active) VALUES
('SOL', 'Solana', 'So11111111111111111111111111111111111111112', 9, 'mainnet', 'native', 1, 1),
('USDC', 'USD Coin', 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v', 6, 'mainnet', 'spl', 1, 1);

-- 默认奖励类型
INSERT OR IGNORE INTO reward_types (code, name, description, icon) VALUES
('memo', 'MEMO 积分', '平台积分奖励', '💎'),
('skr', 'SKR 代币', 'SKR 代币奖励', '🪙'),
('nft', 'NFT', 'NFT 奖励', '🖼️'),
('physical', '实物奖励', '实体物品奖励', '📦'),
('privilege', '权限奖励', '临时VIP等权限', '👑');

-- 默认空投公式
INSERT OR IGNORE INTO airdrop_formulas (name, formula_type, config, description, is_default) VALUES
('Tier 倍数空投', 'tier', '{"base_amount":100,"multipliers":[1.0,1.5,2.5,4.0,6.0]}', '基于用户Tier的空投倍数', 1),
('活跃度评分空投', 'activity', '{"base_amount":100,"weights":{"ai_calls":0.4,"memo_points":0.3,"active_days":0.2,"persona_sync":0.1}}', '基于活跃度评分的空投', 0),
('固定金额空投', 'fixed', '{"amount":100}', '所有用户获得相同金额', 0);

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
